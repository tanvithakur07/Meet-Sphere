// frontend/js/auth.js
// ============================================================
//  Authentication Page Logic — handles register & login forms
// ============================================================

document.addEventListener('DOMContentLoaded', () => {

    // ── Determine which page we're on ────────────────────────────────────
    const isLoginPage    = document.getElementById('loginForm');
    const isRegisterPage = document.getElementById('registerForm');

    // Redirect if already logged in
    if (Auth.isLoggedIn()) {
        window.location.href = Auth.consumeRedirect() || 'dashboard.html';
        return;
    }

    // ── Login Page ────────────────────────────────────────────────────────
    if (isLoginPage) {
        const form     = document.getElementById('loginForm');
        const emailEl  = document.getElementById('loginEmail');
        const passEl   = document.getElementById('loginPassword');
        const alertEl  = document.getElementById('loginAlert');
        const submitEl = document.getElementById('loginSubmit');
        const eyeToggle = document.getElementById('eyeToggle');

        // Password visibility toggle
        eyeToggle?.addEventListener('click', () => {
            const isText = passEl.type === 'text';
            passEl.type = isText ? 'password' : 'text';
            eyeToggle.textContent = isText ? '👁️' : '🙈';
        });

        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            hideAlert(alertEl);

            const email    = emailEl.value.trim();
            const password = passEl.value;

            // Frontend validation
            if (!email || !password) {
                showAlert(alertEl, 'Please fill in all fields.', 'error');
                return;
            }

            // Show loading state on button
            setLoading(submitEl, true, 'Signing In...');

            try {
                const data = await AuthAPI.login(email, password);

                // Persist auth data and redirect
                Auth.save(data);
                showAlert(alertEl, '✅ Login successful! Redirecting...', 'success');

                const next = Auth.consumeRedirect() || 'dashboard.html';
                setTimeout(() => {
                    window.location.href = next;
                }, 800);

            } catch (err) {
                showAlert(alertEl, err.message || 'Login failed. Check credentials.', 'error');
            } finally {
                setLoading(submitEl, false, '🔐 Sign In');
            }
        });
    }

    // ── Register Page ─────────────────────────────────────────────────────
    if (isRegisterPage) {
        const form        = document.getElementById('registerForm');
        const nameEl      = document.getElementById('regName');
        const emailEl     = document.getElementById('regEmail');
        const passEl      = document.getElementById('regPassword');
        const confirmEl   = document.getElementById('regConfirm');
        const alertEl     = document.getElementById('registerAlert');
        const submitEl    = document.getElementById('registerSubmit');
        const strengthBar = document.getElementById('strengthBar');
        const strengthText = document.getElementById('strengthText');

        // Live password strength indicator
        passEl?.addEventListener('input', () => {
            const strength = getPasswordStrength(passEl.value);
            updateStrengthBar(strengthBar, strengthText, strength);
        });

        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            hideAlert(alertEl);

            const name     = nameEl.value.trim();
            const email    = emailEl.value.trim();
            const password = passEl.value;
            const confirm  = confirmEl.value;

            // Validation
            if (!name || !email || !password) {
                showAlert(alertEl, 'Please fill in all required fields.', 'error');
                return;
            }
            if (name.length < 2) {
                showAlert(alertEl, 'Name must be at least 2 characters.', 'error');
                return;
            }
            if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
                showAlert(alertEl, 'Please enter a valid email address.', 'error');
                return;
            }
            if (password.length < 6) {
                showAlert(alertEl, 'Password must be at least 6 characters.', 'error');
                return;
            }
            if (password !== confirm) {
                showAlert(alertEl, 'Passwords do not match.', 'error');
                return;
            }

            setLoading(submitEl, true, 'Creating Account...');

            try {
                await AuthAPI.register(name, email, password);
                showAlert(alertEl, '🎉 Account created! Redirecting to login...', 'success');

                setTimeout(() => {
                    window.location.href = 'login.html';
                }, 1500);

            } catch (err) {
                showAlert(alertEl, err.message || 'Registration failed.', 'error');
            } finally {
                setLoading(submitEl, false, '🚀 Create Account');
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    function showAlert(el, message, type) {
        el.textContent = message;
        el.className = `alert alert-${type} show`;
    }

    function hideAlert(el) {
        el.className = 'alert';
        el.textContent = '';
    }

    function setLoading(btn, loading, label) {
        btn.disabled = loading;
        btn.innerHTML = loading
            ? `<span class="spinner"></span> ${label}`
            : label;
    }

    /**
     * Scores password strength 0-4:
     * 0=empty, 1=weak, 2=fair, 3=good, 4=strong
     */
    function getPasswordStrength(password) {
        if (!password) return 0;
        let score = 0;
        if (password.length >= 8)  score++;
        if (password.length >= 12) score++;
        if (/[A-Z]/.test(password)) score++;
        if (/[0-9]/.test(password)) score++;
        if (/[^A-Za-z0-9]/.test(password)) score++;
        return Math.min(4, score);
    }

    function updateStrengthBar(bar, text, strength) {
        if (!bar) return;
        const colors = ['', '#E74C3C', '#F39C12', '#F1C40F', '#27AE60'];
        const labels = ['', 'Weak', 'Fair', 'Good', 'Strong'];
        const widths = ['0%', '25%', '50%', '75%', '100%'];

        bar.style.width      = widths[strength] || '0%';
        bar.style.background = colors[strength] || 'transparent';
        text.textContent     = labels[strength] || '';
        text.style.color     = colors[strength] || '';
    }
});
