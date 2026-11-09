package lu.kbra.webcal_cmp.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.MealType;
import lu.kbra.webcal_cmp.db.data.MenuData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Profile("menu")
@Service
public class MenuOcrService {

	private static final double[][] COLUMNS = { { 0.123, 0.293 }, { 0.300, 0.467 }, { 0.473, 0.637 }, { 0.647, 0.800 }, { 0.810, 0.956 } };
	private static final double[][] ROWS = { { 0.322, 0.551 }, { 0.565, 0.789 } };

	private final String executable;
	private final String language;
	private final String title;

	public MenuOcrService(
			@Value("${menu.ocr.executable}") final String executable,
			@Value("${menu.ocr.language}") final String language,
			@Value("${menu.title}") final String title) {
		this.executable = executable;
		this.language = language;
		this.title = title;
	}

	public Optional<List<MenuData>> extract(final byte[] bytes, final LocalDate monday) throws IOException, InterruptedException {
		if (bytes.length > 12_000_000) {
			return Optional.empty();
		}
		final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null || image.getWidth() < 600 || image.getHeight() < 350) {
			return Optional.empty();
		}

		if (!this.matchesDateRange(image, monday)) {
			return Optional.empty();
		}

		final String heading = this.ocr(image, 0.275, 0.060, 0.750, 0.155, "6", null);
		if (!MenuOcrService.matchesHeading(heading, this.title)) {
			return Optional.empty();
		}

		final List<MenuData> cells = new ArrayList<>();
		for (int day = 0; day < 5; day++) {
			for (int row = 0; row < 2; row++) {
				final String raw = this.ocr(image,
						MenuOcrService.COLUMNS[day][0],
						MenuOcrService.ROWS[row][0],
						MenuOcrService.COLUMNS[day][1],
						MenuOcrService.ROWS[row][1],
						"6",
						null);
				cells.add(new MenuData(monday.plusDays(day), row == 0 ? MealType.LUNCH : MealType.DINNER, MenuOcrService.cleanMeal(raw)));
			}
		}

		if (cells.stream().noneMatch(cell -> cell.getText() != null)) {
			return Optional.empty();
		}

		return Optional.of(cells);
	}

	static boolean matchesHeading(final String scanned, final String expected) {
		return MenuOcrService.normalize(scanned).contains(MenuOcrService.normalize(expected));
	}

	private boolean matchesDateRange(final BufferedImage image, final LocalDate monday) throws IOException, InterruptedException {
		final String broad = this.ocr(image, 0.350, 0.151, 0.655, 0.225, "6", null);
		if (MenuOcrService.matchesWeek(broad, monday)) {
			return true;
		}

		final String tightLine = this.ocr(image, 0.380, 0.158, 0.600, 0.210, "7", null);
		if (MenuOcrService.matchesWeek(tightLine, monday)) {
			return true;
		}

		final String tightRawLine = this.ocr(image, 0.380, 0.158, 0.600, 0.210, "13", null);
		if (MenuOcrService.matchesWeek(tightRawLine, monday)) {
			return true;
		}

		// Some images perform better with the wider crop and raw-line mode.
		final String broadRawLine = this.ocr(image, 0.350, 0.151, 0.655, 0.225, "13", null);
		return MenuOcrService.matchesWeek(broadRawLine, monday);
	}

	/** Requires the Monday and Friday day numbers and at least one month. */
	static boolean matchesWeek(final String scanned, final LocalDate monday) {
		final LocalDate friday = monday.plusDays(4);
		final String normalized = scanned.toUpperCase(Locale.ROOT).replace('O', '0').replace("M", "11").replace('I', '1').replace('L', '1');
		final String firstDay = "%02d".formatted(monday.getDayOfMonth());
		final String lastDay = "%02d".formatted(friday.getDayOfMonth());
		final String startMonth = "%02d".formatted(monday.getMonthValue());
		final String endMonth = "%02d".formatted(friday.getMonthValue());
		return normalized.contains(firstDay) && normalized.contains(lastDay)
				&& (normalized.contains("/" + startMonth) || normalized.contains("/" + endMonth));
	}

	static String cleanMeal(final String raw) {
		if (raw == null) {
			return null;
		}
		final String text = raw.replace("\f", "")
				.lines()
				.map(line -> line.replaceAll("[\\p{Zs}\\t]+", " ").strip())
				.filter(line -> !line.isEmpty())
				.reduce((a, b) -> a + "\n" + b)
				.orElse("");
		final String normalized = MenuOcrService.normalize(text);
		if (text.isBlank() || normalized.matches("(?s).*PAS DE REP[A-Z]*.*") || normalized.contains("BON WEEK END")
				|| normalized.contains("BON WEEKEND")) {
			return null;
		}
		return Arrays.stream(text.split("\n")).map(c -> " * " + c).collect(Collectors.joining("\n"));
	}

	private static String normalize(final String s) {
		final String text = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
	}

	private String ocr(
			final BufferedImage image,
			final double left,
			final double top,
			final double right,
			final double bottom,
			final String mode,
			final String whitelist)
			throws IOException,
				InterruptedException {
		final int x = (int) Math.round(left * image.getWidth());
		final int y = (int) Math.round(top * image.getHeight());
		final int w = Math.max(1, Math.min(image.getWidth() - x, (int) Math.round(right * image.getWidth()) - x));
		final int h = Math.max(1, Math.min(image.getHeight() - y, (int) Math.round(bottom * image.getHeight()) - y));

		final BufferedImage enlarged = new BufferedImage(w * 4, h * 4, BufferedImage.TYPE_BYTE_GRAY);
		final Graphics2D g = enlarged.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.drawImage(image.getSubimage(x, y, w, h), 0, 0, w * 4, h * 4, null);
		} finally {
			g.dispose();
		}
		final Path input = Files.createTempFile("menu-cell-", ".png");
		try {
			ImageIO.write(enlarged, "png", input.toFile());
			final List<String> command = new ArrayList<>(
					List.of(this.executable, input.toString(), "stdout", "-l", this.language, "--psm", mode));
			if (whitelist != null) {
				command.addAll(List.of("-c", "tessedit_char_whitelist=" + whitelist));
			}
			final Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			if (!process.waitFor(20, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("Tesseract timed out");
			}
			final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				throw new IOException("Tesseract failed, exit code " + process.exitValue() + "; check installed OCR languages");
			}
			return output;
		} finally {
			Files.deleteIfExists(input);
		}
	}

}
