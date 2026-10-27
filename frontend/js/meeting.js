// frontend/js/meeting.js
// ============================================================
//  Meeting Room — Native WebSocket (no SockJS)
//
//  ROOT CAUSE FIX:
//  SockJS was hitting GET /ws/signaling/info which returned 403
//  from Spring Security. This meant the WebSocket connection
//  NEVER actually opened, so neither user ever sent a JOIN
//  message to the signaling server. Both users sat in their
//  own isolated state with no server-side room created.
//
//  Fix: use native WebSocket (ws://) directly — no SockJS,
//  no /info preflight, no tracking prevention interference.
// ============================================================

document.addEventListener('DOMContentLoaded', async () => {

    if (!Auth.requireAuth()) return;

    // ── Meeting code — URL is the single source of truth ─────────────────
    const urlParams    = new URLSearchParams(window.location.search);
    const MEETING_CODE = (urlParams.get('code') || '').trim().toLowerCase();

    console.log('[Meeting] Code from URL:', MEETING_CODE);

    if (!MEETING_CODE) {
        alert('No meeting code found in URL. Going back to dashboard.');
        window.location.href = 'dashboard.html';
        return;
    }

    const MY_NAME = Auth.getName();
    const MY_ID   = Auth.getUserId();

    console.log('[Meeting] User:', MY_NAME, '| ID:', MY_ID);

    // ── State ─────────────────────────────────────────────────────────────
    //  localStream holds our *live* camera + microphone tracks only.
    //  The screen share lives in its own stream and its own RTP sender (see "slots" below),
    //  so camera, microphone and screen can all be active — and stopped — independently.
    let localStream     = new MediaStream();
    let screenStream    = null;
    let ws              = null;
    let isScreenSharing = false;
    let isSidebarOpen   = true;
    let activeTab       = 'chat';          // 'chat' | 'participants'
    let unreadCount     = 0;
    let cameraBusy      = false;           // a camera on/off switch is in progress
    let toastTimer      = null;
    let meetingStartTime = Date.now();
    let clockInterval   = null;

    // Our own mic/camera/screen state — this is what we broadcast with MEDIA_STATE
    //   audio : 'on' | 'muted' | 'unavailable'  (unavailable = no mic / access denied — NOT the same as muted)
    //   video : 'on' | 'off'   | 'unavailable'
    const media = { audio: 'on', video: 'on', screen: false };

    let mySessionId     = null;   // our own signaling session id (sent by server in WELCOME)
    let leaving         = false;  // true once WE decided to leave/end (suppresses "disconnected" noise)

    const pendingCandidates = new Map(); // peerId → ICE candidates that arrived too early
    const disconnectTimers  = new Map(); // peerId → grace timer for transient 'disconnected'
    const peerConnections = new Map(); // peerId → RTCPeerConnection
    // peerId → { name, tile, screenTile, state, slots, camStream, camTrack, screenTrack, screenMs }
    const peers           = new Map();
    const peerStates      = new Map(); // peerId → last MEDIA_STATE (may arrive before the peer's tile exists)
    const pendingCreation = new Set(); // prevents duplicate connection attempts
    const screenOwners    = [];        // 'local' | peerId, in the order they started sharing (last = shown on stage)

    const DEFAULT_STATE = { audio: 'on', video: 'on', screen: false };

    const CFG = window.MEETSPHERE_CONFIG || {};
    const ICE_CONFIG = {
        iceServers: CFG.iceServers || [{ urls: 'stun:stun.l.google.com:19302' }],
    };

    // Small inline icons (static strings — never built from user input)
    const SVG = (body) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
    const ICON = {
        mic:    SVG('<path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/>'),
        micOff: SVG('<line x1="1" y1="1" x2="23" y2="23"/><path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"/><path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"/><line x1="12" y1="19" x2="12" y2="23"/>'),
        camOff: SVG('<path d="M16 16v1a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2m5.66 0H14a2 2 0 0 1 2 2v3.34l1 1L23 7v10"/><line x1="1" y1="1" x2="23" y2="23"/>'),
        screen: SVG('<rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>'),
    };

    // ── DOM ───────────────────────────────────────────────────────────────
    const videoGrid          = document.getElementById('videoGrid');
    const stageLayout        = document.getElementById('stageLayout');
    const stage              = document.getElementById('stage');
    const chatMessages       = document.getElementById('chatMessages');
    const chatInput          = document.getElementById('chatInput');
    const chatSendBtn        = document.getElementById('chatSendBtn');
    const chatBadge          = document.getElementById('chatBadge');
    const chatTabBadge       = document.getElementById('chatTabBadge');
    const sidebarEl          = document.getElementById('sidebar');
    const muteBtn            = document.getElementById('muteBtn');
    const cameraBtn          = document.getElementById('cameraBtn');
    const shareBtn           = document.getElementById('shareBtn');
    const participantsList   = document.getElementById('participantsList');
    const participantsCount  = document.getElementById('participantsCount');
    const screenShareBanner  = document.getElementById('screenShareBanner');
    const meetingLoading     = document.getElementById('meetingLoading');
    const meetingTitleEl     = document.getElementById('meetingTitle');
    const clockEl            = document.getElementById('meetingClock');
    const meetingCodeDisplay = document.getElementById('meetingCodeDisplay');
    const endBtn            = document.getElementById('endBtn');

    if (meetingCodeDisplay) meetingCodeDisplay.textContent = MEETING_CODE;
    clockInterval = setInterval(updateClock, 1000);

    // =========================================================
    //  STEP 1 — REST API: validate + join meeting in DB
    // =========================================================
    async function joinMeetingViaAPI() {
        try {
            console.log('[Meeting] Calling API join for code:', MEETING_CODE);
            const data = await MeetingAPI.join(MEETING_CODE);
            console.log('[Meeting] API join success:', data);

            const title = data.title || 'MeetSphere Meeting';
            sessionStorage.setItem('meetsphere_meetingTitle', title);
            if (meetingTitleEl) meetingTitleEl.textContent = title;

            // Host gets the "End for all" button
            // Host-only controls: End for all + Invite by email (the server enforces this too)
            if (String(data.hostId) === String(MY_ID)) {
                endBtn?.classList.remove('hidden');
                document.getElementById('inviteBtn')?.classList.remove('hidden');
            }

            if (data.chatHistory && data.chatHistory.length > 0) {
                addSystemMessage('— Previous messages —');
                data.chatHistory.forEach(m => displayChatMessage(
                    m.senderName, m.content,
                    m.senderId != null && String(m.senderId) === String(MY_ID),
                    m.sentAt));
                addSystemMessage('— Now —');
            }
            return true;
        } catch (err) {
            console.error('[Meeting] API join failed:', err);
            if (meetingLoading) meetingLoading.style.display = 'none';
            alert(`Could not join meeting.\n\nReason: ${err.message}\n\nRedirecting to dashboard.`);
            window.location.href = 'dashboard.html';
            return false;
        }
    }

    // =========================================================
    //  STEP 2 — Camera + microphone
    // =========================================================
    async function setupLocalMedia() {
        const AUDIO = { echoCancellation: true, noiseSuppression: true };
        const VIDEO = { width: { ideal: 1280 }, height: { ideal: 720 } };
        let audioErr = null, videoErr = null;
        let got = null;

        try {
            // Normal case: ask for both at once (one permission prompt)
            got = await navigator.mediaDevices.getUserMedia({ audio: AUDIO, video: VIDEO });
        } catch (_) {
            // One of them failed (no camera, camera busy, blocked…) — try each on its own
            // so a missing camera never costs us the microphone, and vice versa.
            got = new MediaStream();
            try { (await navigator.mediaDevices.getUserMedia({ audio: AUDIO })).getTracks().forEach(t => got.addTrack(t)); }
            catch (e) { audioErr = e; }
            try { (await navigator.mediaDevices.getUserMedia({ video: VIDEO })).getTracks().forEach(t => got.addTrack(t)); }
            catch (e) { videoErr = e; }
        }

        got.getAudioTracks().forEach(t => localStream.addTrack(t));
        got.getVideoTracks().forEach(t => localStream.addTrack(t));

        media.audio = localStream.getAudioTracks().length ? 'on' : 'unavailable';
        media.video = localStream.getVideoTracks().length ? 'on' : 'unavailable';

        if (media.video === 'unavailable') showToast(describeMediaError(videoErr, 'camera'));
        else if (media.audio === 'unavailable') showToast(describeMediaError(audioErr, 'microphone'));

        addLocalVideoTile();
        updateMediaButtons();
    }

    /** Human-readable reason for a getUserMedia failure */
    function describeMediaError(err, what) {
        const name = err && err.name;
        if (name === 'NotAllowedError' || name === 'SecurityError')
            return `${cap(what)} access is blocked. Allow it in your browser's site settings, then click the ${what} button.`;
        if (name === 'NotFoundError' || name === 'OverconstrainedError')
            return `No ${what} was found on this device.`;
        if (name === 'NotReadableError' || name === 'AbortError')
            return `Your ${what} is being used by another app.`;
        return `${cap(what)} is unavailable.`;
    }
    const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);

    // =========================================================
    //  STEP 3 — Native WebSocket (NO SockJS)
    //
    //  We connect to /ws/signaling on the same host (see js/config.js).
    //  No SockJS preflight to /info, no CDN, no tracking issues.
    // =========================================================
    function connectWebSocket() {
        // The JWT travels in the query string (browsers can't set headers on a WebSocket);
        // the server verifies it in the handshake and takes our identity from it.
        const base  = CFG.wsUrl || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/signaling`;
        const wsUrl = `${base}?token=${encodeURIComponent(Auth.getToken())}`;

        console.log('[Meeting] Connecting native WebSocket to:', base);

        ws = new WebSocket(wsUrl);

        ws.onopen = () => {
            console.log('[Meeting] ✅ WebSocket OPEN');

            // Send JOIN with the exact meeting code from the URL
            const joinMsg = {
                type:        'JOIN',
                meetingCode: MEETING_CODE,
                senderName:  MY_NAME,
            };
            console.log('[Meeting] Sending JOIN:', joinMsg);
            ws.send(JSON.stringify(joinMsg));
            broadcastMediaState();       // tell the room our mic/camera state (server also caches it for late joiners)

            if (meetingLoading) meetingLoading.style.display = 'none';
        };

        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                console.log('[Meeting] ← Received:', msg.type, msg);
                handleSignalingMessage(msg);
            } catch (e) {
                console.error('[Meeting] Parse error:', e, event.data);
            }
        };

        ws.onclose = (e) => {
            console.log('[Meeting] WebSocket CLOSED — code:', e.code, 'reason:', e.reason);
            if (meetingLoading) meetingLoading.style.display = 'none';
            if (!leaving) addSystemMessage(`⚠️ Disconnected from server (code ${e.code}${e.code === 1009 ? ': message too big' : ''}). Refresh to reconnect.`);
        };

        ws.onerror = (e) => {
            console.error('[Meeting] WebSocket ERROR:', e);
            if (meetingLoading) meetingLoading.style.display = 'none';
            addSystemMessage('⚠️ Cannot connect to the meeting server. Is the backend running, and are you still signed in?');
        };
    }

    // =========================================================
    //  Run all 3 steps
    // =========================================================
    const joined = await joinMeetingViaAPI();
    if (!joined) return;

    await setupLocalMedia();
    connectWebSocket();

    // =========================================================
    //  Signaling message handler
    // =========================================================
    async function handleSignalingMessage(msg) {
        const { type, senderId, senderName, payload } = msg;

        switch (type) {

            case 'WELCOME':
                mySessionId = senderId;
                break;

            case 'ERROR':
                leaving = true;
                alert(typeof payload === 'string' ? payload : 'Could not join the meeting.');
                cleanup();
                window.location.href = 'dashboard.html';
                break;

            case 'MEETING_ENDED':
                if (!leaving) {
                    leaving = true;
                    alert('The host ended this meeting.');
                    cleanup();
                    window.location.href = 'dashboard.html';
                }
                break;

            case 'EXISTING_PEERS':
                // We just joined. These are people already in the room.
                // They will send OFFERs to us — we just create connections
                // and wait. isInitiator = false.
                const existingList = Array.isArray(payload) ? payload : [];
                console.log('[Meeting] Existing peers in room:', existingList.length);
                for (const peer of existingList) {
                    // peer.mediaState = what that person's mic/camera/screen is doing right now
                    await createPeerConnection(peer.sessionId, peer.name, false, peer.mediaState);
                }
                break;

            case 'USER_JOINED':
                // Someone new joined our room.
                // WE send the OFFER to them. isInitiator = true.
                console.log('[Meeting] New user joined:', senderName, senderId);
                addSystemMessage(`${senderName || 'Someone'} joined`);
                await createPeerConnection(senderId, senderName, true);
                break;

            case 'USER_LEFT':
                console.log('[Meeting] User left:', senderName);
                addSystemMessage(`${senderName || 'Someone'} left`);
                removePeer(senderId);
                break;

            case 'MEDIA_STATE': {
                const st = normalizeState(payload);
                peerStates.set(senderId, st);
                const peer = peers.get(senderId);
                if (peer) {                       // if the tile doesn't exist yet it is applied when it is created
                    peer.state = st;
                    refreshPeer(senderId);
                    updateParticipantsList();
                }
                break;
            }

            case 'OFFER':
                console.log('[Meeting] Got OFFER from:', senderId);
                await handleOffer(senderId, senderName, payload);
                break;

            case 'ANSWER':
                console.log('[Meeting] Got ANSWER from:', senderId);
                await handleAnswer(senderId, payload);
                break;

            case 'ICE_CANDIDATE':
                await handleIceCandidate(senderId, payload);
                break;

            case 'CHAT': {
                const isOwn = senderId === mySessionId;
                displayChatMessage(senderName, payload, isOwn);
                // Unread badge is driven by INCOMING messages (never our own) while the chat is not on screen
                if (!isOwn && !isChatVisible()) {
                    unreadCount++;
                    updateUnreadUI();
                }
                break;
            }

            default:
                console.log('[Meeting] Unknown message type:', type);
        }
    }

    // =========================================================
    //  WebRTC: Create Peer Connection
    // =========================================================
    async function createPeerConnection(peerId, peerName, isInitiator, initialState) {
        if (peerConnections.has(peerId) || pendingCreation.has(peerId)) {
            console.log('[Meeting] Skipping duplicate connection to:', peerId);
            return;
        }
        pendingCreation.add(peerId);
        console.log('[Meeting] Creating RTCPeerConnection with:', peerName, '| initiator:', isInitiator);

        const pc = new RTCPeerConnection(ICE_CONFIG);
        peerConnections.set(peerId, pc);
        pendingCreation.delete(peerId);

        const state = normalizeState(initialState || peerStates.get(peerId));
        peerStates.set(peerId, state);

        const tile = addRemoteVideoTile(peerId, peerName);
        const peer = {
            name: peerName, tile, screenTile: null, state,
            slots: null, camStream: new MediaStream(), camTrack: null, screenTrack: null, screenMs: null,
        };
        peers.set(peerId, peer);
        tile.querySelector('video').srcObject = peer.camStream;   // camera + mic of that person, filled in by ontrack
        tryPlay(tile.querySelector('video'));

        // ── Three fixed "slots" per connection, always in this order ──────────────
        //   1. microphone   2. camera   3. screen share
        // Every slot is a send+receive transceiver, so turning the camera off/on or starting/stopping
        // a screen share is just replaceTrack() — no renegotiation, and the connection is never torn down.
        // The receiver tells the screen's video apart from the camera's video by m-line order
        // (1st video m-line = camera, 2nd = screen).
        //   • The side that sends the OFFER creates the slots here.
        //   • The side that ANSWERS binds its tracks to the transceivers the offer created
        //     (see bindAnswerSlots) — browsers do not reuse transceivers made with addTransceiver().
        if (isInitiator) {
            const micTrack = localStream.getAudioTracks()[0] || null;
            const camTrack = localStream.getVideoTracks()[0] || null;
            const scrTrack = (isScreenSharing && screenStream) ? screenStream.getVideoTracks()[0] : null;
            peer.slots = {
                audio:  pc.addTransceiver(micTrack || 'audio', { direction: 'sendrecv' }),
                cam:    pc.addTransceiver(camTrack || 'video', { direction: 'sendrecv' }),
                screen: pc.addTransceiver(scrTrack || 'video', { direction: 'sendrecv' }),
            };
        }

        // Incoming media → the right tile
        pc.ontrack = (e) => handleRemoteTrack(peerId, pc, e);

        updateParticipantsList();
        refreshPeer(peerId);

        // Send ICE candidates to the remote peer
        pc.onicecandidate = (e) => {
            if (e.candidate) {
                sendSignal({
                    type:        'ICE_CANDIDATE',
                    meetingCode: MEETING_CODE,
                    targetId:    peerId,
                    payload:     e.candidate,
                });
            }
        };

        pc.onconnectionstatechange = () => {
            console.log(`[Meeting] ${peerName} connection state:`, pc.connectionState);
            const st = pc.connectionState;
            if (st === 'connected') {
                clearTimeout(disconnectTimers.get(peerId));
                disconnectTimers.delete(peerId);
            } else if (st === 'disconnected') {
                // Often just a network blip — give it 10s to recover before dropping the peer
                if (!disconnectTimers.has(peerId)) {
                    disconnectTimers.set(peerId, setTimeout(() => {
                        disconnectTimers.delete(peerId);
                        if (pc.connectionState !== 'connected') removePeer(peerId);
                    }, 10000));
                }
            } else if (st === 'failed') {
                removePeer(peerId);
            }
        };

        // If we are the initiator (existing user), send the offer
        if (isInitiator) {
            try {
                const offer = await pc.createOffer({
                    offerToReceiveVideo: true,
                    offerToReceiveAudio: true,
                });
                await pc.setLocalDescription(offer);
                console.log('[Meeting] Sending OFFER to:', peerId);
                sendSignal({
                    type:        'OFFER',
                    meetingCode: MEETING_CODE,
                    targetId:    peerId,
                    payload:     pc.localDescription,
                });
            } catch (e) {
                console.error('[Meeting] Offer creation error:', e);
            }
        }

        return pc;
    }

    /** Answerer side: attach our mic / camera / screen to the transceivers created by the remote offer */
    async function bindAnswerSlots(pc, peer) {
        const all    = pc.getTransceivers();
        const audioT = all.filter(t => t.receiver.track.kind === 'audio');
        const videoT = all.filter(t => t.receiver.track.kind === 'video');   // m-line order: camera, screen
        peer.slots = { audio: audioT[0], cam: videoT[0], screen: videoT[1] };

        const tracks = {
            audio:  localStream.getAudioTracks()[0] || null,
            cam:    localStream.getVideoTracks()[0] || null,
            screen: (isScreenSharing && screenStream) ? screenStream.getVideoTracks()[0] : null,
        };
        for (const slot of ['audio', 'cam', 'screen']) {
            const t = peer.slots[slot];
            if (!t) continue;
            t.direction = 'sendrecv';                  // offer-created transceivers default to receive-only
            if (tracks[slot]) await t.sender.replaceTrack(tracks[slot]);
        }
    }

    async function handleOffer(senderId, senderName, sdp) {
        let pc = peerConnections.get(senderId);
        if (!pc) pc = await createPeerConnection(senderId, senderName, false);
        if (!pc) return;
        try {
            await pc.setRemoteDescription(new RTCSessionDescription(sdp));
            const peer = peers.get(senderId);
            if (peer && !peer.slots) await bindAnswerSlots(pc, peer);
            await flushPendingCandidates(senderId, pc);
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            console.log('[Meeting] Sending ANSWER to:', senderId);
            sendSignal({
                type:        'ANSWER',
                meetingCode: MEETING_CODE,
                targetId:    senderId,
                payload:     pc.localDescription,
            });
        } catch (e) { console.error('[Meeting] handleOffer error:', e); }
    }

    async function handleAnswer(senderId, sdp) {
        const pc = peerConnections.get(senderId);
        if (!pc || pc.signalingState === 'stable') return;
        try {
            await pc.setRemoteDescription(new RTCSessionDescription(sdp));
            await flushPendingCandidates(senderId, pc);
        } catch (e) { console.error('[Meeting] handleAnswer error:', e); }
    }

    async function handleIceCandidate(senderId, candidate) {
        if (!candidate) return;
        const pc = peerConnections.get(senderId);
        // Candidates can outrun the OFFER/ANSWER — queue them until the remote description is set
        if (!pc || !pc.remoteDescription) {
            if (!pendingCandidates.has(senderId)) pendingCandidates.set(senderId, []);
            pendingCandidates.get(senderId).push(candidate);
            return;
        }
        try {
            await pc.addIceCandidate(new RTCIceCandidate(candidate));
        } catch (e) { console.warn('[Meeting] ICE error (benign):', e.message); }
    }

    async function flushPendingCandidates(peerId, pc) {
        const queued = pendingCandidates.get(peerId) || [];
        pendingCandidates.delete(peerId);
        for (const c of queued) {
            try { await pc.addIceCandidate(new RTCIceCandidate(c)); }
            catch (e) { console.warn('[Meeting] queued ICE error (benign):', e.message); }
        }
    }

    /**
     * Which of the remote peer's two video streams is this — camera or screen?
     * Our SDP always lists the camera m-line before the screen m-line, so it's the order of video m-lines.
     */
    function videoRole(pc, transceiver) {
        const sdp = (pc.remoteDescription && pc.remoteDescription.sdp) || '';
        const videoMids = sdp.split(/\r?\nm=/).slice(1)
            .filter(sec => sec.startsWith('video'))
            .map(sec => { const m = sec.match(/\r?\na=mid:(\S+)/); return m ? m[1] : null; });
        return videoMids.indexOf(transceiver.mid) >= 1 ? 'screen' : 'camera';
    }

    function handleRemoteTrack(peerId, pc, e) {
        const peer = peers.get(peerId);
        if (!peer) return;
        const track = e.track;
        console.log('[Meeting] Got track from', peer.name, ':', track.kind);

        if (track.kind === 'audio') {
            peer.camStream.addTrack(track);                 // voice plays through the camera tile's <video>
            tryPlay(peer.tile.querySelector('video'));
            return;
        }

        if (videoRole(pc, e.transceiver) === 'camera') {
            peer.camTrack = track;
            peer.camStream.addTrack(track);
            // The track reports muted until frames flow — use it to avoid flashing a black tile
            track.onmute = track.onunmute = () => refreshPeerTile(peerId);
            tryPlay(peer.tile.querySelector('video'));
            refreshPeerTile(peerId);
        } else {
            peer.screenTrack = track;
            peer.screenMs = new MediaStream([track]);
            refreshPeerScreen(peerId);
        }
    }

    function removePeer(peerId) {
        clearTimeout(disconnectTimers.get(peerId));
        disconnectTimers.delete(peerId);
        pendingCandidates.delete(peerId);
        peerConnections.get(peerId)?.close();
        peerConnections.delete(peerId);
        const peer = peers.get(peerId);
        peer?.tile?.remove();
        peer?.screenTile?.remove();
        removeScreenOwner(peerId);
        peers.delete(peerId);
        peerStates.delete(peerId);
        updateLayout();
        updateParticipantsList();
    }

    // =========================================================
    //  Video tiles
    // =========================================================
    function normalizeState(p) {
        const st = { ...DEFAULT_STATE };
        if (p && typeof p === 'object') {
            if (['on', 'muted', 'unavailable'].includes(p.audio)) st.audio = p.audio;
            if (['on', 'off', 'unavailable'].includes(p.video))   st.video = p.video;
            st.screen = p.screen === true;
        }
        return st;
    }

    function getInitials(name) {
        return (name || 'U').split(' ').filter(Boolean).map(w => w[0]).join('').toUpperCase().slice(0, 2) || 'U';
    }

    function tryPlay(video) {
        try { const p = video && video.play && video.play(); if (p && p.catch) p.catch(() => {}); } catch (_) {}
    }
    // Browsers may block un-muted autoplay until the user interacts — retry on the first click/tap
    document.addEventListener('click', () => document.querySelectorAll('video').forEach(tryPlay), { once: true });

    /** Builds a tile with textContent only — names are user-controlled, never use innerHTML for them */
    function buildTile(className, id, displayName, withVideoMuted) {
        const tile = document.createElement('div');
        tile.className = className; tile.id = id;

        const video = document.createElement('video');
        video.autoplay = true; video.playsInline = true;
        if (withVideoMuted) video.muted = true;

        // Shown whenever the camera is off / unavailable / not yet delivering frames
        const avatar = document.createElement('div');
        avatar.className = 'video-avatar';
        const circle = document.createElement('div');
        circle.className = 'avatar-circle';
        circle.textContent = getInitials(displayName);
        const nameEl = document.createElement('div');
        nameEl.className = 'avatar-name';
        nameEl.textContent = displayName;
        const note = document.createElement('div');
        note.className = 'avatar-note';
        note.innerHTML = ICON.camOff + '<span></span>';
        avatar.append(circle, nameEl, note);

        const overlay = document.createElement('div');
        overlay.className = 'tile-overlay';
        const tag = document.createElement('span');
        tag.className = 'tile-name';
        tag.textContent = displayName;
        const status = document.createElement('div');
        status.className = 'tile-status';
        overlay.append(tag, status);

        tile.append(video, avatar, overlay);
        return tile;
    }

    /** One badge (mic muted / no mic / camera off) */
    function badge(kind, icon, title) {
        return `<span class="tile-badge ${kind}" title="${title}" aria-label="${title}">${icon}</span>`;
    }

    /** The badges for a given mic/camera state (used on tiles and in the participants list) */
    function statusBadges(audio, video) {
        let html = '';
        if (audio === 'muted')            html += badge('muted',   ICON.micOff, 'Microphone muted');
        else if (audio === 'unavailable') html += badge('unavail', ICON.mic,    'No microphone available');
        if (video === 'off')              html += badge('unavail', ICON.camOff, 'Camera off');
        else if (video === 'unavailable') html += badge('unavail', ICON.camOff, 'No camera available');
        return html;
    }

    /** Applies mic / camera state to a camera tile */
    function renderTile(tile, audio, video, videoLive) {
        if (!tile) return;
        tile.classList.toggle('show-avatar', !(video === 'on' && videoLive));
        tile.classList.toggle('has-note', video !== 'on');
        const noteText = tile.querySelector('.avatar-note span');
        if (noteText) noteText.textContent = video === 'unavailable' ? 'No camera' : 'Camera off';
        const status = tile.querySelector('.tile-status');
        if (status) status.innerHTML = statusBadges(audio, video);
    }

    function renderLocalTile() {
        const cam = localStream.getVideoTracks()[0];
        renderTile(document.getElementById('localTile'), media.audio, media.video, !!cam && cam.readyState === 'live');
    }

    function refreshPeerTile(peerId) {
        const p = peers.get(peerId);
        if (!p) return;
        // "on" + a track that is actually delivering frames → show video, otherwise the avatar
        renderTile(p.tile, p.state.audio, p.state.video, !!p.camTrack && !p.camTrack.muted);
    }

    /** Creates / removes the presentation tile of a remote participant to match their state + received track */
    function refreshPeerScreen(peerId) {
        const p = peers.get(peerId);
        if (!p) return;
        if (p.state.screen && p.screenTrack) {
            if (!p.screenTile) {
                p.screenTile = buildScreenTile(`screen-${peerId}`, peerId, `${p.name} is presenting`, true);
                p.screenTile.querySelector('video').srcObject = p.screenMs;
                stage.appendChild(p.screenTile);
                tryPlay(p.screenTile.querySelector('video'));
            }
            addScreenOwner(peerId);
        } else {
            if (p.screenTile) { p.screenTile.remove(); p.screenTile = null; }
            removeScreenOwner(peerId);
        }
        updateLayout();
    }

    function refreshPeer(peerId) {
        refreshPeerTile(peerId);
        refreshPeerScreen(peerId);
    }

    /** A presentation tile for the stage. Local one is a placeholder (no preview → no "hall of mirrors"). */
    function buildScreenTile(id, owner, label, withVideo) {
        const tile = document.createElement('div');
        tile.className = 'video-tile screen-tile';
        tile.id = id; tile.dataset.owner = owner;

        if (withVideo) {
            const video = document.createElement('video');
            video.autoplay = true; video.playsInline = true; video.muted = true;   // screens carry no audio
            tile.appendChild(video);
        } else {
            const box = document.createElement('div');
            box.className = 'screen-local';
            box.innerHTML = ICON.screen + '<h3>You\'re presenting your screen</h3><p>Everyone in the meeting can see it.</p><button type="button">Stop presenting</button>';
            box.querySelector('button').addEventListener('click', stopScreenShare);
            tile.appendChild(box);
        }

        const chip = document.createElement('div');
        chip.className = 'screen-chip';
        chip.innerHTML = ICON.screen + '<span></span>';
        chip.querySelector('span').textContent = label;
        tile.appendChild(chip);
        return tile;
    }

    function addScreenOwner(id)    { if (!screenOwners.includes(id)) screenOwners.push(id); }
    function removeScreenOwner(id) { const i = screenOwners.indexOf(id); if (i >= 0) screenOwners.splice(i, 1); }

    /**
     * Presenting layout: when anyone shares, their screen takes the stage and every camera tile
     * moves into a strip (right side on desktop, bottom on phones). If several people share at once
     * the most recent one is shown. When nobody shares we are back to the normal grid.
     */
    function updateLayout() {
        const primary = screenOwners.length ? screenOwners[screenOwners.length - 1] : null;
        stage.querySelectorAll('.screen-tile').forEach(t => {
            t.style.display = (t.dataset.owner === primary) ? '' : 'none';
        });
        stageLayout.classList.toggle('presenting', primary !== null);
        updateVideoGrid();
    }

    function addLocalVideoTile() {
        document.getElementById('localTile')?.remove();
        const tile = buildTile('video-tile local', 'localTile', `${MY_NAME} (You)`, true);
        tile.querySelector('video').srcObject = localStream;
        videoGrid.appendChild(tile);
        renderLocalTile();
        tryPlay(tile.querySelector('video'));
        updateVideoGrid();
    }

    function addRemoteVideoTile(peerId, peerName) {
        document.getElementById(`tile-${peerId}`)?.remove();
        const tile = buildTile('video-tile remote', `tile-${peerId}`, peerName || 'Participant', false);
        videoGrid.appendChild(tile);
        updateVideoGrid();
        return tile;
    }

    function updateVideoGrid() {
        const n = videoGrid.querySelectorAll('.video-tile').length;
        videoGrid.className = 'video-grid';
        if (stageLayout.classList.contains('presenting')) videoGrid.classList.add('strip');
        else videoGrid.classList.add(n <= 1 ? 'grid-1' : n === 2 ? 'grid-2' : n <= 4 ? 'grid-3' : 'grid-many');
    }

    // =========================================================
    //  Controls
    // =========================================================

    /** Tell everyone our current mic / camera / screen state (server relays + caches it) */
    function broadcastMediaState() {
        sendSignal({
            type: 'MEDIA_STATE',
            meetingCode: MEETING_CODE,
            payload: { audio: media.audio, video: media.video, screen: media.screen },
        });
    }

    /** Puts a track (or null) into the same slot of every peer connection — no renegotiation needed */
    function setSlotTrack(slot, track) {
        peers.forEach(p => {
            const sender = p.slots?.[slot]?.sender;
            if (sender) sender.replaceTrack(track).catch(console.error);
        });
    }

    /** Keeps the three control buttons + our own tile in sync with `media` */
    function updateMediaButtons() {
        // Microphone
        const mutedLike = media.audio !== 'on';
        muteBtn?.classList.toggle('on', !mutedLike);
        muteBtn?.classList.toggle('off', mutedLike);
        if (muteBtn) {
            muteBtn.firstChild.textContent = media.audio === 'on' ? '🎤' : '🔇';
            ensureLabel(muteBtn, media.audio === 'on' ? 'Mute' : media.audio === 'muted' ? 'Unmute' : 'No mic');
            muteBtn.title = media.audio === 'unavailable' ? 'No microphone available — click to try again' : 'Mute / Unmute';
        }
        // Camera
        const camOn = media.video === 'on';
        cameraBtn?.classList.toggle('on', camOn);
        cameraBtn?.classList.toggle('off', !camOn);
        if (cameraBtn) {
            cameraBtn.firstChild.textContent = camOn ? '📷' : '📵';
            ensureLabel(cameraBtn, camOn ? 'Stop Camera' : media.video === 'off' ? 'Start Camera' : 'No camera');
            cameraBtn.title = media.video === 'unavailable' ? 'No camera available — click to try again' : 'Camera ON / OFF';
        }
        // Screen share
        shareBtn?.classList.toggle('sharing', isScreenSharing);
        ensureLabel(shareBtn, isScreenSharing ? 'Stop Share' : 'Share Screen');
        screenShareBanner?.classList.toggle('show', isScreenSharing);

        renderLocalTile();
        updateParticipantsList();
    }

    // ── Microphone ────────────────────────────────────────────────────────
    muteBtn?.addEventListener('click', async () => {
        if (media.audio === 'unavailable') {
            // Maybe permission was granted since, or a device was plugged in — try again
            try {
                const s = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
                const track = s.getAudioTracks()[0];
                localStream.addTrack(track);
                setSlotTrack('audio', track);
                media.audio = 'on';
            } catch (err) { showToast(describeMediaError(err, 'microphone')); return; }
        } else {
            media.audio = media.audio === 'muted' ? 'on' : 'muted';
            // enabled=false keeps the connection (and the track) alive; remote side just hears silence
            localStream.getAudioTracks().forEach(t => { t.enabled = media.audio === 'on'; });
        }
        updateMediaButtons();
        broadcastMediaState();
    });

    // ── Camera ────────────────────────────────────────────────────────────
    cameraBtn?.addEventListener('click', async () => {
        if (cameraBusy) return;
        cameraBusy = true;
        try {
            if (media.video === 'on') {
                // Turn OFF: really stop the track (camera light goes out) and stop sending video.
                // The peer connection stays up; the camera slot is simply emptied.
                localStream.getVideoTracks().forEach(t => { t.stop(); localStream.removeTrack(t); });
                setSlotTrack('cam', null);
                media.video = 'off';
            } else {
                // Turn ON (or retry after "unavailable")
                try {
                    const s = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 } } });
                    const track = s.getVideoTracks()[0];
                    localStream.addTrack(track);
                    setSlotTrack('cam', track);
                    media.video = 'on';
                    tryPlay(document.querySelector('#localTile video'));
                } catch (err) {
                    media.video = (err.name === 'NotAllowedError' || err.name === 'NotFoundError') ? 'unavailable' : 'off';
                    showToast(describeMediaError(err, 'camera'));
                }
            }
            updateMediaButtons();
            broadcastMediaState();
        } finally { cameraBusy = false; }
    });

    // ── Screen sharing ────────────────────────────────────────────────────
    if (shareBtn && !(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)) {
        // e.g. most phone browsers
        shareBtn.disabled = true;
        shareBtn.style.opacity = '.45';
        shareBtn.title = "Screen sharing isn't supported on this device or browser";
    }

    shareBtn?.addEventListener('click', () => { isScreenSharing ? stopScreenShare() : startScreenShare(); });

    async function startScreenShare() {
        if (isScreenSharing) return;
        let stream;
        try {
            stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
        } catch (e) {
            // NotAllowedError = the user closed the picker or the browser/OS refused permission
            if (e.name === 'NotAllowedError' || e.name === 'AbortError') showToast('Screen sharing was cancelled or blocked.');
            else showToast('Could not start screen sharing: ' + (e.message || e.name));
            return;
        }
        screenStream = stream;
        const track = stream.getVideoTracks()[0];
        // Fires when the user clicks the browser's own "Stop sharing" bar, or the shared window closes
        track.addEventListener('ended', stopScreenShare, { once: true });

        isScreenSharing = true;
        media.screen = true;

        // The screen goes out on its OWN slot — the camera slot is not touched, so both stay visible
        setSlotTrack('screen', track);

        addScreenOwner('local');
        const tile = buildScreenTile('screen-local', 'local', 'You are presenting', false);
        stage.appendChild(tile);
        updateLayout();
        updateMediaButtons();
        broadcastMediaState();
    }

    function stopScreenShare() {
        if (!isScreenSharing) return;
        isScreenSharing = false;
        media.screen = false;

        // Stop ONLY the screen tracks — camera and microphone tracks are separate and untouched
        const old = screenStream;
        screenStream = null;
        old?.getTracks().forEach(t => t.stop());
        setSlotTrack('screen', null);

        document.getElementById('screen-local')?.remove();
        removeScreenOwner('local');
        updateLayout();
        updateMediaButtons();
        broadcastMediaState();
    }

    endBtn?.addEventListener('click', async () => {
        if (!confirm('End this meeting for everyone?')) return;
        leaving = true;
        try { await MeetingAPI.end(MEETING_CODE); }
        catch (err) { leaving = false; alert('Could not end the meeting: ' + err.message); return; }
        cleanup();
        window.location.href = 'dashboard.html';
    });

    document.getElementById('leaveBtn')?.addEventListener('click', async () => {
        leaving = true;
        sendSignal({ type: 'LEAVE', meetingCode: MEETING_CODE, senderName: MY_NAME });
        try { await MeetingAPI.leave(MEETING_CODE); } catch (_) {}
        cleanup();
        window.location.href = 'dashboard.html';
    });

    function cleanup() {
        clearInterval(clockInterval);
        disconnectTimers.forEach(t => clearTimeout(t)); disconnectTimers.clear();
        localStream.getTracks().forEach(t => t.stop());
        screenStream?.getTracks().forEach(t => t.stop());
        peerConnections.forEach(pc => pc.close());
        peerConnections.clear(); peers.clear(); peerStates.clear();
        try { ws?.close(); } catch (_) {}
    }

    // On phones/small windows the sidebar is an overlay — start closed so it doesn't hide the video
    if (window.matchMedia('(max-width: 860px)').matches) {
        isSidebarOpen = false;
        sidebarEl?.classList.add('collapsed');
    }

    document.getElementById('sidebarToggleBtn')?.addEventListener('click', () => {
        isSidebarOpen = !isSidebarOpen;
        sidebarEl?.classList.toggle('collapsed', !isSidebarOpen);
        if (isChatVisible()) markChatRead();
    });

    document.querySelectorAll('.sidebar-tab').forEach(tab => {
        tab.addEventListener('click', () => {
            const n = tab.dataset.tab;
            activeTab = n;
            document.querySelectorAll('.sidebar-tab').forEach(t =>
                t.classList.toggle('active', t.dataset.tab === n));
            document.querySelectorAll('.sidebar-panel').forEach(p =>
                p.classList.toggle('active', p.id === n + 'Panel'));
            if (isChatVisible()) markChatRead();
        });
    });

    // =========================================================
    //  Unread chat notifications
    //  "Visible" = sidebar open AND Chat tab selected AND this browser tab is in the foreground.
    // =========================================================
    function isChatVisible() {
        return isSidebarOpen && activeTab === 'chat' && !document.hidden;
    }

    function markChatRead() {
        unreadCount = 0;
        updateUnreadUI();
        if (chatMessages) chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    function updateUnreadUI() {
        const label = unreadCount > 9 ? '9+' : String(unreadCount);
        [chatBadge, chatTabBadge].forEach(el => {
            if (!el) return;
            const had = !el.classList.contains('hidden');
            el.classList.toggle('hidden', unreadCount === 0);
            el.textContent = unreadCount === 0 ? '' : label;
            if (el === chatBadge && unreadCount > 0) {          // replay the little "pop" on every new message
                el.style.animation = 'none'; void el.offsetWidth; el.style.animation = '';
            }
        });
        if (chatBadge) chatBadge.setAttribute('aria-label', `${unreadCount} unread message${unreadCount === 1 ? '' : 's'}`);
        document.title = (unreadCount > 0 ? `(${unreadCount}) ` : '') + 'Meeting — MeetSphere';
    }

    // Coming back to this browser tab while the chat is open counts as reading
    document.addEventListener('visibilitychange', () => { if (isChatVisible()) markChatRead(); });

    // =========================================================
    //  Chat
    // =========================================================
    chatSendBtn?.addEventListener('click', sendChatMessage);
    chatInput?.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChatMessage(); }
    });

    function sendChatMessage() {
        const text = chatInput?.value.trim();
        if (!text) return;
        sendSignal({ type: 'CHAT', meetingCode: MEETING_CODE, senderName: MY_NAME, payload: text });
        chatInput.value = ''; chatInput.focus();
    }

    function displayChatMessage(senderName, text, isOwn, sentAt) {
        const el = document.createElement('div');
        el.className = `chat-message ${isOwn ? 'own' : 'other'}`;
        const when = sentAt ? new Date(sentAt) : new Date();
        const time = (isNaN(when) ? new Date() : when)
            .toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        el.innerHTML = `<div class="msg-bubble">${!isOwn ? `<div class="msg-sender">${escapeHtml(senderName)}</div>` : ''}<div class="msg-text">${escapeHtml(String(text))}</div><div class="msg-time">${time}</div></div>`;
        chatMessages?.appendChild(el);
        if (chatMessages) chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    function addSystemMessage(text) {
        const el = document.createElement('div');
        el.className = 'system-message'; el.textContent = text;
        chatMessages?.appendChild(el);
        if (chatMessages) chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    // =========================================================
    //  Participants
    // =========================================================
    function updateParticipantsList() {
        if (!participantsList) return;
        const all = [
            { name: MY_NAME + ' (You)', isMe: true, audio: media.audio, video: media.video },
            ...Array.from(peers.values()).map(p => ({ name: p.name, isMe: false, audio: p.state.audio, video: p.state.video })),
        ];
        if (participantsCount) {
            participantsCount.textContent = `${all.length} participant${all.length !== 1 ? 's' : ''}`;
        }
        participantsList.innerHTML = all.map(p => {
            const i = getInitials(p.name);
            const icons = statusBadges(p.audio, p.video) || `<span class="participant-mic" title="Microphone on">${ICON.mic}</span>`;
            return `<div class="participant-item"><div class="participant-avatar">${escapeHtml(i)}</div><div class="participant-info"><div class="participant-name">${escapeHtml(p.name)}</div><div class="participant-status">${p.isMe ? 'You' : 'In meeting'}</div></div><div class="participant-state">${icons}</div></div>`;
        }).join('');
    }
    updateParticipantsList();

    // =========================================================
    //  Copy link
    // =========================================================
    document.getElementById('copyCodeBtn')?.addEventListener('click', () => {
        // Built from the current page, so it is correct wherever the frontend is hosted
        const linkUrl = new URL('meeting.html', window.location.href);
        linkUrl.search = '';
        linkUrl.searchParams.set('code', MEETING_CODE);
        const link = linkUrl.toString();
        navigator.clipboard.writeText(link)
            .then(() => {
                const btn = document.getElementById('copyCodeBtn');
                const saved = btn.innerHTML;
                btn.innerHTML = '✅<span class="ctrl-label">Copied!</span>';
                setTimeout(() => { btn.innerHTML = saved; }, 2000);
            })
            .catch(() => prompt('Copy this link:', link));
    });

    document.querySelector('.meeting-code-display')?.addEventListener('click', () => {
        navigator.clipboard.writeText(MEETING_CODE).then(() => {
            const el = document.querySelector('.code-text');
            if (!el) return;
            const orig = el.textContent;
            el.textContent = 'Copied!';
            setTimeout(() => { el.textContent = orig; }, 1500);
        }).catch(console.error);
    });

    // =========================================================
    //  Utilities
    // =========================================================
    function sendSignal(msg) {
        if (!ws) { console.warn('[Meeting] sendSignal: ws is null'); return; }
        if (ws.readyState === WebSocket.OPEN) {
            console.log('[Meeting] → Sending:', msg.type, msg);
            ws.send(JSON.stringify(msg));
        } else {
            console.warn('[Meeting] sendSignal dropped, readyState:', ws.readyState, msg.type);
        }
    }

    function showToast(text, ms = 5000) {
        let el = document.getElementById('meetingToast');
        if (!el) {
            el = document.createElement('div');
            el.id = 'meetingToast'; el.className = 'meeting-toast'; el.setAttribute('role', 'status');
            document.body.appendChild(el);
        }
        el.textContent = text;
        el.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => el.classList.remove('show'), ms);
    }

    function updateClock() {
        if (!clockEl) return;
        const s = Math.floor((Date.now() - meetingStartTime) / 1000);
        const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
        const p = n => String(n).padStart(2, '0');
        clockEl.textContent = h > 0 ? `${p(h)}:${p(m)}:${p(ss)}` : `${p(m)}:${p(ss)}`;
    }

    function ensureLabel(btn, text) {
        if (!btn) return;
        let lbl = btn.querySelector('.ctrl-label');
        if (!lbl) { lbl = document.createElement('span'); lbl.className = 'ctrl-label'; btn.appendChild(lbl); }
        lbl.textContent = text;
    }

    function escapeHtml(str) {
        const d = document.createElement('div');
        d.appendChild(document.createTextNode(str || ''));
        return d.innerHTML;
    }

    window.addEventListener('beforeunload', () => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'LEAVE', meetingCode: MEETING_CODE, senderName: MY_NAME }));
        }
        localStream.getTracks().forEach(t => t.stop());
    });
});