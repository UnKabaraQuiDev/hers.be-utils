package lu.kbra.webcal_cmp.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.api.Test;

class DownloadFileNameTest {

	private static final URI MOODLE_PDF = URI.create(
			"https://moodle.hers.be/pluginfile.php/59587/mod_resource/content/0/Engagements%20p%C3%A9dagogiques%20BCH1BSI1BEM2.pdf");

	@Test
	void brokenMoodleHeaderPrefersCleanUtf8Url() {
		assertEquals("Engagements pedagogiques BCH1BSI1BEM2.pdf",
				DownloadFileName.resolve("inline; filename=\"Engagements pÃ©dagogiques BCH1BSI1BEM2.pdf\"",
						MOODLE_PDF, "application/pdf", "moodle-resource"));
	}

	@Test
	void brokenHeaderFallbackIsTransliteratedEvenWithoutUsefulUrl() {
		assertEquals("Engagements pedagogiques BCH1BSI1BEM2.pdf",
				DownloadFileName.resolve("inline; filename=\"Engagements pÃ©dagogiques BCH1BSI1BEM2.pdf\"",
						URI.create("https://moodle.hers.be/mod/resource/view.php?id=42"), "application/pdf", "resource"));
	}

	@Test
	void validExtendedParameterWinsAndPreservesPlus() {
		assertEquals("Resume+A.pdf",
				DownloadFileName.resolve("attachment; filename=\"broken.pdf\"; filename*=UTF-8''R%C3%A9sum%C3%A9%2BA.pdf",
						MOODLE_PDF, "application/pdf", "resource"));
	}

	@Test
	void literalPlusAndEncodedPercentAreDecodedExactlyOnce() {
		assertEquals("A+B 100%25 e.pdf", DownloadFileName.resolve("",
				URI.create("https://moodle.hers.be/pluginfile.php/1/mod_resource/content/0/A+B%20100%2525%20%C3%A9.pdf"),
				"application/pdf", "resource"));
	}

	@Test
	void acceptsHashImageWithDescriptiveHeaderAndMIMEFallback() {
		assertEquals("Schema optique.png", DownloadFileName.resolve("inline; filename=\"SchÃ©ma optique.png\"",
				URI.create("https://moodle.hers.be/pluginfile.php/1/mod_resource/content/0/b1c150732e98b1769be7ebab142827b2b5e42324.png"),
				"image/png", "resource"));
		assertEquals("image.jpg", DownloadFileName.resolve("",
				URI.create("https://moodle.hers.be/pluginfile.php/1/mod_resource/content/0/image"), "image/jpeg", "resource"));
	}

	@Test
	void extendedHeaderWithInvalidCharsetFallsBackToUrl() {
		assertEquals("Engagements pedagogiques BCH1BSI1BEM2.pdf",
				DownloadFileName.resolve("attachment; filename*=not-a-charset''R%C3%A9sum%C3%A9.pdf",
						MOODLE_PDF, "application/pdf", "resource"));
	}
}
