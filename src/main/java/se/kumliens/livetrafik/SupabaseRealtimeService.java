package se.kumliens.livetrafik;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
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
import se.kumliens.livetrafik.config.SupabaseProperties;
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
    private final SupabaseProperties supabaseProperties;
    private final List<String> channelNames;

    private WebSocketClient wsClient;
    long reconnectDelayMillis = 5_000;
    // One shared scheduler for Phoenix heartbeats and reconnects; per-attempt executors leaked a thread each
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "supabase-realtime");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean reconnectPending = new AtomicBoolean();
    private volatile boolean shuttingDown;
    private final AtomicInteger messageRef = new AtomicInteger(1);
    private final AtomicLong relayedMessages = new AtomicLong();
    private volatile boolean supabaseConnected;

    public SupabaseRealtimeService(
            SupabaseProperties supabaseProperties,
            VehicleCacheService vehicleCacheService,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate,
            MeterRegistry meterRegistry) {
        if (supabaseProperties.getAnonKey() == null || supabaseProperties.getAnonKey().isBlank()) {
            throw new IllegalStateException("supabase.anon-key is empty – set SUPABASE_ANON_KEY");
        }
        this.supabaseProperties = supabaseProperties;
        this.channelNames = resolveChannelNames(supabaseProperties);
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
        String fullUrl = supabaseProperties.getUrl() + "?apikey=" + supabaseProperties.getAnonKey() + "&vsn=1.0.0";

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
                    log.warn("WebSocket closed: {} - {}", code, reason);
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
    
    private synchronized void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
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
    
    void handleMessage(String message) {
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

        ChannelDescriptor descriptor = ChannelDescriptor.parse(channel);
        if (descriptor == null) {
            log.debug("Relayed but not cached: {}", channel);
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
        // onError and onClose can both fire for one failure; keep a single pending attempt
        if (shuttingDown || !reconnectPending.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            reconnectPending.set(false);
            if (!shuttingDown) {
                log.info("Attempting to reconnect...");
                connect();
            }
        }, reconnectDelayMillis, TimeUnit.MILLISECONDS);
    }

    private static List<String> resolveChannelNames(SupabaseProperties properties) {
        if (!properties.getChannel().isEmpty()) {
            properties.getChannel().stream()
                .filter(channel -> ChannelDescriptor.parse(channel) == null)
                .forEach(channel -> log.warn("Channel {} is not <region>/vehicles/<type>; it is relayed but not cached", channel));
            return properties.getChannel();
        }

        List<String> channels = new ArrayList<>();
        for (String region : properties.getRegions()) {
            for (String type : properties.getVehicleTypes()) {
                channels.add(region + "/vehicles/" + type);
            }
        }
        return channels;
    }

    private String extractChannel(String topic) {
        if (topic == null) {
            return null;
        }
        return topic.startsWith("realtime:") ? topic.substring("realtime:".length()) : topic;
    }

    private record ChannelDescriptor(String region, String type) {
        /** Returns null for anything but {region}/vehicles/{type}, so no cache is filled under a guessed key. */
        static ChannelDescriptor parse(String channel) {
            String[] parts = channel.split("/", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
                return null;
            }
            return new ChannelDescriptor(parts[0], parts[2]);
        }
    }
    
    @PreDestroy
    public void disconnect() {
        shuttingDown = true;
        scheduler.shutdownNow();
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
