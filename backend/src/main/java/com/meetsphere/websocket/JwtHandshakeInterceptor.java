package com.meetsphere.websocket;

import com.meetsphere.model.User;
import com.meetsphere.repository.UserRepository;
import com.meetsphere.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Authenticates the WebSocket upgrade request.
 *
 * Browsers cannot set an Authorization header on a WebSocket, so the frontend
 * passes the JWT as  ws://host/ws/signaling?token=...
 * On success the verified userId and display name are stored in the session
 * attributes — the handler never trusts a name sent by the client.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> tokens = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().get("token");

        if (tokens == null || tokens.isEmpty() || !jwtTokenProvider.validateToken(tokens.get(0))) {
            log.warn("WS handshake rejected: missing or invalid token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String email = jwtTokenProvider.getEmailFromToken(tokens.get(0));
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !jwtTokenProvider.isTokenCurrent(tokens.get(0), user.getPassword())) {   // unknown user, or token predates a password change
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put("userId", user.getId());
        attributes.put("userName", user.getName());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
