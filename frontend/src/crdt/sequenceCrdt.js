// A sequence CRDT that mirrors the server's engine (backend org.cce.backend.engine.Crdt) exactly, so
// the browser and the server converge to the same document. Each character is a node with a globally
// unique id "counter@username" and links to its neighbours; concurrent inserts competing for the same
// gap are ordered by the total order `compareIds`. The module is deliberately free of React and Quill
// so it can be unit-tested in isolation (see sequenceCrdt.test.js).

/**
 * Total order over unique ids "counter@username": primarily by username, then by numeric counter.
 * Because ids are unique this never ties for distinct items, so every replica orders concurrent
 * inserts identically. The numeric counter comparison is what a naive string compare got wrong
 * (e.g. "10@u" vs "2@u").
 */
export function compareIds(a, b) {
    const atName = a.slice(a.indexOf('@') + 1);
    const btName = b.slice(b.indexOf('@') + 1);
    if (atName < btName) return -1;
    if (atName > btName) return 1;
    const ac = parseInt(a.slice(0, a.indexOf('@')), 10) || 0;
    const bc = parseInt(b.slice(0, b.indexOf('@')), 10) || 0;
    return ac - bc;
}

export class SequenceCrdt {
    constructor() {
        this.items = new Map(); // id -> { id, content, leftId, rightId, deleted, bold, italic }
        this.firstId = null;
    }

    has(id) {
        return this.items.has(id);
    }

    get(id) {
        return this.items.get(id) || null;
    }

    /** Rebuild from the server's ordered change list (GET /api/docs/changes). */
    load(changes) {
        this.items = new Map();
        this.firstId = null;
        for (const c of changes) {
            this.items.set(c.id, {
                id: c.id,
                content: c.content,
                leftId: c.left ?? null,
                rightId: c.right ?? null,
                deleted: !!c.isdeleted,
                bold: !!c.isbold,
                italic: !!c.isitalic,
            });
            if (c.left === null || c.left === undefined) this.firstId = c.id;
        }
    }

    /**
     * Integrates an item at the position implied by its origin neighbours. Idempotent: re-delivering
     * an id is a no-op. Returns the integrated item.
     */
    integrate({ id, content, leftId, rightId, deleted = false, bold = false, italic = false }) {
        if (this.items.has(id)) return this.items.get(id);

        let left = leftId && this.items.has(leftId) ? leftId : null;
        const originRight = rightId && this.items.has(rightId) ? rightId : null;
        let scanId = left === null ? this.firstId : this.items.get(left).rightId;
        while (scanId !== null && scanId !== originRight && compareIds(scanId, id) < 0) {
            left = scanId;
            scanId = this.items.get(scanId).rightId;
        }

        const item = { id, content, leftId: left, rightId: scanId, deleted, bold, italic };
        this.items.set(id, item);
        if (left === null) {
            this.firstId = id;
        } else {
            this.items.get(left).rightId = id;
        }
        if (scanId !== null) {
            this.items.get(scanId).leftId = id;
        }
        return item;
    }

    /**
     * Inserts a character typed at visible (Quill) position `index`, using the current visible
     * neighbours as its origins. Returns the wire op to broadcast.
     */
    localInsert(index, id, content, bold = false, italic = false) {
        const visible = this.visibleIds();
        const leftId = index > 0 ? visible[index - 1] : null;
        const rightId = index < visible.length ? visible[index] : null;
        this.integrate({ id, content, leftId, rightId, bold, italic });
        return { id, content, left: leftId, right: rightId, operation: 'insert', isbold: bold, isitalic: italic };
    }

    markDeleted(id) {
        const item = this.items.get(id);
        if (item) item.deleted = true;
    }

    setFormat(id, bold, italic) {
        const item = this.items.get(id);
        if (item) {
            item.bold = bold;
            item.italic = italic;
        }
    }

    /**
     * Number of visible characters before `id` — i.e. its Quill index. Returns -1 if the id is
     * unknown OR is a tombstone (so callers never map a deleted node onto a visible neighbour).
     */
    indexOfVisible(id) {
        let idx = 0;
        let cur = this.firstId;
        while (cur !== null) {
            const item = this.items.get(cur);
            if (cur === id) return item.deleted ? -1 : idx;
            if (!item.deleted) idx += 1;
            cur = item.rightId;
        }
        return -1;
    }

    /** Ordered ids of the non-deleted characters (the Quill visual order). */
    visibleIds() {
        const out = [];
        let cur = this.firstId;
        while (cur !== null) {
            const item = this.items.get(cur);
            if (!item.deleted) out.push(cur);
            cur = item.rightId;
        }
        return out;
    }

    toString() {
        let text = '';
        let cur = this.firstId;
        while (cur !== null) {
            const item = this.items.get(cur);
            if (!item.deleted) text += item.content;
            cur = item.rightId;
        }
        return text;
    }
}
