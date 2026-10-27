// backend/src/main/java/com/meetsphere/security/UserDetailsServiceImpl.java
package com.meetsphere.security;

import com.meetsphere.model.User;
import com.meetsphere.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

/**
 * UserDetailsServiceImpl — bridges our User entity with Spring Security.
 *
 * Spring Security calls loadUserByUsername() when authenticating a request.
 * We look up the user by email and wrap them in a Spring UserDetails object.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Load user from DB using email as the "username"
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with email: " + email));

        // Build Spring Security UserDetails with no additional roles
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_USER")  // Basic role — extend for admin features
                .build();
    }
}
