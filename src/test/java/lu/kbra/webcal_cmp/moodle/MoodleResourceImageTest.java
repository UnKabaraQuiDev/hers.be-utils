package lu.kbra.webcal_cmp.moodle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;

class MoodleResourceImageTest {

	private static final URI PREVIEW = URI.create("https://moodle.hers.be/mod/resource/view.php?id=21058");

	@Test
	void findsEmbeddedImageInMoodleResourcePreview() throws IOException {
		// Representative markup from the provided Spectre electromagnetique page.
		final String html = """
				<div id="region-main">
				  <div role="main">
				    <div class="resourcecontent resourceimg">
				      <img title="Spectre électromagnétique" class="resourceimage"
				           src="/pluginfile.php/56389/mod_resource/content/0/spectre-electromagnetique.png" alt="">
				    </div>
				  </div>
				</div>
				""";
		assertEquals(URI.create("https://moodle.hers.be/pluginfile.php/56389/mod_resource/content/0/spectre-electromagnetique.png"),
				MoodleBrowserClient.resourceFileTarget(html, PREVIEW));
	}

	@Test
	void resolvesRelativeSavedImageSrc() throws IOException {
		final String html = """
				<div id="region-main"><div class="resourcecontent resourceimg">
				<img class="resourceimage" src="resource_files/b1c150732e98b1769be7ebab142827b2b5e42324.png">
				</div></div>
				""";
		assertEquals(URI.create("https://moodle.hers.be/mod/resource/resource_files/b1c150732e98b1769be7ebab142827b2b5e42324.png"),
				MoodleBrowserClient.resourceFileTarget(html, PREVIEW));
	}

	@Test
	void rejectsExternalImagesAndNonHttpDataUris() {
		assertThrows(IOException.class, () -> MoodleBrowserClient.resourceFileTarget(
				"<div id='region-main'><div class='resourcecontent resourceimg'><img src='https://evil.example/image.png'></div></div>",
				PREVIEW));
		assertThrows(IOException.class, () -> MoodleBrowserClient.resourceFileTarget(
				"<div id='region-main'><div class='resourcecontent resourceimg'><img src='data:image/png;base64,AAAA'></div></div>",
				PREVIEW));
	}

	@Test
	void doesNotTreatActivityIconsAsResources() throws IOException {
		assertNull(MoodleBrowserClient.resourceFileTarget(
				"<div id='region-main'><img class='activityicon' src='/theme/icon.svg'></div>", PREVIEW));
	}

	@Test
	void preservesExtensionOrInfersFromImageContentType() {
		assertEquals("diagram.png", MoodleBrowserClient.fileName("",
				URI.create("https://moodle.hers.be/pluginfile.php/1/mod_resource/content/0/diagram.png"), "image/png"));
		assertEquals("image.jpg", MoodleBrowserClient.fileName("",
				URI.create("https://moodle.hers.be/pluginfile.php/1/mod_resource/content/0/image"), "image/jpeg; charset=binary"));
		assertEquals("figure.webp", MoodleBrowserClient.fileName("attachment; filename=\"figure.webp\"",
				URI.create("https://moodle.hers.be/mod/resource/view.php?id=21058"), "image/webp"));
		assertTrue(MoodleBrowserClient.fileName("", PREVIEW, "image/png").endsWith(".png"));
	}
}
