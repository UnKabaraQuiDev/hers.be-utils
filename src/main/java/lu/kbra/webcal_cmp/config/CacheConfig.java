package lu.kbra.webcal_cmp.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.github.benmanes.caffeine.cache.Caffeine;

@Profile("calendar")
@Configuration
public class CacheConfig {

	@Bean
	CacheManager cacheManager() {
		final CaffeineCacheManager manager = new CaffeineCacheManager("calendars", "parsedCal", "transformed");
		manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(2)).maximumSize(1000));
		return manager;
	}

}
