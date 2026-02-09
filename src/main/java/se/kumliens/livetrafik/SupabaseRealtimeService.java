package se.kumliens.livetrafik;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import se.kumliens.livetrafik.VehicleCacheService.CacheMetrics;
import se.kumliens.livetrafik.model.VehicleBroadcastPayload;

/**
 * Connects to Supabase Realtime, subscribes to regional vehicle channels, and
 * forwards incoming payloads to STOMP topics while keeping the local vehicle cache
 * in sync for typed feeds.
 */
@Service
@Slf4j
public class SupabaseRealtimeService {

    private final VehicleCacheService vehicleCacheService;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry meterRegistry;
    private final Timer supabasePayloadLatency;
    private final Timer stompDispatchTimer;
    private final Counter stompDispatchCounter;
    private final Counter supabasePayloadCounter;
    
    @Value("${supabase.url}")
    private String supabaseWsUrl;
    
    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;
    
    @Value("${supabase.channel:}")
    private String channelNamesProperty;
    
    @Value("${supabase.regions:ul,sl}")
    private String regionsProperty;
    
    @Value("${supabase.vehicle-types:bus,train}")
    private String vehicleTypesProperty;
    
    private WebSocketClient wsClient;
    private ScheduledExecutorService heartbeatExecutor;
    private final AtomicInteger messageRef = new AtomicInteger(1);
    private final AtomicLong relayedMessages = new AtomicLong();
    private volatile boolean supabaseConnected;
    private List<String> channelNames = List.of();
    private List<String> activeRegions = List.of("ul", "sl");
    private List<String> vehicleTypes = List.of("bus", "train");

    public SupabaseRealtimeService(
            VehicleCacheService vehicleCacheService,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate,
            MeterRegistry meterRegistry) {
        this.vehicleCacheService = vehicleCacheService;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.meterRegistry = meterRegistry;
        this.supabasePayloadLatency = Timer.builder("trafik.supabase.payload.latency")
            .description("Latency from Supabase timestamp until the payload is relayed")
            .publishPercentileHistogram(true)
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .minimumExpectedValue(Duration.ofMillis(1))
            .maximumExpectedValue(Duration.ofSeconds(5))
            .register(meterRegistry);
        this.stompDispatchTimer = Timer.builder("trafik.stomp.dispatch.latency")
            .description("Time spent broadcasting a Supabase payload to STOMP")
            .publishPercentileHistogram(true)
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .minimumExpectedValue(Duration.ofNanos(100_000))
            .maximumExpectedValue(Duration.ofMillis(500))
            .register(meterRegistry);
        this.stompDispatchCounter = meterRegistry.counter("trafik.stomp.messages.sent");
        this.supabasePayloadCounter = meterRegistry.counter("trafik.supabase.payloads.received");
    }

    /**
     * Establishes the WebSocket connection to Supabase Realtime and subscribes to
     * all configured region/type channels.
     */
    @PostConstruct
    public void connect() {
        String fullUrl = supabaseWsUrl + "?apikey=" + supabaseAnonKey + "&vsn=1.0.0";
        this.activeRegions = resolveList(regionsProperty, List.of("ul", "sl"));
        this.vehicleTypes = resolveList(vehicleTypesProperty, List.of("bus", "train"));
        this.channelNames = resolveChannelNames();
        
        try {
            wsClient = new WebSocketClient(new URI(fullUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("Connected to Supabase Realtime");
                    supabaseConnected = true;
                    joinChannels();
                    startHeartbeat();
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("WebSocket closed: {} - {}. Reconnecting...", code, reason);
                    supabaseConnected = false;
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error", ex);
                    supabaseConnected = false;
                }
            };
            
            wsClient.connect();
            
        } catch (Exception e) {
            log.error("Failed to create WebSocket client", e);
            scheduleReconnect();
        }
    }
    
