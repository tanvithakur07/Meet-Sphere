// backend/src/main/java/com/meetsphere/controller/AuthController.java
package com.meetsphere.controller;

import com.meetsphere.exception.ApiException;
import com.meetsphere.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AuthController — user registration and login.
 *
 *   POST /api/auth/register   → register new user
 *   POST /api/auth/login      → login and receive JWT
 *   POST /api/auth/change-password → change the logged-in user's password (requires a JWT)
 *
 * register and login are PUBLIC; change-password is protected (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        try {
            String name     = request.get("name");
            String email    = request.get("email");
            String password = request.get("password");

            if (name == null || name.isBlank())     return badRequest("Name is required");
            if (name.trim().length() > 100)         return badRequest("Name is too long");
            if (email == null || email.isBlank())   return badRequest("Email is required");
            if (password == null || password.length() < 6)
                return badRequest("Password must be at least 6 characters");

            return ResponseEntity.ok(authService.register(name.trim(), email.trim(), password));

        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Register failed", e);
            return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String email    = request.get("email");
            String password = request.get("password");

            if (email == null || email.isBlank())       return badRequest("Email is required");
            if (password == null || password.isBlank()) return badRequest("Password is required");

            return ResponseEntity.ok(authService.login(email.trim(), password));

        } catch (AuthenticationException | ApiException e) {
            // Don't reveal whether the email exists
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        } catch (Exception e) {
            log.error("Login failed", e);
            return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
        }
    }

    /**
     * Change the password of the user identified by the JWT.
     * Body: { "currentPassword": "...", "newPassword": "...", "confirmPassword": "..." }
     * The account comes from the verified Authentication object — there is no userId in the request to tamper with.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody(required = false) Map<String, String> request,
                                            Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }
            Map<String, String> body = (request != null) ? request : Map.of();
            return ResponseEntity.ok(authService.changePassword(
                    authentication.getName(),
                    body.get("currentPassword"),
                    body.get("newPassword"),
                    body.get("confirmPassword")));

        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Log only the exception type — the request holds passwords, so never log the body
            log.error("Change password failed: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
        }
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
