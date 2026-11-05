package lu.kbra.webcal_cmp.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.CalendarEvent;

import lombok.RequiredArgsConstructor;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.validate.ValidationException;

@Service
@RequiredArgsConstructor
public class IcsParser {

	private static final String GROUP_EVENT_NAME = "COURS";

	private static final ZoneId ZONE = ZoneId.of("Europe/Luxembourg");

	private final FetchService fetchService;

	@Value("${calendar.class}")
	private String className;

	private Instant toInstant(final Temporal temporal) {
		return switch (temporal) {
		case final Instant instant -> instant;
		case final ZonedDateTime zonedDateTime -> zonedDateTime.toInstant();
		case final OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
		case final LocalDateTime localDateTime -> localDateTime.atZone(IcsParser.ZONE).toInstant();
		case final LocalDate localDate -> localDate.atStartOfDay(IcsParser.ZONE).toInstant();
		case null, default -> throw new IllegalArgumentException("Unsupported temporal type: " + temporal.getClass());
		};
	}

	@Cacheable("parsedCal")
	public Calendar getCalendar() throws IOException, ParserException {
		final String ics = this.fetchService.downloadCalendar();
		return this.parse(ics);
	}

	public List<CalendarEvent> eventsForToday(final List<CalendarEvent> events) {
		final LocalDate today = LocalDate.now(IcsParser.ZONE);

		final Instant start = today.atStartOfDay(IcsParser.ZONE).toInstant();

		final Instant end = today.plusDays(1).atStartOfDay(IcsParser.ZONE).toInstant();

		return events.stream().filter(event -> event.start().isBefore(end) && (event.end() == null || event.end().isAfter(start))).toList();
	}

	public Calendar parse(final String ics) throws IOException, ParserException {
		final CalendarBuilder builder = new CalendarBuilder();

		Calendar calendar;

		try (StringReader reader = new StringReader(ics)) {
			calendar = builder.build(reader);
		}

		return calendar;
	}

	public List<CalendarEvent> extractEvents(final Calendar calendar) {
		final List<CalendarEvent> events = new ArrayList<>();

		for (final Component component : calendar.getComponents(Component.VEVENT)) {
			final VEvent event = (VEvent) component;

			if (event.getUid().isEmpty() || IcsParser.GROUP_EVENT_NAME.equals(event.getName())) {
				continue;
			}

			final String uid = event.getUid().map(Uid::getValue).orElseThrow();

			String summary = event.getSummary().map(Summary::getValue).orElse("");
			this.fixDescription(event, summary);
			summary = this.fixSummary(summary);

			final String location = event.getLocation().map(Location::getValue).orElse("");

			final Instant start = event.getDateTimeStart().map(dt -> this.toInstant(dt.getDate())).orElseThrow();
			final Instant end = event.getDateTimeEnd().map(dt -> this.toInstant(dt.getDate())).orElse(null);

			events.add(new CalendarEvent(uid, summary, start, end, location));
		}

		return events;
	}

	private void fixDescription(final VEvent event, final String summary) {
		if (event.getDescription().isPresent()) {
			if (event.getDescription().get().getValue().contains("\n--")) {
				return;
			}
			event.getDescription().get().setValue(event.getDescription().get().getValue() + "\n\n-----\n" + summary);
		} else {
			event.add(new Description(summary));
		}
	}

