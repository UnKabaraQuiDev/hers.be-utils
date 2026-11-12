package lu.kbra.webcal_cmp.endpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lu.kbra.webcal_cmp.dwn.DwnService;
import lu.kbra.webcal_cmp.moodle.MoodleService;

import lombok.RequiredArgsConstructor;

@Profile("files-hers")
@RestController
@RequiredArgsConstructor
public class DwnEndpoint {

	private final DwnService files;
	private final MoodleService moodle;

	@Value("${dwn.webhook-token:}")
	private String webhookToken;

	@PostMapping("/dwn")
	public ResponseEntity<?> refresh(
			@RequestHeader(value = "Authorization", required = false) final String authorization,
			@RequestHeader(value = "X-Webhook-Token", required = false) final String headerToken,
			@RequestParam(value = "key", required = false) final String key) {
		if (!this.authorized(authorization, headerToken, key)) {
			return ResponseEntity.status(401).build();
		}
		if (!this.files.triggerAsync() | !moodle.triggerAsync()) {
			return ResponseEntity.status(409).body(Map.of("status", "already-running"));
		}
		return ResponseEntity.accepted().body(Map.of("status", "started"));
	}

	@PostMapping("/myhers")
	public ResponseEntity<?> refreshMyHers(
			@RequestHeader(value = "Authorization", required = false) final String authorization,
			@RequestHeader(value = "X-Webhook-Token", required = false) final String headerToken,
			@RequestParam(value = "key", required = false) final String key) {
		if (!this.authorized(authorization, headerToken, key)) {
			return ResponseEntity.status(401).build();
		}
		if (!this.files.triggerAsync()) {
			return ResponseEntity.status(409).body(Map.of("status", "already-running"));
		}
		return ResponseEntity.accepted().body(Map.of("status", "started"));
	}

	@PostMapping("/moodle")
	public ResponseEntity<?> refreshMoodle(
			@RequestHeader(value = "Authorization", required = false) final String authorization,
			@RequestHeader(value = "X-Webhook-Token", required = false) final String headerToken,
			@RequestParam(value = "key", required = false) final String key) {
		if (!this.authorized(authorization, headerToken, key)) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
		}
		if (!this.moodle.triggerAsync()) {
			return ResponseEntity.status(409).body(Map.of("status", "already-running"));
		}
		return ResponseEntity.accepted().body(Map.of("status", "started"));
	}

	private boolean authorized(final String auth, final String header, final String key) {
		if (this.webhookToken == null || this.webhookToken.isBlank()) {
			return false;
		}
		final String provided = auth != null && auth.startsWith("Bearer ") ? auth.substring(7)
				: header != null ? header
				: key;
		return provided != null
				&& MessageDigest.isEqual(this.webhookToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
	}

	@GetMapping("/files/status")
	public ResponseEntity<?> status(
			@RequestHeader(value = "Authorization", required = false) final String authorization,
			@RequestParam(value = "source", defaultValue = "myhers") final String source) {
		if (!this.authorized(authorization, null, null)) {
			return ResponseEntity.status(401).build();
		}
		final boolean isMoodle = "moodle".equalsIgnoreCase(source);
		final var report = isMoodle ? this.moodle.lastReport() : this.files.lastReport();
		return ResponseEntity.ok(Map.of("running",
				isMoodle ? this.moodle.isRunning() : this.files.isRunning(),
				"source",
				isMoodle ? "moodle" : "myhers",
				"started",
				report == null ? "none" : report.started().toString(),
				"coursesChecked",
				report == null ? 0 : report.coursesChecked(),
				"filesChecked",
				report == null ? 0 : report.filesChecked(),
				"downloaded",
				report == null ? List.of() : report.downloads(),
				"failures",
				report == null ? List.of() : report.failures()));
	}
}
