package org.cce.backend.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the sequence CRDT: total-order tie-breaking, convergence of concurrent
 * operations regardless of delivery order, and serialization that preserves stable ids and tombstones.
 */
class CrdtTest {

    /** One insert operation as it would arrive over the wire (id, content, and the neighbour ids). */
    private record Op(String id, String content, String leftId, String rightId) {}

    private static void apply(Crdt crdt, Op op) {
        Item item = new Item(op.id(), op.content(),
                crdt.getItem(op.rightId()), crdt.getItem(op.leftId()),
                "insert", false, false, false);
        crdt.insert(op.id(), item);
    }

    private static Crdt replicaOf(List<Op> ops) {
        Crdt crdt = new Crdt();
        for (Op op : ops) {
            apply(crdt, op);
        }
        return crdt;
    }

    // ---- total order -------------------------------------------------------

    @Test
    void compareIds_ordersByUsernameThenNumericCounter() {
        assertTrue(Crdt.compareIds("1@alice", "1@bob") < 0, "alice precedes bob");
        assertTrue(Crdt.compareIds("1@bob", "1@alice") > 0, "bob follows alice");
        assertEquals(0, Crdt.compareIds("1@alice", "1@alice"), "identical ids tie");
        // The whole point of the fix: 2 must precede 10 numerically, where a string compare would fail.
        assertTrue(Crdt.compareIds("2@alice", "10@alice") < 0, "counter is compared numerically");
        assertTrue(Crdt.compareIds("10@alice", "2@alice") > 0, "counter is compared numerically");
    }

    // ---- convergence -------------------------------------------------------

    @Test
    void concurrentHeadInserts_converge() {
        Op a = new Op("1@alice", "A", null, null);
        Op b = new Op("1@bob", "B", null, null);

        Crdt r1 = replicaOf(List.of(a, b));
        Crdt r2 = replicaOf(List.of(b, a));

        assertEquals(r1.toString(), r2.toString());
        assertEquals("AB", r1.toString()); // deterministic: ascending id order
    }

    @Test
    void concurrentMiddleInserts_converge() {
        Op x = new Op("1@s", "X", null, null);
        Op y = new Op("2@s", "Y", "1@s", null);
        // alice and bob both type into the same gap between X and Y, each unaware of the other.
        Op a = new Op("1@alice", "a", "1@s", "2@s");
        Op b = new Op("1@bob", "b", "1@s", "2@s");

        Crdt r1 = replicaOf(List.of(x, y, a, b));
        Crdt r2 = replicaOf(List.of(x, y, b, a));

        assertEquals(r1.toString(), r2.toString());
        assertEquals("XabY", r1.toString());
    }

    @Test
    void concurrentInsertAndDelete_converge() {
        Op a = new Op("1@s", "A", null, null);
        Op b = new Op("2@s", "B", "1@s", null);

        // Replica 1: build AB, delete A, then a concurrent insert of C after A's origin.
        Crdt r1 = replicaOf(List.of(a, b));
        r1.delete("1@s");
        apply(r1, new Op("1@alice", "C", "1@s", "2@s"));

        // Replica 2: same ops, delete applied last.
        Crdt r2 = replicaOf(List.of(a, b));
        apply(r2, new Op("1@alice", "C", "1@s", "2@s"));
        r2.delete("1@s");

        assertEquals(r1.toString(), r2.toString());
        assertEquals("CB", r1.toString());
    }

    @Test
    void manyConcurrentInserts_convergeUnderEveryDeliveryOrder() {
        // Ten users each insert one character into the same gap between X and Y.
        List<Op> base = List.of(
                new Op("1@s", "X", null, null),
                new Op("2@s", "Y", "1@s", null));
        List<Op> concurrent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            concurrent.add(new Op("1@user" + i, String.valueOf((char) ('0' + i)), "1@s", "2@s"));
        }

        String expected = null;
        Random random = new Random(42);
        for (int trial = 0; trial < 200; trial++) {
            List<Op> shuffled = new ArrayList<>(concurrent);
            Collections.shuffle(shuffled, random);
            List<Op> ops = new ArrayList<>(base);
            ops.addAll(shuffled);
            String result = replicaOf(ops).toString();
            if (expected == null) {
                expected = result;
            } else {
                assertEquals(expected, result, "diverged on trial " + trial);
            }
        }
        assertNotNull(expected);
        assertTrue(expected.startsWith("X") && expected.endsWith("Y"));
        assertEquals(12, expected.length()); // X + 10 chars + Y
    }

    // ---- serialization -----------------------------------------------------

    @Test
    void serializationRoundTrip_preservesStableIdsAndTombstones() {
        Crdt crdt = replicaOf(List.of(
                new Op("1@u", "A", null, null),
                new Op("2@u", "B", "1@u", null)));
        crdt.delete("1@u"); // tombstone A; visible text is now "B"

        Crdt restored = new Crdt(crdt.getSerializedCrdt());

        assertEquals("B", restored.toString(), "visible text survives a round-trip");
        assertEquals(2, restored.getItems().size(), "the tombstone is retained, not dropped");

        Item tombstone = restored.getItem("1@u");
        assertNotNull(tombstone, "the original id is preserved, not renumbered");
        assertTrue(tombstone.isIsdeleted(), "the tombstone flag survives");
        assertEquals("A", tombstone.getContent());

        Item kept = restored.getItem("2@u");
        assertNotNull(kept);
        assertEquals("B", kept.getContent());
    }

    @Test
    void serializedStateStillMergesLateOps() {
        // A late op that references a tombstoned id still integrates after reload, because ids are stable.
        Crdt crdt = replicaOf(List.of(
                new Op("1@u", "A", null, null),
                new Op("2@u", "B", "1@u", null)));
        crdt.delete("1@u");

        Crdt restored = new Crdt(crdt.getSerializedCrdt());
        apply(restored, new Op("1@z", "C", "1@u", "2@u")); // origin-left is the tombstone

        assertEquals("CB", restored.toString());
    }

    @Test
    void emptyContent_deserializesToEmptyDocument() {
        Crdt crdt = new Crdt(new byte[0]);
        assertEquals("", crdt.toString());
        assertTrue(crdt.getItems().isEmpty());
    }
}
