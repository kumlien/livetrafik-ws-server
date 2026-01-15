package se.kumliens.livetrafik;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import se.kumliens.livetrafik.VehicleCacheService.CacheMetrics;
import se.kumliens.livetrafik.model.VehicleBroadcastPayload;

class VehicleCacheServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private VehicleCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new VehicleCacheService(mapper, Duration.ofMinutes(5));
    }

    @Test
    void removeOnlyMessagePurgesVehicleImmediately() {
        VehicleBroadcastPayload initial = payload("ul", "bus", List.of(vehicle("A", 1.0, ts())), List.of(), 1L);
        assertEquals(1, initial.getVehicles().size());
        cache.applyDelta(initial);
        assertEquals(1, cache.sizeForTesting("ul", "bus"));

        cache.applyDelta(payload("ul", "bus", List.of(), List.of("A"), 2L));

        ArrayNode vehicles = vehiclesForRegion("ul");
        assertEquals(0, vehicles.size());
    }

    @Test
    void removeThenAddInSameMessageKeepsNewestData() {
        cache.applyDelta(payload("ul", "bus", List.of(vehicle("A", 1.0, ts())), List.of(), 1L));

        VehicleBroadcastPayload delta = payload(
            "ul",
            "bus",
            List.of(vehicle("A", 2.0, ts())),
            List.of("A"),
            2L
        );

        cache.applyDelta(delta);

        ArrayNode vehicles = vehiclesForRegion("ul");
        assertEquals(1, vehicles.size());
        assertEquals(2.0, vehicles.get(0).path("latitude").asDouble());
    }

    @Test
    void addOnlyMessageDoesNotRemoveOtherVehicles() {
        cache.applyDelta(payload("ul", "bus", List.of(
            vehicle("A", 1.0, ts()),
            vehicle("B", 2.0, ts())
        ), List.of(), 1L));

        cache.applyDelta(payload("ul", "bus", List.of(
            vehicle("A", 5.0, ts())
        ), List.of(), 2L));

        ArrayNode vehicles = vehiclesForRegion("ul");
        assertEquals(2, vehicles.size());

        Map<String, Double> latitudes = Map.of(
            vehicles.get(0).path("vehicle_id").asText(), vehicles.get(0).path("latitude").asDouble(),
            vehicles.get(1).path("vehicle_id").asText(), vehicles.get(1).path("latitude").asDouble()
        );

        assertEquals(5.0, latitudes.get("A"));
        assertEquals(2.0, latitudes.get("B"));
    }

    @Test
    void missingListsAreTolerated() {
        VehicleBroadcastPayload payload = payload("ul", "bus", null, null, 1L);

        CacheMetrics metrics = cache.applyDelta(payload);

        assertEquals(0, metrics.removedCount());
        assertEquals(0, metrics.updatedCount());
        assertEquals(0, metrics.cacheSize());
    }

    @Test
    void explicitRemovalsTakePrecedenceOverStaleCleanup() {
        VehicleCacheService shortTtlCache = new VehicleCacheService(mapper, Duration.ZERO);
        shortTtlCache.applyDelta(payload("ul", "bus", List.of(
            vehicle("A", 1.0, ts())
        ), List.of(), 1L));

        shortTtlCache.applyDelta(payload("ul", "bus", List.of(), List.of("A"), 2L));

        ArrayNode vehicles = assertInstanceOf(ArrayNode.class, shortTtlCache.getLatestVehicles("ul").get("vehicles"));
        assertEquals(0, vehicles.size());
    }

    private ArrayNode vehiclesForRegion(String region) {
        Map<String, Object> latest = cache.getLatestVehicles(region);
        Object vehicles = latest.get("vehicles");
        assertTrue(vehicles instanceof ArrayNode, "vehicles payload should be an ArrayNode");
        return (ArrayNode) vehicles;
    }

    private VehicleBroadcastPayload payload(String region, String type, List<ObjectNode> vehicles, List<String> removed, long timestamp) {
        VehicleBroadcastPayload payload = new VehicleBroadcastPayload();
        payload.setRegion(region);
        payload.setVehicleType(type);
        payload.setTimestamp(timestamp);
        payload.setVehicles(vehicles);
        payload.setRemovedVehicleIds(removed);
        return payload;
    }

    private ObjectNode vehicle(String id, double lat, String updatedAt) {
        ObjectNode node = mapper.createObjectNode();
        node.put("vehicle_id", id);
        node.put("latitude", lat);
        node.put("updated_at", updatedAt);
        return node;
    }

    private String ts() {
        return Instant.now().toString();
    }
}