	public Calendar fixCal(final Calendar calendar) throws Exception {
		final List<VEvent> events = calendar.getComponents(Component.VEVENT)
				.stream()
				.map(component -> (VEvent) component)
				.collect(Collectors.toList());

		for (final VEvent event : events) {
			event.getAlarms().clear();

			if (event.getSummary().isEmpty() || event.getUid().isEmpty() || IcsParser.GROUP_EVENT_NAME.equals(event.getName())) {
				continue;
			}

			String summary = event.getSummary().get().getValue();
			this.fixDescription(event, summary);
			summary = this.fixSummary(summary);

			event.getSummary().get().setValue(summary);
		}

		// Group events by their calendar date
		final Map<LocalDate, List<VEvent>> eventsByDay = events.stream()
				.filter(e -> !(e.getUid().isEmpty() || IcsParser.GROUP_EVENT_NAME.equals(e.getName())))
				.collect(Collectors.groupingBy(event -> event.getDateTimeStart()
						.map(dt -> this.toInstant(dt.getDate()))
						.orElseThrow()
						.atZone(ZoneId.systemDefault())
						.toLocalDate()));

		for (final List<VEvent> dayEvents : eventsByDay.values()) {
			dayEvents.sort(Comparator.comparing(event -> event.getDateTimeStart().map(dt -> this.toInstant(dt.getDate())).orElseThrow()));

			// Ignore "COURS SUSPENDUS" at the beginning of the day
			int first = 0;
			while (first < dayEvents.size() && (this.isCancelled(dayEvents.get(first)) || this.isNotConcerned(dayEvents.get(first)))) {
				first++;
			}

			// Ignore "COURS SUSPENDUS" at the end of the day
			int last = dayEvents.size() - 1;
			while (last >= first && (this.isCancelled(dayEvents.get(last)) || this.isNotConcerned(dayEvents.get(last)))) {
				last--;
			}

			// No actual classes on this day
			if (first > last) {
				continue;
			}

			final VEvent firstEvent = dayEvents.get(first);
			final VEvent lastEvent = dayEvents.get(last);

			final Instant start = firstEvent.getDateTimeStart().map(dt -> this.toInstant(dt.getDate())).orElseThrow();
			final Instant end = lastEvent.getDateTimeEnd().map(dt -> this.toInstant(dt.getDate())).orElseThrow();

			// One timed event covering the school day
			final VEvent dayEvent = new VEvent();
			dayEvent.add(new DtStart<>(start));
			dayEvent.add(new DtEnd<>(end));
			dayEvent.add(new Summary(IcsParser.GROUP_EVENT_NAME));
			final Uid uid = new Uid(this.generateUid(dayEvent));
			dayEvent.add(uid);

			final VAlarm alarm = new VAlarm();
			alarm.add(new Trigger(Duration.ofMinutes(-30)));
			dayEvent.add(alarm);

			calendar.getComponents(Component.VEVENT)
					.stream()
					.map(component -> (VEvent) component)
					.filter(event -> event.getUid().map(Uid::getValue).map(t -> t.equals(uid.getValue())).orElse(false))
					.findFirst()
					.ifPresentOrElse(existing -> {
						calendar.remove(existing);
						calendar.add(dayEvent);
					}, () -> calendar.add(dayEvent));
		}

		calendar.validate(true);

		return calendar;
	}

	private String fixSummary(String summary) {
		final String[] split = summary.split(";\\s+");

		final int index = Arrays.stream(split).filter(s -> s.contains(this.className)).findFirst().map(s -> {
			final String[] parts = s.split("/");
			return IntStream.range(0, parts.length).filter(i -> parts[i].contains(this.className)).findFirst().orElse(-1);
		}).orElse(-1);

		if (index >= 0 && index < split.length && split.length >= 2) {
			summary = split[index];
			summary += " [";
			summary += Arrays.stream(split[split.length - 2].split("/")).map(String::trim).collect(Collectors.joining(", "));
			summary += "] (";
			summary += split[split.length - 1].trim().toUpperCase();
			summary += ")";
		}

		return summary;
	}

	private String generateUid(final VEvent event) {
		final LocalDate startDate = event.getDateTimeStart()
				.map(dt -> this.toInstant(dt.getDate()))
				.map(instant -> instant.atZone(ZoneId.systemDefault()).toLocalDate())
				.orElseThrow();

		final String input = this.className + "|" + startDate;

		return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private boolean isCancelled(final VEvent event) {
		return event.getSummary()
				.map(Summary::getValue)
				.map(String::trim)
				.map(summary -> summary.toUpperCase().contains("SUSPENDU"))
				.orElse(false);
	}

	private boolean isNotConcerned(final VEvent event) {
		return event.getSummary()
				.map(Summary::getValue)
				.map(String::trim)
				.map(summary -> !summary.toUpperCase().contains(this.className.toUpperCase()))
				.orElse(false);
	}

	public String calToString(final Calendar cal) throws ValidationException, IOException {
		final CalendarOutputter outputter = new CalendarOutputter();
		final StringWriter writer = new StringWriter();
		final PrintWriter pw = new PrintWriter(writer);
		outputter.output(cal, pw);
		return writer.toString();
	}

}