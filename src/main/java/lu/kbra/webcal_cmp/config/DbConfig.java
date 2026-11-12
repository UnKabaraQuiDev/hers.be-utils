package lu.kbra.webcal_cmp.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lu.kbra.pclib.db.dbms.SQLiteDbmsProvider;
import lu.kbra.pclib.db.rule.ValidationRule;
import lu.kbra.pclib.db.utils.DatabaseQueryableHookTemplate;
import lu.kbra.pclib.db.utils.QueryableTemplate;
import lu.kbra.pclib.db.utils.impl.SQLQueryableRule;
import lu.kbra.pclib.db.validation.ConstraintCreator;
import lu.kbra.pclib.db.validation.TableValidatorFactory;
import lu.kbra.webcal_cmp.db.table.FileTable;

@Configuration
public class DbConfig {

	@Bean
	DatabaseQueryableHookTemplate hookTemplate(final List<SQLQueryableRule> rules) {
		final DatabaseQueryableHookTemplate template = new DatabaseQueryableHookTemplate();
		rules.forEach(template::add);
		template.matchDbms(SQLiteDbmsProvider.DBMS_QUALIFIER_NAME);
		return template;
	}

	@Bean
	ValidationRule validationRule(final TableValidatorFactory v) {
		return new ValidationRule(v);
	}

	@Bean
	TableValidatorFactory tableValidatorFactory(final List<ConstraintCreator> c) {
		return new TableValidatorFactory(c);
	}

	@Profile("files-hers")
	@Bean
	QueryableTemplate myhersFileTable() {
		return new QueryableTemplate(FileTable.class).setTableId("myhers-file-table").setName("myhers_file");
	}

	@Profile("files-hers")
	@Bean
	QueryableTemplate moodleFileTable() {
		return new QueryableTemplate(FileTable.class).setTableId("moodle-file-table").setName("moodle_file");
	}

}
