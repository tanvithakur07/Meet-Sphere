// backend/src/main/java/com/meetsphere/repository/UserRepository.java
package com.meetsphere.repository;

import com.meetsphere.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * UserRepository — Spring Data JPA auto-implements all CRUD methods.
 * Custom finder methods follow naming conventions (Spring generates the SQL).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Used during login to find user by email
    Optional<User> findByEmail(String email);

    // Used during registration to prevent duplicate emails
    boolean existsByEmail(String email);
}
