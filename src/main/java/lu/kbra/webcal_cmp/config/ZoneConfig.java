package lu.kbra.webcal_cmp.config;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ZoneConfig {

	@Bean
	ZoneId zone(@Value("${calendar.zone:Europe/Luxembourg}") final String zone) {
		return ZoneId.of(zone);
	}

}
