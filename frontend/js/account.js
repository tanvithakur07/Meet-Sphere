// frontend/js/account.js
// ============================================================
//  "Change password" dialog on the dashboard.
//
//  Flow:  navbar button → dialog → client-side checks → POST /api/auth/change-password
//         { currentPassword, newPassword, confirmPassword } + JWT  (see AuthAPI.changePassword in api.js)
//  The backend decides whose password changes from the JWT — nothing in this request names an account.
//  On success the server returns a fresh JWT (older tokens are revoked), which replaces the stored one so
//  this browser stays signed in.
//
//  Security notes: passwords live only in the three <input> fields and in the request body. They are never
//  logged, never written to storage, and the fields are cleared after every attempt.
// ============================================================
(() => {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const modal = $('passwordModal');
    if (!modal) return;

    const form       = $('changePasswordForm');
    const openBtn    = $('changePasswordBtn');
    const closeBtn   = $('passwordCloseBtn');
    const cancelBtn  = $('passwordCancelBtn');
    const submitBtn  = $('passwordSubmitBtn');
    const spinner    = $('passwordSpinner');
    const label      = $('passwordSubmitLabel');
    const alertBox   = $('passwordAlert');
    const current    = $('currentPassword');
    const next       = $('newPassword');
    const confirm    = $('confirmPassword');
    const fields     = [current, next, confirm];

    const MIN_LENGTH = 8, MAX_BYTES = 72;       // same rules as PasswordPolicy.java
    let sending = false;                        // true while the request is running → blocks double submits
    let done = false;                           // true after a successful change, until the dialog closes
    let lastFocus = null;
    let closeTimer = null;

    // ── rules shown live under the fields ───────────────────────────────
    function problemWithNewPassword(p) {
        if (p.length < MIN_LENGTH) return `The new password must be at least ${MIN_LENGTH} characters long.`;
        if (new TextEncoder().encode(p).length > MAX_BYTES) return `The new password is too long (maximum ${MAX_BYTES} bytes).`;
        if (!/\p{L}/u.test(p) || !/\p{N}/u.test(p)) return 'The new password must contain at least one letter and one number.';
        if (/[\u0000-\u001f\u007f]/.test(p)) return 'The new password contains characters that are not allowed.';
        return null;
    }

    function setRule(name, state) {                       // state: 'ok' | 'bad' | ''
        const li = modal.querySelector(`[data-rule="${name}"]`);
        if (li) li.className = state;
    }

    function refreshRules() {
        const p = next.value;
        setRule('length', p === '' ? '' : (p.length >= MIN_LENGTH ? 'ok' : 'bad'));
        setRule('mix',    p === '' ? '' : (/\p{L}/u.test(p) && /\p{N}/u.test(p) ? 'ok' : 'bad'));
        setRule('match',  confirm.value === '' ? '' : (confirm.value === p ? 'ok' : 'bad'));
    }
    next.addEventListener('input', refreshRules);
    confirm.addEventListener('input', refreshRules);

    // ── alert helpers ───────────────────────────────────────────────────
    function showAlert(type, text) {
        alertBox.className = `alert alert-${type} show`;
        alertBox.textContent = text;                       // textContent: server text is never treated as HTML
    }
    function clearAlert() { alertBox.className = 'alert'; alertBox.textContent = ''; }

    // ── show / hide password buttons ────────────────────────────────────
    modal.querySelectorAll('.pw-toggle').forEach(btn => {
        btn.addEventListener('click', () => {
            const input = $(btn.dataset.target);
            const reveal = input.type === 'password';
            input.type = reveal ? 'text' : 'password';
            btn.textContent = reveal ? 'Hide' : 'Show';
            btn.setAttribute('aria-pressed', String(reveal));
            btn.setAttribute('aria-label', (reveal ? 'Hide ' : 'Show ') + btn.getAttribute('aria-label').replace(/^(Show|Hide) /, ''));
            input.focus();
        });
    });
    function hideAllPasswords() {
        modal.querySelectorAll('.pw-toggle').forEach(btn => {
            $(btn.dataset.target).type = 'password';
            btn.textContent = 'Show';
            btn.setAttribute('aria-pressed', 'false');
            btn.setAttribute('aria-label', 'Show ' + btn.getAttribute('aria-label').replace(/^(Show|Hide) /, ''));
        });
    }
    function clearFields() { fields.forEach(f => { f.value = ''; }); refreshRules(); }

    // ── open / close ────────────────────────────────────────────────────
    function openModal() {
        clearTimeout(closeTimer);
        done = false; submitBtn.disabled = false;
        lastFocus = document.activeElement;
        clearFields(); hideAllPasswords(); clearAlert();
        modal.classList.remove('hidden');
        current.focus();
    }
    function closeModal() {
        if (sending) return;                               // don't lose the result of a request in flight
        clearTimeout(closeTimer);
        clearFields(); hideAllPasswords(); clearAlert();   // nothing sensitive stays in the page
        modal.classList.add('hidden');
        if (lastFocus && lastFocus.focus) lastFocus.focus();
    }
    openBtn.addEventListener('click', openModal);
    closeBtn.addEventListener('click', closeModal);
    cancelBtn.addEventListener('click', closeModal);
    modal.addEventListener('mousedown', (e) => { if (e.target === modal) closeModal(); });
    document.addEventListener('keydown', (e) => {
        if (modal.classList.contains('hidden')) return;
        if (e.key === 'Escape') { closeModal(); return; }
        if (e.key === 'Tab') {                             // keep keyboard focus inside the dialog
            const f = [...modal.querySelectorAll('button:not([disabled]), input')].filter(el => el.offsetParent !== null);
            if (!f.length) return;
            const first = f[0], last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
    });

    // ── submit ──────────────────────────────────────────────────────────
    function setSending(on) {
        sending = on;
        spinner.classList.toggle('hidden', !on);
        label.textContent = on ? 'Changing…' : 'Change password';
        submitBtn.disabled = on;
        cancelBtn.disabled = closeBtn.disabled = on;
        fields.forEach(f => { f.readOnly = on; });
        modal.querySelectorAll('.pw-toggle').forEach(b => { b.disabled = on; });
        submitBtn.setAttribute('aria-busy', String(on));
    }

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (sending || done) return;                       // ← prevents duplicate submissions
        clearAlert();

        // Client-side checks (the server repeats all of them)
        if (!current.value || !next.value || !confirm.value) {
            showAlert('error', 'Please fill in all password fields.'); return;
        }
        if (next.value !== confirm.value) {
            showAlert('error', 'New password and confirmation do not match.'); confirm.focus(); return;
        }
        const problem = problemWithNewPassword(next.value);
        if (problem) { showAlert('error', problem); next.focus(); return; }
        if (next.value === current.value) {
            showAlert('error', 'The new password must be different from your current password.'); next.focus(); return;
        }

        setSending(true);
        try {
            const res = await AuthAPI.changePassword(current.value, next.value, confirm.value);
            // Keep this browser signed in: swap in the new token (the old one has just been revoked)
            if (res.token) localStorage.setItem('meetsphere_token', res.token);
            done = true;
            clearFields(); hideAllPasswords();
            showAlert('success', (res.message || 'Your password was changed.') + ' Other devices have been signed out.');
            closeTimer = setTimeout(() => { sending = false; closeModal(); }, 2600);
        } catch (err) {
            current.value = '';                            // never leave a (possibly wrong) password sitting in the field
            const network = err instanceof TypeError;
            showAlert('error', network ? "Can't reach the server. Check your connection and try again."
                                       : (err.message || 'Something went wrong. Please try again.'));
            current.focus();
        } finally {
            sending = false;
            setSending(false);
            if (done) submitBtn.disabled = true;           // success is showing; the dialog closes by itself
        }
    });
})();
