package lu.kbra.webcal_cmp.data;

import java.time.Instant;

public record CalendarEventChange(CalendarEvent oldEvent, CalendarEvent newEvent) implements EventData {

	@Override
	public Instant getNewStartTime() {
		return this.newEvent.start();
	}

}