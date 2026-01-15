package se.kumliens.livetrafik.model;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO representing the vehicle delta payload emitted by Supabase Edge
 * Functions. Lists default to empty to simplify downstream handling.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleBroadcastPayload {

    @JsonProperty("vehicles")
    private List<ObjectNode> vehicles = Collections.emptyList();

    @JsonProperty("removed_vehicle_ids")
    private List<String> removedVehicleIds = Collections.emptyList();

    private String region;
    private String vehicleType;
    private Long timestamp;

    public List<ObjectNode> getVehicles() {
        return vehicles == null ? Collections.emptyList() : vehicles;
    }

    public List<String> getRemovedVehicleIds() {
        return removedVehicleIds == null ? Collections.emptyList() : removedVehicleIds;
    }

    public void backfillRegionAndType(String fallbackRegion, String fallbackType) {
        if (region == null || region.isBlank()) {
            this.region = fallbackRegion;
        }
        if (vehicleType == null || vehicleType.isBlank()) {
            this.vehicleType = fallbackType;
        }
    }
}
