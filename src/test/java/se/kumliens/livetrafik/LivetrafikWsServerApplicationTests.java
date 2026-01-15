package se.kumliens.livetrafik;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringBootTest
class LivetrafikWsServerApplicationTests {

	@Test
	void contextLoads() {
	}

	@Configuration(proxyBeanMethods = false)
	static class TestConfig {
		@Bean
		SupabaseRealtimeService supabaseRealtimeService() {
			return Mockito.mock(SupabaseRealtimeService.class);
		}
	}

}
