import { describe, it, expect } from 'vitest';
import { SequenceCrdt, compareIds } from './sequenceCrdt.js';

// Apply a wire op (as broadcast by the server) to a replica.
function apply(crdt, op) {
    if (op.operation === 'delete') {
        crdt.markDeleted(op.id);
    } else if (op.operation === 'format') {
        crdt.setFormat(op.id, op.isbold, op.isitalic);
    } else {
        crdt.integrate({
            id: op.id,
            content: op.content,
            leftId: op.left ?? null,
            rightId: op.right ?? null,
            bold: !!op.isbold,
            italic: !!op.isitalic,
        });
    }
}

function replicaOf(ops) {
    const crdt = new SequenceCrdt();
    ops.forEach((op) => apply(crdt, op));
    return crdt;
}

const ins = (id, content, left, right) => ({ id, content, left, right, operation: 'insert' });

describe('compareIds total order', () => {
    it('orders by username then numeric counter', () => {
        expect(compareIds('1@alice', '1@bob')).toBeLessThan(0);
        expect(compareIds('1@bob', '1@alice')).toBeGreaterThan(0);
        expect(compareIds('1@alice', '1@alice')).toBe(0);
        // The fix: 2 precedes 10 numerically, where a string compare would put "10" first.
        expect(compareIds('2@alice', '10@alice')).toBeLessThan(0);
        expect(compareIds('10@alice', '2@alice')).toBeGreaterThan(0);
    });
});

describe('convergence', () => {
    it('concurrent head inserts converge', () => {
        const a = ins('1@alice', 'A', null, null);
        const b = ins('1@bob', 'B', null, null);
        const r1 = replicaOf([a, b]);
        const r2 = replicaOf([b, a]);
        expect(r1.toString()).toBe(r2.toString());
        expect(r1.toString()).toBe('AB');
    });

    it('concurrent middle inserts converge', () => {
        const x = ins('1@s', 'X', null, null);
        const y = ins('2@s', 'Y', '1@s', null);
        const a = ins('1@alice', 'a', '1@s', '2@s');
        const b = ins('1@bob', 'b', '1@s', '2@s');
        const r1 = replicaOf([x, y, a, b]);
        const r2 = replicaOf([x, y, b, a]);
        expect(r1.toString()).toBe(r2.toString());
        expect(r1.toString()).toBe('XabY');
    });

    it('concurrent insert and delete converge', () => {
        const a = ins('1@s', 'A', null, null);
        const b = ins('2@s', 'B', '1@s', null);

        const r1 = replicaOf([a, b]);
        r1.markDeleted('1@s');
        apply(r1, ins('1@alice', 'C', '1@s', '2@s'));

        const r2 = replicaOf([a, b]);
        apply(r2, ins('1@alice', 'C', '1@s', '2@s'));
        r2.markDeleted('1@s');

        expect(r1.toString()).toBe(r2.toString());
        expect(r1.toString()).toBe('CB');
    });

    it('re-delivering the same op is idempotent', () => {
        const a = ins('1@s', 'A', null, null);
        const b = ins('2@s', 'B', '1@s', null);
        const r = replicaOf([a, b, b, a]); // duplicates
        expect(r.toString()).toBe('AB');
        expect(r.visibleIds().length).toBe(2);
    });

    it('converges under every delivery order of many concurrent inserts', () => {
        const base = [ins('1@s', 'X', null, null), ins('2@s', 'Y', '1@s', null)];
        const concurrent = [];
        for (let i = 0; i < 10; i++) {
            concurrent.push(ins(`1@user${i}`, String(i), '1@s', '2@s'));
        }
        let expected = null;
        for (let trial = 0; trial < 200; trial++) {
            const shuffled = [...concurrent];
            for (let i = shuffled.length - 1; i > 0; i--) {
                // deterministic Fisher–Yates so failures reproduce
                const j = (i * 2654435761 + trial * 40503) % (i + 1);
                [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
            }
            const result = replicaOf([...base, ...shuffled]).toString();
            if (expected === null) expected = result;
            else expect(result).toBe(expected);
        }
        expect(expected.startsWith('X')).toBe(true);
        expect(expected.endsWith('Y')).toBe(true);
        expect(expected.length).toBe(12);
    });
});

describe('visible index mapping', () => {
    it('tracks Quill indices across inserts and a tombstone', () => {
        const crdt = new SequenceCrdt();
        apply(crdt, ins('1@u', 'A', null, null));
        apply(crdt, ins('2@u', 'B', '1@u', null));
        apply(crdt, ins('3@u', 'C', '2@u', null)); // ABC
        expect(crdt.visibleIds()).toEqual(['1@u', '2@u', '3@u']);
        expect(crdt.indexOfVisible('3@u')).toBe(2);

        crdt.markDeleted('2@u'); // A C
        expect(crdt.toString()).toBe('AC');
        expect(crdt.indexOfVisible('3@u')).toBe(1); // C shifts left past the tombstone
        expect(crdt.indexOfVisible('2@u')).toBe(-1); // a tombstone maps to no visible index
    });

    it('localInsert emits ops with the right origin neighbours', () => {
        const crdt = new SequenceCrdt();
        const op0 = crdt.localInsert(0, '1@u', 'H');
        expect(op0).toMatchObject({ left: null, right: null });
        const op1 = crdt.localInsert(1, '2@u', 'I'); // "HI"
        expect(op1).toMatchObject({ left: '1@u', right: null });
        const op2 = crdt.localInsert(1, '3@u', 'X'); // "HXI"
        expect(op2).toMatchObject({ left: '1@u', right: '2@u' });
        expect(crdt.toString()).toBe('HXI');
    });
});

describe('load from server changes', () => {
    it('rehydrates order and tombstones from the persisted change list', () => {
        const crdt = new SequenceCrdt();
        crdt.load([
            { id: '1@u', content: 'A', left: null, right: '2@u', isdeleted: true, isbold: false, isitalic: false },
            { id: '2@u', content: 'B', left: '1@u', right: null, isdeleted: false, isbold: false, isitalic: false },
        ]);
        expect(crdt.toString()).toBe('B');
        expect(crdt.visibleIds()).toEqual(['2@u']);
        // A late op referencing the tombstone still integrates because ids are stable.
        apply(crdt, ins('1@z', 'C', '1@u', '2@u'));
        expect(crdt.toString()).toBe('CB');
    });
});
