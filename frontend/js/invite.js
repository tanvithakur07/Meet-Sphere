// frontend/js/invite.js
// ============================================================
//  "Invite by email" dialog on the meeting page (host only).
//
//  Flow:  host types addresses → chips (validated) → "Send invitations"
//         → POST /api/meetings/{code}/invite  { emails: [...] }   (JWT added by apiRequest)
//         → backend checks host + validates again + sends via SMTP → result shown here.
//
//  Depends on api.js (MeetingAPI). The button is shown to the host by meeting.js.
// ============================================================
(() => {
    'use strict';

    const MAX_RECIPIENTS = 10;                     // same limit as the backend
    // Same rules as the backend (EmailAddressUtil) — the server still re-validates everything
    const EMAIL_RE = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;

    const code = new URLSearchParams(window.location.search).get('code');

    const $ = (id) => document.getElementById(id);
    const openBtn   = $('inviteBtn');
    const modal     = $('inviteModal');
    if (!openBtn || !modal || !code) return;

    const closeBtn  = $('inviteCloseBtn');
    const cancelBtn = $('inviteCancelBtn');
    const sendBtn   = $('inviteSendBtn');
    const spinner   = sendBtn.querySelector('.btn-spinner');
    const sendLabel = sendBtn.querySelector('.btn-label');
    const input     = $('inviteInput');
    const chipsEl   = $('inviteChips');
    const chipBox   = $('inviteChipBox');
    const errorEl   = $('inviteError');
    const okEl      = $('inviteSuccess');
    const failedEl  = $('inviteFailed');
    const nameEl    = $('inviteMeetingName');

    let emails  = [];      // addresses added as chips, in order
    let sending = false;   // true while a request is in flight → blocks duplicate submits
    let lastFocus = null;

    // ── validation ───────────────────────────────────────────────────────
    function isValidEmail(e) {
        if (e.length > 254 || !EMAIL_RE.test(e)) return false;
        const local = e.slice(0, e.lastIndexOf('@'));
        return local.length <= 64 && !local.startsWith('.') && !local.endsWith('.') && !local.includes('..');
    }

    // ── messages ─────────────────────────────────────────────────────────
    function show(el, text) { el.textContent = text; el.classList.remove('hidden'); }
    function hide(el)       { el.textContent = '';   el.classList.add('hidden'); }
    function clearMessages() { hide(errorEl); hide(okEl); failedEl.textContent = ''; failedEl.classList.add('hidden'); }

    function showFailed(list) {
        failedEl.textContent = '';
        list.forEach(f => {
            const li = document.createElement('li');
            const strong = document.createElement('strong');
            strong.textContent = f.email;
            li.append(strong, ' — ' + f.reason);
            failedEl.appendChild(li);
        });
        failedEl.classList.toggle('hidden', list.length === 0);
    }

    // ── chips ────────────────────────────────────────────────────────────
    function renderChips() {
        chipsEl.textContent = '';
        emails.forEach(email => {
            const chip = document.createElement('span');
            chip.className = 'chip';
            const label = document.createElement('span');
            label.textContent = email;                       // textContent: never HTML
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'chip-remove';
            remove.setAttribute('aria-label', 'Remove ' + email);
            remove.textContent = '×';
            remove.addEventListener('click', () => {
                if (sending) return;
                emails = emails.filter(x => x !== email);
                renderChips(); input.focus();
            });
            chip.append(label, remove);
            chipsEl.appendChild(chip);
        });
        updateSendState();
    }

    function updateSendState() {
        sendBtn.disabled = sending || (emails.length === 0 && input.value.trim() === '');
    }

    /**
     * Turns text into chips. Valid, new addresses become chips; invalid ones stay in the input so they
     * can be corrected. Returns true if everything was accepted.
     */
    function commit(text) {
        const tokens = text.split(/[,;\s]+/).filter(Boolean);
        const invalid = [];
        let tooMany = false;

        tokens.forEach(raw => {
            const email = raw.toLowerCase();
            if (!isValidEmail(email)) { invalid.push(raw); return; }
            if (emails.includes(email)) return;                       // duplicates are ignored silently
            if (emails.length >= MAX_RECIPIENTS) { tooMany = true; invalid.push(raw); return; }
            emails.push(email);
        });

        input.value = invalid.join(' ');
        chipBox.classList.toggle('invalid', invalid.length > 0);
        hide(errorEl);
        if (tooMany) {
            show(errorEl, `You can invite up to ${MAX_RECIPIENTS} people at a time.`);
        } else if (invalid.length) {
            const shown = invalid.slice(0, 2).map(x => `“${x.length > 40 ? x.slice(0, 40) + '…' : x}”`).join(', ');
            show(errorEl, invalid.length === 1
                ? `${shown} isn't a valid email address. Check it and try again.`
                : `These aren't valid email addresses: ${shown}${invalid.length > 2 ? ' and more' : ''}.`);
        }
        renderChips();
        return invalid.length === 0;
    }

    // ── open / close ─────────────────────────────────────────────────────
    function openModal() {
        lastFocus = document.activeElement;
        nameEl.textContent = ($('meetingTitle') && $('meetingTitle').textContent.trim()) || 'this meeting';
        clearMessages();
        modal.classList.remove('hidden');
        input.focus();
    }
    function closeModal() {
        if (sending) return;                                 // don't lose the result of a request in flight
        modal.classList.add('hidden');
        if (lastFocus && lastFocus.focus) lastFocus.focus();
    }

    openBtn.addEventListener('click', openModal);
    closeBtn.addEventListener('click', closeModal);
    cancelBtn.addEventListener('click', closeModal);
    modal.addEventListener('mousedown', (e) => { if (e.target === modal) closeModal(); });   // click on the dark backdrop
    chipBox.addEventListener('click', () => input.focus());

    document.addEventListener('keydown', (e) => {
        if (modal.classList.contains('hidden')) return;
        if (e.key === 'Escape') { e.stopPropagation(); closeModal(); return; }
        if (e.key === 'Tab') {                               // keep keyboard focus inside the dialog
            const f = [...modal.querySelectorAll('button:not([disabled]), input')].filter(el => el.offsetParent !== null);
            if (!f.length) return;
            const first = f[0], last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
    }, true);

    // ── input handling ───────────────────────────────────────────────────
    input.addEventListener('keydown', (e) => {
        if (sending) { e.preventDefault(); return; }
        if (e.key === 'Enter') {
            e.preventDefault();
            if (input.value.trim()) commit(input.value);
            else if (emails.length) send();                  // Enter on an empty box sends
        } else if (e.key === ',' || e.key === ';' || e.key === ' ') {
            if (input.value.trim()) { e.preventDefault(); commit(input.value); }
        } else if (e.key === 'Backspace' && input.value === '' && emails.length) {
            emails.pop(); renderChips();                     // backspace removes the last chip
        }
    });
    input.addEventListener('input', () => {
        chipBox.classList.remove('invalid');
        // phone keyboards / autocomplete may insert a separator: commit everything typed before it
        if (/[,;\s]$/.test(input.value) && input.value.trim()) commit(input.value);
        updateSendState();
    });
    input.addEventListener('paste', (e) => {
        const text = (e.clipboardData || window.clipboardData).getData('text');
        if (!text) return;
        e.preventDefault();
        commit(input.value + ' ' + text);
    });
    input.addEventListener('blur', () => { if (!sending && input.value.trim()) commit(input.value); });

    // ── send ─────────────────────────────────────────────────────────────
    function setSending(on) {
        sending = on;
        spinner.classList.toggle('hidden', !on);
        sendLabel.textContent = on ? 'Sending…' : 'Send invitations';
        input.disabled = on;
        closeBtn.disabled = cancelBtn.disabled = on;
        modal.querySelectorAll('.chip-remove').forEach(b => { b.disabled = on; });
        sendBtn.setAttribute('aria-busy', on ? 'true' : 'false');
        updateSendState();
    }

    async function send() {
        if (sending) return;                                 // ← prevents duplicate submissions
        clearMessages();
        if (input.value.trim() && !commit(input.value)) return;     // pending text was invalid
        if (emails.length === 0) { show(errorEl, 'Please enter at least one email address.'); return; }

        setSending(true);
        try {
            const res = await MeetingAPI.invite(code, emails.slice());
            const failed = res.failed || [];
            const failedSet = new Set(failed.map(f => f.email));
            emails = emails.filter(e => failedSet.has(e));   // keep only the ones that failed, so the host can retry
            show(okEl, res.message || 'Invitations sent.');
            showFailed(failed);
            renderChips();
        } catch (err) {
            if (err.data && Array.isArray(err.data.failed) && err.data.failed.length) showFailed(err.data.failed);
            show(errorEl, friendlyError(err));
        } finally {
            setSending(false);
            if (emails.length === 0) input.focus();
        }
    }

    function friendlyError(err) {
        if (err instanceof TypeError) return "Can't reach the server. Check your connection and try again.";   // fetch() network failure
        return err.message || 'Something went wrong. Please try again.';
    }

    sendBtn.addEventListener('click', send);
})();
