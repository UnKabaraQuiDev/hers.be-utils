package lu.kbra.webcal_cmp.moodle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

class MoodleHtmlParserTest {

	private static final URI COURSES_URL = URI.create("https://moodle.hers.be/my/courses.php");
	private static final URI COURSE_URL = URI.create("https://moodle.hers.be/course/view.php?id=217");

	@Test
	void enrolledCoursesAreFoundWithoutDuplicates() {
		final String html = """
				<div data-region="course-content" data-course-id="217">
				  <a href="/course/view.php?id=217">Langue 1 [LANG2974-1]-[LANG2974-A-a]</a>
				  <a href="/course/view.php?id=217">Same course again</a>
				</div>
				<div data-region="course-content" data-course-id="4412">
				  <a href="/course/view.php?id=4412">Utiliser et référencer l'IA à la HERS</a>
				</div>
				""";
		final var found = MoodleHtmlParser.courses(html, COURSES_URL);
		assertEquals(2, found.size());
		assertEquals("LANG2974-1", found.get(0).code());
		assertEquals("course-4412", found.get(1).code());
		assertEquals(COURSE_URL, found.get(0).uri());
	}

	@Test
	void activityParserUsesNestedSectionsAndSkipsSidebarDuplicates() {
		final String html = """
				<div id="course-index"><a href="/mod/url/view.php?id=14345">Index duplicate</a></div>
				<div id="region-main">
				  <ul><li class="section" data-sectionname="18th September">
				    <ul><li class="activity modtype_resource" data-id="14244">
				      <div data-activityname="Syllabus"><a href="/mod/resource/view.php?id=14244">File</a></div>
				    </li>
				    <li class="section" data-sectionname="Additional exercises">
				      <ul><li class="activity modtype_url" data-id="14345">
				        <div data-activityname="Asking and giving the date"><a href="/mod/url/view.php?id=14345">Link</a></div>
				      </li></ul>
				    </li></ul>
				  </li></ul>
				</div>
				""";
		final var found = MoodleHtmlParser.activities(html, COURSE_URL);
		assertEquals(2, found.size());
		assertEquals(MoodleHtmlParser.Type.FILE, found.get(0).type());
		assertEquals("18th September", found.get(0).section());
		assertEquals(MoodleHtmlParser.Type.URL, found.get(1).type());
		assertEquals("18th September/Additional exercises", found.get(1).section());
		assertTrue(found.get(1).uri().toString().contains("id=14345"));
	}
}
