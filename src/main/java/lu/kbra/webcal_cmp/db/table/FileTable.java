package lu.kbra.webcal_cmp.db.table;

import java.util.List;

import lu.kbra.pclib.db.annotations.query.Param;
import lu.kbra.pclib.db.annotations.query.Query;
import lu.kbra.pclib.db.base.DeferredDatabase;
import lu.kbra.pclib.db.table.DeferredDatabaseTable;
import lu.kbra.webcal_cmp.db.data.FileData;

public abstract class FileTable extends DeferredDatabaseTable<FileData> {

	protected FileTable(final DeferredDatabase database) {
		super(database);
	}

	@Query
	public abstract List<FileData> byRemoteId(@Param String remoteId);

	@Query
	public abstract List<FileData> byCourseCode(@Param String courseCode);
}
