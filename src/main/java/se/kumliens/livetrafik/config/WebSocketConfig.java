package se.kumliens.livetrafik.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Configures the STOMP broker relaying used by browser clients, exposing the
 * `/ws` endpoint over SockJS and enabling the `/topic` destinations.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Matches the 10 s/10 s Bussig clients request; without a scheduler the broker negotiates 0,0
    private static final long[] STOMP_HEARTBEAT_MILLIS = {10_000, 10_000};

    private final MeterRegistry meterRegistry;
    private final TaskScheduler messageBrokerTaskScheduler;

    public WebSocketConfig(
            MeterRegistry meterRegistry,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.meterRegistry = meterRegistry;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic")
            .setHeartbeatValue(STOMP_HEARTBEAT_MILLIS)
            .setTaskScheduler(messageBrokerTaskScheduler);
        // Deltas must reach each client in publish order; the 16-thread outbound pool reorders otherwise
        config.setPreservePublishOrder(true);
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(new RejectClientSendInterceptor(meterRegistry));
    }

    /**
     * Clients are subscribe-only. Without this, the simple broker relays a client
     * SEND to `/topic/**` to every subscriber, letting anyone on the public port
     * inject fake vehicles or removals.
     */
    @Slf4j
    static class RejectClientSendInterceptor implements ChannelInterceptor {

        private final Counter rejectedCounter;

        RejectClientSendInterceptor(MeterRegistry meterRegistry) {
            this.rejectedCounter = Counter.builder("trafik.stomp.inbound.rejected")
                .description("Client STOMP SEND frames dropped because clients are subscribe-only")
                .register(meterRegistry);
        }

        @Override
        public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
            if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
                return message;
            }
            rejectedCounter.increment();
            log.debug("Dropped client SEND to {} from session {}",
                SimpMessageHeaderAccessor.getDestination(message.getHeaders()),
                SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
            return null;
        }
    }
}
