package lu.kbra.webcal_cmp.moodle;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.data.FileSource;
import lu.kbra.webcal_cmp.dwn.DwnPortalClient;
import lu.kbra.webcal_cmp.files.FileStoreService;
import lu.kbra.webcal_cmp.moodle.MoodleHtmlParser.Activity;
import lu.kbra.webcal_cmp.moodle.MoodleHtmlParser.Course;
import lu.kbra.webcal_cmp.service.PushNotificationService;

@Slf4j
@Profile("files-hers")
@Service
public class MoodleService {

	private final DwnPortalClient client;
	private final FileStoreService store;
	private final PushNotificationService notifications;
	private final URI coursesUrl;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile DownloadReport lastReport;

	public MoodleService(final DwnPortalClient client, final FileStoreService store,
			final PushNotificationService notifications,
			@Value("${dwn.moodle.courses-url:}") final String coursesUrl) {
		this.client = client;
		this.store = store;
		this.notifications = notifications;
		this.coursesUrl = coursesUrl.isBlank() ? null : URI.create(coursesUrl);
	}

	public boolean configured() {
		return this.coursesUrl != null && this.client.configured();
	}

	public boolean isRunning() {
		return this.running.get();
	}

	public DownloadReport lastReport() {
		return this.lastReport;
	}

	public boolean triggerAsync() {
		if (!this.running.compareAndSet(false, true)) {
			return false;
		}
		Thread.startVirtualThread(() -> {
			try {
				this.scanInternal();
			} finally {
				this.running.set(false);
			}
		});
		return true;
	}

	public void scheduledScan() {
		if (!this.triggerAsync()) {
			log.info("Skipping Moodle refresh: already running");
		}
	}

	private void scanInternal() {
		final DownloadReport report = new DownloadReport();
		try {
			if (!this.configured()) {
				log.info("Moodle scraper disabled: courses URL or shared HERS credentials not configured");
				return;
			}
			this.store.awaitAvailable(FileSource.MOODLE);
			final DwnPortalClient.Page page = this.client.getMoodlePage(this.coursesUrl);
			final List<Course> courses = MoodleHtmlParser.courses(page.html(), page.uri());
			if (courses.isEmpty()) {
				throw new IOException("No enrolled Moodle courses found on " + this.coursesUrl);
			}
			for (final Course course : courses) {
				try {
					this.scanCourse(course, report);
				} catch (final InterruptedException e) {
					throw e;
				} catch (final Exception e) {
					report.failure(course.code(), "Course page", e);
					log.warn("Moodle course {} failed: {}", course.code(), e.toString());
				}
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			report.failure("Moodle", "Scan interrupted", e);
		} catch (final Exception e) {
			report.failure("Moodle", "Course discovery or OneDrive mount", e);
			log.error("Moodle refresh failed", e);
		} finally {
			this.lastReport = report;
			log.info("Moodle: {} courses, {} files checked, {} changed, {} failures", report.coursesChecked(),
					report.filesChecked(), report.downloads().size(), report.failures().size());
			if (!report.downloads().isEmpty()) {
				this.notifications.sendFiles(report);
			}
		}
	}

	private void scanCourse(final Course course, final DownloadReport report) throws IOException, InterruptedException {
		final DwnPortalClient.Page page = this.client.getMoodlePage(course.uri());
		report.courseChecked();
		for (final Activity activity : MoodleHtmlParser.activities(page.html(), page.uri())) {
			report.checked();
			try {
				final String id = course.id() + ':' + activity.type() + ':' + activity.id();
				if (activity.type() == MoodleHtmlParser.Type.URL) {
					final URI url = this.client.getMoodleExternalUrl(activity.uri());
					this.store.storeUrl(FileSource.MOODLE, id, course.code(), activity.section(), activity.title(),
							url.toString(), report);
				} else {
					final DwnPortalClient.MoodleDownload downloaded = this.client.downloadMoodleResource(activity.uri());
					try {
						this.store.storeDownload(FileSource.MOODLE, id, course.code(), activity.section(), downloaded.filename(),
								downloaded.path(), report);
					} finally {
						Files.deleteIfExists(downloaded.path());
					}
				}
			} catch (final InterruptedException e) {
					throw e;
			} catch (final Exception e) {
				report.failure(course.code(), activity.title(), e);
				log.warn("Moodle activity {} ({}) failed: {}", activity.id(), activity.title(), e.toString());
			}
		}
	}
}
