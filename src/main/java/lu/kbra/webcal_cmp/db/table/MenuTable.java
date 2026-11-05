package lu.kbra.webcal_cmp.db.table;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lu.kbra.pclib.db.annotations.query.Param;
import lu.kbra.pclib.db.annotations.query.Query;
import lu.kbra.pclib.db.base.DeferredDatabase;
import lu.kbra.pclib.db.table.DeferredDatabaseTable;
import lu.kbra.webcal_cmp.db.data.MenuData;

@Profile("menu")
@Component
public abstract class MenuTable extends DeferredDatabaseTable<MenuData> {

	protected MenuTable(final DeferredDatabase database) {
		super(database);
	}

	@Query
	public abstract List<MenuData> byDate(@Param LocalDate date);

}
