package se.kumliens.livetrafik;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupabaseRealtimeService {

    private final VehicleCacheService vehicleCacheService;
    private final ObjectMapper objectMapper;
    
    @Value("${supabase.url}")
    private String supabaseWsUrl;
    
    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;
    
    @Value("${supabase.channel}")
    private String channelName;
    
    private WebSocketClient wsClient;
    private ScheduledExecutorService heartbeatExecutor;
    private final AtomicInteger messageRef = new AtomicInteger(1);

    @PostConstruct
    public void connect() {
        String fullUrl = supabaseWsUrl + "?apikey=" + supabaseAnonKey + "&vsn=1.0.0";
        
        try {
            wsClient = new WebSocketClient(new URI(fullUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("Connected to Supabase Realtime");
                    joinChannel();
                    startHeartbeat();
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("WebSocket closed: {} - {}. Reconnecting...", code, reason);
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error", ex);
                }
            };
            
            wsClient.connect();
            
        } catch (Exception e) {
            log.error("Failed to create WebSocket client", e);
            scheduleReconnect();
        }
    }
    
    private void joinChannel() {
        // Supabase Realtime Phoenix protocol - join broadcast channel
        String joinMessage = String.format("""
            {
                "topic": "realtime:%s",
                "event": "phx_join",
                "payload": {
                    "config": {
                        "broadcast": {
                            "self": false
                        }
                    }
                },
                "ref": "%d"
            }
            """, channelName, messageRef.getAndIncrement());
        
        wsClient.send(joinMessage);
        log.info("Sent join request for channel: {}", channelName);
    }
    
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (wsClient != null && wsClient.isOpen()) {
                String heartbeat = String.format("""
                    {
                        "topic": "phoenix",
                        "event": "heartbeat",
                        "payload": {},
                        "ref": "%d"
                    }
                    """, messageRef.getAndIncrement());
                wsClient.send(heartbeat);
                log.trace("Sent heartbeat");
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String event = root.path("event").asText();
            String topic = root.path("topic").asText();
            
            log.debug("Received event: {} on topic: {}", event, topic);
            
            if ("broadcast".equals(event)) {
                JsonNode payload = root.path("payload");
                String broadcastEvent = payload.path("event").asText();
                
                if ("vehicles".equals(broadcastEvent)) {
                    JsonNode vehiclePayload = payload.path("payload");
                    String region = vehiclePayload.path("region").asText();
                    JsonNode vehiclesNode = vehiclePayload.path("vehicles");
                    
                    if (vehiclesNode.isArray()) {
                        log.info("Received {} vehicles for region: {}", 
                            vehiclesNode.size(), region);
                        vehicleCacheService.updateVehicles(region, vehiclesNode);
                    }
                }
            } else if ("phx_reply".equals(event)) {
                String status = root.path("payload").path("status").asText();
                log.info("Channel join status: {}", status);
            }
            
        } catch (Exception e) {
            log.error("Error parsing message: {}", message, e);
        }
    }
    
    private void scheduleReconnect() {
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            log.info("Attempting to reconnect...");
            connect();
        }, 5, TimeUnit.SECONDS);
    }
    
    @PreDestroy
    public void disconnect() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        if (wsClient != null) {
            wsClient.close();
        }
    }
}
