package se.kumliens.livetrafik.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "supabase")
public class SupabaseProperties {

    /** Full Realtime WebSocket URL (wss://…/realtime/v1/websocket). */
    private String url;
    private String anonKey;
    /** Explicit channel list; when non-empty it replaces the regions × vehicle-types list. */
    private List<String> channel = List.of();
    private List<String> regions = List.of("ul", "sl");
    private List<String> vehicleTypes = List.of("bus", "train");
}
