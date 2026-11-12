package lu.kbra.webcal_cmp.scheduled;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.dwn.DwnService;
import lu.kbra.webcal_cmp.moodle.MoodleService;

import lombok.RequiredArgsConstructor;

@Profile("files-hers")
@Service
@RequiredArgsConstructor
public class DwnScheduler {

	private final DwnService files;
	private final MoodleService moodle;

	@Scheduled(cron = "0 0 0 * * *")
	public void midnight() {
		this.files.scheduledScan();
		this.moodle.scheduledScan();
	}

	@Scheduled(cron = "0 0 12 * * MON-FRI")
	public void twelve() {
		this.files.scheduledScan();
		this.moodle.scheduledScan();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		this.moodle.scheduledScan();
	}

}
