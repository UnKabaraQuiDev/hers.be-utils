package lu.kbra.webcal_cmp.dwn;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class PortalHtmlParser {

	private static final Pattern COURSE_URL = Pattern
			.compile("(?i)(?:https?://[^'\"\\s,}]+)?/?portail/MPDIV/infosCoursForEtudiant\\.do\\?[^'\"\\s,}]+");
	private static final Pattern RESOURCE_URL = Pattern.compile("(?i)(?:https?://[^'\"\\s,}]+)?/?es/modalViewEs\\.do\\?[^'\"\\s,}]+");

	private PortalHtmlParser() {
	}

	public record Course(String code, String title, URI url) {
	}

	public record Resource(String title, URI url) {
	}

	public record Asset(String idEs, String idFile, String filename, URI url) {
	}

	public static List<Course> courses(final String html, final URI pageUri) {
		final Document document = Jsoup.parse(html, pageUri.toString());
		final Map<String, Course> found = new LinkedHashMap<>();
		for (final Element e : document.select("[onclick], a[href]")) {
			final String text = PortalHtmlParser.unescapeJs(e.hasAttr("onclick") ? e.attr("onclick") : e.attr("href"));
			final Matcher match = PortalHtmlParser.COURSE_URL.matcher(text);
			if (!match.find()) {
				continue;
			}
			final URI url = PortalHtmlParser.resolve(pageUri, match.group());
			final String code = PortalHtmlParser.query(url, "as_codCoursDecl");
			if (code == null || code.isBlank()) {
				continue;
			}
			final Element row = e.closest(".fwAccueilCoursRow");
			final String title = row == null ? code : row.select(".fwAccueilCoursLib").text();
			found.putIfAbsent(code, new Course(code, title.isBlank() ? code : title, url));
		}
		return List.copyOf(found.values());
	}

	public static List<Resource> resources(final String html, final URI pageUri) {
		final Document document = Jsoup.parse(html, pageUri.toString());
		final Map<String, Resource> found = new LinkedHashMap<>();
		for (final Element e : document.select("[onclick], a[href], iframe[src]")) {
			final String js = PortalHtmlParser.unescapeJs(e.hasAttr("onclick") ? e.attr("onclick")
					: e.hasAttr("href") ? e.attr("href")
					: e.attr("src"));
			final Matcher match = PortalHtmlParser.RESOURCE_URL.matcher(js);
			if (!match.find()) {
				continue;
			}
			final URI url = PortalHtmlParser.resolve(pageUri, match.group());
			final String id = PortalHtmlParser.query(url, "ai_idEs");
			if (id != null && id.matches("\\d+")) {
				final String label = e.text().isBlank() ? "Resource " + id : e.text();
				found.putIfAbsent(id, new Resource(label, url));
			}
		}
		return List.copyOf(found.values());
	}

	public static List<Asset> assets(final String html, final URI pageUri) {
		final Document document = Jsoup.parse(html, pageUri.toString());
		final Map<String, Asset> found = new LinkedHashMap<>();
		for (final Element e : document.select("a[href*='es_download.do']")) {
			final URI url = PortalHtmlParser.resolve(pageUri, e.attr("href"));
			if (!url.getPath().endsWith("/es_download.do")) {
				continue;
			}
			final String es = PortalHtmlParser.query(url, "ai_idEs");
			final String file = PortalHtmlParser.query(url, "ai_idFile");
			if (es == null || file == null || !es.matches("\\d+") || !file.matches("\\d+")) {
				continue;
			}
			final Element row = e.closest("tr");
			final String name = row != null && row.select("td").size() >= 2 ? row.select("td").get(1).text().strip() : "file-" + file;
			found.putIfAbsent(es + ":" + file, new Asset(es, file, name, url));
		}
		return List.copyOf(found.values());
	}

	static URI resolve(final URI base, final String raw) {
		// JS literals and HTML entities can both appear in onclick values.
		String cleaned = PortalHtmlParser.unescapeJs(raw).replace("&amp;", "&").strip();
		if (cleaned.startsWith("portail/") || cleaned.startsWith("es/")) {
			cleaned = "/" + cleaned;
		}
		return base.resolve(cleaned.replace(" ", "%20"));
	}

	static String unescapeJs(final String js) {
		return js.replace("\\/", "/").replace("\\'", "'").replace("&amp;", "&");
	}

	static String query(final URI uri, final String key) {
		final String q = uri.getRawQuery();
		if (q == null) {
			return null;
		}
		for (final String part : q.split("&")) {
			final int equals = part.indexOf('=');
			final String name = equals < 0 ? part : part.substring(0, equals);
			if (URLDecoder.decode(name, StandardCharsets.UTF_8).equals(key)) {
				return URLDecoder.decode(equals < 0 ? "" : part.substring(equals + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}
}
