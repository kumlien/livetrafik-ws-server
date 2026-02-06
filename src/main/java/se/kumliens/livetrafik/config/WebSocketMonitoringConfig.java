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
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import io.micrometer.core.instrument.Counter;
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
    public void configureClientOutboundChannel(@NonNull ChannelRegistration registration) {
        registration
            .taskExecutor()
                .corePoolSize(4)
                .maxPoolSize(16)
                .queueCapacity(2_000);

        registration.interceptors(new OutboundLatencyInterceptor(meterRegistry));
    }

    private static class OutboundLatencyInterceptor implements ChannelInterceptor {

        private final Timer queueTimer;
        private final Counter interceptCounter;

        private OutboundLatencyInterceptor(MeterRegistry meterRegistry) {
            this.queueTimer = Timer.builder("trafik.stomp.outbound.queue")
                .description("Time STOMP messages spend waiting on the outbound channel queue")
                .register(meterRegistry);
            this.interceptCounter = Counter.builder("trafik.stomp.outbound.intercepts")
                .description("Number of client outbound STOMP messages observed by the interceptor")
                .register(meterRegistry);
        }

        @Override
        public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
            interceptCounter.increment();
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
            accessor.setHeader(ENQUEUED_AT_HEADER, System.nanoTime());
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
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
