package lu.kbra.webcal_cmp.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.CalendarChanges;
import lu.kbra.webcal_cmp.data.CalendarEvent;
import lu.kbra.webcal_cmp.data.CalendarEventChange;

@Profile("calendar")
@Service
public class CalendarComparator {

	public CalendarChanges compare(final List<CalendarEvent> oldEvents, final List<CalendarEvent> newEvents) {
		final Map<String, CalendarEvent> oldMap = oldEvents.stream().collect(Collectors.toMap(CalendarEvent::uid, Function.identity()));

		final Map<String, CalendarEvent> newMap = newEvents.stream().collect(Collectors.toMap(CalendarEvent::uid, Function.identity()));

		final Set<CalendarEvent> added = new HashSet<>();
		final Set<CalendarEvent> removed = new HashSet<>();
		final Set<CalendarEventChange> modified = new HashSet<>();

		for (final CalendarEvent event : newEvents) {
			final CalendarEvent old = oldMap.get(event.uid());

			if (old == null) {
				added.add(event);
			} else if (this.hasChanged(old, event)) {
				modified.add(new CalendarEventChange(old, event));
			}
		}

		for (final CalendarEvent event : oldEvents) {
			if (!newMap.containsKey(event.uid())) {
				removed.add(event);
			}
		}

		return new CalendarChanges(added, removed, modified, new HashSet<>());
	}

	private boolean hasChanged(final CalendarEvent old, final CalendarEvent current) {
		return !Objects.equals(old.summary(), current.summary()) || !Objects.equals(old.start(), current.start())
				|| !Objects.equals(old.end(), current.end()) || !Objects.equals(old.location(), current.location());
	}

}