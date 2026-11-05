package lu.kbra.webcal_cmp.dwn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.pclib.PCUtils;
import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.db.data.FileData;
import lu.kbra.webcal_cmp.db.table.FileTable;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Asset;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Course;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Resource;
import lu.kbra.webcal_cmp.service.PushNotificationService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Profile("files-hers")
@Service
public class DwnService {

	private final DwnPortalClient client;
	private final FileTable table;
	private final PushNotificationService notifications;
	private final Path root;
	private final Path linkRoot;
	private final Path onedriveRoot;
	private final int retries;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile DownloadReport lastReport;

	public DwnService(
			final DwnPortalClient client,
			final FileTable table,
			final PushNotificationService notifications,
			@Value("${dwn.target-dir:./downloads}") final String targetDir,
			@Value("${dwn.link-dir:}") final String linkDir,
			@Value("${dwn.root-dir:}") final String rootDir,
			@Value("${dwn.retries:3}") final int retries) {
		this.client = client;
		this.table = table;
		this.notifications = notifications;
		this.root = Path.of(targetDir).toAbsolutePath().normalize();
		this.linkRoot = Path.of(linkDir).toAbsolutePath().normalize();
		this.onedriveRoot = Path.of(rootDir).toAbsolutePath().normalize();
		if (targetDir.isBlank() || linkDir.isBlank() || rootDir.isBlank() || !this.root.startsWith(this.onedriveRoot)
				|| !this.linkRoot.startsWith(this.onedriveRoot) || this.root.equals(this.linkRoot) || this.linkRoot.startsWith(this.root)) {
			throw new IllegalArgumentException("DWN_ROOT_DIR must contain distinct DWN_TARGET_DIR and DWN_LINK_DIR directories");
		}
		this.retries = Math.max(1, retries);
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
		}
	}

	void store(final Course course, final Asset asset, final DownloadReport report) throws IOException, InterruptedException {
		final String remoteId = course.code() + ":" + asset.idEs() + ":" + asset.idFile();
		final List<FileData> history = new ArrayList<>(this.table.byRemoteId(remoteId));
		final FileData latest = history.stream().max(Comparator.comparingInt(FileData::getVersion)).orElse(null);
		final Path temp = this.client.download(asset.url(), asset.filename());
		try {
			final String remoteHash = DwnService.sha256(temp);
			final String filename = DwnService.safePart(asset.filename(), "file-" + asset.idFile());
			final Path base = this.resolveWithinRoot(course.code(), filename);
			final Path current = latest == null ? null : this.existingRecordPath(latest);

			if (latest != null && Objects.equals(latest.getSha256(), remoteHash)) {
				if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
					return; // Never overwrite a user's local OneDrive edits.
				}
				if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Refusing to replace non-file backing path: " + current);
				}
				final Path restoreTo = current != null && (current.getParent().equals(base.getParent())
						|| latest.getLinkRelativePath() != null && !latest.getLinkRelativePath().isBlank()) ? current
								: this.availablePath(base, history);
				this.writeWithRetries(temp, restoreTo);
				if (!restoreTo.equals(current)) {
					try {
						this.updatePath(latest, restoreTo);
					} catch (final RuntimeException e) {
						try {
							Files.deleteIfExists(restoreTo);
						} catch (final IOException cleanup) {
							e.addSuppressed(cleanup);
						}
						throw e;
					}
				}
				this.ensureLink(latest, report);
				report.success(course.code(), asset.filename(), "restored", this.root.relativize(restoreTo).toString(), remoteHash);
				return;
			}

			final int version = latest == null ? 1 : latest.getVersion() + 1;
			final Path dest = this.availablePath(base, history);
			this.writeWithRetries(temp, dest);
			final String relativePath = this.root.relativize(dest).toString();
			final FileData newRecord = new FileData(remoteId,
					version,
					course.code(),
					asset.filename(),
					relativePath,
					remoteHash,
					Instant.now().toString(),
					null);
			try {
				this.table.loadIfExistsElseInsert(newRecord);
			} catch (final RuntimeException e) {
				try {
					Files.deleteIfExists(dest);
				} catch (final IOException cleanup) {
					e.addSuppressed(cleanup);
				}
				throw e;
			}
			this.ensureLink(newRecord, report);
			report.success(course.code(), asset.filename(), latest == null ? "new" : "updated", relativePath, remoteHash);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private Path availablePath(final Path base, final List<FileData> history) throws IOException {
		final List<FileData> all = new ArrayList<>(history);
		final List<FileData> others = this.table.byCourseCode(this.root.relativize(base).getName(0).toString());
		if (others != null) {
			all.addAll(others);
		}
		final Set<String> reserved = all.stream().map(FileData::getRelativePath).collect(Collectors.toSet());
		if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS) && !reserved.contains(this.root.relativize(base).toString())) {
			return base;
		}
		return DwnService.versionedPath(this.root, base, all);
	}

	private Path existingRecordPath(final FileData entry) throws IOException {
		final Path path = this.root.resolve(entry.getRelativePath()).normalize();
		if (!path.startsWith(this.root) || path.equals(this.root)) {
			throw new IOException("Stored path escapes download root: " + entry.getRelativePath());
		}
		return path;
	}

	private void updatePath(final FileData entry, final Path destination) {
		final String previous = entry.getRelativePath();
		entry.setRelativePath(this.root.relativize(destination).toString());
		try {
			this.table.update(entry);
		} catch (final RuntimeException e) {
			entry.setRelativePath(previous);
			throw e;
		}
	}

	private void ensureLink(final FileData entry, final DownloadReport report) {
		if (entry.getLinkRelativePath() != null && !entry.getLinkRelativePath().isBlank()) {
			return;
		}
		try {
			final Path backed = this.existingRecordPath(entry);
			if (!Files.isRegularFile(backed, LinkOption.NOFOLLOW_LINKS)) {
				return; // Missing historical versions cannot be recreated from the current remote.
			}
			final Path courseFolder = this.linkRoot.resolve(DwnService.safePart(entry.getCourseCode(), "course"));
			Files.createDirectories(courseFolder);
			if (!courseFolder.toRealPath().startsWith(this.onedriveRoot.toRealPath())) {
				throw new IOException("Frontend course folder escapes OneDrive root");
			}
			final Path requested = courseFolder.resolve(backed.getFileName());
			final Path dest = Files.exists(requested, LinkOption.NOFOLLOW_LINKS) && !DwnService.linkPointsTo(requested, backed)
					? DwnService.nextFreeLink(requested)
					: requested;
			if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
				Files.createSymbolicLink(dest, dest.getParent().relativize(backed));
			}
			entry.setLinkRelativePath(this.onedriveRoot.relativize(dest).toString());
			try {
				this.table.update(entry);
			} catch (final RuntimeException e) {
				entry.setLinkRelativePath(null);
				throw e;
			}
		} catch (final IOException | RuntimeException e) {
			report.failure(entry.getCourseCode(), entry.getOriginalName() + " (symlink)", e);
			DwnService.log.warn("Could not create frontend symlink for {}: {}", entry.getRelativePath(), e.toString());
		}
	}

	static boolean linkPointsTo(final Path link, final Path backed) throws IOException {
		return Files.isSymbolicLink(link) && link.getParent().resolve(Files.readSymbolicLink(link)).normalize().equals(backed);
	}

	static Path nextFreeLink(final Path requested) {
		final String name = requested.getFileName().toString();
		final int dot = name.lastIndexOf('.');
		final String stem = dot > 0 ? name.substring(0, dot) : name;
		final String ext = dot > 0 ? name.substring(dot) : "";
		int i = 1;
		Path candidate;
		do {
			candidate = requested.resolveSibling(stem + "-" + i++ + ext);
		} while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS));
		return candidate;
	}

	static Path versionedPath(final Path root, final Path base, final List<FileData> history) throws IOException {
		final Set<String> recorded = history.stream().map(FileData::getRelativePath).collect(Collectors.toSet());
		final String file = base.getFileName().toString();
		final int dot = file.lastIndexOf('.');
		Path candidate = dot > 0 ? Path.of(PCUtils.getIncrement(base.toString())) : base.resolveSibling(file + "-1");
		if (!recorded.contains(root.relativize(candidate).toString()) && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
			return candidate;
		}
		final String stem = dot > 0 ? file.substring(0, dot) : file;
		final String ext = dot > 0 ? file.substring(dot) : "";
		int suffix = 1;
		do {
			candidate = base.resolveSibling(stem + "-" + suffix++ + ext);
		} while (recorded.contains(root.relativize(candidate).toString()) || Files.exists(candidate, LinkOption.NOFOLLOW_LINKS));
		return candidate;
	}

	private Path resolveWithinRoot(final String code, final String filename) throws IOException {
		final Path result = this.root.resolve(DwnService.safePart(code, "course")).resolve(filename).normalize();
		if (!result.startsWith(this.root)) {
			throw new IOException("Unsafe download path");
		}
		return result;
	}

	static String safePart(final String value, final String fallback) {
		if (value == null) {
			return fallback;
		}
		final String cleaned = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").replaceAll("^\\.+", "_").replaceAll("[. ]+$", "").strip();
		return cleaned.isBlank() || cleaned.matches("[._-]+") ? fallback
				: cleaned.length() > 200 ? cleaned.substring(0, 200)
				: cleaned;
	}

	private void writeWithRetries(final Path localTemp, final Path destination) throws IOException, InterruptedException {
		IOException last = null;
		for (int attempt = 1; attempt <= this.retries; attempt++) {
			Path staging = null;
			try {
				Files.createDirectories(destination.getParent());
				staging = Files.createTempFile(destination.getParent(), ".partial-", ".tmp");
				Files.copy(localTemp, staging, StandardCopyOption.REPLACE_EXISTING);
				try {
					Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
				} catch (final AtomicMoveNotSupportedException e) {
					Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
				}
				return;
			} catch (final IOException e) {
				last = e;
				DwnService.log.warn("Saving to {} failed (attempt {}/{}): {}", destination, attempt, this.retries, e.toString());
			} finally {
				if (staging != null) {
					try {
						Files.deleteIfExists(staging);
					} catch (final IOException e) {
						DwnService.log.debug("Failed to remove staging file", e);
					}
				}
			}
			if (attempt < this.retries) {
				TimeUnit.SECONDS.sleep(Math.min(attempt * 3, 15));
			}
		}
		throw last == null ? new IOException("Could not persist download") : last;
	}

	static String sha256(final Path path) throws IOException {
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		try (InputStream in = Files.newInputStream(path)) {
			final byte[] buffer = new byte[64 * 1024];
			for (int n; (n = in.read(buffer)) >= 0;) {
				digest.update(buffer, 0, n);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
