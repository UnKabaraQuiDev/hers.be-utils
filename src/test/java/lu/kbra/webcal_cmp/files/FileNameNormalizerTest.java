package lu.kbra.webcal_cmp.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class FileNameNormalizerTest {

	@Test
	void stripsDiacriticsFromNames() {
		assertEquals("Resume - Eleve - Francais.pdf", FileNameNormalizer.normalize("Résumé - Élève - Français.pdf"));
		assertEquals("Strasse_ae_oe_O_L.pdf", FileNameNormalizer.normalize("Straße_æ_œ_Ø_Ł.pdf"));
		assertEquals("Cafe.pdf", FileNameNormalizer.normalize("Cafe\u0301.pdf"));
	}

	@Test
	void repairsMojibakeBeforeRemovingAccents() {
		assertEquals("Electricite.pdf", FileNameNormalizer.normalize("Ã‰lectricitÃ©.pdf"));
		assertEquals("Cafe.pdf", FileNameNormalizer.normalize("CafÃ©.pdf"));
		assertEquals("Cafe.pdf", FileNameNormalizer.normalize("CafÃƒÂ©.pdf"));
		assertEquals("Engagements pedagogiques.pdf", FileNameNormalizer.normalize("Engagements pÃ©dagogiques.pdf"));
		assertEquals("Creme brulee, garcon, dejeuner.pdf", FileNameNormalizer.normalize("CrÃ¨me brÃ»lÃ©e, garÃ§on, dÃ©jeuner.pdf"));
		assertEquals("Ubung, Muller, Fruhstuck.pdf", FileNameNormalizer.normalize("Ãœbung, MÃ¼ller, FrÃ¼hstÃ¼ck.pdf"));
		assertEquals("L'ecole.pdf", FileNameNormalizer.normalize("L’Ã©cole.pdf"));
	}

	@Test
	void safePartAppliesNormalizationToAllFilesystemSegments() {
		assertEquals("Geometrie 1.pdf", FileStoreService.safePart("GÃ©omÃ©trie 1.pdf", "download"));
		assertEquals("Physique - Optique", FileStoreService.safePart("Physique - Optique", "section"));
		assertEquals("download", FileStoreService.safePart("...", "download"));
	}
}
