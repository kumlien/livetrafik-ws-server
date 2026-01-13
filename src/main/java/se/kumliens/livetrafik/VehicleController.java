package se.kumliens.livetrafik;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class VehicleController {

    private final VehicleCacheService vehicleCacheService;

    public VehicleController(VehicleCacheService vehicleCacheService) {
        this.vehicleCacheService = vehicleCacheService;
    }

    @GetMapping("/latest/{region}")
    public Map<String, Object> getLatestVehicles(@PathVariable String region) {
        return vehicleCacheService.getLatestVehicles(region);
    }
    
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "timestamp", System.currentTimeMillis()
        );
    }
}

