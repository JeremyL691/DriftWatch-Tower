package com.driftwatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WsTicketService ticketService;

    public WebSocketConfig(WsTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new TicketHandshakeInterceptor(ticketService))
                .withSockJS();
    }

    /**
     * The handshake must carry a valid short-lived ticket; the security chain permits the
     * endpoint path because browsers cannot attach Basic credentials to a WebSocket handshake.
     */
    static class TicketHandshakeInterceptor implements HandshakeInterceptor {

        private final WsTicketService ticketService;

        TicketHandshakeInterceptor(WsTicketService ticketService) {
            this.ticketService = ticketService;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, java.util.Map<String, Object> attributes) {
            String query = request.getURI().getQuery();
            String ticket = null;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("ticket=")) {
                        ticket = java.net.URLDecoder.decode(part.substring("ticket=".length()),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            String username = ticketService.verify(ticket);
            if (username == null) {
                return false;
            }
            attributes.put("wsUser", username);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Exception exception) {
        }
    }
}
