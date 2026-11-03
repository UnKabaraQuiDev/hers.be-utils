package lu.kbra.webcal_cmp.db.data;

import java.time.LocalDate;

import lu.kbra.pclib.PCUtils;
import lu.kbra.pclib.db.annotations.entry.Column;
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

	public MenuData(LocalDate date) {
		this.date = date;
	}

	public MenuData(LocalDate date, MealType mealType) {
		this.date = date;
		this.mealType = mealType;
	}

	public MenuData(LocalDate date, MealType mealType, String text) {
		this.date = date;
		this.mealType = mealType;
		this.text = text;
	}

	@Override
	public MenuData clone() {
		return PCUtils.safeClone(super::clone);
	}

}
