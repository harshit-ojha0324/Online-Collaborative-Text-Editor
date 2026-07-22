package org.cce.backend.engine;

import jakarta.annotation.PreDestroy;
import org.cce.backend.repository.DocRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class CrdtManagerService {
    private static final Logger log = LoggerFactory.getLogger(CrdtManagerService.class);

    @Autowired
    private DocRepository docRepository;
    private final ConcurrentHashMap<Long, Crdt> crdtMap = new ConcurrentHashMap<>();

    public Crdt getCrdt(Long docId) {
        return crdtMap.get(docId);
    }

    public void createCrdt(Long docId) {
        crdtMap.computeIfAbsent(docId, id -> {
            byte[] crdtContent = docRepository.getDocById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id))
                    .getContent();
            Crdt crdt = new Crdt();
            crdt.InitCrdt(crdtContent);
            return crdt;
        });
    }

    /**
     * Serializes the in-memory document back to the DB without evicting it. Taken under the
     * per-document monitor so it never observes a half-applied edit.
     */
    public void saveCrdt(Long docId) {
        Crdt crdt = crdtMap.get(docId);
        if (crdt == null) {
            return;
        }
        byte[] content;
        synchronized (crdt) {
            content = crdt.getSerializedCrdt();
        }
        docRepository.findById(docId).ifPresent(doc -> {
            doc.setContent(content);
            docRepository.save(doc);
        });
    }

    /** Persists then evicts — used when the last collaborator disconnects. */
    public synchronized void saveAndDeleteCrdt(Long docId) {
        if (!crdtMap.containsKey(docId)) {
            return;
        }
        saveCrdt(docId);
        deleteCrdt(docId);
    }

    /**
     * Periodic checkpoint of every open document so edits survive a crash or redeploy, not just a
     * graceful last-user disconnect. Interval is configurable via {@code crdt.snapshot.interval-ms}.
     */
    @Scheduled(fixedDelayString = "${crdt.snapshot.interval-ms:15000}")
    public void snapshotAllCrdts() {
        for (Long docId : crdtMap.keySet()) {
            try {
                saveCrdt(docId);
            } catch (Exception e) {
                log.warn("Failed to snapshot document {}", docId, e);
            }
        }
    }

    /** Flush everything still held in memory when the application shuts down. */
    @PreDestroy
    public void flushOnShutdown() {
        snapshotAllCrdts();
    }

    private void deleteCrdt(Long docId) {
        crdtMap.remove(docId);
    }
}
