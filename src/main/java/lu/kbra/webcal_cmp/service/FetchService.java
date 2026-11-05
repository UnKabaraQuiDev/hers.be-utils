package lu.kbra.webcal_cmp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Profile("calendar")
@Service
public class FetchService {

	private final RestClient restClient;

	@Value("${calendar.url}")
	private String url;

	public FetchService(final RestClient.Builder builder) {
		this.restClient = builder.build();
	}

	@Cacheable("calendars")
	public String downloadCalendar() {
		return this.restClient.get().uri(this.url).retrieve().body(String.class);
	}

}