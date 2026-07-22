import NavBar from "../../components/NavBar/NavBar";
import ReactQuill, {Quill} from 'react-quill';
import 'react-quill/dist/quill.snow.css';
import {useState, useRef, useEffect} from 'react';
import './editor.css'
import {useParams, useLocation} from "react-router-dom";
import {useSubscription, useStompClient} from "react-stomp-hooks";
import Delta from 'quill-delta';
import QuillCursors from "quill-cursors";
import loadingGif from "../../assets/loading.gif";
import {SequenceCrdt} from "../../crdt/sequenceCrdt.js";
import {apiFetch} from "../../api.js";

Quill.register('modules/cursors', QuillCursors);

// Stable per-username cursor colour (so a collaborator keeps the same colour instead of flickering).
function colorFor(name) {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
    return `hsl(${Math.abs(hash) % 360}, 70%, 45%)`;
}

export default function Edit() {
    const quillRef = useRef(null);
    // The CRDT and the id counter are refs, not state: they are mutable models the STOMP callbacks must
    // read at their latest value, and they must never trigger a re-render. Keeping them in useState was
    // the source of the stale-closure bugs where remote ops were applied against outdated data.
    const crdtRef = useRef(new SequenceCrdt());
    const counterRef = useRef(0);
    const cursorRef = useRef(null);

    const {docId} = useParams();
    const {state} = useLocation();
    const [loading, setLoading] = useState(true);
    const [loadingContent, setLoadingContent] = useState(true);
    const [username] = useState(localStorage.getItem('username'));
    // A per-tab client id (username + random nonce) so the same user editing in two tabs still mints
    // globally-unique character ids; otherwise both tabs would emit e.g. "6@alice" and silently drop
    // each other's characters. The nonce lives in the id's site segment, so the total order is unaffected.
    const [clientId] = useState(() => `${username}#${Math.random().toString(36).slice(2, 8)}`);
    const [cursor, setCursor] = useState(null);
    const [currentUsers, setCurrentUsers] = useState([]);
    const [isOwner, setIsOwner] = useState(false);
    const [isEditor, setIsEditor] = useState(false);

    const stompClient = useStompClient();

    const publish = (body) => {
        if (stompClient) {
            stompClient.publish({destination: `/docs/change/${docId}`, body: JSON.stringify(body)});
        }
    };

    useEffect(() => {
        apiFetch(`/api/docs/${docId}`).then(res => res.json()).then(data => {
            setIsOwner(data.owner === username);
            setIsEditor(data.sharedWith.some(user => user.username === username && user.permission === 'EDIT'));
            setLoading(false);
        }).catch(err => {
            console.error(err);
        });
        // Runs once on mount to load this document's permissions.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (!quillRef.current) return;
        const cursorsModule = quillRef.current.getEditor().getModule('cursors');
        setCursor(cursorsModule);
        cursorRef.current = cursorsModule;

        apiFetch(`/api/docs/changes/${docId}`).then(res => res.json()).then(data => {
            const crdt = crdtRef.current;
            crdt.load(data);

            const quill = quillRef.current.getEditor();
            const delta = new Delta();
            crdt.visibleIds().forEach((id) => {
                const item = crdt.get(id);
                delta.insert(item.content, {bold: item.bold, italic: item.italic});
            });
            quill.updateContents(delta, "silent");
            setLoadingContent(false);
        }).catch(err => {
            console.error(err);
        });
        // Hydrates the editor once Quill has mounted (i.e. once loading flips false).
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [loading]);

    useEffect(() => {
        if (!cursor) return;
        currentUsers.forEach((name) => {
            if (name !== username) cursor.createCursor(name, name, colorFor(name));
        });
    }, [cursor, currentUsers, username]);

    useSubscription(`/docs/broadcast/usernames/${docId}`, (msg) => {
        const incomingUsername = JSON.parse(msg.body).usernames;
        if (incomingUsername === null) return;
        setCurrentUsers(incomingUsername);
    });

    useSubscription(`/docs/broadcast/cursors/${docId}`, (msg) => {
        const incomingCursor = JSON.parse(msg.body);
        if (incomingCursor === null || incomingCursor.username === username) return;
        const cursors = cursorRef.current;
        if (!cursors) return;
        cursors.createCursor(incomingCursor.username, incomingCursor.username, colorFor(incomingCursor.username));
        cursors.moveCursor(incomingCursor.username, {index: incomingCursor.index, length: incomingCursor.length});
    });

    useSubscription(`/docs/broadcast/changes/${docId}`, (msg) => {
        const incoming = JSON.parse(msg.body);
        if (incoming === null) return;
        const crdt = crdtRef.current;
        const quill = quillRef.current?.getEditor();
        if (!quill) return;

        if (incoming.operation === 'format') {
            const item = crdt.get(incoming.id);
            if (!item || item.deleted || (item.bold === incoming.isbold && item.italic === incoming.isitalic)) return;
            crdt.setFormat(incoming.id, incoming.isbold, incoming.isitalic);
            const index = crdt.indexOfVisible(incoming.id);
            if (index < 0) return;
            quill.updateContents(
                new Delta().retain(index).retain(1, {bold: incoming.isbold, italic: incoming.isitalic}), "silent");
            return;
        }

        if (incoming.operation === 'delete') {
            const item = crdt.get(incoming.id);
            if (!item || item.deleted) return;
            const index = crdt.indexOfVisible(incoming.id);
            crdt.markDeleted(incoming.id);
            if (index >= 0) quill.updateContents(new Delta().retain(index).delete(1), "silent");
            return;
        }

        // insert — skip our own echo and any duplicate delivery (integrate is id-idempotent anyway).
        if (crdt.has(incoming.id)) return;
        crdt.integrate({
            id: incoming.id,
            content: incoming.content,
            leftId: incoming.left ?? null,
            rightId: incoming.right ?? null,
            bold: !!incoming.isbold,
            italic: !!incoming.isitalic,
        });
        const index = crdt.indexOfVisible(incoming.id);
        if (index < 0) return;
        quill.updateContents(
            new Delta().retain(index).insert(incoming.content, {bold: !!incoming.isbold, italic: !!incoming.isitalic}),
            "silent");
    });

    // Translate a Quill change delta into CRDT operations. Every op in the delta is processed (not just
    // the last one), so multi-character inserts, pastes, and select-and-replace all work correctly.
    const handleChange = (content, delta, source) => {
        if (source === 'silent') return;
        const crdt = crdtRef.current;
        let cursorIndex = 0;

        for (const op of delta.ops) {
            if (op.retain !== undefined) {
                if (op.attributes) {
                    const visible = crdt.visibleIds();
                    for (let k = 0; k < op.retain; k++) {
                        const id = visible[cursorIndex + k];
                        if (id === undefined) continue;
                        const item = crdt.get(id);
                        const bold = 'bold' in op.attributes ? !!op.attributes.bold : item.bold;
                        const italic = 'italic' in op.attributes ? !!op.attributes.italic : item.italic;
                        crdt.setFormat(id, bold, italic);
                        publish({id, operation: 'format', isbold: bold, isitalic: italic});
                    }
                }
                cursorIndex += op.retain;
            } else if (typeof op.insert === 'string') {
                const attrs = op.attributes || {};
                for (const ch of op.insert) {
                    const id = `${counterRef.current++}@${clientId}`;
                    const wireOp = crdt.localInsert(cursorIndex, id, ch, !!attrs.bold, !!attrs.italic);
                    publish(wireOp);
                    cursorIndex += 1;
                }
            } else if (op.delete !== undefined) {
                const toDelete = crdt.visibleIds().slice(cursorIndex, cursorIndex + op.delete);
                for (const id of toDelete) {
                    crdt.markDeleted(id);
                    publish({id, operation: 'delete'});
                }
                // cursorIndex stays: deleted characters are gone from the visible sequence.
            }
        }
    };

    return (<>
        {loading && <div className="h-screen w-screen bg-[#f1f3f4] flex justify-center items-center">
            <img src={loadingGif} className="m-auto" alt="Loading"/>
        </div>}
        {!loading && <>
            <NavBar title={state} signedin={loading} setsignedin={setLoading} usernames={currentUsers}/>

            <div className="bg-[#f1f3f4] flex justify-center p-4 min-h-screen">
                <div className="w-10/12 lg:w-8/12 text-black bg-white">
                    <div id="toolbar" className='flex justify-center '>
                        <button className="ql-bold" aria-label="Bold"/>
                        <button className="ql-italic" aria-label="Italic"/>
                    </div>
                    <ReactQuill
                        ref={quillRef}
                        readOnly={(!isEditor && !isOwner) || loadingContent}
                        onChange={handleChange}
                        onChangeSelection={(range) => {
                            if (!range) return;
                            publishCursor(range);
                        }}
                        modules={{
                            toolbar: ['bold', 'italic'], cursors: {selectionChangeSource: 'test'}
                        }}
                    />
                </div>
            </div>
        </>}
    </>);

    function publishCursor(range) {
        if (stompClient) {
            stompClient.publish({
                destination: `/docs/cursor/${docId}`,
                body: JSON.stringify({username: username, index: range.index, length: range.length})
            });
        }
    }
}
