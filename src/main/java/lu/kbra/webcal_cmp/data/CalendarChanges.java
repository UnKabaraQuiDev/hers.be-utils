package lu.kbra.webcal_cmp.data;

import java.util.Set;

public record CalendarChanges(
		Set<CalendarEvent> added,
		Set<CalendarEvent> removed,
		Set<CalendarEventChange> modified,
		Set<CalendarEventWarning> warning) {

	public boolean hasChanges() {
		return !this.added.isEmpty() || !this.removed.isEmpty() || !this.modified.isEmpty() || !this.warning.isEmpty();
	}

}