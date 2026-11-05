package lu.kbra.webcal_cmp.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.MealType;
import lu.kbra.webcal_cmp.db.data.MenuData;
import lu.kbra.webcal_cmp.db.table.MenuTable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Profile("menu")
@Service
@RequiredArgsConstructor
public class MenuService {

	private final ZoneId zone;
	private final MenuTable menus;
	private final FacebookMenuSource source;
	private final PushNotificationService notifications;

	public synchronized void refresh() {
		try {
			this.ensureCached(MenuService.targetMonday(ZonedDateTime.now(this.zone)));
		} catch (final Exception e) {
			MenuService.log.error("Menu refresh failed; existing cache is unchanged", e);
		}
	}

	public synchronized void dailyNotification() {
		final ZonedDateTime now = ZonedDateTime.now(this.zone);
		final LocalDate today = now.toLocalDate();
		try {
			if (today.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) {
				this.ensureCached(MenuService.targetMonday(now));
				return;
			}
			// Today's data is enough for a notification even if a previous
			// partial write left another date of the week uncached.
			if (!this.menus.exists(new MenuData(today, MealType.LUNCH)) && !this.menus.exists(new MenuData(today, MealType.DINNER))) {
				this.ensureCached(MenuService.targetMonday(now));
			}
			final List<MenuData> menu = this.menus.byDate(today);
			this.notifications.sendMenu(today, menu);
		} catch (final Exception e) {
			MenuService.log.error("Menu notification check failed for {}", today, e);
		}
	}

	static LocalDate targetMonday(final ZonedDateTime now) {
		final LocalDate monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		final DayOfWeek day = now.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || day == DayOfWeek.FRIDAY && now.getHour() >= 18) {
			return monday.plusWeeks(1);
		}
		return monday;
	}

	private void ensureCached(final LocalDate monday) throws Exception {
		if (this.weekCached(monday)) {
			return;
		}
		final Optional<List<MenuData>> extracted = this.source.findWeek(monday);
		if (extracted.isEmpty()) {
			MenuService.log.info("No verified menu found yet for week {}", monday);
			return;
		}
		for (final MenuData day : extracted.get()) {
			this.menus.loadIfExistsElseInsert(day);
		}
		MenuService.log.info("Cached menu week {}", monday);
	}

	private boolean weekCached(final LocalDate monday) {
		for (int day = 0; day < 5; day++) {
			if (!this.menus.exists(new MenuData(monday.plusDays(day)))) {
				return false;
			}
		}
		return true;
	}
}
