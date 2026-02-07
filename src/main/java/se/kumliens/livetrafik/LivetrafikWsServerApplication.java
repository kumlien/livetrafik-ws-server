package se.kumliens.livetrafik;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.ImportRuntimeHints;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import se.kumliens.livetrafik.config.MonitoringProperties;

@SpringBootApplication
@ImportRuntimeHints(se.kumliens.livetrafik.config.LivetrafikRuntimeHints.class)
@EnableConfigurationProperties(MonitoringProperties.class)
@Slf4j
public class LivetrafikWsServerApplication {

    private final MonitoringProperties monitoringProperties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    public LivetrafikWsServerApplication(
        MonitoringProperties monitoringProperties,
        ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.monitoringProperties = monitoringProperties;
        this.buildPropertiesProvider = buildPropertiesProvider;
    }

	public static void main(String[] args) {
		SpringApplication.run(LivetrafikWsServerApplication.class, args);
	}

    @PostConstruct
    void logVersionInformation() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        if (buildProperties != null) {
            log.info(
                "Starting LivetrafikWsServerApplication – buildVersion={} buildTime={} monitoringVersion={} serverId={}",
                buildProperties.getVersion(),
                buildProperties.getTime(),
                monitoringProperties.getVersion(),
                monitoringProperties.getServerId());
        } else {
            log.info(
                "Starting LivetrafikWsServerApplication – monitoringVersion={} serverId={} (build properties unavailable)",
                monitoringProperties.getVersion(),
                monitoringProperties.getServerId());
        }
    }

}
