package se.kumliens.livetrafik.config;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Configuration
public class WebSocketMonitoringConfig implements WebSocketMessageBrokerConfigurer {

    private static final String ENQUEUED_AT_HEADER = "trafik.enqueuedAt";

    private final MeterRegistry meterRegistry;

    public WebSocketMonitoringConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration
            .taskExecutor()
                .corePoolSize(4)
                .maxPoolSize(16)
                .queueCapacity(2_000);

        registration.interceptors(new OutboundLatencyInterceptor(meterRegistry));
    }

    private static class OutboundLatencyInterceptor implements ChannelInterceptor {

        private final Timer queueTimer;

        private OutboundLatencyInterceptor(MeterRegistry meterRegistry) {
            this.queueTimer = meterRegistry.timer("trafik.stomp.outbound.queue");
        }

        @Override
        public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
            SimpMessageHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
            if (accessor == null) {
                return message;
            }

            long now = System.nanoTime();
            return MessageBuilder.fromMessage(message)
                .setHeader(ENQUEUED_AT_HEADER, now)
                .build();
        }

        @Override
        public void afterSendCompletion(
            @NonNull Message<?> message,
            @NonNull MessageChannel channel,
            boolean sent,
            @Nullable Exception ex) {
            Object header = message.getHeaders().get(ENQUEUED_AT_HEADER);
            if (header instanceof Long enqueuedAt) {
                queueTimer.record(Duration.ofNanos(System.nanoTime() - enqueuedAt));
            }
        }
    }
}
