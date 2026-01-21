package se.kumliens.livetrafik.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import se.kumliens.livetrafik.SupabaseRealtimeService;
import se.kumliens.livetrafik.config.MonitoringProperties;

@Service
@Slf4j
public class ProxyHeartbeatService {

    private final MonitoringProperties monitoringProperties;
    private final SupabaseRealtimeService supabaseRealtimeService;
    private final WebSocketConnectionTracker connectionTracker;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "proxy-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> heartbeatTask;
    private final long startTimeMillis = System.currentTimeMillis();

    public ProxyHeartbeatService(
        MonitoringProperties monitoringProperties,
        SupabaseRealtimeService supabaseRealtimeService,
        WebSocketConnectionTracker connectionTracker,
        ObjectMapper objectMapper,
        HttpClient httpClient
    ) {
        this.monitoringProperties = monitoringProperties;
        this.supabaseRealtimeService = supabaseRealtimeService;
        this.connectionTracker = connectionTracker;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @PostConstruct
    void start() {
        long interval = Math.max(15, monitoringProperties.getHeartbeat().getIntervalSeconds());
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, interval, TimeUnit.SECONDS);
        log.info("Proxy heartbeat scheduled every {}s", interval);
    }

    @PreDestroy
    void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        scheduler.shutdown();
    }

    void sendHeartbeat() {
        try {
            long uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000;
            ObjectNode payload = buildHeartbeatPayload(uptimeSeconds);

            String body = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(monitoringProperties.getHeartbeat().getUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + supabaseAnonKey)
                .header("apikey", supabaseAnonKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        log.warn("Heartbeat failed: {} - {}", response.statusCode(), response.body());
                    } else {
                        log.debug("Heartbeat acknowledged: {}", response.body());
                    }
                })
                .exceptionally(e -> {
                    log.warn("Heartbeat error: {}", e.getMessage());
                    return null;
                });
        } catch (Exception ex) {
            log.error("Failed to send heartbeat", ex);
        }
    }

    ObjectNode buildHeartbeatPayload(long uptimeSeconds) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("server_id", monitoringProperties.getServerId());
        payload.put("uptime_seconds", uptimeSeconds);
        payload.put("connected_clients", connectionTracker.getConnectedClients());
        payload.put("version", monitoringProperties.getVersion());
        payload.put("supabase_connected", supabaseRealtimeService.isSupabaseConnected());
        payload.put("messages_relayed", supabaseRealtimeService.getRelayedMessages());
        return payload;
    }
}
