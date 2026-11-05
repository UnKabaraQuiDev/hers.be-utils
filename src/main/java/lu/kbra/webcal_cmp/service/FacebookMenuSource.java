package lu.kbra.webcal_cmp.service;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;

import lu.kbra.webcal_cmp.db.data.MenuData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Profile("menu")
@Service
@RequiredArgsConstructor
public class FacebookMenuSource {

	private static final int MAX_SCROLLS = 7;
	private static final int MAX_IMAGES = 45;

	private final ZoneId zone;
	private final MenuOcrService ocr;

	@Value("${menu.facebook}")
	private String facebookUrl;

	@Value("${menu.cookies:}")
	private String cookies;

	public Optional<List<MenuData>> findWeek(final LocalDate monday) throws IOException, InterruptedException {
		if (this.facebookUrl.isBlank()) {
			FacebookMenuSource.log.debug("Menu Facebook source is not configured");
			return Optional.empty();
		}
		final LocalDate earliest = monday.minusDays(3);
		final Set<String> seen = new HashSet<>();
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true)
								.setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox")))) {
			try (BrowserContext context = browser
					.newContext(new Browser.NewContextOptions().setLocale("fr-BE").setViewportSize(1280, 900))) {
				this.installCookies(context);
				final Page page = context.newPage();
				page.navigate(this.facebookUrl,
						new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
				page.waitForTimeout(1400);
				for (int scroll = 0; scroll <= FacebookMenuSource.MAX_SCROLLS && seen.size() < FacebookMenuSource.MAX_IMAGES; scroll++) {
					boolean encounteredOlder = false;
					for (final ImageRef photo : this.readImages(page)) {
						final LocalDate posted = this.postedOn(photo.timestamp());
						if (posted != null && posted.isBefore(earliest)) {
							encounteredOlder = true;
							continue;
						}
						if (!seen.add(photo.url()) || seen.size() > FacebookMenuSource.MAX_IMAGES
								|| !FacebookMenuSource.allowedImageUrl(photo.url())) {
							continue;
						}
						try {
							final APIResponse response = context.request().get(photo.url());
							try {
								if (response.ok()) {
									final Optional<List<MenuData>> week = this.ocr.extract(response.body(), monday);
									if (week.isPresent()) {
										FacebookMenuSource.log.info("Found menu for week {} in Facebook photo", monday);
										return week;
									}
								}
							} finally {
								response.dispose();
							}
						} catch (PlaywrightException | IOException ex) {
							FacebookMenuSource.log.debug("Unable to read Facebook photo", ex);
						}
					}
					if (encounteredOlder) {
						break; // Do not go back past the preceding Friday.
					}
					page.evaluate("window.scrollBy(0, Math.max(window.innerHeight, 850))");
					page.waitForTimeout(1200);
				}
				final String visible = page.locator("body").innerText().toLowerCase();
				if (visible.contains("temporarily blocked") || visible.contains("temporairement bloqu")
						|| visible.contains("you must log in") || visible.contains("vous devez vous connecter")) {
					throw new IOException("Facebook returned a login/block page. Set MENU_COOKIES from an authorized session.");
				}
				if (seen.isEmpty()) {
					throw new IOException("Facebook did not expose any accessible feed images (possible login/block page)");
				}
				return Optional.empty();
			}
		} catch (final PlaywrightException ex) {
			throw new IOException("Facebook browser failed (cookies, login wall, or Chromium installation)", ex);
		}
	}

	private void installCookies(final BrowserContext context) {
		if (this.cookies == null || this.cookies.isBlank()) {
			return;
		}
		for (final String pair : this.cookies.split(";")) {
			final int equals = pair.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			final String name = pair.substring(0, equals).trim();
			final String value = pair.substring(equals + 1).trim();
			if (!name.isBlank()) {
				context.addCookies(List.of(new Cookie(name, value).setDomain(".facebook.com").setPath("/").setSecure(true)));
			}
		}
	}

	private LocalDate postedOn(final String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return null;
		}
		try {
			if (timestamp.matches("\\d{10,13}")) {
				final long value = Long.parseLong(timestamp);
				return Instant.ofEpochMilli(timestamp.length() == 13 ? value : value * 1000).atZone(this.zone).toLocalDate();
			}
			return Instant.parse(timestamp).atZone(this.zone).toLocalDate();
		} catch (DateTimeParseException | NumberFormatException ignored) {
			return null;
		}
	}

	private static boolean allowedImageUrl(final String url) {
		try {
			final URI uri = URI.create(url);
			final String host = uri.getHost();
			return "https".equals(uri.getScheme()) && host != null && ("fbcdn.net".equals(host) || host.endsWith(".fbcdn.net")
					|| "facebook.com".equals(host) || host.endsWith(".facebook.com"));
		} catch (final IllegalArgumentException ignored) {
			return false;
		}
	}

	private List<ImageRef> readImages(final Page page) {
		final Object output = page.evaluate("""
				() => [...document.querySelectorAll('img')]
				  .filter(img => img.naturalWidth >= 600 && img.naturalHeight >= 350
				    && img.naturalWidth / img.naturalHeight >= 1.25
				    && img.naturalWidth / img.naturalHeight <= 1.85)
				  .map(img => {
				    const article = img.closest('[role="article"], article, [data-pagelet*="FeedUnit"]');
				    const time = article?.querySelector('time, abbr[data-utime]');
				    return { url: img.currentSrc || img.src,
				      timestamp: time?.getAttribute('datetime') || time?.getAttribute('data-utime') || '' };
				  })
				""");
		if (!(output instanceof final List<?> images)) {
			return List.of();
		}
		return images.stream()
				.filter(Map.class::isInstance)
				.map(Map.class::cast)
				.map(map -> new ImageRef(String.valueOf(map.get("url")), String.valueOf(map.get("timestamp"))))
				.toList();
	}

	private record ImageRef(String url, String timestamp) {
	}

}
