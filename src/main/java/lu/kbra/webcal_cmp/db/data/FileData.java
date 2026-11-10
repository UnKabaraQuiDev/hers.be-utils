package lu.kbra.webcal_cmp.db.data;

import lu.kbra.pclib.PCUtils;
import lu.kbra.pclib.db.annotations.entry.Column;
import lu.kbra.pclib.db.annotations.entry.Nullable;
import lu.kbra.pclib.db.annotations.entry.PrimaryKey;
import lu.kbra.pclib.db.annotations.entry.def.MaxLength;
import lu.kbra.pclib.db.impl.DatabaseEntry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
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

	public FileData(String remoteId, int version) {
		this.remoteId = remoteId;
		this.version = version;
	}

	@Override
	public FileData clone() {
		return PCUtils.safeClone(super::clone);
	}

}
