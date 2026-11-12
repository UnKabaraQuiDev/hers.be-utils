package lu.kbra.webcal_cmp.moodle;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.data.FileSource;
import lu.kbra.webcal_cmp.files.FileStoreService;
import lu.kbra.webcal_cmp.moodle.MoodleBrowserClient.MoodleDownload;
import lu.kbra.webcal_cmp.moodle.MoodleHtmlParser.Activity;
import lu.kbra.webcal_cmp.moodle.MoodleHtmlParser.Course;
import lu.kbra.webcal_cmp.service.PushNotificationService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Profile("files-hers")
@Service
public class MoodleService {

	private final MoodleBrowserClient client;
	private final FileStoreService store;
	private final PushNotificationService notifications;
	private final URI coursesUrl;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile DownloadReport lastReport;

	public MoodleService(
			final MoodleBrowserClient client,
			final FileStoreService store,
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
			MoodleService.log.info("Skipping Moodle refresh: already running");
		}
	}

	private void scanInternal() {
		final DownloadReport report = new DownloadReport();
		try {
			if (!this.configured()) {
				MoodleService.log.info("Moodle scraper disabled: courses URL or shared HERS credentials not configured");
				return;
			}
			this.store.awaitAvailable(FileSource.MOODLE);
			try (MoodleBrowserClient.Session browser = this.client.openSession()) {
				final List<Course> courses = browser.enrolledCourses(this.coursesUrl);
				for (final Course course : courses) {
					try {
						this.scanCourse(browser, course, report);
					} catch (final InterruptedException e) {
						throw e;
					} catch (final Exception e) {
						report.failure(course.code(), "Course page", e);
						MoodleService.log.warn("Moodle course {} failed: {}", course.code(), e.toString());
					}
				}
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			report.failure("Moodle", "Scan interrupted", e);
		} catch (final Exception e) {
			report.failure("Moodle", "Course discovery or OneDrive mount", e);
			MoodleService.log.error("Moodle refresh failed", e);
		} finally {
			this.lastReport = report;
			MoodleService.log.info("Moodle: {} courses, {} files checked, {} changed, {} failures",
					report.coursesChecked(),
					report.filesChecked(),
					report.downloads().size(),
					report.failures().size());
			if (!report.downloads().isEmpty()) {
				this.notifications.sendFiles(report);
			}
		}
	}

	private void scanCourse(final MoodleBrowserClient.Session browser, final Course course, final DownloadReport report)
			throws IOException,
				InterruptedException {
		final String html = browser.courseHtml(course.uri());
		report.courseChecked();
		for (final Activity activity : MoodleHtmlParser.activities(html, course.uri())) {
			report.checked();
			try {
				final String id = course.id() + ':' + activity.type() + ':' + activity.id();
				if (activity.type() == MoodleHtmlParser.Type.URL) {
					final URI url = browser.externalUrl(activity.uri());
					this.store.storeUrl(FileSource.MOODLE, id, course.code(), activity.section(), activity.title(), url.toString(), report);
				} else {
					final MoodleDownload downloaded = browser.downloadResource(activity.uri());
					try {
						this.store.storeDownload(FileSource.MOODLE,
								id,
								course.code(),
								activity.section(),
								downloaded.filename(),
								downloaded.path(),
								report);
					} finally {
						Files.deleteIfExists(downloaded.path());
					}
				}
			} catch (final InterruptedException e) {
				throw e;
			} catch (final Exception e) {
				report.failure(course.code(), activity.title(), e);
				MoodleService.log.warn("Moodle activity {} ({}) failed: {}", activity.id(), activity.title(), e.toString());
			}
		}
	}
}
