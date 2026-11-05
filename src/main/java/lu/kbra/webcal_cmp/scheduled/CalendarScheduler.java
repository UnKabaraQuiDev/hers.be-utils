package lu.kbra.webcal_cmp.scheduled;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.service.CheckCalendarService;

import lombok.RequiredArgsConstructor;

@Profile("calendar")
@Service
@RequiredArgsConstructor
public class CalendarScheduler {

	private final CheckCalendarService checkCalendarService;

	@Scheduled(cron = "0 0/5 7-17 * * *")
	public void checkCalendarDuringDay() {
		this.checkCalendarService.checkCalendar(true, false);
	}

	@Scheduled(cron = "0 5 18 * * *")
	public void nextDay() {
		this.checkCalendarService.checkCalendar(true, true);
	}

	@Scheduled(cron = "0 5 0 * * *")
	public void midnight() {
		this.checkCalendarService.checkCalendar(true, false);
	}

//	@EventListener(ApplicationReadyEvent.class)
//	public void onApplicationReady() {
//		this.checkCalendarService.checkCalendar(true, false);
//		this.checkCalendarService.checkCalendar(true, true);
//	}

}