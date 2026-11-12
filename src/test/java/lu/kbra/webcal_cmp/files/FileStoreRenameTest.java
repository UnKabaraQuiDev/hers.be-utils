package lu.kbra.webcal_cmp.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.data.FileSource;
import lu.kbra.webcal_cmp.db.data.FileData;
import lu.kbra.webcal_cmp.db.table.FileTable;

class FileStoreRenameTest {

	@TempDir
	Path temp;

	@Test
	void rescanRenamesUnchangedFileAndUpdatesDatabaseAndSymlink() throws Exception {
		final Path root = this.temp.resolve("onedrive");
		final Path front = root.resolve("front");
		final Path back = root.resolve("moodle-back");
		Files.createDirectories(front);
		Files.writeString(front.resolve("PATHS.csv"), "", StandardCharsets.UTF_8);
		final Path old = back.resolve("Physique/Engagements pÃ©dagogiques.pdf");
		Files.createDirectories(old.getParent());
		Files.writeString(old, "same content", StandardCharsets.UTF_8);
		final Path oldLink = front.resolve("Physique/Engagements pÃ©dagogiques.pdf");
		Files.createDirectories(oldLink.getParent());
		Files.createSymbolicLink(oldLink, oldLink.getParent().relativize(old));

		final FileData entry = new FileData("course:FILE:1", 1, "Physique", "Engagements pÃ©dagogiques.pdf",
				"Physique/Engagements pÃ©dagogiques.pdf", FileStoreService.sha256(old),
				"2026-11-12T12:00:00Z", root.relativize(oldLink).toString());
		final FileTable moodle = mock(FileTable.class);
		when(moodle.byRemoteId("course:FILE:1")).thenReturn(List.of(entry));
		when(moodle.byCourseCode(anyString())).thenReturn(List.of(entry));
		final FileStoreService service = new FileStoreService(mock(FileTable.class), moodle, null,
				root.toString(), front.toString(), root.resolve("myhers-back").toString(), back.toString(), 1, 1);
		final DownloadReport report = new DownloadReport();
		service.storeDownload(FileSource.MOODLE, entry.getRemoteId(), "Physique", "",
				"Engagements pédagogiques.pdf", old, report);

		final Path renamed = back.resolve("Physique/Engagements pedagogiques.pdf");
		assertTrue(Files.isRegularFile(renamed));
		assertFalse(Files.exists(old));
		assertFalse(Files.exists(oldLink));
		assertEquals("Physique/Engagements pedagogiques.pdf", entry.getRelativePath());
		assertEquals("Engagements pedagogiques.pdf", entry.getOriginalName());
		assertEquals("renamed", report.downloads().getFirst().status());
		assertTrue(FileStoreService.linkPointsTo(front.resolve("Physique/Engagements pedagogiques.pdf"), renamed));
		verify(moodle, atLeastOnce()).update(entry);
	}

