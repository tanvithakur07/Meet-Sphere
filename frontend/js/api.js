// frontend/js/api.js
// ============================================================
//  API Client — centralizes all HTTP calls to the Spring Boot backend
//  All functions return Promises and throw on HTTP errors
// ============================================================

const API_BASE = (window.MEETSPHERE_CONFIG && window.MEETSPHERE_CONFIG.apiBase) || '/api';

/**
 * Generic fetch wrapper with JWT authorization header.
 * Automatically includes the token from localStorage.
 */
async function apiRequest(endpoint, options = {}) {
    const token = localStorage.getItem('meetsphere_token');

    const config = {
        headers: {
            'Content-Type': 'application/json',
            // Attach JWT if available
            ...(token && { 'Authorization': `Bearer ${token}` }),
            ...options.headers,
        },
        ...options,
    };

    const response = await fetch(`${API_BASE}${endpoint}`, config);

    // Parse response body
    const data = await response.json().catch(() => ({}));

    // Expired / invalid session: clear it and go to login (remember where we were).
    // Login/register themselves legitimately return 401 for bad credentials — don't redirect those.
    // (Other /auth/ endpoints such as change-password DO need a valid session, so an expired one redirects.)
    const isCredentialCheck = endpoint === '/auth/login' || endpoint === '/auth/register';
    if (response.status === 401 && token && !isCredentialCheck) {
        Auth.clear();
        sessionStorage.setItem('meetsphere_redirect', window.location.href);
        window.location.href = 'login.html';
        throw new Error('Your session expired. Please sign in again.');
    }

    // Throw a meaningful error for non-2xx responses
    if (!response.ok) {
        const err = new Error(data.error || `HTTP ${response.status}`);
        err.status = response.status;   // callers can react to specific codes (e.g. 429, 502)
        err.data = data;                // and read extra fields the backend sent (e.g. "failed")
        throw err;
    }

    return data;
}

// ── Auth APIs ────────────────────────────────────────────────────────────

/** Register a new user */
const AuthAPI = {
    register: (name, email, password) =>
        apiRequest('/auth/register', {
            method: 'POST',
            body: JSON.stringify({ name, email, password }),
        }),

    login: (email, password) =>
        apiRequest('/auth/login', {
            method: 'POST',
            body: JSON.stringify({ email, password }),
        }),

    /** Logged-in user changes their own password → { message, token }. The account comes from the JWT, not from this body. */
    changePassword: (currentPassword, newPassword, confirmPassword) =>
        apiRequest('/auth/change-password', {
            method: 'POST',
            body: JSON.stringify({ currentPassword, newPassword, confirmPassword }),
        }),
};

// ── Meeting APIs ─────────────────────────────────────────────────────────

const MeetingAPI = {
    /** Create a new meeting room */
    create: (title) =>
        apiRequest('/meetings/create', {
            method: 'POST',
            body: JSON.stringify({ title }),
        }),

    /** Join an existing meeting by code */
    join: (meetingCode) =>
        apiRequest(`/meetings/join/${meetingCode}`, {
            method: 'POST',
        }),

    /** Leave a meeting */
    leave: (meetingCode) =>
        apiRequest(`/meetings/leave/${meetingCode}`, {
            method: 'POST',
        }),

    /** Host only: email the meeting invitation to one or more addresses → { message, sent, failed } */
    invite: (meetingCode, emails) =>
        apiRequest(`/meetings/${encodeURIComponent(meetingCode)}/invite`, {
            method: 'POST',
            body: JSON.stringify({ emails }),
        }),

    /** Host only: end the meeting for everyone */
    end: (meetingCode) =>
        apiRequest(`/meetings/end/${meetingCode}`, {
            method: 'POST',
        }),

    /** Get active participants of a meeting */
    getParticipants: (meetingCode) =>
        apiRequest(`/meetings/${meetingCode}/participants`),
};

// ── Auth State Helpers ────────────────────────────────────────────────────

const Auth = {
    /** Save login data to localStorage */
    save: (data) => {
        localStorage.setItem('meetsphere_token',   data.token);
        localStorage.setItem('meetsphere_userId',  String(data.userId));
        localStorage.setItem('meetsphere_name',    data.name);
        localStorage.setItem('meetsphere_email',   data.email || '');
    },

    /** Clear all auth data (logout) */
    clear: () => {
        localStorage.removeItem('meetsphere_token');
        localStorage.removeItem('meetsphere_userId');
        localStorage.removeItem('meetsphere_name');
        localStorage.removeItem('meetsphere_email');
    },

    /** True if a token exists AND has not expired (exp is read from the JWT payload) */
    isLoggedIn: () => {
        const t = localStorage.getItem('meetsphere_token');
        if (!t) return false;
        try {
            const payload = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
            if (payload.exp && payload.exp * 1000 < Date.now()) {
                Auth.clear();
                return false;
            }
        } catch (_) { /* not decodable — let the server decide */ }
        return true;
    },

    /** Where to go after login: the page the user originally wanted (e.g. a shared meeting link) */
    consumeRedirect: () => {
        const url = sessionStorage.getItem('meetsphere_redirect');
        sessionStorage.removeItem('meetsphere_redirect');
        try {
            // only same-origin targets, never an arbitrary external URL
            if (url && new URL(url).origin === window.location.origin) return url;
        } catch (_) {}
        return null;
    },

    /** Get stored user name */
    getName:  () => localStorage.getItem('meetsphere_name') || 'Guest',
    getUserId: () => localStorage.getItem('meetsphere_userId'),
    getToken: () => localStorage.getItem('meetsphere_token'),

    /** Redirect to login if not authenticated */
    requireAuth: () => {
        if (!Auth.isLoggedIn()) {
            // remember the page (with ?code=...) so login can send the user back
            sessionStorage.setItem('meetsphere_redirect', window.location.href);
            window.location.href = 'login.html';   // relative: works wherever the frontend is hosted
            return false;
        }
        return true;
    },
};
