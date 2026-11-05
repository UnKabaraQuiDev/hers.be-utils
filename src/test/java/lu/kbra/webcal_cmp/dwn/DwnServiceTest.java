package lu.kbra.webcal_cmp.dwn;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import lu.kbra.webcal_cmp.data.DownloadReport;
import lu.kbra.webcal_cmp.db.data.FileData;
import lu.kbra.webcal_cmp.db.table.FileTable;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Asset;
import lu.kbra.webcal_cmp.dwn.PortalHtmlParser.Course;
import lu.kbra.webcal_cmp.service.PushNotificationService;

class DwnServiceTest {

	@TempDir
	Path root;

	@Test
	void comparesWithDatabaseHashNotModifiedOneDriveCopyAndVersionsOnlyChangedRemote() throws Exception {
		final FileTable table = Mockito.mock(FileTable.class);
		final DwnPortalClient client = Mockito.mock(DwnPortalClient.class);
		final List<FileData> entries = new ArrayList<>();
		final AtomicReference<byte[]> remote = new AtomicReference<>("version one".getBytes());
		Mockito.when(table.byRemoteId(ArgumentMatchers.anyString())).thenAnswer(a -> List.copyOf(entries));
		Mockito.when(table.byCourseCode(ArgumentMatchers.anyString())).thenAnswer(a -> List.copyOf(entries));
		Mockito.doAnswer(a -> {
			entries.add(a.getArgument(0));
			return null;
		}).when(table).loadIfExistsElseInsert(ArgumentMatchers.any(FileData.class));
		Mockito.when(client.download(ArgumentMatchers.any(URI.class), ArgumentMatchers.anyString())).thenAnswer(a -> {
			final Path temp = Files.createTempFile("remote-test", ".tmp");
			Files.write(temp, remote.get());
			return temp;
		});
		final Path backing = this.root.resolve("back");
		final Path frontend = this.root.resolve("front");
		final DwnService service = new DwnService(client,
				table,
				Mockito.mock(PushNotificationService.class),
				backing.toString(),
				frontend.toString(),
				this.root.toString(),
				1);
		final Course course = new Course("MECA0011-1",
				"Mécanique",
				URI.create("https://my.hers.be/portail/MPDIV/infosCoursForEtudiant.do"));
		final Asset asset = new Asset("661783",
				"910709",
				"Statique.pdf",
				URI.create("https://my.hers.be/es/es_download.do?ai_idEs=661783&ai_idFile=910709"));

		final DownloadReport first = new DownloadReport();
		service.store(course, asset, first);
		Assertions.assertEquals(1, first.downloads().size());
		Assertions.assertEquals(1, entries.size());
		final Path original = backing.resolve(entries.getFirst().getRelativePath());
		Assertions.assertEquals(backing.resolve("MECA0011-1/Statique.pdf"), original);
		final Path frontOriginal = frontend.resolve("MECA0011-1/Statique.pdf");
		Assertions.assertTrue(Files.isSymbolicLink(frontOriginal));
		Assertions.assertFalse(Files.readSymbolicLink(frontOriginal).isAbsolute());
		Assertions.assertEquals(original, frontOriginal.getParent().resolve(Files.readSymbolicLink(frontOriginal)).normalize());
		Assertions.assertEquals("front/MECA0011-1/Statique.pdf", entries.getFirst().getLinkRelativePath());
		Assertions.assertEquals("version one", Files.readString(original));

		// The user can rename/move the frontend link without the scraper recreating it.
		final Path renamed = frontend.resolve("MECA0011-1/Renamed Statique.pdf");
		Files.move(frontOriginal, renamed);
		// Local OneDrive was edited, but server and last stored server hash are unchanged.
		Files.writeString(original, "changed on OneDrive");
		service.store(course, asset, new DownloadReport());
		Assertions.assertEquals("changed on OneDrive", Files.readString(original));
		Assertions.assertFalse(Files.exists(frontOriginal));
		Assertions.assertTrue(Files.isSymbolicLink(renamed));
		Assertions.assertEquals(1, entries.size());

		remote.set("version two".getBytes());
		final DownloadReport changed = new DownloadReport();
		service.store(course, asset, changed);
		Assertions.assertEquals("updated", changed.downloads().getFirst().status());
		Assertions.assertEquals(2, entries.size());
		final Path second = backing.resolve(entries.getLast().getRelativePath());
		Assertions.assertTrue(Files.isSymbolicLink(frontend.resolve("MECA0011-1/Statique-1.pdf")));
		Assertions.assertEquals("Statique-1.pdf", second.getFileName().toString());
		Assertions.assertEquals("version two", Files.readString(second));
		Assertions.assertEquals("changed on OneDrive", Files.readString(original));

		// A missing archived version should be restored without creating an extra version.
		Files.delete(second);
		final DownloadReport restored = new DownloadReport();
		service.store(course, asset, restored);
		Assertions.assertEquals("restored", restored.downloads().getFirst().status());
		Assertions.assertEquals(2, entries.size());
		Assertions.assertEquals("version two", Files.readString(second));
		Assertions.assertFalse(Files.exists(frontOriginal));
	}

