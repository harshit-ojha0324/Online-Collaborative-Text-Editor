package org.cce.backend.service;

import org.cce.backend.entity.Doc;
import org.cce.backend.enums.Permission;
import org.cce.backend.repository.DocRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocAuthorizationServiceImpl implements DocAuthorizationService {

    @Autowired
    DocRepository docRepository;

    @Override
    public boolean canAccess(String username, Doc doc) {
        return isOwner(username, doc)
                || doc.getSharedWith().stream()
                        .anyMatch(userDoc -> userDoc.getUser().getUsername().equals(username));
    }

    @Override
    public boolean canEdit(String username, Doc doc) {
        return isOwner(username, doc) || doc.getSharedWith().stream()
                .anyMatch(userDoc -> userDoc.getUser().getUsername().equals(username)
                        && userDoc.getPermission().equals(Permission.EDIT));
    }

    @Override
    public boolean fullAccess(String username, Doc doc) {
        return isOwner(username, doc);
    }

    private boolean isOwner(String username, Doc doc) {
        return doc.getOwner() != null && doc.getOwner().getUsername().equals(username);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAccessDoc(String username, Long docId) {
        return docRepository.findById(docId).map(doc -> canAccess(username, doc)).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canEditDoc(String username, Long docId) {
        return docRepository.findById(docId).map(doc -> canEdit(username, doc)).orElse(false);
    }
}
