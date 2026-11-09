package lu.kbra.webcal_cmp.db.data;

import lu.kbra.pclib.PCUtils;
import lu.kbra.pclib.db.annotations.entry.Column;
import lu.kbra.pclib.db.annotations.entry.Nullable;
import lu.kbra.pclib.db.annotations.entry.PrimaryKey;
import lu.kbra.pclib.db.annotations.entry.def.MaxLength;
import lu.kbra.pclib.db.impl.DatabaseEntry;

import lombok.Data;

@Data
public class FileData implements DatabaseEntry {

	@Column
	@PrimaryKey
	private @MaxLength(160) String remoteId;

	@Column
	@PrimaryKey
	private int version;

	@Column
	private @MaxLength(64) String courseCode;

	@Column
	private @MaxLength(512) String originalName;

	@Column
	private @MaxLength(2048) String relativePath;

	@Column
	private @MaxLength(64) String sha256;

	@Column
	private @MaxLength(64) String downloadedAt;

	@Column
	@Nullable
	private @MaxLength(2048) String linkRelativePath;

	public FileData() {
	}

	public FileData(
			final String remoteId,
			final int version,
			final String courseCode,
			final String originalName,
			final String relativePath,
			final String sha256,
			final String downloadedAt,
			final String linkRelativePath) {
		this.remoteId = remoteId;
		this.version = version;
		this.courseCode = courseCode;
		this.originalName = originalName;
		this.relativePath = relativePath;
		this.sha256 = sha256;
		this.downloadedAt = downloadedAt;
		this.linkRelativePath = linkRelativePath;
	}

	@Override
	public FileData clone() {
		return PCUtils.safeClone(super::clone);
	}
}