	@Test
	void migratesExistingIdDirectoriesAndCreatesOnlyInitialFrontendLink() throws Exception {
		final Path backing = this.root.resolve("back");
		final Path frontend = this.root.resolve("front");
		final FileTable table = Mockito.mock(FileTable.class);
		final DwnPortalClient client = Mockito.mock(DwnPortalClient.class);
		final String oldRelative = "MECA0011-1/661783/910709/Statique.pdf";
		final Path oldFile = backing.resolve(oldRelative);
		Files.createDirectories(oldFile.getParent());
		Files.writeString(oldFile, "old content");
		final FileData stored = new FileData("MECA0011-1:661783:910709",
				1,
				"MECA0011-1",
				"Statique.pdf",
				oldRelative,
				DwnService.sha256(oldFile),
				"2026-11-04T00:00:00Z",
				null);
		Mockito.when(table.byRemoteId(ArgumentMatchers.anyString())).thenReturn(List.of(stored));
		Mockito.when(table.byCourseCode(ArgumentMatchers.anyString())).thenReturn(List.of(stored));
		Mockito.when(client.download(ArgumentMatchers.any(URI.class), ArgumentMatchers.anyString())).thenAnswer(a -> {
			final Path tmp = Files.createTempFile(this.root, "remote-", ".tmp");
			Files.writeString(tmp, "old content");
			return tmp;
		});
		final DwnService service = new DwnService(client,
				table,
				Mockito.mock(PushNotificationService.class),
				backing.toString(),
				frontend.toString(),
				this.root.toString(),
				1);
		final Course course = new Course("MECA0011-1", "Mécanique", URI.create("https://my.hers.be/portail/MPDIV/x"));
		final Asset asset = new Asset("661783", "910709", "Statique.pdf", URI.create("https://my.hers.be/es/es_download.do"));

		final DownloadReport report = new DownloadReport();
		service.store(course, asset, report);
		Assertions.assertTrue(report.downloads().isEmpty());
		Assertions.assertEquals("MECA0011-1/Statique.pdf", stored.getRelativePath());
		Assertions.assertFalse(Files.exists(oldFile));
		Assertions.assertTrue(Files.exists(backing.resolve(stored.getRelativePath())));
		Assertions.assertTrue(Files.isSymbolicLink(frontend.resolve("MECA0011-1/Statique.pdf")));
		Assertions.assertEquals("front/MECA0011-1/Statique.pdf", stored.getLinkRelativePath());
		Mockito.verify(table, Mockito.atLeast(2)).update(stored); // Migration + initial symlink creation.

		final Path movedLink = frontend.resolve("My files/Mechanics.pdf");
		Files.createDirectories(movedLink.getParent());
		Files.move(frontend.resolve("MECA0011-1/Statique.pdf"), movedLink);
		service.store(course, asset, new DownloadReport());
		Assertions.assertFalse(Files.exists(frontend.resolve("MECA0011-1/Statique.pdf")));
		Assertions.assertEquals("old content", Files.readString(movedLink));
	}

	@Test
	void sameNameFromDifferentRemoteIdsDoesNotOverwriteBackingFile() throws Exception {
		final Path backing = this.root.resolve("back");
		final Path frontend = this.root.resolve("front");
		final FileTable table = Mockito.mock(FileTable.class);
		final DwnPortalClient client = Mockito.mock(DwnPortalClient.class);
		final List<FileData> records = new ArrayList<>();
		Mockito.when(table.byRemoteId(ArgumentMatchers.anyString()))
				.thenAnswer(a -> records.stream().filter(f -> f.getRemoteId().equals(a.getArgument(0))).toList());
		Mockito.when(table.byCourseCode(ArgumentMatchers.anyString())).thenAnswer(a -> List.copyOf(records));
		Mockito.doAnswer(a -> {
			records.add(a.getArgument(0));
			return null;
		}).when(table).loadIfExistsElseInsert(ArgumentMatchers.any(FileData.class));
		Mockito.when(client.download(ArgumentMatchers.any(URI.class), ArgumentMatchers.anyString())).thenAnswer(a -> {
			final Path temp = Files.createTempFile(this.root, "remote", ".tmp");
			Files.writeString(temp, a.getArgument(0).toString());
			return temp;
		});
		final DwnService service = new DwnService(client,
				table,
				Mockito.mock(PushNotificationService.class),
				backing.toString(),
				frontend.toString(),
				this.root.toString(),
				1);
		final Course course = new Course("MECA0011-1", "Mécanique", URI.create("https://my.hers.be/portail/MPDIV/x"));
		service.store(course, new Asset("12", "11", "exercise.pdf", URI.create("https://my.hers.be/es/a")), new DownloadReport());
		service.store(course, new Asset("12", "22", "exercise.pdf", URI.create("https://my.hers.be/es/b")), new DownloadReport());
		Assertions.assertEquals(2, records.size());
		Assertions.assertNotEquals(records.get(0).getRelativePath(), records.get(1).getRelativePath());
		Assertions.assertTrue(Files.isRegularFile(backing.resolve("MECA0011-1/exercise.pdf")));
		Assertions.assertTrue(Files.isRegularFile(backing.resolve("MECA0011-1/exercise-1.pdf")));
		Assertions.assertTrue(Files.isSymbolicLink(frontend.resolve("MECA0011-1/exercise.pdf")));
		Assertions.assertTrue(Files.isSymbolicLink(frontend.resolve("MECA0011-1/exercise-1.pdf")));
		// Even if a user removes a backing file, its filename must remain reserved
		// for the recorded remote ID, not silently get assigned to another ID.
		Files.delete(backing.resolve("MECA0011-1/exercise.pdf"));
		service.store(course, new Asset("12", "33", "exercise.pdf", URI.create("https://my.hers.be/es/c")), new DownloadReport());
		Assertions.assertTrue(Files.isRegularFile(backing.resolve("MECA0011-1/exercise-2.pdf")));
		service.store(course, new Asset("12", "11", "exercise.pdf", URI.create("https://my.hers.be/es/a")), new DownloadReport());
		Assertions.assertTrue(Files.isRegularFile(backing.resolve("MECA0011-1/exercise.pdf")));
		Assertions.assertEquals(3, records.size());
	}
}
