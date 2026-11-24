package lu.kbra.webcal_cmp.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
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

	@Value("${menu.username:}")
	private String username;

	@Value("${menu.password:}")
	private String password;

	@Value("${menu.session-path:./facebook-state/session.json}")
	private String sessionPath;

	public synchronized Optional<List<MenuData>> findWeek(final LocalDate monday) throws IOException, InterruptedException {
		if (this.facebookUrl.isBlank()) {
			return Optional.empty();
		}
		final Path state = Path.of(this.sessionPath).toAbsolutePath();
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true)
								.setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox")))) {
			BrowserContext initial;
			try {
				initial = browser.newContext(this.contextOptions(state));
			} catch (final PlaywrightException ex) {
				if (!Files.isRegularFile(state)) {
					throw ex;
				}
				FacebookMenuSource.log.warn("Unable to restore Facebook session; starting with a clean context");
				initial = browser.newContext(this.contextOptions(null));
			}
			try (BrowserContext context = initial) {
				final ScanResult result = this.scan(context, monday);
				if (result.usable()) {
					if (FacebookMenuSource.authenticated(context)) {
						this.saveSession(context, state);
					}
					return result.week();
				}
			}

			if (this.username.isBlank() || this.password.isBlank()) {
				throw new IOException("Facebook feed inaccessible; configure menu.username and menu.password");
			}
			FacebookMenuSource.log.info("Facebook feed inaccessible; attempting one login and retry");
			try (BrowserContext context = browser.newContext(this.contextOptions(null))) {
				this.login(context);
				this.saveSession(context, state);
				final ScanResult result = this.scan(context, monday);
				if (!result.usable()) {
					throw new IOException("Facebook feed remains inaccessible after login; existing menu cache is unchanged");
				}
				if (FacebookMenuSource.authenticated(context)) {
					this.saveSession(context, state);
				}
				return result.week();
			}
		} catch (final PlaywrightException ex) {
			throw new IOException("Facebook browser failed (navigation or Chromium installation)", ex);
		}
	}

	private Browser.NewContextOptions contextOptions(final Path state) {
		final Browser.NewContextOptions options = new Browser.NewContextOptions().setLocale("fr-BE").setViewportSize(1280, 900);
		if (state != null && Files.isRegularFile(state)) {
			options.setStorageStatePath(state);
		}
		return options;
	}

	private void login(final BrowserContext context) throws IOException {
		final Page page = context.newPage();
		try {
			page.navigate("https://www.facebook.com/login/",
					new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
			this.dismissConsent(page);
			final URI uri = URI.create(page.url());
			if (!"https".equals(uri.getScheme()) || !"www.facebook.com".equalsIgnoreCase(uri.getHost())) {
				throw new IOException("Facebook login redirected outside the expected login host");
			}
			page.locator("input[name=email], #email").first().fill(this.username, new Locator.FillOptions().setTimeout(15_000));
			page.locator("input[name=pass], #pass").first().fill(this.password, new Locator.FillOptions().setTimeout(15_000));
			page.locator("button[name=login], input[name=login], #loginbutton, button[type=submit]").first().click();
			for (int i = 0; i < 30; i++) {
				if (FacebookMenuSource.challenge(page)) {
					throw new IOException("Facebook requires a checkpoint, CAPTCHA or two-factor authentication; manual action required");
				}
				if (FacebookMenuSource.authenticated(context) && !FacebookMenuSource.loginWall(page)) {
					FacebookMenuSource.log.info("Facebook login succeeded");
					return;
				}
				page.waitForTimeout(1000);
			}
			throw new IOException("Facebook login not confirmed; check credentials or account restrictions");
		} catch (PlaywrightException | IllegalArgumentException ex) {
			// Playwright action traces can contain the values passed to fill().
			throw new IOException("Facebook login could not complete; check credentials, consent or account restrictions");
		} finally {
			page.close();
		}
	}

	private static boolean authenticated(final BrowserContext context) {
		final var cookies = context.cookies("https://www.facebook.com/");
		return cookies.stream().anyMatch(c -> "c_user".equals(c.name) && !c.value.isBlank())
				&& cookies.stream().anyMatch(c -> "xs".equals(c.name) && !c.value.isBlank());
	}

	private void saveSession(final BrowserContext context, final Path state) throws IOException {
		Files.createDirectories(state.getParent());
		final Path temporary = Files.createTempFile(state.getParent(),
				".facebook-",
				".json",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		try {
			Files.writeString(temporary, context.storageState());
			try {
				Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (final AtomicMoveNotSupportedException ex) {
				Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private void dismissConsent(final Page page) {
		final Locator reject = page.getByRole(AriaRole.BUTTON,
				new Page.GetByRoleOptions().setName(Pattern.compile(
						"(?i)decline optional cookies|only allow essential cookies|refuser les cookies facultatifs|autoriser uniquement les cookies essentiels")))
				.first();
		try {
			if (reject.isVisible()) {
				reject.click(new Locator.ClickOptions().setTimeout(3000));
			}
		} catch (final PlaywrightException ignored) {
			// Some Facebook variants do not show a consent dialog.
		}
	}

	private static boolean challenge(final Page page) {
		final String url = page.url().toLowerCase(Locale.ROOT);
		return url.contains("/checkpoint") || url.contains("/two_step_verification") || url.contains("/captcha");
	}

	private static boolean loginWall(final Page page) {
		final String url = page.url().toLowerCase(Locale.ROOT);
		final String visible = page.locator("body").innerText().toLowerCase(Locale.ROOT);
		return FacebookMenuSource.challenge(page) || url.contains("/login") || visible.contains("temporarily blocked")
				|| visible.contains("temporairement bloqu") || visible.contains("you must log in")
				|| visible.contains("vous devez vous connecter");
	}

	private ScanResult scan(final BrowserContext context, final LocalDate monday) throws IOException, InterruptedException {
		final Set<String> seen = new HashSet<>();
		int accessible = 0;
		final Page page = context.newPage();
		try {
			page.navigate(this.facebookUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
			page.waitForTimeout(1400);
			this.dismissConsent(page);
			for (int scroll = 0; scroll <= FacebookMenuSource.MAX_SCROLLS && seen.size() < FacebookMenuSource.MAX_IMAGES; scroll++) {
				if (FacebookMenuSource.loginWall(page)) {
					return new ScanResult(false, Optional.empty());
				}
				for (final ImageRef photo : this.readImages(page)) {
					if (!FacebookMenuSource.allowedImageUrl(photo.url()) || seen.size() >= FacebookMenuSource.MAX_IMAGES
							|| !seen.add(photo.url())) {
						continue;
					}
					try {
						final APIResponse response = context.request().get(photo.url());
						try {
							if (response.ok()) {
								final byte[] body = response.body();
								final String contentType = response.headers().getOrDefault("content-type", "");
								if (!contentType.startsWith("image/") || body.length == 0) {
									continue;
								}
								accessible++;
								final Optional<List<MenuData>> week = this.ocr.extract(body, monday);
								if (week.isPresent()) {
									FacebookMenuSource.log.info("Found menu for week {} in Facebook photo", monday);
									return new ScanResult(true, week);
								}
							}
						} finally {
							response.dispose();
						}
					} catch (PlaywrightException | IOException ex) {
						FacebookMenuSource.log.debug("Unable to read Facebook photo");
					}
				}
				page.evaluate("window.scrollBy(0, Math.max(window.innerHeight, 850))");
				page.waitForTimeout(1200);
			}
			return new ScanResult(accessible > 0 && !FacebookMenuSource.loginWall(page), Optional.empty());
		} finally {
			page.close();
		}
	}

	private record ScanResult(boolean usable, Optional<List<MenuData>> week) {
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
