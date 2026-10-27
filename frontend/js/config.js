// frontend/js/config.js
// ============================================================
//  Central runtime configuration for the frontend.
//
//  NORMAL CASE (recommended): Spring Boot serves the pages AND the API, so everything is the same origin:
//      pages    http://localhost:8080/pages/login.html
//      REST     /api/...                (relative path)
//      WebSocket ws://localhost:8080/ws/signaling   (wss:// automatically when the site uses https)
//  → no CORS, no hard-coded host, and the same build works on localhost or on a real domain.
//
//  DEV FALLBACK: if the pages are opened through a separate static server (VS Code Live Server on :5500,
//  :3000, ...) or straight from disk (file://), the backend is assumed to be on the same host at :8080.
// ============================================================
window.MEETSPHERE_CONFIG = (() => {
    const DEV_STATIC_PORTS = ['5500', '5501', '5502', '3000', '5173', '4200'];
    const separateFrontend = location.protocol === 'file:' || DEV_STATIC_PORTS.includes(location.port);

    let apiBase, wsUrl;
    if (separateFrontend) {
        const host = location.hostname || 'localhost';
        apiBase = `http://${host}:8080/api`;
        wsUrl   = `ws://${host}:8080/ws/signaling`;
    } else {
        apiBase = '/api';
        wsUrl   = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/signaling`;
    }

    return {
        apiBase,
        wsUrl,

        // STUN alone fails for many real-world networks (corporate / mobile / symmetric NAT).
        // Add a TURN server for reliable calls — e.g. self-hosted coturn or a hosted provider:
        iceServers: [
            { urls: 'stun:stun.l.google.com:19302' },
            { urls: 'stun:stun1.l.google.com:19302' },
            // {
            //     urls: 'turn:turn.example.com:3478',
            //     username: 'YOUR_TURN_USERNAME',
            //     credential: 'YOUR_TURN_PASSWORD',
            // },
        ],
    };
})();
