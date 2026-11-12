package lu.kbra.webcal_cmp.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.data.FileSource;
import lu.kbra.webcal_cmp.db.data.FileData;
import lu.kbra.webcal_cmp.db.table.FileTable;
import lu.kbra.webcal_cmp.service.PushNotificationService;

import lombok.extern.slf4j.Slf4j;

/** Shared, versioned file storage and PATHS.csv symlink mapping. No network scraping here. */
@Slf4j
@Service
@Profile("files-hers")
public class FileStoreService {

	private record Mapping(Path source, Path destination) {
	}

	private final FileTable myHersTable;
	private final FileTable moodleTable;
	private final PushNotificationService notifications;
	private final Path root;
	private final Path front;
	private final Path myHersBack;
	private final Path moodleBack;
	private final int retries;
	private final int retryDelaySeconds;

	public FileStoreService(
			@Qualifier("myhersFileTable") final FileTable myHersTable,
			@Qualifier("moodleFileTable") final FileTable moodleTable,
			final PushNotificationService notifications,
			@Value("${dwn.root-dir}") final String rootDir,
			@Value("${dwn.link-dir}") final String frontDir,
			@Value("${dwn.myhers.target-dir}") final String myHersDir,
			@Value("${dwn.moodle.target-dir}") final String moodleDir,
			@Value("${dwn.retries:3}") final int retries,
			@Value("${dwn.directory-retry-delay-seconds:5}") final int retryDelaySeconds) {
		if (rootDir.isBlank() || frontDir.isBlank() || myHersDir.isBlank() || moodleDir.isBlank()) {
			throw new IllegalArgumentException("OneDrive root, front and both backing directories must be configured");
		}
		this.root = Path.of(rootDir).toAbsolutePath().normalize();
		this.front = Path.of(frontDir).toAbsolutePath().normalize();
		this.myHersBack = Path.of(myHersDir).toAbsolutePath().normalize();
		this.moodleBack = Path.of(moodleDir).toAbsolutePath().normalize();
		if (!this.front.startsWith(this.root) || !this.myHersBack.startsWith(this.root) || !this.moodleBack.startsWith(this.root)
				|| this.myHersBack.equals(this.moodleBack) || this.front.startsWith(this.myHersBack)
				|| this.front.startsWith(this.moodleBack) || this.myHersBack.startsWith(this.moodleBack)
				|| this.moodleBack.startsWith(this.myHersBack)) {
			throw new IllegalArgumentException("Backing folders and front must be distinct children of the OneDrive root");
		}
		this.myHersTable = myHersTable;
		this.moodleTable = moodleTable;
		this.notifications = notifications;
		this.retries = Math.max(1, retries);
		this.retryDelaySeconds = Math.max(1, retryDelaySeconds);
	}

	public Path root() {
		return this.root;
	}

	public void awaitAvailable(final FileSource source) throws IOException, InterruptedException {
		IOException last = null;
		for (int i = 1; i <= this.retries; i++) {
			try {
				this.verifyAvailable();
				return;
			} catch (final IOException e) {
				last = e;
				FileStoreService.log.warn("OneDrive unavailable ({}, attempt {}/{}): {}", source, i, this.retries, e.getMessage());
				if (i < this.retries) {
					TimeUnit.SECONDS.sleep(this.retryDelaySeconds);
				}
			}
		}
		this.notifications.sendFilesUnavailable(source.name(), this.root.toString(), this.retries, last);
		throw new IOException("OneDrive unavailable after " + this.retries + " checks; scan skipped", last);
	}

