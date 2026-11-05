package lu.kbra.webcal_cmp.scheduled;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.dwn.DwnService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DwnScheduler {

	private final DwnService files;

	@Scheduled(cron = "0 0 0 * * *")
	public void midnight() {
		this.files.scheduledScan();
	}

	@Scheduled(cron = "0 0 12 * * MON-FRI")
	public void twelve() {
		this.files.scheduledScan();
	}

//	@EventListener(ApplicationReadyEvent.class)
//	public void onApplicationReady() {
//		this.files.scheduledScan();
//	}

}
