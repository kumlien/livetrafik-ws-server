package se.kumliens.livetrafik;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import se.kumliens.livetrafik.config.SupabaseProperties;

class SupabaseRealtimeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VehicleCacheService cache = new VehicleCacheService(objectMapper, 5);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

    private static SupabaseProperties properties() {
        SupabaseProperties properties = new SupabaseProperties();
        // Port 1 refuses connections immediately, so every attempt ends in onClose -> reconnect
        properties.setUrl("ws://localhost:1");
        properties.setAnonKey("anon");
        return properties;
    }

    private SupabaseRealtimeService service(SupabaseProperties properties) {
        return new SupabaseRealtimeService(properties, cache, objectMapper, messagingTemplate, new SimpleMeterRegistry());
    }

    private SupabaseRealtimeService serviceAgainstUnreachableSupabase() {
        SupabaseRealtimeService service = service(properties());
        service.reconnectDelayMillis = 20;
        return service;
    }

    private static String vehiclesBroadcast(String channel) {
        return """
            {"topic": "realtime:%s", "event": "broadcast",
             "payload": {"event": "vehicles", "payload": {
               "vehicles": [{"vehicle_id": "v1"}], "removed_vehicle_ids": []}}}
            """.formatted(channel);
    }

    @Test
    void typedChannelFillsTheCacheKeyNamedByTheChannel() {
        service(properties()).handleMessage(vehiclesBroadcast("sl/vehicles/train"));

        assertThat(cache.sizeForTesting("sl", "train")).isEqualTo(1);
    }

    @Test
    void legacyDashChannelIsRelayedButNotCachedUnderAGuessedRegion() {
        service(properties()).handleMessage(vehiclesBroadcast("vehicle-updates-vt-bus"));

        verify(messagingTemplate).convertAndSend(eq("/topic/vehicle-updates-vt-bus"), any(Object.class));
        // The removed dash parser read this as region "vt", type "bus"
        assertThat(cache.sizeForTesting("vt", "bus")).isZero();
        assertThat(cache.sizeForTesting("ul", "bus")).isZero();
    }

    @Test
    void missingAnonKeyFailsAtStartupInsteadOfConnectingWithoutAuth() {
        SupabaseProperties properties = properties();
        properties.setAnonKey("");

        assertThatThrownBy(() -> service(properties)).hasMessageContaining("SUPABASE_ANON_KEY");
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