	/**
	 * PATHS.csv doubles as an explicit mount sentinel, so an empty accidental bind directory is
	 * rejected.
	 */
	private void verifyAvailable() throws IOException {
		final Path mappings = this.front.resolve("PATHS.csv");
		if (!Files.isDirectory(this.root) || !Files.isReadable(this.root) || !Files.isWritable(this.root)
				|| !Files.isRegularFile(mappings, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(mappings)) {
			throw new IOException("OneDrive root or its PATHS.csv marker is missing/unreadable: " + this.root);
		}
		// Force an actual read, not just an existence check.
		Files.readAllLines(mappings, StandardCharsets.UTF_8);
	}

	public void storeDownload(
			final FileSource source,
			final String remoteId,
			final String courseCode,
			final String subdirectory,
			final String originalName,
			final Path temp,
			final DownloadReport report)
			throws IOException,
				InterruptedException {
		this.verifyAvailable();
		final Path back = this.back(source);
		final String filename = FileStoreService.safePart(originalName, "download");
		Path base = back.resolve(FileStoreService.safePart(courseCode, "course"));
		if (subdirectory != null && !subdirectory.isBlank()) {
			for (final String segment : subdirectory.split("/")) {
				if (!segment.isBlank()) {
					base = base.resolve(FileStoreService.safePart(segment, "section"));
				}
			}
		}
		base = base.resolve(filename).normalize();
		if (!base.startsWith(back)) {
			throw new IOException("Unsafe backing file path");
		}
		final List<? extends FileData> history = this.history(source, remoteId);
		final FileData latest = history.stream().max(Comparator.comparingInt(FileData::getVersion)).orElse(null);
		final String remoteHash = FileStoreService.sha256(temp);
		final Path current = latest == null ? null : this.existingPath(source, latest);
		if (latest != null && Objects.equals(remoteHash, latest.getSha256())) {
			if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
				if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Cannot restore over a non-file: " + current);
				}
				this.writeWithRetries(temp, current);
				report.success(courseCode, originalName, "restored", this.root.relativize(current).toString(), remoteHash);
			}
			this.ensureLink(source, latest, report);
			return; // Preserve local modifications to old OneDrive versions.
		}
		final List<FileData> reserved = new ArrayList<>(history);
		reserved.addAll(this.courseHistory(source, courseCode));
		final Path destination = FileStoreService.availablePath(back, base, reserved);
		this.writeWithRetries(temp, destination);
		final String relativePath = back.relativize(destination).toString();
		final int version = latest == null ? 1 : latest.getVersion() + 1;
		final FileData entry = this.create(source, remoteId, version, courseCode, originalName, relativePath, remoteHash);
		try {
			this.insert(source, entry);
		} catch (final RuntimeException e) {
			try {
				Files.deleteIfExists(destination);
			} catch (final IOException cleanup) {
				e.addSuppressed(cleanup);
			}
			throw e;
		}
		this.ensureLink(source, entry, report);
		report.success(courseCode,
				originalName,
				latest == null ? "new" : "updated",
				this.root.relativize(destination).toString(),
				remoteHash);
	}

	public void storeUrl(
			final FileSource source,
			final String remoteId,
			final String courseCode,
			final String subdirectory,
			final String name,
			final String url,
			final DownloadReport report)
			throws IOException,
				InterruptedException {
		if (!url.startsWith("https://") && !url.startsWith("http://")) {
			throw new IOException("Unsupported external URL scheme");
		}
		final Path temp = Files.createTempFile("moodle-url-", ".url");
		try {
			Files.writeString(temp, "[InternetShortcut]\nURL=" + url.replace("\n", "").replace("\r", "") + "\n", StandardCharsets.UTF_8);
			this.storeDownload(source, remoteId, courseCode, subdirectory, FileStoreService.safePart(name, "link") + ".url", temp, report);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private List<? extends FileData> history(final FileSource source, final String id) {
		return source == FileSource.MY_HERS ? this.myHersTable.byRemoteId(id) : this.moodleTable.byRemoteId(id);
	}

	private List<FileData> courseHistory(final FileSource source, final String code) {
		return new ArrayList<>(source == FileSource.MY_HERS ? this.myHersTable.byCourseCode(code) : this.moodleTable.byCourseCode(code));
	}

	private Path back(final FileSource source) {
		return source == FileSource.MY_HERS ? this.myHersBack : this.moodleBack;
	}

	private FileData create(
			final FileSource source,
			final String id,
			final int version,
			final String code,
			final String originalName,
			final String path,
			final String hash) {
		final String now = Instant.now().toString();
		return source == FileSource.MY_HERS ? new FileData(id, version, code, originalName, path, hash, now, null)
				: new FileData(id, version, code, originalName, path, hash, now, null);
	}

	private void insert(final FileSource source, final FileData entry) {
		(source == FileSource.MY_HERS ? this.myHersTable : this.moodleTable).loadIfExistsElseInsert((FileData) entry);
	}

	private void update(final FileSource source, final FileData entry) {
		(source == FileSource.MY_HERS ? this.myHersTable : this.moodleTable).update((FileData) entry);
	}

	private Path existingPath(final FileSource source, final FileData entry) throws IOException {
		final Path back = this.back(source);
		final Path result = back.resolve(entry.getRelativePath()).normalize();
		if (!result.startsWith(back) || result.equals(back)) {
			throw new IOException("Backing path escapes source root: " + entry.getRelativePath());
		}
		return result;
	}

	private void ensureLink(final FileSource source, final FileData entry, final DownloadReport report) {
		try {
			final Path backed = this.existingPath(source, entry);
			if (!Files.isRegularFile(backed, LinkOption.NOFOLLOW_LINKS)) {
				return;
			}
			final Path destination = this.mappingDestination(source == FileSource.MY_HERS ? this.myHersBack : this.moodleBack, backed);
			if (destination == null) {
				if (entry.getLinkRelativePath() != null && !entry.getLinkRelativePath().isBlank()) {
					final Path old = this.root.resolve(entry.getLinkRelativePath()).normalize();
					if (old.startsWith(this.root) && FileStoreService.linkPointsTo(old, backed)) {
						Files.deleteIfExists(old);
						entry.setLinkRelativePath(null);
						this.update(source, entry);
					}
				}
				return;
			}
			this.safeCreateDirectories(destination.getParent());
			final Path current = entry.getLinkRelativePath() == null ? null : this.root.resolve(entry.getLinkRelativePath()).normalize();
			if (current != null && (!current.startsWith(this.root) || current.equals(this.root))) {
				throw new IOException("Invalid historical symlink path");
			}
			final Path requested = destination;
			final Path link = Files.exists(requested, LinkOption.NOFOLLOW_LINKS) && !FileStoreService.linkPointsTo(requested, backed)
					? FileStoreService.nextFreeLink(requested)
					: requested;
			if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
				Files.createSymbolicLink(link, link.getParent().relativize(backed));
			}
			final String relative = this.root.relativize(link).toString();
			if (!Objects.equals(relative, entry.getLinkRelativePath())) {
				final String previous = entry.getLinkRelativePath();
				entry.setLinkRelativePath(relative);
				try {
					this.update(source, entry);
				} catch (final RuntimeException e) {
					entry.setLinkRelativePath(previous);
					throw e;
				}
				if (current != null && !current.equals(link) && FileStoreService.linkPointsTo(current, backed)) {
					Files.deleteIfExists(current);
				}
			}
		} catch (final IOException | RuntimeException e) {
			report.failure(entry.getCourseCode(), entry.getOriginalName() + " (symlink)", e);
			FileStoreService.log.warn("Could not link {}: {}", entry.getRelativePath(), e.toString());
		}
	}

	Path mappingDestination(final Path sourceRoot, final Path backed) throws IOException {
		final Path source = sourceRoot.relativize(backed);

		final Path candidate = Path.of(source.toString().replace('\\', '/'));

		for (final String line : Files.readAllLines(this.front.resolve("PATHS.csv"), StandardCharsets.UTF_8)) {

			final String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#") || "source,destination".equalsIgnoreCase(trimmed)) {
				continue;
			}

			final String[] pair = FileStoreService.parseMapping(trimmed);
			if (pair == null) {
				FileStoreService.log.warn("Skipping invalid PATHS.csv mapping: {}", trimmed);
				continue;
			}

			try {
				final String patternString = pair[0].replace('\\', '/');

				if (patternString.matches(".*[\\\\.^$|?*+()\\[\\]{}].*")) {
					final Pattern pattern = Pattern.compile(patternString);

					final Matcher matcher = pattern.matcher(candidate.toString());

					if (matcher.matches()) {
						final String replacement = matcher.replaceFirst(pair[1]);
						final Path target = this.front.resolve(replacement).normalize();

						if (!target.startsWith(this.front) || target.equals(this.front)) {
							throw new IOException("PATHS.csv destination outside frontend");
						}

						return target;
					}
				} else {
					final Path prefix = this.parseRelative(patternString);

					if (candidate.startsWith(prefix)) {
						final Path remainder = prefix.relativize(candidate);
						final Path target = this.front.resolve(pair[1]).resolve(remainder).normalize();

						if (!target.startsWith(this.front) || target.equals(this.front)) {
							throw new IOException("PATHS.csv destination outside frontend");
						}

						return target;
					}
				}
			} catch (final PatternSyntaxException e) {
				FileStoreService.log.warn("Invalid regex in PATHS.csv mapping: {}", trimmed, e);
			}
		}

		final Path target = this.front.resolve(source).normalize();

		if (!target.startsWith(this.front) || target.equals(this.front)) {
			throw new IOException("PATHS.csv destination outside frontend");
		}

		return target;
	}

	private Path parseRelative(final String value) throws IOException {
		String cleaned = value.strip();
		if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
			cleaned = cleaned.substring(1, cleaned.length() - 1).replace("\"\"", "\"");
		}
		cleaned = cleaned.replace('\\', '/');
		if (cleaned.isBlank() || cleaned.startsWith("/") || cleaned.matches("(?i)^[a-z]:.*")) {
			throw new IOException("PATHS.csv requires nonempty paths relative to OneDrive root/frontend: " + value);
		}
		final Path path = Path.of(cleaned).normalize();
		if (path.isAbsolute() || path.startsWith("..") || ".".equals(path.toString())) {
			throw new IOException("PATHS.csv path traversal: " + value);
		}
		return path;
	}

	static String[] parseMapping(final String line) {
		if (line.contains(" -> ")) {
			return line.split("\\s+->\\s+", 2);
		}
		// Support quoted CSV fields, including escaped quotes and commas.
		final List<String> columns = new ArrayList<>();
		final StringBuilder part = new StringBuilder();
		boolean quote = false;
		for (int i = 0; i < line.length(); i++) {
			final char c = line.charAt(i);
			if (c == '"') {
				if (quote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					part.append('"');
					i++;
				} else {
					quote = !quote;
				}
			} else if (c == ',' && !quote) {
				columns.add(part.toString().strip());
				part.setLength(0);
			} else {
				part.append(c);
			}
		}
		columns.add(part.toString().strip());
		return quote || columns.size() != 2 ? null : columns.toArray(String[]::new);
	}

	/** Create only real directories beneath the known OneDrive mount (never traverse symlinks). */
	private void safeCreateDirectories(final Path directory) throws IOException {
		final Path target = directory.toAbsolutePath().normalize();
		if (!target.startsWith(this.root) || !Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Directory outside available OneDrive root: " + directory);
		}
		final Path realRoot = this.root.toRealPath();
		Path cursor = this.root;
		for (final Path name : this.root.relativize(target)) {
			cursor = cursor.resolve(name);
			if (Files.isSymbolicLink(cursor)) {
				throw new IOException("Refusing OneDrive directory symlink: " + cursor);
			}
			if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(cursor);
			}
			if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS) || !cursor.toRealPath().startsWith(realRoot)) {
				throw new IOException("Invalid OneDrive directory: " + cursor);
			}
		}
	}

	private void writeWithRetries(final Path temp, final Path destination) throws IOException, InterruptedException {
		IOException last = null;
		for (int i = 1; i <= this.retries; i++) {
			Path staging = null;
			try {
				this.safeCreateDirectories(destination.getParent());
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Refusing to overwrite existing backing file: " + destination);
				}
				staging = Files.createTempFile(destination.getParent(), ".partial-", ".tmp");
				Files.copy(temp, staging, StandardCopyOption.REPLACE_EXISTING);
				try {
					Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
				} catch (final AtomicMoveNotSupportedException e) {
					Files.move(staging, destination);
				}
				return;
			} catch (final IOException e) {
				last = e;
				FileStoreService.log.warn("Cannot save {} (attempt {}/{}): {}", destination, i, this.retries, e.getMessage());
			} finally {
				if (staging != null) {
					Files.deleteIfExists(staging);
				}
			}
			if (i < this.retries) {
				TimeUnit.SECONDS.sleep(Math.min(i * 3, 15));
			}
		}
		throw last == null ? new IOException("Failed to persist download") : last;
	}

	static Path availablePath(final Path root, final Path base, final List<? extends FileData> history) {
		final Set<String> paths = history.stream().map(FileData::getRelativePath).collect(Collectors.toSet());
		final String basename = base.getFileName().toString();
		final int dot = basename.lastIndexOf('.');
		final String stem = dot > 0 ? basename.substring(0, dot) : basename;
		final String ext = dot > 0 ? basename.substring(dot) : "";
		Path current = base;
		int i = 1;
		while (Files.exists(current, LinkOption.NOFOLLOW_LINKS) || paths.contains(root.relativize(current).toString())) {
			current = base.resolveSibling(stem + "-" + i++ + ext);
		}
		return current;
	}

	static boolean linkPointsTo(final Path link, final Path target) throws IOException {
		return Files.isSymbolicLink(link) && link.getParent().resolve(Files.readSymbolicLink(link)).normalize().equals(target);
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

	public static String safePart(final String raw, final String fallback) {
		if (raw == null) {
			return fallback;
		}
		final String name = raw.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").replaceAll("^\\.+", "_").replaceAll("[. ]+$", "").strip();
		return name.isBlank() || name.matches("[._-]+") ? fallback
				: name.length() > 200 ? name.substring(0, 200)
				: name;
	}

	static String sha256(final Path path) throws IOException {
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		try (InputStream in = Files.newInputStream(path)) {
			final byte[] block = new byte[65536];
			for (int n; (n = in.read(block)) >= 0;) {
				digest.update(block, 0, n);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
