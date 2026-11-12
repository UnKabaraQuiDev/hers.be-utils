package lu.kbra.webcal_cmp.moodle;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import lu.kbra.webcal_cmp.files.FileStoreService;
import lu.kbra.webcal_cmp.files.FileNameNormalizer;

/** Parses server-rendered Moodle course cards and activity lists, not navigation/sidebar duplicates. */
public final class MoodleHtmlParser {

	private static final Pattern COURSE_CODE = Pattern.compile("\\[([A-Za-z0-9]+-[0-9]+)\\]");
	private static final Pattern ID = Pattern.compile("(?:^|[?&])id=([0-9]+)(?:&|$)");

	public record Course(String id, String code, String title, URI uri) {
	}

	public enum Type {
		FILE, URL
	}

	public record Activity(String id, String title, String section, Type type, URI uri) {
	}

	private MoodleHtmlParser() {
	}

	public static List<Course> courses(final String html, final URI base) {
		final Document document = Jsoup.parse(html, base.toString());
		final Map<String, Course> found = new LinkedHashMap<>();
		for (final Element card : document.select("[data-region=course-content][data-course-id]")) {
			final String id = card.attr("data-course-id");
			if (!id.matches("[0-9]+")) {
				continue;
			}
			final Element titleLink = card.selectFirst("a[href*='/course/view.php?id=']");
			if (titleLink == null) {
				continue;
			}
			String title = titleLink.text().strip();
			if (title.isBlank()) {
					final Element explicitTitle = card.selectFirst(".coursename, .course-card-title, .multiline");
					title = explicitTitle == null ? "Course " + id : explicitTitle.text().strip();
			}
			final Matcher code = COURSE_CODE.matcher(title);
			found.putIfAbsent(id, new Course(id, code.find() ? code.group(1) : "course-" + id,
					FileNameNormalizer.normalize(title), base.resolve(titleLink.attr("href"))));
		}
		// Some themes render enrolled course lists without cards.
		if (found.isEmpty()) {
			for (final Element anchor : document.select("#region-main a[href*='/course/view.php?id=']")) {
				final String id = getId(anchor.attr("href"));
				if (id == null || anchor.text().isBlank()) {
					continue;
				}
				final String title = anchor.text().strip();
				final Matcher code = COURSE_CODE.matcher(title);
				found.putIfAbsent(id, new Course(id, code.find() ? code.group(1) : "course-" + id,
						FileNameNormalizer.normalize(title), base.resolve(anchor.attr("href"))));
			}
		}
		return List.copyOf(found.values());
	}

	public static List<Activity> activities(final String html, final URI base) {
		final Document document = Jsoup.parse(html, base.toString());
		final Map<String, Activity> found = new LinkedHashMap<>();
		for (final Element activity : document.select("li.activity.modtype_resource, li.activity.modtype_url")) {
			final Element section = activity.closest("li.section");
			if (section == null || section.closest("#region-main") == null) {
				continue; // Exclude the course-index drawer and translated navigation links.
			}
			final Type type = activity.hasClass("modtype_resource") ? Type.FILE : Type.URL;
			final Element link = activity.selectFirst(type == Type.FILE
					? "a[href*='/mod/resource/view.php?id=']" : "a[href*='/mod/url/view.php?id=']");
			if (link == null) {
				continue;
			}
			final String id = activity.attr("data-id");
			if (!id.matches("[0-9]+")) {
				continue;
			}
			final Element card = activity.selectFirst("[data-activityname]");
			final String title = card == null || card.attr("data-activityname").isBlank()
					? link.text().strip() : card.attr("data-activityname").strip();
			final List<String> sections = new ArrayList<>();
			for (Element current = section; current != null; current = current.parent() == null ? null : current.parent().closest("li.section")) {
				final String name = current.attr("data-sectionname").strip();
				if (!name.isBlank() && !name.equalsIgnoreCase("Généralités")) {
					sections.add(0, FileStoreService.safePart(name, "section"));
				}
			}
			found.putIfAbsent(type.name() + ':' + id, new Activity(id, FileNameNormalizer.normalize(title),
					String.join("/", sections), type, base.resolve(link.attr("href"))));
		}
		return List.copyOf(found.values());
	}

	private static String getId(final String url) {
		final Matcher match = ID.matcher(url);
		return match.find() ? match.group(1) : null;
	}
}
