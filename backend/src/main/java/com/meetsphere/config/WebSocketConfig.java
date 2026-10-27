// backend/src/main/java/com/meetsphere/config/WebSocketConfig.java
package com.meetsphere.config;

import com.meetsphere.websocket.JwtHandshakeInterceptor;
import com.meetsphere.websocket.SignalingHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private SignalingHandler signalingHandler;

    @Autowired
    private JwtHandshakeInterceptor jwtHandshakeInterceptor;

    // Same list the REST API uses (app.cors.allowed-origins)
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * Raise Tomcat's WebSocket message limit.
     *
     * Tomcat's default is 8 KB per text message. A WebRTC offer (SDP) carrying audio + camera + screen-share
     * media lines is bigger than that in real browsers (more codecs than headless test browsers), so the
     * server used to CLOSE the sender's socket with code 1009 ("message too big") the moment a second
     * person joined — the first person saw "Disconnected" and the second saw them "left".
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(256 * 1024);     // 256 KB — plenty for SDP + chat
        container.setMaxBinaryMessageBufferSize(256 * 1024);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(signalingHandler, "/ws/signaling")
            .addInterceptors(jwtHandshakeInterceptor)          // JWT required to connect
            .setAllowedOrigins(allowedOrigins.split(","));     // no more "*"
        // Native WebSocket (no SockJS) — see README "Key Technical Decisions"
    }
}
