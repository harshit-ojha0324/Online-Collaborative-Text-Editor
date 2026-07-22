package org.cce.backend.service;


import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${jwt.secret-key:}")
    private String secretKey;
    private JwtParser parser;


    @Autowired
    private UserDetailsService userDetailsService;

    @PostConstruct
    public void initJwtService() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set the JWT_SECRET_KEY environment variable "
                            + "to a Base64-encoded key of at least 256 bits, e.g. `openssl rand -base64 48`.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "JWT_SECRET_KEY is not valid Base64. Generate one with `openssl rand -base64 48`.", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET_KEY must decode to at least 256 bits (32 bytes) for HS256; got "
                            + keyBytes.length + " bytes. Generate one with `openssl rand -base64 48`.");
        }
        parser = Jwts.parser().setSigningKey(getSignInKey()).build();
    }

    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private Claims extractAllClaims(String token) throws JwtException {
        return parser.parseClaimsJws(token).getBody();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String generateToken(String username) {
        return generateToken(new HashMap<>(), username);
    }

    public String generateToken(Map<String, Object> extraClaims, String username) {
        int keyDuration =  60 * 60 * 1000;
        return Jwts.builder().setClaims(extraClaims).setSubject(username).setIssuedAt(new Date(System.currentTimeMillis())).setExpiration(new Date(System.currentTimeMillis() + keyDuration)).setHeaderParam("typ", "JWT").signWith(getSignInKey(), SignatureAlgorithm.HS256).compact();

    }

    public boolean validateUserAndToken(String token) {
        String username = extractUsername(token);
        if (username == null) {
            return false;
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        return isTokenValid(token, userDetails);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        boolean isTokenValid = username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        return isTokenValid;
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
}
