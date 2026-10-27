// backend/src/main/java/com/meetsphere/service/AuthService.java
package com.meetsphere.service;

import com.meetsphere.exception.ApiException;
import com.meetsphere.model.User;
import com.meetsphere.repository.UserRepository;
import com.meetsphere.security.JwtTokenProvider;
import com.meetsphere.util.PasswordPolicy;
import com.meetsphere.util.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * AuthService — business logic for registration and login.
 *
 * Registration flow:
 *   1. Check email uniqueness
 *   2. Hash password with BCrypt
 *   3. Save user to DB
 *
 * Login flow:
 *   1. Authenticate credentials via Spring AuthenticationManager
 *   2. Generate JWT
 *   3. Return token + user info
 *
 * Change-password flow:
 *   1. Identify the user from the verified JWT (never from the request body)
 *   2. Verify the CURRENT password with the PasswordEncoder
 *   3. Validate + hash the new password, save it, issue a fresh JWT (old tokens stop working)
 */
@Service
public class AuthService {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthenticationManager authenticationManager;

    // Brute-force protection for "current password" guesses (e.g. someone with a stolen token):
    // at most 10 attempts per user per 15 minutes
    private final SlidingWindowRateLimiter passwordAttemptLimiter =
            new SlidingWindowRateLimiter(10, 15L * 60 * 1000);

    /**
     * Register a new user.
     * Returns the saved user object (without the password field exposed).
     */
    public Map<String, Object> register(String name, String email, String password) {
        // Prevent duplicate accounts
        if (userRepository.existsByEmail(email)) {
            throw new ApiException("Email already registered");
        }

        // Hash password before persisting
        String hashedPassword = passwordEncoder.encode(password);
        User user = new User(name, email, hashedPassword);
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Registration successful!");
        response.put("userId", user.getId());
        response.put("name", user.getName());
        return response;
    }

    /**
     * Authenticate credentials and return a JWT.
     */
    public Map<String, Object> login(String email, String password) {
        // Spring Security validates email/password against the DB
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));

        // If authentication succeeds, load the full user for the response
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException("Invalid email or password", 401));

        // Generate JWT signed with our secret key
        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getPassword());

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("userId", user.getId());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        return response;
    }

    /**
     * Changes the password of the CURRENTLY AUTHENTICATED user.
     *
     * @param email           from the verified authentication context (Authentication.getName()) — NOT from the request
     * @param currentPassword the password the user types now; verified against the stored hash
     * @return { message, token } — the token is a fresh JWT for this session; all older tokens are revoked
     *
     * Status codes: 400 invalid input / mismatch / weak or unchanged password, 403 wrong current password,
     *               401 account no longer exists, 429 too many attempts.
     * Passwords are never logged and never returned.
     */
    @Transactional
    public Map<String, Object> changePassword(String email, String currentPassword,
                                              String newPassword, String confirmPassword) {
        // 1. Input checks that need no database
        if (isEmpty(currentPassword) || isEmpty(newPassword) || isEmpty(confirmPassword)) {
            throw new ApiException("Please fill in all password fields.", 400);
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new ApiException("New password and confirmation do not match.", 400);
        }
        String problem = PasswordPolicy.validate(newPassword);
        if (problem != null) {
            throw new ApiException(problem, 400);
        }

        // 2. Load the account of the authenticated user (a valid token for a deleted account → 401)
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException("Your account could not be found. Please sign in again.", 401));

        // 3. Throttle guesses, then verify the current password with the project's PasswordEncoder (BCrypt)
        if (!passwordAttemptLimiter.tryAcquire(user.getId(), 1)) {
            throw new ApiException("Too many password attempts. Please wait 15 minutes and try again.", 429);
        }
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new ApiException("Current password is incorrect.", 403);
        }
        if (newPassword.equals(currentPassword)) {
            throw new ApiException("The new password must be different from your current password.", 400);
        }

        // 4. Hash with the same encoder and store in the existing users.password column (only this user's row)
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // 5. New JWT for this session. Its stamp matches the new hash; every earlier token no longer does.
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Your password was changed successfully.");
        response.put("token", jwtTokenProvider.generateToken(user.getEmail(), user.getPassword()));
        return response;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
