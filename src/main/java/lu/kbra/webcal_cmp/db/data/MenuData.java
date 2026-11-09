package lu.kbra.webcal_cmp.db.data;

import java.time.LocalDate;

import lu.kbra.pclib.PCUtils;
import lu.kbra.pclib.db.annotations.entry.Column;
import lu.kbra.pclib.db.annotations.entry.DefaultValue;
import lu.kbra.pclib.db.annotations.entry.Nullable;
import lu.kbra.pclib.db.annotations.entry.PrimaryKey;
import lu.kbra.pclib.db.annotations.entry.def.MaxLength;
import lu.kbra.pclib.db.impl.DatabaseEntry;
import lu.kbra.webcal_cmp.data.MealType;

import lombok.Data;

@Data
public class MenuData implements DatabaseEntry {

	@Column
	@PrimaryKey
	private LocalDate date;

	@Column
	@PrimaryKey
	private MealType mealType;

	@Column
	@Nullable
	private @MaxLength(512) String text;

	@Column
	@DefaultValue("'0'")
	private boolean sentNotification = false;

	public MenuData(final LocalDate date) {
		this.date = date;
	}

	public MenuData(final LocalDate date, final MealType mealType) {
		this.date = date;
		this.mealType = mealType;
	}

	public MenuData(final LocalDate date, final MealType mealType, final String text) {
		this.date = date;
		this.mealType = mealType;
		this.text = text;
	}

	@Override
	public MenuData clone() {
		return PCUtils.safeClone(super::clone);
	}

}
