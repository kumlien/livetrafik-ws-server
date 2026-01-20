package se.kumliens.livetrafik;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;

import lombok.extern.slf4j.Slf4j;
import se.kumliens.livetrafik.config.MonitoringProperties;

@SpringBootApplication
@ImportRuntimeHints(se.kumliens.livetrafik.config.LivetrafikRuntimeHints.class)
@EnableConfigurationProperties(MonitoringProperties.class)
@Slf4j
public class LivetrafikWsServerApplication {

	public static void main(String[] args) {
		log.info("Starting LivetrafikWsServerApplication");
		SpringApplication.run(LivetrafikWsServerApplication.class, args);
	}

}
