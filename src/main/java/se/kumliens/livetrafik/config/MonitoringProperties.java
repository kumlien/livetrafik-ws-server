package se.kumliens.livetrafik.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring")
public class MonitoringProperties {

    private String serverId = "pi-proxy-1";
    private String version = "0.0.1-SNAPSHOT";
    private Heartbeat heartbeat = new Heartbeat();

    @Getter
    @Setter
    public static class Heartbeat {
        private String url = "https://wuwzjgqegoipxkoxjfhy.supabase.co/functions/v1/proxy-heartbeat";
        private long intervalSeconds = 30;
    }
}
