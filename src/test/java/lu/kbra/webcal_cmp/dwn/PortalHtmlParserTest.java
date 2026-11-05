package lu.kbra.webcal_cmp.dwn;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PortalHtmlParserTest {

	private String fixture(final String name) throws Exception {
		try (var stream = this.getClass().getResourceAsStream("/dwn/" + name)) {
			Assertions.assertNotNull(stream, "Missing fixture: " + name);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void discoversCourseLinksFromInlineJavascript() throws Exception {
		final var courses = PortalHtmlParser.courses(this.fixture("index.html"), URI.create("https://my.hers.be/FW/index.do"));
		Assertions.assertTrue(courses.size() >= 7);
		final var statique = courses.stream().filter(c -> "MECA0011-1".equals(c.code())).findFirst().orElseThrow();
		Assertions.assertEquals("/portail/MPDIV/infosCoursForEtudiant.do", statique.url().getPath());
		Assertions.assertTrue(statique.url().getQuery().contains("as_tabCur=NOLI"));
	}

	@Test
	void discoversCourseLinksFromAjaxIndexCoursResponse() throws Exception {
		final var courses = PortalHtmlParser.courses(this.fixture("indexCours.html"),
				URI.create("https://my.hers.be/FW/indexCours?nocache=test"));
		Assertions.assertEquals(7, courses.size(), "Only courses with an enabled Supports button should be scanned");
		Assertions.assertTrue(courses.stream().anyMatch(c -> "CHIM9292-4".equals(c.code())));
		Assertions.assertTrue(courses.stream().anyMatch(c -> "MECA0011-1".equals(c.code())));
		Assertions.assertTrue(courses.stream().anyMatch(c -> "TECH0767-5".equals(c.code())));
		Assertions.assertFalse(courses.stream().anyMatch(c -> "DESS0029-1".equals(c.code())));
	}

	@Test
	void discoversModalResourceAndIndividualPdfWithoutDownloadAll() throws Exception {
		final String sample = """
				<a onclick="fwModalWindow_show(1000,1000,'/es/modalViewEs.do?ai_idEs=661783&amp;as_typeEs=NOLI&amp;as_afficheEs=pop','Syllabus')">Syllabus de Statique</a>
				""";
		final var resources = PortalHtmlParser.resources(sample, URI.create("https://my.hers.be/portail/MPDIV/infosCoursForEtudiant.do"));
		Assertions.assertEquals(1, resources.size());
		Assertions.assertEquals("661783", PortalHtmlParser.query(resources.getFirst().url(), "ai_idEs"));
		final var allResources = PortalHtmlParser.resources(this.fixture("dwn.html"),
				URI.create("https://my.hers.be/portail/MPDIV/infosCoursForEtudiant.do"));
		Assertions.assertEquals(7, allResources.size());
		Assertions.assertEquals("661783", PortalHtmlParser.query(allResources.getFirst().url(), "ai_idEs"));
		final var assets = PortalHtmlParser.assets(this.fixture("modal.html"), resources.getFirst().url());
		Assertions.assertEquals(1, assets.size());
		Assertions.assertEquals("910709", assets.getFirst().idFile());
		Assertions.assertEquals("MECA0011-A-a Statique BSI1.pdf", assets.getFirst().filename());
		Assertions.assertEquals("/es/es_download.do", assets.getFirst().url().getPath());
	}

	@Test
	void sanitizesFilenameComponents() {
		final String value = DwnService.safePart("../../notes.pdf", "fallback");
		Assertions.assertFalse(value.contains("/"));
		Assertions.assertFalse(value.contains("\\"));
		Assertions.assertTrue(value.endsWith("notes.pdf"));
		Assertions.assertEquals("fallback", DwnService.safePart("...", "fallback"));
	}
}
