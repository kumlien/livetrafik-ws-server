package se.kumliens.livetrafik.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import se.kumliens.livetrafik.SupabaseRealtimeService;
import se.kumliens.livetrafik.config.MonitoringProperties;

class ProxyHeartbeatServiceTest {

    @Test
    void heartbeatPayloadReflectsConnectedClients() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setServerId("test-server");
        properties.setVersion("1.2.3");
        properties.getHeartbeat().setUrl("https://example.com/heartbeat");

        SupabaseRealtimeService supabaseRealtimeService = mock(SupabaseRealtimeService.class);
        when(supabaseRealtimeService.isSupabaseConnected()).thenReturn(true);
        when(supabaseRealtimeService.getRelayedMessages()).thenReturn(321L);

        WebSocketConnectionTracker tracker = mock(WebSocketConnectionTracker.class);
        when(tracker.getConnectedClients()).thenReturn(7);

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

        ProxyHeartbeatService service = new ProxyHeartbeatService(
            properties,
            supabaseRealtimeService,
            tracker,
            new ObjectMapper(),
            httpClient
        );

        ObjectNode payload = service.buildHeartbeatPayload(12_345);

        assertThat(payload.path("server_id").asText()).isEqualTo("test-server");
        assertThat(payload.path("version").asText()).isEqualTo("1.2.3");
        assertThat(payload.path("connected_clients").asInt()).isEqualTo(7);
        assertThat(payload.path("uptime_seconds").asLong()).isEqualTo(12_345);
        assertThat(payload.path("supabase_connected").asBoolean()).isTrue();
        assertThat(payload.path("messages_relayed").asLong()).isEqualTo(321L);
    }
}
