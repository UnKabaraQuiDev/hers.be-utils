package lu.kbra.webcal_cmp.scheduled;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.service.MenuService;

import lombok.RequiredArgsConstructor;

@Profile("menu")
@Service
@RequiredArgsConstructor
public class MenuScheduler {

	private final MenuService menus;

	@Scheduled(cron = "1 0 6,18 * * *")
	public void refresh() {
		this.menus.refresh();
	}

	@Scheduled(cron = "0 0 8,10 * * *")
	public void notifyMenuSameDay() {
		this.menus.dailyNotification(false);
	}
	
	@Scheduled(cron = "0 0 18 * * *")
	public void notifyMenuNextDay() {
		this.menus.dailyNotification(true);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		this.menus.refresh();
	}

}
