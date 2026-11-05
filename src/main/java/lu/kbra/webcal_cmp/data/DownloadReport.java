package lu.kbra.webcal_cmp.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DownloadReport {

	public record Item(String course, String filename, String status, String path, String sha256) {
	}

	public record Failure(String course, String resource, String reason) {
	}

	private final Instant started = Instant.now();
	private final List<Item> downloads = new ArrayList<>();
	private final List<Failure> failures = new ArrayList<>();
	private int filesChecked;
	private int coursesChecked;

	public void success(final String course, final String filename, final String status, final String path, final String hash) {
		this.downloads.add(new Item(course, filename, status, path, hash));
	}

	public void failure(final String course, final String resource, final Throwable error) {
		this.failures
				.add(new Failure(course, resource, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
	}

	public void checked() {
		this.filesChecked++;
	}

	public void courseChecked() {
		this.coursesChecked++;
	}

	public Instant started() {
		return this.started;
	}

	public List<Item> downloads() {
		return List.copyOf(this.downloads);
	}

	public List<Failure> failures() {
		return List.copyOf(this.failures);
	}

	public int filesChecked() {
		return this.filesChecked;
	}

	public int coursesChecked() {
		return this.coursesChecked;
	}

}
