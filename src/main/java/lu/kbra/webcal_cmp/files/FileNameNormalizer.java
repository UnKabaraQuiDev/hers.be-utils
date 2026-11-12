package lu.kbra.webcal_cmp.files;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Normalizes display/file names, never URLs, cookies or remote identifiers. */
public final class FileNameNormalizer {

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
	// Last-resort map for Latin characters when a mixed or partially mangled
	// header cannot be decoded as a complete ISO-8859-1/Windows-1252 byte run.
	private static final Map<String, String> BROKEN_LATIN = brokenLatinFallbacks();
	// Typical signs of UTF-8 bytes mistakenly decoded as Latin-1 or Windows-1252.
	private static final Pattern MOJIBAKE = Pattern.compile("(?:Ã[\\u0080-\\u00BF]|Â[\\u0080-\\u00BF]|â[\\u0080-\\u00BF€™œ“”–—¦€])");
	private static final Pattern MOJIBAKE_RUN = Pattern.compile("(?:[ÃÂâ][\\u0080-\\u00FF€™œ“”–—¦€ƒ])+");

	private FileNameNormalizer() {
	}

	public static String normalize(final String input) {
		if (input == null) {
			return null;
		}
		String repaired = input;
		for (int i = 0; i < 3 && MOJIBAKE.matcher(repaired).find(); i++) {
			final String decoded = tryRepair(repaired);
			if (decoded == null || decoded.equals(repaired)) {
				final Matcher matcher = MOJIBAKE_RUN.matcher(repaired);
				final StringBuffer result = new StringBuffer();
				boolean changed = false;
				while (matcher.find()) {
					final String fixed = tryRepair(matcher.group());
					if (fixed != null && !fixed.equals(matcher.group())) {
						matcher.appendReplacement(result, Matcher.quoteReplacement(fixed));
						changed = true;
					}
				}
				if (!changed) {
					break;
				}
				matcher.appendTail(result);
				repaired = result.toString();
			} else {
				repaired = decoded;
			}
		}
		// Some server strings are a mix of already-correct text and broken bytes.
		// Replace any remaining recognizable UTF-8-as-Latin-1 combinations.
		for (int i = 0; i < 3; i++) {
			final String fixed = fallbackRepair(repaired);
			if (fixed.equals(repaired)) {
				break;
			}
			repaired = fixed;
		}
		final String expanded = repaired.replace("\uFFFD", "_").replace("\uFEFF", "").replace("\u200B", "")
				.replace("’", "'").replace("‘", "'")
				.replace("“", "\"").replace("”", "\"")
				.replace("–", "-").replace("—", "-")
				.replace("ß", "ss").replace("ẞ", "SS")
				.replace("Æ", "AE").replace("æ", "ae")
				.replace("Œ", "OE").replace("œ", "oe")
				.replace("Ø", "O").replace("ø", "o")
				.replace("Ł", "L").replace("ł", "l")
				.replace("Đ", "D").replace("đ", "d")
				.replace("Ð", "D").replace("ð", "d")
				.replace("Þ", "Th").replace("þ", "th")
				.replace("ı", "i");
		return MARKS.matcher(Normalizer.normalize(expanded, Normalizer.Form.NFKD)).replaceAll("");
	}


	private static String fallbackRepair(final String input) {
		String result = input;
		for (final Map.Entry<String, String> entry : BROKEN_LATIN.entrySet()) {
			result = result.replace(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private static Map<String, String> brokenLatinFallbacks() {
		final Map<String, String> candidates = new LinkedHashMap<>();
		for (int codePoint = 0x00C0; codePoint <= 0x024F; codePoint++) {
			final String letter = new String(Character.toChars(codePoint));
			final String expanded = letter.replace("ß", "ss").replace("ẞ", "SS")
					.replace("Æ", "AE").replace("æ", "ae").replace("Œ", "OE").replace("œ", "oe")
					.replace("Ø", "O").replace("ø", "o").replace("Ł", "L").replace("ł", "l")
					.replace("Đ", "D").replace("đ", "d").replace("Ð", "D").replace("ð", "d")
					.replace("Þ", "Th").replace("þ", "th").replace("ı", "i");
			final String ascii = MARKS.matcher(Normalizer.normalize(expanded, Normalizer.Form.NFKD)).replaceAll("");
			if (!ascii.matches("[A-Za-z]+")) {
				continue;
			}
			for (final Charset charset : new Charset[] { WINDOWS_1252, StandardCharsets.ISO_8859_1 }) {
				final String broken = new String(letter.getBytes(StandardCharsets.UTF_8), charset);
				if (!broken.contains("\uFFFD")) {
					candidates.putIfAbsent(broken, ascii);
				}
			}
		}
		// Longest sequences first to avoid consuming part of a longer pattern.
		final List<Map.Entry<String, String>> sorted = new ArrayList<>(candidates.entrySet());
		sorted.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
		final Map<String, String> result = new LinkedHashMap<>();
		for (final Map.Entry<String, String> entry : sorted) {
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private static String tryRepair(final String value) {
		for (final Charset charset : new Charset[] { WINDOWS_1252, StandardCharsets.ISO_8859_1 }) {
			try {
				final ByteBuffer bytes = charset.newEncoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.encode(CharBuffer.wrap(value));
				final String candidate = StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(bytes).toString();
				if (!candidate.contains("\uFFFD")) {
					return candidate;
				}
			} catch (final CharacterCodingException ignored) {
				// Mixed or already-correct Unicode: do not corrupt the original.
			}
		}
		return null;
	}
}
