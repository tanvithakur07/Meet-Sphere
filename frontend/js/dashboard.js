// frontend/js/dashboard.js
// ============================================================
//  Dashboard Page Logic — create / join meetings
//
//  FIX: Meeting code is trimmed, lowercased, and validated
//  before navigation so Bhumi never arrives with a dirty code.
//  Also stores the clean code in sessionStorage so meeting.js
//  can use it even if URL parsing fails.
// ============================================================

document.addEventListener('DOMContentLoaded', () => {

    if (!Auth.requireAuth()) return;

    // ── Populate user info ────────────────────────────────────────────────
    const userName = Auth.getName();
    const initials = userName.split(' ').map(w => w[0]).join('').toUpperCase().slice(0, 2);
    document.getElementById('userNameDisplay').textContent = userName;
    document.getElementById('greetingName').textContent    = getGreeting() + ', ' + userName.split(' ')[0] + '!';
    document.getElementById('userAvatar').textContent      = initials;

    // ── Create Meeting ────────────────────────────────────────────────────
    const createBtn = document.getElementById('createMeetingBtn');
    createBtn?.addEventListener('click', async () => {
        const titleInput = document.getElementById('meetingTitle');
        const title      = titleInput?.value.trim() || 'MeetSphere Meeting';
        const alertEl    = document.getElementById('createAlert');

        hideAlert(alertEl);
        setButtonLoading(createBtn, true, '⏳ Creating...');

        try {
            const data = await MeetingAPI.create(title);

            // Clean the code just in case
            const cleanCode = (data.meetingCode || '').trim().toLowerCase();

            // Store in sessionStorage so meeting.js can use it as fallback
            sessionStorage.setItem('meetsphere_meetingCode',  cleanCode);
            sessionStorage.setItem('meetsphere_meetingTitle', data.title || title);

            showToast(`✅ Meeting created! Code: ${cleanCode}`);

            setTimeout(() => {
                // Always put the code in the URL — this is the primary source of truth
                window.location.href = `meeting.html?code=${encodeURIComponent(cleanCode)}`;
            }, 1000);

        } catch (err) {
            showAlert(alertEl, '❌ ' + err.message, 'error');
        } finally {
            setButtonLoading(createBtn, false, '🎥 Start New Meeting');
        }
    });

    // ── Join Meeting ──────────────────────────────────────────────────────
    const joinBtn   = document.getElementById('joinMeetingBtn');
    const codeInput = document.getElementById('joinCode');

    joinBtn?.addEventListener('click', () => attemptJoin());
    codeInput?.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') attemptJoin();
        autoFormatCode(e);
    });

    async function attemptJoin() {
        const alertEl = document.getElementById('joinAlert');
        hideAlert(alertEl);

        // Clean: trim whitespace, lowercase, remove accidental extra chars
        const rawCode  = (codeInput.value || '').trim().toLowerCase().replace(/[^a-z0-9-]/g, '');
        codeInput.value = rawCode; // show cleaned version to user

        if (!rawCode) {
            showAlert(alertEl, 'Please enter a meeting code.', 'error');
            return;
        }

        // Validate format xxxx-xxxx-xxxx
        if (!/^[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}$/.test(rawCode)) {
            showAlert(alertEl, 'Format must be: xxxx-xxxx-xxxx (e.g. ab3d-e7gh-ij2l)', 'error');
            return;
        }

        setButtonLoading(joinBtn, true, '⏳ Joining...');

        try {
            const data = await MeetingAPI.join(rawCode);

            // Store the EXACT same code the API confirmed
            const confirmedCode = rawCode; // use what we sent — server echoes it back
            sessionStorage.setItem('meetsphere_meetingCode',  confirmedCode);
            sessionStorage.setItem('meetsphere_meetingTitle', data.title || 'MeetSphere Meeting');

            console.log('[Dashboard] Navigating to meeting with code:', confirmedCode);

            // Navigate — code goes in URL as the single source of truth
            window.location.href = `meeting.html?code=${encodeURIComponent(confirmedCode)}`;

        } catch (err) {
            const msg = err.message || '';
            if (msg.toLowerCase().includes('not found')) {
                showAlert(alertEl, '❌ Meeting not found. Check the code and try again.', 'error');
            } else if (msg.toLowerCase().includes('ended')) {
                showAlert(alertEl, '❌ This meeting has already ended.', 'error');
            } else {
                showAlert(alertEl, '❌ ' + msg, 'error');
            }
        } finally {
            setButtonLoading(joinBtn, false, '🔗 Join Meeting');
        }
    }

    // Auto-format input as "xxxx-xxxx-xxxx" while typing
    function autoFormatCode(e) {
        setTimeout(() => {
            let val = codeInput.value.replace(/[^a-z0-9]/gi, '').toLowerCase();
            if (val.length > 4)  val = val.slice(0, 4)  + '-' + val.slice(4);
            if (val.length > 9)  val = val.slice(0, 9)  + '-' + val.slice(9);
            if (val.length > 14) val = val.slice(0, 14);
            codeInput.value = val;
        }, 0);
    }

    // ── Logout ────────────────────────────────────────────────────────────
    document.getElementById('logoutBtn')?.addEventListener('click', () => {
        Auth.clear();
        window.location.href = 'login.html';
    });

    // ── Helpers ───────────────────────────────────────────────────────────

    function getGreeting() {
        const h = new Date().getHours();
        if (h < 12) return 'Good morning';
        if (h < 18) return 'Good afternoon';
        return 'Good evening';
    }

    function showAlert(el, message, type) {
        if (!el) return;
        el.textContent = message;
        el.className   = `alert alert-${type} show`;
    }

    function hideAlert(el) {
        if (el) { el.className = 'alert'; el.textContent = ''; }
    }

    function setButtonLoading(btn, loading, label) {
        if (!btn) return;
        btn.disabled    = loading;
        btn.textContent = label;
    }
});

function showToast(message, duration = 3000) {
    const toast = document.getElementById('toast');
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), duration);
}