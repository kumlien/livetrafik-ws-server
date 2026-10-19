package se.kumliens.livetrafik.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import se.kumliens.livetrafik.SupabaseRealtimeService;
import se.kumliens.livetrafik.config.MonitoringProperties;
import se.kumliens.livetrafik.config.SupabaseProperties;

class ProxyHeartbeatServiceTest {

    private static SupabaseProperties supabaseProperties() {
        SupabaseProperties properties = new SupabaseProperties();
        properties.setAnonKey("anon-key");
        return properties;
    }

    private static ProxyHeartbeatService serviceWith(MonitoringProperties properties) {
        return new ProxyHeartbeatService(
            properties,
            supabaseProperties(),
            mock(SupabaseRealtimeService.class),
            mock(WebSocketConnectionTracker.class),
            new ObjectMapper(),
            HttpClient.newHttpClient()
        );
    }

    @Test
    void heartbeatCarriesSharedSecretSoSupabaseCanRejectSpoofedHeartbeats() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.getHeartbeat().setSecret("s3cret");

        HttpRequest request = serviceWith(properties).buildHeartbeatRequest("{}");

        assertThat(request.headers().firstValue("x-proxy-secret")).contains("s3cret");
    }

    @Test
    void heartbeatOmitsSecretHeaderWhenNotConfigured() {
        HttpRequest request = serviceWith(new MonitoringProperties()).buildHeartbeatRequest("{}");

        assertThat(request.headers().firstValue("x-proxy-secret")).isEmpty();
    }

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
            supabaseProperties(),
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
