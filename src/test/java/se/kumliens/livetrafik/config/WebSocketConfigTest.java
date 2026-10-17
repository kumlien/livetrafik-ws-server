package se.kumliens.livetrafik.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import se.kumliens.livetrafik.config.WebSocketConfig.RejectClientSendInterceptor;

class WebSocketConfigTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RejectClientSendInterceptor interceptor = new RejectClientSendInterceptor(meterRegistry);

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionId("session-1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void clientSendToVehicleTopicIsDroppedSoFakeVehiclesNeverReachSubscribers() {
        Message<?> result = interceptor.preSend(frame(StompCommand.SEND, "/topic/ul/vehicles/bus"), mock(MessageChannel.class));

        assertThat(result).isNull();
        assertThat(meterRegistry.counter("trafik.stomp.inbound.rejected").count()).isEqualTo(1.0);
    }

    @Test
    void subscribeStillPassesSoBrowsersKeepReceivingVehicles() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/topic/ul/vehicles/bus");

        assertThat(interceptor.preSend(subscribe, mock(MessageChannel.class))).isSameAs(subscribe);
        assertThat(meterRegistry.counter("trafik.stomp.inbound.rejected").count()).isZero();
    }
}
