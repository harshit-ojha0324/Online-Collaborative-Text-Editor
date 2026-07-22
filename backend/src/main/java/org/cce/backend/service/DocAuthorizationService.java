package org.cce.backend.service;

import org.cce.backend.entity.Doc;

public interface DocAuthorizationService {
    boolean canAccess(String username, Doc doc);
    boolean canEdit(String username, Doc doc);
    boolean fullAccess(String username, Doc doc);

    // Load-by-id variants for callers that only hold a document id (e.g. the WebSocket layer).
    boolean canAccessDoc(String username, Long docId);
    boolean canEditDoc(String username, Long docId);
}
