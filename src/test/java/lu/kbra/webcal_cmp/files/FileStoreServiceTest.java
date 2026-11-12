package lu.kbra.webcal_cmp.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import lu.kbra.webcal_cmp.data.FileSource;
import lu.kbra.webcal_cmp.service.PushNotificationService;

import org.junit.jupiter.api.Test;

class FileStoreServiceTest {

	@Test
	void parsesQuotedAndArrowPaths() {
		assertArrayEquals(new String[] { "back/course/sub/", "COURSE_SUB/" },
				FileStoreService.parseMapping("\"back/course/sub/\",\"COURSE_SUB/\""));
		assertArrayEquals(new String[] { "back/course/sub/", "COURSE_SUB/" },
				FileStoreService.parseMapping("back/course/sub/ -> COURSE_SUB/"));
	}

	@Test
	void sanitizesPathsAndNames() {
		assertEquals("__evil.txt", FileStoreService.safePart("../evil.txt", "file"));
		assertEquals("some_name.pdf", FileStoreService.safePart("some/name.pdf", "file"));
	}

	@Test
	void mapLongestPrefixFromBackingDirectory(@TempDir final Path root) throws IOException {
		final Path front = root.resolve("front");
		final Path myhers = root.resolve("back");
		final Path moodle = root.resolve("moodle-back");
		final var store = new FileStoreService(null, null, null, root.toString(), front.toString(),
				myhers.toString(), moodle.toString(), 1, 1);
		Files.writeString(root.resolve("PATHS.csv"), """
				source,destination
				back/,MyHERS/
				back/CHIM9292-4/Chapitre 1/,CHIM_CH1/
				""");
		final Path original = myhers.resolve("CHIM9292-4/Chapitre 1/notes.pdf");
		assertEquals(front.resolve("CHIM_CH1/notes.pdf"), store.mappingDestination(original));
		assertEquals(front.resolve("MyHERS/CHIM9292-4/Chapitre 2/notes.pdf"),
				store.mappingDestination(myhers.resolve("CHIM9292-4/Chapitre 2/notes.pdf")));
	}

	@Test
	void missingMountMarkerCausesAlert(@TempDir final Path root) throws Exception {
		final var notifications = mock(PushNotificationService.class);
		final var store = new FileStoreService(null, null, notifications, root.toString(), root.toString(),
				root.resolve("back").toString(), root.resolve("moodle-back").toString(), 1, 1);
		assertThrows(IOException.class, () -> store.awaitAvailable(FileSource.MOODLE));
		verify(notifications).sendFilesUnavailable(eq("MOODLE"), eq(root.toString()), eq(1), isA(IOException.class));
	}

}
