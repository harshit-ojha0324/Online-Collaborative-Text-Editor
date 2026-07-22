package org.cce.backend.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
public class Crdt {
    private static final Logger log = LoggerFactory.getLogger(Crdt.class);

    private HashMap<String, Item> crdtMap;
    private Item firstItem;

    public Crdt() {
        crdtMap = new HashMap<>();
    }

    public Crdt(byte[] bytes) {
        InitCrdt(bytes);
    }

    public void InitCrdt(byte[] bytes) {
        crdtMap = new HashMap<>();
        firstItem = null;
        List<?> list = getDeserializedList(bytes);
        if (list.isEmpty()) {
            return;
        }
        Object head = list.get(0);
        if (head instanceof PersistedItem) {
            loadFromPersisted(castPersisted(list));
        } else if (head instanceof Item) {
            loadFromLegacy(castLegacy(list));
        } else {
            log.warn("Unknown persisted CRDT element type {}; starting from empty document",
                    head.getClass().getName());
        }
    }

    public Item getItem(String id) {
        return crdtMap.getOrDefault(id, null);
    }

    /**
     * Integrates an item at the position defined by its origin-left / origin-right neighbours (the
     * ids the author saw when typing). Concurrent items competing for the same gap are ordered by the
     * total order {@link #compareIds}, so every replica—and every browser client running the same
     * rule—converges to the same sequence regardless of the order ops arrive in. A missing origin
     * (an op that raced ahead of its neighbour) degrades to a head/tail placement rather than
     * throwing; strict causal buffering is a known future improvement.
     */
    public void insert(String key, Item item) {
        Item left = item.getLeft();                  // origin-left node (null = before the head)
        Item originRight = item.getRight();          // origin-right node (null = after the tail)
        Item scan = (left == null) ? firstItem : left.getRight();
        while (scan != null && scan != originRight && compareIds(scan.getId(), item.getId()) < 0) {
            left = scan;
            scan = scan.getRight();
        }
        item.setLeft(left);
        item.setRight(scan);
        if (left == null) {
            firstItem = item;
        } else {
            left.setRight(item);
        }
        if (scan != null) {
            scan.setLeft(item);
        }
        crdtMap.put(item.getId(), item);
    }

    public void delete(String key) {
        Item item = crdtMap.get(key);
        if (item == null)
            return;
        item.setIsdeleted(true);
        item.setOperation("delete");
    }

    public void format(String key, boolean bold, boolean italic) {
        Item item = crdtMap.get(key);
        if (item == null)
            return;
        item.setIsbold(bold);
        item.setIsitalic(italic);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        Item current = firstItem;
        while (current != null) {
            if (!current.isIsdeleted())
                sb.append(current.getContent());
            current = current.getRight();
        }
        return sb.toString();
    }

    public List<Item> getItems() {
        List<Item> items = new ArrayList<>();
        Item current = firstItem;
        while (current != null) {
            items.add(current);
            current = current.getRight();
        }
        return items;
    }

    /**
     * Serializes the document as a flat, id-linked list that preserves the original {@code
     * counter@clientId} identifiers <em>and</em> tombstones (deleted items). This keeps the
     * persisted state a real, mergeable CRDT so that a reload restores stable identity — unlike a
     * renumbered, tombstone-stripped snapshot, which would break convergence. Links are stored as id
     * strings rather than object references, so serialization is O(n) and cannot overflow the stack
     * on a long document. Tombstones are retained for correctness; garbage-collecting them safely
     * would require a version-vector scheme and is left as a future improvement.
     */
    public byte[] getSerializedCrdt() {
        ArrayList<PersistedItem> snapshot = new ArrayList<>();
        Item current = firstItem;
        while (current != null) {
            snapshot.add(new PersistedItem(
                    current.getId(),
                    current.getContent(),
                    current.getLeft() == null ? null : current.getLeft().getId(),
                    current.getRight() == null ? null : current.getRight().getId(),
                    current.isIsdeleted(),
                    current.isIsbold(),
                    current.isIsitalic()));
            current = current.getRight();
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(snapshot);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize CRDT", e);
        }
    }

    private void loadFromPersisted(List<PersistedItem> items) {
        for (PersistedItem persisted : items) {
            Item item = Item.builder()
                    .id(persisted.id)
                    .content(persisted.content)
                    .isdeleted(persisted.isdeleted)
                    .isbold(persisted.isbold)
                    .isitalic(persisted.isitalic)
                    .operation(persisted.isdeleted ? "delete" : "insert")
                    .build();
            crdtMap.put(persisted.id, item);
        }
        for (PersistedItem persisted : items) {
            Item item = crdtMap.get(persisted.id);
            item.setLeft(persisted.leftId == null ? null : crdtMap.get(persisted.leftId));
            item.setRight(persisted.rightId == null ? null : crdtMap.get(persisted.rightId));
        }
        firstItem = crdtMap.get(items.get(0).id);
    }

    // Backwards compatibility with documents persisted in the original object-graph format.
    private void loadFromLegacy(List<Item> items) {
        for (Item item : items) {
            crdtMap.put(item.getId(), item);
        }
        firstItem = items.get(0);
    }

    private List<?> getDeserializedList(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                ObjectInputStream in = new ObjectInputStream(bis)) {
            Object obj = in.readObject();
            if (obj instanceof List<?> list) {
                return list;
            }
            return new ArrayList<>();
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            // A corrupt or incompatible blob must not prevent the document from opening.
            log.warn("Failed to deserialize persisted CRDT; starting from empty document", e);
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<PersistedItem> castPersisted(List<?> list) {
        return (List<PersistedItem>) list;
    }

    @SuppressWarnings("unchecked")
    private List<Item> castLegacy(List<?> list) {
        return (List<Item>) list;
    }

    /**
     * Total order over unique {@code counter@username} ids: primarily by username, then by numeric
     * counter. Because ids are globally unique, this never ties for distinct items, so every replica
     * orders concurrent inserts identically and converges to the same document.
     */
    static int compareIds(String a, String b) {
        String[] pa = a.split("@");
        String[] pb = b.split("@");
        String userA = pa.length > 1 ? pa[1] : "";
        String userB = pb.length > 1 ? pb[1] : "";
        int byUser = userA.compareTo(userB);
        if (byUser != 0) {
            return byUser;
        }
        return Integer.compare(parseCounter(pa[0]), parseCounter(pb[0]));
    }

    private static int parseCounter(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Flat, self-contained serialization record — no object references, so no deep recursion. */
    private static class PersistedItem implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        final String content;
        final String leftId;
        final String rightId;
        final boolean isdeleted;
        final boolean isbold;
        final boolean isitalic;

        PersistedItem(String id, String content, String leftId, String rightId,
                boolean isdeleted, boolean isbold, boolean isitalic) {
            this.id = id;
            this.content = content;
            this.leftId = leftId;
            this.rightId = rightId;
            this.isdeleted = isdeleted;
            this.isbold = isbold;
            this.isitalic = isitalic;
        }
    }
}
