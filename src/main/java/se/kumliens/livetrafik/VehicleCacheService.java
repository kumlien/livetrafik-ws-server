package se.kumliens.livetrafik;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VehicleCacheService {

    private static final Logger log = LoggerFactory.getLogger(VehicleCacheService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Cache: region -> senaste fordonsdata (som JSON string)
    private final Map<String, String> vehicleCache = new ConcurrentHashMap<>();
    private final Map<String, Long> timestampCache = new ConcurrentHashMap<>();

    public VehicleCacheService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void updateVehicles(String region, JsonNode vehiclesNode) {
        try {
            String vehiclesJson = objectMapper.writeValueAsString(vehiclesNode);
            vehicleCache.put(region, vehiclesJson);
            timestampCache.put(region, System.currentTimeMillis());
            
            // Broadcast till alla STOMP-prenumeranter
            Map<String, Object> payload = Map.of(
                "vehicles", vehiclesNode,
                "region", region,
                "timestamp", System.currentTimeMillis()
            );
            
            messagingTemplate.convertAndSend(
                "/topic/vehicles-" + region, 
                payload
            );
            
            log.debug("Broadcasted {} vehicles to /topic/vehicles-{}", 
                vehiclesNode.size(), region);
                
        } catch (Exception e) {
            log.error("Error updating vehicle cache", e);
        }
    }
    
    public Map<String, Object> getLatestVehicles(String region) {
        String vehiclesJson = vehicleCache.get(region);
        Long timestamp = timestampCache.get(region);
        
        if (vehiclesJson == null) {
            return Map.of(
                "vehicles", new Object[0],
                "region", region,
                "timestamp", 0L
            );
        }
        
        try {
            JsonNode vehicles = objectMapper.readTree(vehiclesJson);
            return Map.of(
                "vehicles", vehicles,
                "region", region,
                "timestamp", timestamp != null ? timestamp : 0L
            );
        } catch (Exception e) {
            log.error("Error reading cache", e);
            return Map.of("vehicles", new Object[0], "region", region);
        }
    }
}

