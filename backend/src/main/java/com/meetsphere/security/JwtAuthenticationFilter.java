// backend/src/main/java/com/meetsphere/security/JwtAuthenticationFilter.java
package com.meetsphere.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JwtAuthenticationFilter — runs once per HTTP request.
 *
 * Steps:
 *   1. Extract Bearer token from Authorization header
 *   2. Validate the token with JwtTokenProvider
 *   3. Load UserDetails and set into SecurityContext
 *      → This tells Spring Security "the user is authenticated"
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Step 1: Extract JWT from "Authorization: Bearer <token>" header
        String token = extractTokenFromRequest(request);

        // Step 2: Validate and authenticate
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            String email = jwtTokenProvider.getEmailFromToken(token);

            // Step 3: Load user and build authentication object
            // (a valid token for a since-deleted user stays unauthenticated -> 401, not a 500)
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // A token issued before the user's last password change is no longer valid -> stays unauthenticated (401)
                if (!jwtTokenProvider.isTokenCurrent(token, userDetails.getPassword())) {
                    filterChain.doFilter(request, response);
                    return;
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());

                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                // Step 4: Put authentication into SecurityContext
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException ignored) {
                // fall through unauthenticated
            }
        }

        // Always continue the filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Parses "Bearer eyJhbGci..." → "eyJhbGci..."
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
