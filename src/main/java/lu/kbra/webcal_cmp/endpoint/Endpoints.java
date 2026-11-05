package lu.kbra.webcal_cmp.endpoint;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lu.kbra.webcal_cmp.service.CheckCalendarService;
import lu.kbra.webcal_cmp.service.IcsParser;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.fortuna.ical4j.model.Calendar;

@Slf4j
@RestController
@RequiredArgsConstructor
public class Endpoints {

	private final CheckCalendarService checkCalendarService;
	private final IcsParser parser;

	@Value("${calendar.key}")
	private String key;

	@PostConstruct
	public void init() {
		Endpoints.log.info("Key: {}", this.key);
	}

	@PostMapping("/refresh")
	public ResponseEntity<?> refreshCalendar(@RequestParam final String key) {
		if (!Objects.equals(this.key, key)) {
			return ResponseEntity.badRequest().build();
		}
		this.checkCalendarService.checkCalendar(true, false);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/webcal")
	public ResponseEntity<String> getCalendar(@RequestParam final String key) throws Exception {
		if (!Objects.equals(this.key, key)) {
			return ResponseEntity.badRequest().build();
		}

		final Calendar cal = this.parser.getCalendar();
		final String transformed = this.checkCalendarService.storeTransformed(cal);

		return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar")).body(transformed);
	}

}
