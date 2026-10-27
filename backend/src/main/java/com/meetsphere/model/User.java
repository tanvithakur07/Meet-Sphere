// backend/src/main/java/com/meetsphere/model/User.java
package com.meetsphere.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User entity — maps to the `users` MySQL table.
 * Lombok @Data generates getters, setters, equals, hashCode, toString.
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Full display name shown in meeting
    @NotBlank
    @Column(nullable = false, length = 100)
    private String name;

    // Used as the login username
    @Email
    @NotBlank
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    // BCrypt-hashed password — never stored as plain text
    @NotBlank
    @Column(nullable = false)
    private String password;

    // Single emoji used as avatar in meetings (optional)
    @Column(length = 10)
    private String avatar = "🧑";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Convenience constructor for registration
    public User(String name, String email, String password) {
        this.name     = name;
        this.email    = email;
        this.password = password;
    }
}