	@Test
	void collisionDoesNotOverwriteAnotherFile() throws Exception {
		final Path root = this.temp.resolve("onedrive");
		final Path front = root.resolve("front");
		final Path back = root.resolve("moodle-back");
		Files.createDirectories(front);
		Files.writeString(front.resolve("PATHS.csv"), "", StandardCharsets.UTF_8);
		final Path old = back.resolve("Physique/RÃ©sumÃ©.pdf");
		final Path occupied = back.resolve("Physique/Resume.pdf");
		Files.createDirectories(old.getParent());
		Files.writeString(old, "downloaded", StandardCharsets.UTF_8);
		Files.writeString(occupied, "someone else's document", StandardCharsets.UTF_8);
		final FileData entry = new FileData("id", 1, "Physique", "RÃ©sumÃ©.pdf",
				"Physique/RÃ©sumÃ©.pdf", FileStoreService.sha256(old), "now", null);
		final FileTable moodle = mock(FileTable.class);
		when(moodle.byRemoteId("id")).thenReturn(List.of(entry));
		when(moodle.byCourseCode(anyString())).thenReturn(List.of(entry));
		final FileStoreService service = new FileStoreService(mock(FileTable.class), moodle, null,
				root.toString(), front.toString(), root.resolve("myhers-back").toString(), back.toString(), 1, 1);
		service.storeDownload(FileSource.MOODLE, "id", "Physique", "", "Résumé.pdf", old, new DownloadReport());
		assertEquals("someone else's document", Files.readString(occupied));
		assertEquals("downloaded", Files.readString(back.resolve("Physique/Resume-1.pdf")));
	}
	@Test
	void preservesLocallyModifiedFileWhenRemoteHashIsUnchanged() throws Exception {
		final Path root = this.temp.resolve("onedrive");
		final Path front = root.resolve("front");
		final Path back = root.resolve("moodle-back");
		Files.createDirectories(front);
		Files.writeString(front.resolve("PATHS.csv"), "", StandardCharsets.UTF_8);
		final Path old = back.resolve("Physique/RÃ©sumÃ©.pdf");
		Files.createDirectories(old.getParent());
		Files.writeString(old, "original", StandardCharsets.UTF_8);
		final String remoteHash = FileStoreService.sha256(old);
		Files.writeString(old, "locally edited", StandardCharsets.UTF_8);
		final Path remote = this.temp.resolve("download.pdf");
		Files.writeString(remote, "original", StandardCharsets.UTF_8);
		final FileData entry = new FileData("id", 1, "Physique", "RÃ©sumÃ©.pdf",
				"Physique/RÃ©sumÃ©.pdf", remoteHash, "now", null);
		final FileTable moodle = mock(FileTable.class);
		when(moodle.byRemoteId("id")).thenReturn(List.of(entry));
		final FileStoreService service = new FileStoreService(mock(FileTable.class), moodle, null,
				root.toString(), front.toString(), root.resolve("myhers-back").toString(), back.toString(), 1, 1);
		service.storeDownload(FileSource.MOODLE, "id", "Physique", "", "Résumé.pdf", remote, new DownloadReport());
		assertEquals("locally edited", Files.readString(old));
		assertFalse(Files.exists(back.resolve("Physique/Resume.pdf")));
		assertEquals("Physique/RÃ©sumÃ©.pdf", entry.getRelativePath()); // Link metadata may update.
	}

	@Test
	void databaseFailureRestoresOldFileAndLink() throws Exception {
		final Path root = this.temp.resolve("onedrive");
		final Path front = root.resolve("front");
		final Path back = root.resolve("moodle-back");
		Files.createDirectories(front);
		Files.writeString(front.resolve("PATHS.csv"), "", StandardCharsets.UTF_8);
		final Path old = back.resolve("Physique/RÃ©sumÃ©.pdf");
		Files.createDirectories(old.getParent());
		Files.writeString(old, "original", StandardCharsets.UTF_8);
		final FileData entry = new FileData("id", 1, "Physique", "RÃ©sumÃ©.pdf",
				"Physique/RÃ©sumÃ©.pdf", FileStoreService.sha256(old), "now", null);
		final FileTable moodle = mock(FileTable.class);
		when(moodle.byRemoteId("id")).thenReturn(List.of(entry));
		when(moodle.byCourseCode(anyString())).thenReturn(List.of(entry));
		doThrow(new IllegalStateException("Database unavailable")).when(moodle).update(entry);
		final FileStoreService service = new FileStoreService(mock(FileTable.class), moodle, null,
				root.toString(), front.toString(), root.resolve("myhers-back").toString(), back.toString(), 1, 1);
		assertThrows(IllegalStateException.class, () -> service.storeDownload(FileSource.MOODLE,
				"id", "Physique", "", "Résumé.pdf", old, new DownloadReport()));
		assertEquals("original", Files.readString(old));
		assertFalse(Files.exists(back.resolve("Physique/Resume.pdf")));
		assertEquals("Physique/RÃ©sumÃ©.pdf", entry.getRelativePath());
	}

}
