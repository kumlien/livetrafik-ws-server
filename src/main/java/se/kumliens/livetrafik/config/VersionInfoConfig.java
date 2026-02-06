package se.kumliens.livetrafik.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VersionInfoConfig {

    @Bean
    InfoContributor versionInfoContributor(
        ObjectProvider<BuildProperties> buildProperties,
        MonitoringProperties monitoringProperties) {

        return builder -> {
            buildProperties.ifAvailable(bp -> builder.withDetail("build", buildDetail(bp)));

            builder.withDetail("monitoring", Map.of(
                "serverId", monitoringProperties.getServerId(),
                "version", monitoringProperties.getVersion()
            ));
        };
    }

    private Map<String, Object> buildDetail(BuildProperties bp) {
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("artifact", bp.getArtifact());
        build.put("name", bp.getName());
        build.put("group", bp.getGroup());
        build.put("version", bp.getVersion());
        build.put("time", bp.getTime());
        return build;
    }
}
