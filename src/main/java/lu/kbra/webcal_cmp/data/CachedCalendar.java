package lu.kbra.webcal_cmp.data;

import java.time.LocalDate;
import java.util.List;

public record CachedCalendar(LocalDate date, List<CalendarEvent> events) {

	public CachedCalendar {
		events = List.copyOf(events);
	}
}
