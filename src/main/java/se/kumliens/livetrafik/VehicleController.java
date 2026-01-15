package se.kumliens.livetrafik;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes REST endpoints for retrieving cached vehicle data and a simple health
 * probe so external monitors can verify that the service is alive.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class VehicleController {

    private final VehicleCacheService vehicleCacheService;

    public VehicleController(VehicleCacheService vehicleCacheService) {
        this.vehicleCacheService = vehicleCacheService;
    }

    /**
     * Returns the latest cached vehicles for the requested region, combining bus
     * and train payloads as persisted by {@link VehicleCacheService}.
     */
    @GetMapping("/latest/{region}")
    public Map<String, Object> getLatestVehicles(@PathVariable String region) {
        return vehicleCacheService.getLatestVehicles(region);
    }
    
    /**
     * Lightweight health endpoint used by local testing or systemd health checks.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "timestamp", System.currentTimeMillis()
        );
    }
}

