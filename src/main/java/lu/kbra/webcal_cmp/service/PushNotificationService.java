package lu.kbra.webcal_cmp.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import lu.kbra.pclib.PCUtils;
import lu.kbra.webcal_cmp.data.CalendarChanges;
import lu.kbra.webcal_cmp.data.CalendarEvent;
import lu.kbra.webcal_cmp.data.CalendarEventChange;
import lu.kbra.webcal_cmp.data.CalendarEventWarning;
import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.data.EventData;
import lu.kbra.webcal_cmp.db.data.MenuData;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PushNotificationService {

	private final ZoneId zone;

	private final RestClient restClient;

	@Value("${ntfy.alert}")
	private String alertTopic;

	@Value("${ntfy.menu}")
	private String menuTopic;

	@Value("${ntfy.files}")
	private String filesTopic;

	@Value("${dwn.webhook-token}")
	private String dwnWebhookToken;

	@Value("${spring.application.host-url}")
	private String hostUrl;

	public PushNotificationService(final ZoneId zone, final RestClient.Builder restClientBuilder) {
		this.zone = zone;
		this.restClient = restClientBuilder.baseUrl("https://ntfy.sh").build();
	}

	@PostConstruct
	public void init() {
		PushNotificationService.log.info("Using topics: " + this.alertTopic + " / " + this.menuTopic + " / " + this.filesTopic);
	}

	public void sendCalendarChanged(final CalendarChanges changes) {
		final String body = this.buildMessage(changes);

		try {
			this.restClient.post()
					.uri("/" + this.alertTopic)
					.header("X-Title", "Calendar changed")
					.header("X-Priority", "high")
					.header("X-Tags", "calendar")
					.header("X-Markdown", "true")
					.header("Content-Type", "text/plain; charset=utf-8")
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (final HttpClientErrorException v) {
			v.printStackTrace();
		}
	}

	public void sendMenu(final LocalDate date, final List<MenuData> menus) {
		if (menus.isEmpty() || this.menuTopic == null || this.menuTopic.isBlank()) {
			return;
		}
		final StringBuilder body = new StringBuilder();
		menus.stream().sorted(Comparator.comparingInt(c -> c.getMealType().ordinal())).forEachOrdered(meal -> {
			final String value = meal.getText();
			if (value != null && !value.isBlank()) {
				if (!body.isEmpty()) {
					body.append("\n\n");
				}
				body.append("**" + PCUtils.capitalize(meal.getMealType().name().toLowerCase()) + "**\n").append(value);
			}
		});
		if (body.isEmpty()) {
			return;
		}
		final String dateLabel = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH));
		try {
			this.restClient.post()
					.uri("/" + this.menuTopic)
					.header("X-Title", "Menu: " + dateLabel)
					.header("X-Tags", "fork_and_knife")
					.header("X-Markdown", "true")
					.header("Content-Type", "text/plain; charset=utf-8")
					.body(body.toString())
					.retrieve()
					.toBodilessEntity();
		} catch (final Exception e) {
			PushNotificationService.log.error("Failed to send menu notification for {}", date, e);
		}
	}

	public void sendFiles(final DownloadReport report) {
		if (report.downloads().isEmpty() || this.filesTopic == null || this.filesTopic.isBlank()) {
			return;
		}
		final List<String> entries = new ArrayList<>();
		for (final DownloadReport.Item item : report.downloads()) {
			final String icon = switch (item.status()) {
			case "new" -> "➕";
			case "updated" -> "🔄";
			default -> "📥";
			};
			entries.add(icon + " **" + item.status() + "** [" + item.course() + "] " + item.filename() + "\n  `" + item.path() + "`\n");
		}
		if (!report.failures().isEmpty()) {
			entries.add("\n**Failures**\n");
			for (final DownloadReport.Failure failure : report.failures()) {
				final String reason = failure.reason().length() > 450 ? failure.reason().substring(0, 450) + "..." : failure.reason();
				entries.add("❌ [" + failure.course() + "] " + failure.resource() + ": " + reason + "\n");
			}
		}
		final String heading = "Downloaded: " + report.downloads().size() + " | Checked: " + report.filesChecked() + " | Failed: "
				+ report.failures().size() + "\n\n";
		// ntfy has a per-message size limit. Break long first-run reports into chunks.
		final List<String> chunks = new ArrayList<>();
		final StringBuilder chunk = new StringBuilder(heading);
		for (final String entry : entries) {
			if (chunk.toString().getBytes(StandardCharsets.UTF_8).length + entry.getBytes(StandardCharsets.UTF_8).length > 3000
					&& chunk.length() > heading.length()) {
				chunks.add(chunk.toString());
				chunk.setLength(0);
				chunk.append(heading);
			}
			chunk.append(entry);
		}
		if (chunk.length() > heading.length()) {
			chunks.add(chunk.toString());
		}
		for (int i = 0; i < chunks.size(); i++) {
			try {
				this.restClient.post()
						.uri("/" + this.filesTopic)
						.header("X-Title",
								"HERS: " + report.downloads().size() + " file(s) downloaded (" + (i + 1) + "/" + chunks.size() + ")")
						.header("X-Tags", "file_folder")
						.header("X-Markdown", "true")
						.header("Content-Type", "text/plain; charset=utf-8")
						.headers(headers -> {
							if (this.dwnWebhookToken != null && !this.dwnWebhookToken.isBlank()) {
								headers.set("X-Actions",
										"http, Refresh files, " + this.hostUrl + "/dwn, method=POST, " + "headers.Authorization=Bearer "
												+ this.dwnWebhookToken + ", body=");
							}
						})
						.body(chunks.get(i))
						.retrieve()
						.toBodilessEntity();
			} catch (final Exception e) {
				PushNotificationService.log.error("Failed sending HERS file notification part {} of {}", i + 1, chunks.size(), e);
			}
		}
	}

	private String buildMessage(final CalendarChanges changes) {
		final StringBuilder message = new StringBuilder();
		message.append("\n");

		final Map<CalendarEvent, EventData> unique = new HashMap<>();
		changes.modified().forEach(change -> unique.put(change.newEvent(), change));
		changes.added().forEach(added -> unique.put(added, added));
		changes.removed().forEach(removed -> unique.put(removed, removed));
		changes.warning().forEach(warning -> unique.put(warning.event(), warning));

		final PriorityQueue<EventData> queue = new PriorityQueue<>(Comparator.comparing(EventData::getNewStartTime));
		queue.addAll(unique.values());

		final Set<CalendarEvent> added = changes.added();

		for (final EventData e : queue) {
			switch (e) {
			case final CalendarEventChange change -> message.append("✏️ ").append(this.formatModifiedEvent(change)).append("\n\n");
			case final CalendarEventWarning warning -> {
				message.append("❗ [**").append(warning.type()).append("**] ").append(warning.event().summary()).append("\n");

				message.append(this.formatEvent(warning.event())).append("\n\n");
			}
			case final CalendarEvent event -> {
				if (added.contains(event)) {
					message.append("➕ ").append(event.summary()).append("\n");

					message.append(this.formatEvent(event)).append("\n\n");
				} else {
					message.append("❌ ").append(event.summary()).append("\n");

					message.append(this.formatEvent(event)).append("\n\n");
				}
			}
			}
		}

		return message.toString().strip();

	}

	private String formatEvent(final CalendarEvent event) {
		final StringBuilder message = new StringBuilder();

		message.append("Date: ").append(this.formatDate(event.start())).append("\n");
		message.append("Time: ").append(this.formatDateTime(event.start()));

		if (event.end() != null) {
			message.append(" - ").append(this.formatDateTime(event.end()));
		}

		message.append("\n");

		if (event.location() != null && !event.location().isBlank()) {
			message.append("Location: ").append(event.location()).append("\n");
		}

		return message.toString().stripTrailing();
	}

	private String formatModifiedEvent(final CalendarEventChange change) {
		final CalendarEvent oldEvent = change.oldEvent();
		final CalendarEvent newEvent = change.newEvent();

		final StringBuilder message = new StringBuilder();

		message.append(newEvent.summary()).append("\n");

		message.append("Date: ").append(this.formatDate(newEvent.start())).append("\n");

		if (!oldEvent.start().equals(newEvent.start())) {
			message.append("Start ")
					.append(this.formatDateTime(oldEvent.start()))
					.append(" -> **")
					.append(this.formatDateTime(newEvent.start()))
					.append("**\n");
		} else {
			message.append("Time: ").append(this.formatDateTime(newEvent.start())).append("\n");
		}

		if (!Objects.equals(oldEvent.end(), newEvent.end())) {
			message.append("End ")
					.append(this.formatDateTime(oldEvent.end()))
					.append(" -> **")
					.append(this.formatDateTime(newEvent.end()))
					.append("**\n");
		} else {
			message.append("Time: ").append(this.formatDateTime(newEvent.end())).append("\n");
		}

		if (!Objects.equals(oldEvent.location(), newEvent.location())) {
			message.append("Location ")
					.append(this.valueOrEmpty(oldEvent.location()))
					.append(" -> **")
					.append(this.valueOrEmpty(newEvent.location()))
					.append("**\n");
		}

		return message.toString().stripTrailing();
	}

	private String valueOrEmpty(final String value) {
		return value == null || value.isBlank() ? "(none)" : value;
	}

	private String formatDate(final Instant instant) {
		return instant.atZone(this.zone).format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yy", Locale.ENGLISH));
	}

	private String formatDateTime(final Instant instant) {
		return instant.atZone(this.zone).format(DateTimeFormatter.ofPattern("HH:mm"));
	}

	public void sendFail(final Exception e) {
		try {
			this.restClient.post()
					.uri("/" + this.alertTopic)
					.header("X-Title", "Error occured")
					.header("X-Priority", "low")
					.header("X-Tags", "calendar")
					.header("X-Markdown", "true")
					.header("Content-Type", "text/plain; charset=utf-8")
					.body("Error occured:\n" + PCUtils.toString(e))
					.retrieve()
					.toBodilessEntity();
		} catch (final HttpClientErrorException v) {
			v.addSuppressed(e);
			v.printStackTrace();
		}
	}

	public void sendOk() {
		try {
			this.restClient.post()
					.uri("/" + this.alertTopic)
					.header("X-Title", "Error occured")
					.header("X-Priority", "low")
					.header("X-Tags", "calendar")
					.header("X-Markdown", "true")
					.header("Content-Type", "text/plain; charset=utf-8")
					.body("Back online.")
					.retrieve()
					.toBodilessEntity();
		} catch (final HttpClientErrorException v) {
			v.printStackTrace();
		}
	}

}
