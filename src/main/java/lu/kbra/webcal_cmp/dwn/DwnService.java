package lu.kbra.webcal_cmp.dwn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.data.FileSource;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Asset;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Course;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Resource;
import lu.kbra.webcal_cmp.service.PushNotificationService;
import lu.kbra.webcal_cmp.files.FileStoreService;

@Slf4j
@Profile("files-hers")
@Service
public class DwnService {

	private final DwnPortalClient client;
	private final FileStoreService store;
	private final PushNotificationService notifications;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile DownloadReport lastReport;

	public DwnService(final DwnPortalClient client, final FileStoreService store,
			final PushNotificationService notifications) {
		this.client = client;
		this.store = store;
		this.notifications = notifications;
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
			DwnService.log.info("Skipping scheduled scan: one is already running");
		}
	}

	private void scanInternal() {
		final DownloadReport report = new DownloadReport();
		try {
			if (!this.client.configured()) {
				DwnService.log.info("HERS file scraper disabled: portal credentials/index not configured");
				return;
			}
			this.store.awaitAvailable(FileSource.MY_HERS);
			this.client.openPortal();
			final DwnPortalClient.Page courseIndex = this.client.getCoursesPage();
			final List<Course> courses = PortalHtmlParser.courses(courseIndex.html(), courseIndex.uri());
			if (courses.isEmpty()) {
				throw new IOException("No accessible course support links found after SSO login; verify portal index HTML and permissions");
			}
			DwnService.log.info("Scanning {} courses for new HERS course files", courses.size());
			for (final Course course : courses) {
				try {
					this.scanCourse(course, report);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					throw e;
				} catch (final Exception e) {
					report.failure(course.code(), "Course list", e);
					DwnService.log.warn("Course {} failed: {}", course.code(), e.toString());
				}
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			report.failure("Portal", "Scan interrupted", e);
		} catch (final Exception e) {
			report.failure("Portal", "Login/course discovery", e);
			DwnService.log.error("HERS download scan failed", e);
		} finally {
			this.lastReport = report;
			DwnService.log.info("HERS scan: {} courses, {} files checked, {} downloaded, {} failures",
					report.coursesChecked(),
					report.filesChecked(),
					report.downloads().size(),
					report.failures().size());
			if (!report.downloads().isEmpty()) {
				this.notifications.sendFiles(report);
			}
		}
	}

	private void scanCourse(final Course course, final DownloadReport report) throws IOException, InterruptedException {
		final DwnPortalClient.Page page = this.client.getPage(course.url());
		report.courseChecked();
		for (final Asset asset : PortalHtmlParser.assets(page.html(), page.uri())) {
			this.downloadOne(course, asset, report);
		}
		final List<Resource> resources = PortalHtmlParser.resources(page.html(), page.uri());
		for (final Resource resource : resources) {
			try {
				final DwnPortalClient.Page modal = this.client.getPage(resource.url());
				for (final Asset asset : PortalHtmlParser.assets(modal.html(), modal.uri())) {
					this.downloadOne(course, asset, report);
				}
			} catch (final InterruptedException e) {
				throw e;
			} catch (final Exception e) {
				report.failure(course.code(), resource.title(), e);
				DwnService.log.warn("Resource {} in {} failed: {}", resource.title(), course.code(), e.toString());
			}
		}
		if (resources.isEmpty() && PortalHtmlParser.assets(page.html(), page.uri()).isEmpty()) {
			DwnService.log.debug("No downloadable resources for course {}", course.code());
		}
	}

	private void downloadOne(final Course course, final Asset asset, final DownloadReport report) throws InterruptedException {
		report.checked();
		try {
			this.store(course, asset, report);
		} catch (final InterruptedException e) {
			throw e;
		} catch (final Exception e) {
			report.failure(course.code(), asset.filename(), e);
			DwnService.log.warn("File {} in {} failed: {}", asset.filename(), course.code(), e.toString());
			e.printStackTrace();
		}
	}

	void store(final Course course, final Asset asset, final DownloadReport report) throws IOException, InterruptedException {
		final String remoteId = course.code() + ":" + asset.idEs() + ":" + asset.idFile();
		final Path temp = this.client.download(asset.url(), asset.filename());
		try {
			this.store.storeDownload(FileSource.MY_HERS, remoteId, course.code(), "", asset.filename(), temp, report);
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
