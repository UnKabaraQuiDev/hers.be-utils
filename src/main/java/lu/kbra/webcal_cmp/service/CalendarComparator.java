package lu.kbra.webcal_cmp.service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.CalendarChanges;
import lu.kbra.webcal_cmp.data.CalendarEvent;
import lu.kbra.webcal_cmp.data.CalendarEventChange;

@Profile("calendar")
@Service
public class CalendarComparator {

	private static final ZoneId ZONE = ZoneId.of("Europe/Luxembourg");

	public CalendarChanges compare(final List<CalendarEvent> oldEvents, final List<CalendarEvent> newEvents) {
		final List<CalendarEvent> old = new ArrayList<>(oldEvents);
		final List<CalendarEvent> current = new ArrayList<>(newEvents);
		final var modified = new HashSet<CalendarEventChange>();

		// Match unchanged occurrences first, including feeds that regenerate UIDs.
		CalendarComparator.removeIdentical(old, current, true);
		CalendarComparator.removeIdentical(old, current, false);

		// Prefer a stable UID. Never arbitrarily pair multiple occurrences of a recurring UID.
		CalendarComparator.matchUnique(old, current, (a, b) -> a.uid() != null && a.uid().equals(b.uid()), modified);

		// For regenerated UIDs, match a unique lesson on the same local day.
		// A repeated course with several possible matches remains an add/remove.
		CalendarComparator.matchUnique(old,
				current,
				(a, b) -> a.summary() != null && !a.summary().isBlank() && a.summary().equals(b.summary())
						&& a.start()
								.atZone(CalendarComparator.ZONE)
								.toLocalDate()
								.equals(b.start().atZone(CalendarComparator.ZONE).toLocalDate()),
				modified);

		return new CalendarChanges(new HashSet<>(current), new HashSet<>(old), modified, new HashSet<>());
	}

	private static void removeIdentical(final List<CalendarEvent> old, final List<CalendarEvent> current, final boolean requireUid) {
		for (int i = 0; i < current.size();) {
			final CalendarEvent event = current.get(i);
			int match = -1;
			for (int j = 0; j < old.size(); j++) {
				if ((!requireUid || Objects.equals(old.get(j).uid(), event.uid())) && CalendarComparator.sameContent(old.get(j), event)) {
					match = j;
					break;
				}
			}
			if (match < 0) {
				i++;
			} else {
				old.remove(match);
				current.remove(i);
			}
		}
	}

	private static void matchUnique(
			final List<CalendarEvent> old,
			final List<CalendarEvent> current,
			final BiPredicate<CalendarEvent, CalendarEvent> matches,
			final HashSet<CalendarEventChange> modified) {
		final List<CalendarEventChange> pairs = new ArrayList<>();
		for (final CalendarEvent before : old) {
			final List<CalendarEvent> candidates = current.stream().filter(after -> matches.test(before, after)).toList();
			if (candidates.size() != 1) {
				continue;
			}
			final CalendarEvent after = candidates.getFirst();
			if (old.stream().filter(other -> matches.test(other, after)).count() == 1) {
				pairs.add(new CalendarEventChange(before, after));
			}
		}
		for (final CalendarEventChange pair : pairs) {
			old.remove(pair.oldEvent());
			current.remove(pair.newEvent());
			if (!CalendarComparator.sameContent(pair.oldEvent(), pair.newEvent())) {
				modified.add(pair);
			}
		}
	}

	private static boolean sameContent(final CalendarEvent a, final CalendarEvent b) {
		return Objects.equals(a.summary(), b.summary()) && Objects.equals(a.start(), b.start()) && Objects.equals(a.end(), b.end())
				&& Objects.equals(a.location(), b.location());
	}
}
