package lu.kbra.webcal_cmp.scheduled;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.service.MenuService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MenuScheduler {

	private final MenuService menus;

	@Scheduled(cron = "0 0 6,18 * * *")
	public void refresh() {
		this.menus.refresh();
	}

	@Scheduled(cron = "0 0 10 * * *")
	public void notifyMenu() {
		this.menus.dailyNotification();
	}

//	@EventListener(ApplicationReadyEvent.class)
//	public void onApplicationReady() {
//		this.menus.refresh();
//		this.menus.dailyNotification();
//	}

}
