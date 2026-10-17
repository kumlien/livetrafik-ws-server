package se.kumliens.livetrafik;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SupabaseRealtimeServiceTest {

    private static SupabaseRealtimeService serviceAgainstUnreachableSupabase() {
        ObjectMapper objectMapper = new ObjectMapper();
        SupabaseRealtimeService service = new SupabaseRealtimeService(
            new VehicleCacheService(objectMapper, 5),
            objectMapper,
            mock(SimpMessagingTemplate.class),
            new SimpleMeterRegistry());
        // Port 1 refuses connections immediately, so every attempt ends in onClose -> reconnect
        ReflectionTestUtils.setField(service, "supabaseWsUrl", "ws://localhost:1");
        ReflectionTestUtils.setField(service, "supabaseAnonKey", "anon");
        service.reconnectDelayMillis = 20;
        return service;
    }

    @Test
    void reconnectLoopDoesNotLeakThreadsSoAFlakySupabaseCannotExhaustThePi() throws InterruptedException {
        SupabaseRealtimeService service = serviceAgainstUnreachableSupabase();
        int threadsBefore = Thread.activeCount();

        service.connect();
        Thread.sleep(1_500); // dozens of reconnect cycles at 20 ms
        int threadsDuringOutage = Thread.activeCount();
        service.disconnect();

        assertThat(threadsDuringOutage - threadsBefore).isLessThan(5);
    }

    @Test
    void disconnectStopsReconnectingSoShutdownCompletes() throws InterruptedException {
        SupabaseRealtimeService service = serviceAgainstUnreachableSupabase();
        service.connect();
        Thread.sleep(200);

        service.disconnect();
        Object clientAtShutdown = ReflectionTestUtils.getField(service, "wsClient");
        Thread.sleep(300);

        // A reconnect would replace the client
        assertThat(ReflectionTestUtils.getField(service, "wsClient")).isSameAs(clientAtShutdown);
    }
}