    private void joinChannels() {
        channelNames.forEach(channel -> {
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
                """, channel, messageRef.getAndIncrement());

            wsClient.send(joinMessage);
            log.info("Sent join request for channel: {}", channel);
        });

        log.info("Supabase Realtime: subscribing to {} channels", channelNames.size());
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

            if ("broadcast".equals(event)) {
                JsonNode payload = root.path("payload");
                String broadcastEvent = payload.path("event").asText();

                if ("vehicles".equals(broadcastEvent)) {
                    JsonNode vehiclePayload = payload.path("payload");
                    supabasePayloadCounter.increment(); // Count all incoming payloads
                    recordSupabaseLatency(vehiclePayload); // Measure as early as possible
                    handleVehiclePayload(topic, vehiclePayload);
                }
            } else if ("phx_reply".equals(event)) {
                String status = root.path("payload").path("status").asText();
                log.debug("Channel join status: {} ({})", status, topic);
            }

        } catch (Exception e) {
            log.error("Error parsing message: {}", message, e);
        }
    }

    /**
     * Maps an incoming Supabase channel payload to the equivalent STOMP topic
     * and updates the cache for typed feeds.
     */
    private void handleVehiclePayload(String topic, JsonNode vehiclePayload) {
        String channel = extractChannel(topic);
        if (channel == null) {
            log.warn("Unable to extract channel from topic {}", topic);
            return;
        }

        // Broadcast upstream payload to clients regardless of vehicles/removed entries
        String stompTopic = "/topic/" + channel;
        long dispatchStart = System.nanoTime();
        messagingTemplate.convertAndSend(stompTopic, vehiclePayload);
        stompDispatchTimer.record(Duration.ofNanos(System.nanoTime() - dispatchStart));
        stompDispatchCounter.increment();
        log.debug("Forwarded payload to {}", stompTopic);
        relayedMessages.incrementAndGet();

        ChannelDescriptor descriptor = ChannelDescriptor.from(channel);
        if (descriptor.type() == null) {
            // Combined region feed – no cache update to avoid duplicate legacy broadcasts
            return;
        }

        VehicleBroadcastPayload dto = objectMapper.convertValue(vehiclePayload, VehicleBroadcastPayload.class);
        dto.backfillRegionAndType(descriptor.region(), descriptor.type());

        CacheMetrics metrics = vehicleCacheService.applyDelta(dto);
        log.debug("[STOMP] vehicles update: region={} type={} received={} removed={} cacheSize={}",
            dto.getRegion(),
            dto.getVehicleType(),
            dto.getVehicles().size(),
            dto.getRemovedVehicleIds().size(),
            metrics.cacheSize());
    }

    private void recordSupabaseLatency(JsonNode vehiclePayload) {
        long timestamp = vehiclePayload.path("timestamp").asLong(0L);
        if (timestamp <= 0L) {
            return;
        }
        long diff = System.currentTimeMillis() - timestamp;
        if (diff >= 0) {
            supabasePayloadLatency.record(Duration.ofMillis(diff));
        }
    }
    
    private void scheduleReconnect() {
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            log.info("Attempting to reconnect...");
            connect();
        }, 5, TimeUnit.SECONDS);
    }

    private List<String> resolveChannelNames() {
        List<String> explicitChannels = resolveList(channelNamesProperty, List.of());
        if (!explicitChannels.isEmpty()) {
            return explicitChannels;
        }

        List<String> channels = new ArrayList<>();
        for (String region : activeRegions) {
            for (String type : vehicleTypes) {
                channels.add(region + "/vehicles/" + type);
            }
        }
        return channels;
    }

    private List<String> resolveList(String csv, List<String> defaults) {
        if (csv == null || csv.isBlank()) {
            return defaults;
        }
        List<String> values = Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        return values.isEmpty() ? defaults : values;
    }

    private String extractChannel(String topic) {
        if (topic == null) {
            return null;
        }
        return topic.startsWith("realtime:") ? topic.substring("realtime:".length()) : topic;
    }

    private record ChannelDescriptor(String region, String type) {
        static ChannelDescriptor from(String channel) {
            if (channel == null || channel.isBlank()) {
                return new ChannelDescriptor("ul", "bus");
            }
            String[] slashParts = channel.split("/");
            if (slashParts.length >= 3) {
                return new ChannelDescriptor(slashParts[0], slashParts[2]);
            }
            if (slashParts.length == 2) {
                return new ChannelDescriptor(slashParts[0], null);
            }

            // Fallback for legacy dash-separated names
            String[] dashParts = channel.split("-");
            if (dashParts.length >= 3) {
                return new ChannelDescriptor(dashParts[2], dashParts[dashParts.length - 1]);
            }
            return new ChannelDescriptor(dashParts[0], "bus");
        }
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

    public long getRelayedMessages() {
        return relayedMessages.get();
    }

    public boolean isSupabaseConnected() {
        return supabaseConnected && wsClient != null && wsClient.isOpen();
    }
}
