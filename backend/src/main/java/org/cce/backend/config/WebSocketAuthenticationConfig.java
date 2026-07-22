package org.cce.backend.config;

import io.jsonwebtoken.JwtException;
import org.cce.backend.service.DocAuthorizationService;
import org.cce.backend.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.Collections;
import java.util.Map;

/**
 * Secures the STOMP layer. CONNECT frames are authenticated against the JWT; every SUBSCRIBE and
 * SEND frame is then authorized against the target document's permissions.
 *
 * <p>Because the simple broker and the application share the "/docs" prefix, a raw SEND to a broker
 * topic (e.g. /docs/broadcast/changes/42) would otherwise be relayed to all subscribers without ever
 * hitting a controller. We therefore <strong>allow-list</strong> the client-writable application
 * destinations and reject everything else, so clients can never publish onto broadcast topics.
 */
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketAuthenticationConfig implements WebSocketMessageBrokerConfigurer {

    // The client sends the stored "Bearer <jwt>" value; the prefix is exactly 7 characters.
    private static final int BEARER_PREFIX_LENGTH = 7;

    // Client-writable application destinations. Editing requires EDIT rights; the rest need read access.
    private static final String EDIT_DESTINATION_PREFIX = "/docs/change/";
    private static final String CURSOR_DESTINATION_PREFIX = "/docs/cursor/";
    private static final String USERNAME_DESTINATION_PREFIX = "/docs/username/";
    private static final String DOC_PREFIX = "/docs/";

    // The authenticated username is stashed in the STOMP session attributes at CONNECT so it is
    // reliably available on every later frame, independent of Principal propagation.
    private static final String USERNAME_ATTRIBUTE = "cce.username";

    @Autowired
    JwtService jwtService;

    @Autowired
    DocAuthorizationService docAuthorizationService;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }
                switch (accessor.getCommand()) {
                    case CONNECT -> authenticate(accessor);
                    case SUBSCRIBE -> authorizeSubscribe(accessor);
                    case SEND -> authorizeSend(accessor);
                    default -> { /* other frames (DISCONNECT, UNSUBSCRIBE, ...) need no checks */ }
                }
                return message;
            }
        });
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorizationHeader = accessor.getFirstNativeHeader("Authorization");
        if (authorizationHeader == null || authorizationHeader.length() <= BEARER_PREFIX_LENGTH) {
            throw new MessagingException("Missing or malformed authentication header");
        }
        String jwtToken = authorizationHeader.substring(BEARER_PREFIX_LENGTH);
        try {
            if (!jwtService.validateUserAndToken(jwtToken)) {
                throw new MessagingException("Invalid authentication token");
            }
            String username = jwtService.extractUsername(jwtToken);
            accessor.setUser(
                    new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList()));
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                sessionAttributes.put(USERNAME_ATTRIBUTE, username);
            }
        } catch (JwtException e) {
            throw new MessagingException("Invalid authentication token");
        }
    }

    /** Reading a document (subscribing to any of its broadcast topics) requires read access. */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(DOC_PREFIX)) {
            return; // not a document-scoped subscription
        }
        requirePermission(accessor, destination, false);
    }

    /**
     * Only three application destinations are writable by clients. Broadcast topics and any other
     * destination are rejected so a client cannot inject frames straight onto a broker topic.
     */
    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new MessagingException("Missing destination");
        }
        if (destination.startsWith(EDIT_DESTINATION_PREFIX)) {
            requirePermission(accessor, destination, true);
        } else if (destination.startsWith(CURSOR_DESTINATION_PREFIX)
                || destination.startsWith(USERNAME_DESTINATION_PREFIX)) {
            requirePermission(accessor, destination, false);
        } else {
            throw new MessagingException("Forbidden destination: " + destination);
        }
    }

    private void requirePermission(StompHeaderAccessor accessor, String destination, boolean requireEdit) {
        String username = currentUsername(accessor);
        if (username == null) {
            throw new MessagingException("Unauthenticated WebSocket message");
        }
        Long docId = parseDocId(destination);
        if (docId == null) {
            throw new MessagingException("Unrecognized document destination");
        }
        if (!isAllowed(accessor, username, docId, requireEdit)) {
            throw new MessagingException("Not authorized for document " + docId);
        }
    }

    /**
     * Resolves the permission decision once per (document, level) and caches it in the STOMP session
     * so hot-path frames (a SEND per keystroke) do not each hit the database. A permission change
     * therefore takes effect on the user's next reconnect, which matches the client, whose editability
     * is fixed when the page loads.
     */
    private boolean isAllowed(StompHeaderAccessor accessor, String username, Long docId, boolean requireEdit) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String cacheKey = (requireEdit ? "authz.edit." : "authz.access.") + docId;
        if (attrs != null && attrs.get(cacheKey) instanceof Boolean cached) {
            return cached;
        }
        boolean allowed = requireEdit
                ? docAuthorizationService.canEditDoc(username, docId)
                : docAuthorizationService.canAccessDoc(username, docId);
        if (attrs != null) {
            attrs.put(cacheKey, allowed);
            if (requireEdit && allowed) {
                attrs.put("authz.access." + docId, true); // an editor can also read
            }
        }
        return allowed;
    }

    private String currentUsername(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get(USERNAME_ATTRIBUTE) instanceof String username) {
            return username;
        }
        Principal user = accessor.getUser();
        return user != null ? user.getName() : null;
    }

    // Every doc-scoped destination ends in the numeric document id (…/change/42, …/broadcast/changes/42).
    private Long parseDocId(String destination) {
        if (destination == null) {
            return null;
        }
        int lastSlash = destination.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == destination.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(destination.substring(lastSlash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
