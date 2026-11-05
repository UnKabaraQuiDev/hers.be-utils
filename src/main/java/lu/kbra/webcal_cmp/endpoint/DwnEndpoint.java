package lu.kbra.webcal_cmp.endpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lu.kbra.webcal_cmp.dwn.DwnService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class DwnEndpoint {

	private final DwnService files;

	@Value("${dwn.webhook-token:}")
	private String webhookToken;

	@PostMapping("/dwn")
	public ResponseEntity<?> refresh(
			@RequestHeader(value = "Authorization", required = false) final String authorization,
			@RequestHeader(value = "X-Webhook-Token", required = false) final String headerToken,
			@RequestParam(value = "key", required = false) final String key) {
		if (this.webhookToken == null || this.webhookToken.isBlank()) {
			return ResponseEntity.status(503).body(Map.of("error", "DWN_WEBHOOK_TOKEN not configured"));
		}
		final String provided = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7)
				: headerToken != null ? headerToken
				: key;
		if (provided == null
				|| !MessageDigest.isEqual(this.webhookToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
		}
		if (!this.files.triggerAsync()) {
			return ResponseEntity.status(409).body(Map.of("status", "already-running"));
		}
		return ResponseEntity.accepted().body(Map.of("status", "started"));
	}

	@GetMapping("/files/status")
	public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) final String authorization) {
		if (this.webhookToken == null || this.webhookToken.isBlank() || authorization == null || !authorization.startsWith("Bearer ")
				|| !MessageDigest.isEqual(this.webhookToken.getBytes(StandardCharsets.UTF_8),
						authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
			return ResponseEntity.status(401).build();
		}
		final var report = this.files.lastReport();
		return ResponseEntity.ok(Map.of("running",
				this.files.isRunning(),
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
