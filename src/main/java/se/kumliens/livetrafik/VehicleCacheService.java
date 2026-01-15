package se.kumliens.livetrafik;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import se.kumliens.livetrafik.model.VehicleBroadcastPayload;

/**
 * Maintains per-region, per-vehicle-type caches using remove-first delta merge
 * semantics and provides combined snapshots for REST clients.
 */
@Service
@Slf4j
public class VehicleCacheService {

    private final ObjectMapper objectMapper;
    private final Duration staleTtl;
    private final Map<CacheKey, VehicleState> stateByKey = new ConcurrentHashMap<>();

    @Autowired
    public VehicleCacheService(
            ObjectMapper objectMapper,
            @Value("${vehicles.cache.ttl-minutes:5}") long cacheTtlMinutes) {
        this(objectMapper, Duration.ofMinutes(cacheTtlMinutes));
    }

    VehicleCacheService(ObjectMapper objectMapper, Duration staleTtl) {
        this.objectMapper = objectMapper;
        this.staleTtl = staleTtl;
    }

    /**
     * Applies a delta payload using remove-first semantics.
     */
    public CacheMetrics applyDelta(VehicleBroadcastPayload payload) {
        String region = sanitize(payload.getRegion());
        String vehicleType = sanitize(payload.getVehicleType());

        if (region == null || vehicleType == null) {
            log.warn("Skipping payload without region/type: region={} type={}", payload.getRegion(), payload.getVehicleType());
            return CacheMetrics.empty(region, vehicleType);
        }

        CacheKey key = CacheKey.of(region, vehicleType);
        VehicleState state = stateByKey.computeIfAbsent(key, unused -> new VehicleState());

        payload.backfillRegionAndType(region, vehicleType);

        long now = System.currentTimeMillis();
        int removed = state.removeAll(payload.getRemovedVehicleIds());
        int updated = state.upsert(payload.getVehicles(), now);
        int cleaned = state.cleanup(now, staleTtl);

        return new CacheMetrics(region, vehicleType, removed, updated, cleaned, state.size());
    }

    /**
     * Returns the latest combined snapshot for a region used by the REST API.
     */
    public Map<String, Object> getLatestVehicles(String region) {
        String sanitizedRegion = sanitize(region);
        if (sanitizedRegion == null) {
            return Map.of(
                "vehicles", objectMapper.createArrayNode(),
                "region", region,
                "timestamp", 0L
            );
        }

        Snapshot bus = snapshotFor(sanitizedRegion, "bus");
        Snapshot train = snapshotFor(sanitizedRegion, "train");

        ArrayNode combined = objectMapper.createArrayNode();
        combined.addAll(bus.data());
        combined.addAll(train.data());

        long timestamp = Math.max(bus.latestTimestamp(), train.latestTimestamp());

        return Map.of(
            "vehicles", combined,
            "region", sanitizedRegion,
            "timestamp", timestamp
        );
    }

    private Snapshot snapshotFor(String region, String vehicleType) {
        VehicleState state = stateByKey.get(CacheKey.of(region, vehicleType));
        if (state == null) {
            return new Snapshot(objectMapper.createArrayNode(), 0L);
        }
        return new Snapshot(state.snapshot(objectMapper), state.latestTimestamp());
    }

    int sizeForTesting(String region, String vehicleType) {
        VehicleState state = stateByKey.get(CacheKey.of(sanitize(region), sanitize(vehicleType)));
        return state == null ? 0 : state.size();
    }

    ArrayNode snapshotForTesting(String region, String vehicleType) {
        VehicleState state = stateByKey.get(CacheKey.of(sanitize(region), sanitize(vehicleType)));
        return state == null ? objectMapper.createArrayNode() : state.snapshot(objectMapper);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }

    private record CacheKey(String region, String vehicleType) {
        static CacheKey of(String region, String vehicleType) {
            return new CacheKey(region, vehicleType);
        }
    }

    private static final class VehicleState {
        private final Map<String, StoredVehicle> entries = new ConcurrentHashMap<>();

        int removeAll(List<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return 0;
            }
            int removed = 0;
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                removed += entries.remove(id) != null ? 1 : 0;
            }
            return removed;
        }

        int upsert(List<ObjectNode> vehicles, long fallbackTimestamp) {
            if (vehicles == null || vehicles.isEmpty()) {
                return 0;
            }
            int updated = 0;
            for (ObjectNode vehicle : vehicles) {
                if (vehicle == null) {
                    continue;
                }
                String vehicleId = vehicle.path("vehicle_id").asText(null);
                if (vehicleId == null || vehicleId.isBlank()) {
                    continue;
                }
                long updatedAt = resolveUpdatedAt(vehicle, fallbackTimestamp);
                entries.put(vehicleId, new StoredVehicle(vehicle.deepCopy(), updatedAt));
                updated++;
            }
            return updated;
        }

        int cleanup(long now, Duration ttl) {
            long threshold = now - ttl.toMillis();
            int removed = 0;
            for (Map.Entry<String, StoredVehicle> entry : entries.entrySet()) {
                if (entry.getValue().lastUpdated() < threshold) {
                    entries.remove(entry.getKey(), entry.getValue());
                    removed++;
                }
            }
            return removed;
        }

        ArrayNode snapshot(ObjectMapper mapper) {
            ArrayNode array = mapper.createArrayNode();
            entries.values().forEach(stored -> array.add(stored.vehicle().deepCopy()));
            return array;
        }

        long latestTimestamp() {
            return entries.values().stream()
                .mapToLong(StoredVehicle::lastUpdated)
                .max()
                .orElse(0L);
        }

        int size() {
            return entries.size();
        }

        private static long resolveUpdatedAt(ObjectNode vehicle, long fallbackTimestamp) {
            String updatedAt = vehicle.path("updated_at").asText(null);
            if (updatedAt == null || updatedAt.isBlank()) {
                return fallbackTimestamp;
            }
            try {
                return Instant.parse(updatedAt).toEpochMilli();
            } catch (Exception ex) {
                return fallbackTimestamp;
            }
        }
    }

    private record StoredVehicle(ObjectNode vehicle, long lastUpdated) { }

    private record Snapshot(ArrayNode data, long latestTimestamp) { }

    public record CacheMetrics(
        String region,
        String vehicleType,
        int removedCount,
        int updatedCount,
        int cleanedCount,
        int cacheSize
    ) {
        private static CacheMetrics empty(String region, String type) {
            return new CacheMetrics(region, type, 0, 0, 0, 0);
        }
    }
}
