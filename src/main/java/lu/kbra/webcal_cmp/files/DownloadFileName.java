package lu.kbra.webcal_cmp.files;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves download filenames without relying on a server's often misencoded
 * Content-Disposition filename=. URLs are decoded once, not through URI.getPath
 * and then URLDecoder (which would double-decode %25 and turn '+' into space).
 */
public final class DownloadFileName {

	private static final Pattern PARAMETER = Pattern.compile(
			"(?i)(?:^|;)\\s*(filename\\*|filename)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))");
	private static final Pattern EXTENSION = Pattern.compile("(?i).+\\.[a-z0-9]{1,12}$");
	private static final Pattern OPAQUE_NAME = Pattern.compile("(?i)(?:[a-f0-9]{24,}|[a-f0-9-]{36})\\.[a-z0-9]{1,12}");

	private DownloadFileName() {
	}

	public static String resolve(final String disposition, final URI uri, final String contentType, final String prefix) {
		final String raw = disposition == null ? "" : disposition;
		String extended = null;
		String plain = null;
		final Matcher matcher = PARAMETER.matcher(raw);
		while (matcher.find()) {
			final String parameter = matcher.group(1);
			final String value = matcher.group(2) == null ? matcher.group(3).strip() : matcher.group(2).replace("\\\"", "\"");
			if (parameter.endsWith("*")) {
				if (extended == null) {
					extended = decodeExtended(value);
				}
			} else if (plain == null) {
				plain = value;
			}
		}

		// RFC 6266: filename* takes precedence over filename= when valid.
		if (extended != null && !extended.isBlank()) {
			return normalizedWithExtension(extended, contentType);
		}
		final String urlName = urlFileName(uri);
		final String headerName = FileNameNormalizer.normalize(plain);
		// Moodle frequently sends "pÃ©dagogiques.pdf" in filename= while its
		// pluginfile URL contains the correctly UTF-8-encoded original name.
		// Do not prefer opaque/hash URL basenames over a descriptive header.
		if (urlName != null && EXTENSION.matcher(urlName).matches()
				&& !OPAQUE_NAME.matcher(urlName).matches()) {
			return normalizedWithExtension(urlName, contentType);
		}
		if (headerName != null && !headerName.isBlank()) {
			return normalizedWithExtension(headerName, contentType);
		}
		if (urlName != null && !urlName.isBlank()) {
			return normalizedWithExtension(urlName, contentType);
		}
		return prefix + "-" + Integer.toHexString(uri.toString().hashCode()) + extension(contentType);
	}

	static String urlFileName(final URI uri) {
		if (uri == null || uri.getRawPath() == null) {
			return null;
		}
		final String path = uri.getRawPath();
		final String segment = path.substring(path.lastIndexOf('/') + 1);
		if (segment.isBlank()) {
			return null;
		}
		try {
			final String decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
			// Invalid UTF-8 in the URL cannot be repaired reliably; prefer
			// the Content-Disposition name rather than recording replacement chars.
			// Ignore endpoint or numeric segments (often a Moodle resource ID).
			if (decoded.contains("\uFFFD") || decoded.toLowerCase(Locale.ROOT).endsWith(".php") || decoded.matches("\\d+")) {
				return null;
			}
			return FileNameNormalizer.normalize(decoded);
		} catch (final IllegalArgumentException e) {
			return null;
		}
	}

	private static String decodeExtended(final String raw) {
		final int first = raw.indexOf('\'');
		if (first <= 0) {
			return null;
		}
		final int second = raw.indexOf('\'', first + 1);
		if (second < 0) {
			return null;
		}
		try {
			final Charset charset = Charset.forName(raw.substring(0, first));
			final String decoded = URLDecoder.decode(raw.substring(second + 1).replace("+", "%2B"), charset);
			return decoded.contains("\uFFFD") ? null : decoded;
		} catch (final IllegalArgumentException e) {
			return null;
		}
	}

	private static String normalizedWithExtension(final String name, final String contentType) {
		final String normalized = FileNameNormalizer.normalize(name);
		return EXTENSION.matcher(normalized).matches() ? normalized : normalized + extension(contentType);
	}

	private static String extension(final String contentType) {
		final String type = (contentType == null ? "" : contentType).split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		return switch (type) {
		case "image/png" -> ".png";
		case "image/jpeg", "image/pjpeg" -> ".jpg";
		case "image/gif" -> ".gif";
		case "image/webp" -> ".webp";
		case "image/svg+xml" -> ".svg";
		case "image/avif" -> ".avif";
		case "image/bmp" -> ".bmp";
		case "image/tiff" -> ".tif";
		case "application/pdf" -> ".pdf";
		case "text/plain" -> ".txt";
		case "application/zip", "application/x-zip-compressed" -> ".zip";
		default -> ".bin";
		};
	}
}
