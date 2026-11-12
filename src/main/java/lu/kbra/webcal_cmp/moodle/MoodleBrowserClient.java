package lu.kbra.webcal_cmp.moodle;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;

import lombok.extern.slf4j.Slf4j;
import lu.kbra.webcal_cmp.moodle.MoodleHtmlParser.Course;

/**
 * Browser-backed Moodle access. The course overview is filled by JavaScript,
 * and the authenticated browser context shares its cookies with API requests
 * used to download attachments and resolve URL activities.
 *
 * The username/password settings are the same ones used by MyHERS. Passwords
 * are only entered on the trusted HERS SimpleSAML identity-provider page.
 */
@Slf4j
@Service
@Profile("files-hers")
public class MoodleBrowserClient {

	private static final String MOODLE_HOST = "moodle.hers.be";
	private static final String IDP_HOST = "idp.hers.be";
	private static final String IDP_LOGIN_PATH = "/simplesaml/module.php/core/loginuserpass";
	private static final String LOGIN_BUTTON =
			".login-identityproviders a[href*='/auth/saml2/login.php'], "
			+ "a.login-identityprovider-btn[href*='/auth/saml2/login.php']";
	private static final String COURSE_VIEW = "[data-region='courses-view']";
	private static final String COURSE_CARDS = COURSE_VIEW + " [data-region='course-content'][data-course-id]";
	private static final int NAVIGATION_TIMEOUT_MS = 60_000;
	private static final int RENDER_TIMEOUT_MS = 30_000;
	private static final int MAX_COURSE_PAGES = 100;
	private static final Pattern FILENAME_STAR = Pattern.compile("(?i)filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)");
	private static final Pattern FILENAME = Pattern.compile("(?i)filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))");

	private final String username;
	private final String password;
	private final int timeoutMs;
	private final int retries;

	public MoodleBrowserClient(
			@Value("${dwn.portal.username}") final String username,
			@Value("${dwn.portal.password}") final String password,
			@Value("${dwn.download-timeout-seconds}") final int downloadTimeoutSeconds,
			@Value("${dwn.retries}") final int retries) {
		this.username = username == null ? "" : username;
		this.password = password == null ? "" : password;
		this.timeoutMs = Math.max(20, downloadTimeoutSeconds) * 1000;
		this.retries = Math.max(1, retries);
	}

	public boolean configured() {
		return !this.username.isBlank() && !this.password.isBlank();
	}

	/** Each scan owns a fresh browser; never share a Playwright Page across threads. */
	public Session openSession() throws IOException {
		if (!this.configured()) {
			throw new IOException("Shared MyHERS/Moodle SSO credentials have not been configured");
		}
		Playwright playwright = null;
		Browser browser = null;
		BrowserContext context = null;
		try {
			playwright = Playwright.create();
			browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
					.setHeadless(true).setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox")));
			context = browser.newContext(new Browser.NewContextOptions()
					.setLocale("fr-BE").setAcceptDownloads(true).setViewportSize(1280, 900));
			final Page page = context.newPage();
			page.setDefaultTimeout(RENDER_TIMEOUT_MS);
			page.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS);
			return new Session(playwright, browser, context, page);
		} catch (final RuntimeException e) {
			if (context != null) {
				context.close();
			}
			if (browser != null) {
				browser.close();
			}
			if (playwright != null) {
				playwright.close();
			}
			throw new IOException("Could not start Chromium for Moodle", e);
		}
	}

	static boolean isMoodleUri(final URI uri) {
		return trustedHost(uri, MOODLE_HOST);
	}

	static boolean isIdentityProviderLogin(final URI uri) {
		return trustedHost(uri, IDP_HOST) && IDP_LOGIN_PATH.equals(uri.getPath());
	}

	private static boolean trustedHost(final URI uri, final String host) {
		return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost());
	}

	/** Extracts the SAML provider URL without confusing it with Moodle's local login form. */
	static URI samlProvider(final String html, final URI pageUri) throws IOException {
		if (!isMoodleUri(pageUri)) {
			throw new IOException("Unexpected SAML provider page: " + pageUri.getHost());
		}
		final Element button = Jsoup.parse(html, pageUri.toString()).selectFirst(LOGIN_BUTTON);
		if (button == null) {
			return null;
		}
		final URI result = pageUri.resolve(button.attr("href"));
		if (!isMoodleUri(result) || !"/auth/saml2/login.php".equals(result.getPath())) {
			throw new IOException("Refusing an untrusted Moodle identity-provider URL");
		}
		return result;
	}

	public record MoodleDownload(Path path, String filename) {
	}

	public final class Session implements AutoCloseable {
		private final Playwright playwright;
		private final Browser browser;
		private final BrowserContext context;
		private final Page page;

		private Session(final Playwright playwright, final Browser browser, final BrowserContext context, final Page page) {
			this.playwright = playwright;
			this.browser = browser;
			this.context = context;
			this.page = page;
		}

		/** Logs in through "Se connecter avec MyHERS" and reads all paginated course cards. */
		public List<Course> enrolledCourses(final URI uri) throws IOException {
			this.navigateAuthenticated(uri);
			try {
				this.page.locator(COURSE_VIEW).waitFor(new Locator.WaitForOptions().setTimeout(RENDER_TIMEOUT_MS));
				// Moodle's overview is populated by a JavaScript/AJAX course renderer.
				this.page.waitForFunction("""
						() => !!document.querySelector('[data-region="courses-view"] '
						  + '[data-region="course-content"][data-course-id]')
						""", null, new Page.WaitForFunctionOptions().setTimeout(RENDER_TIMEOUT_MS));
				final Map<String, Course> courses = new LinkedHashMap<>();
				int expectedTotal = 0;
				for (int i = 0; i < MAX_COURSE_PAGES; i++) {
					final String html = this.page.content();
					for (final Course course : MoodleHtmlParser.courses(html, URI.create(this.page.url()))) {
						courses.putIfAbsent(course.id(), course);
					}
					final String total = this.page.locator(COURSE_VIEW).getAttribute("data-totalcoursecount");
					if (total != null && total.matches("[0-9]+")) {
						expectedTotal = Math.max(expectedTotal, Integer.parseInt(total));
					}
					final Locator next = this.page.locator(COURSE_VIEW
							+ " [data-region='paging-bar'] [data-control='next']:not(.disabled):not([aria-disabled='true']) a[data-region='page-link']");
					if (next.count() == 0) {
						break;
					}
					final Locator cards = this.page.locator(COURSE_CARDS);
					final String firstId = cards.count() == 0 ? "" : cards.first().getAttribute("data-course-id");
					final String pageNo = this.page.locator(COURSE_VIEW + " [data-region='paging-bar']")
							.first().getAttribute("data-active-page-number");
					next.first().click();
					this.page.waitForFunction("""
							old => { const view = document.querySelector('[data-region="courses-view"]');
							  const bar = view?.querySelector('[data-region="paging-bar"]');
							  const first = view?.querySelector('[data-region="course-content"][data-course-id]');
							  return first && bar && bar.getAttribute('data-active-page-number') !== old.pageNo
							    && first.getAttribute('data-course-id') !== old.firstId;
							}
							""", Map.of("pageNo", pageNo == null ? "" : pageNo,
							"firstId", firstId == null ? "" : firstId),
							new Page.WaitForFunctionOptions().setTimeout(RENDER_TIMEOUT_MS));
				}
				if (courses.isEmpty()) {
					throw new IOException("No Moodle course cards were rendered at " + uri
							+ "; confirm the authenticated /my/courses.php view contains enrolled courses");
				}
				if (expectedTotal > courses.size()) {
					throw new IOException("Moodle rendered only " + courses.size() + " of " + expectedTotal
							+ " enrolled courses; pagination/JS loading is incomplete");
				}
				log.info("Moodle Playwright discovered {} enrolled courses", courses.size());
				return List.copyOf(courses.values());
			} catch (final PlaywrightException e) {
				throw new IOException("Timed out waiting for JavaScript-rendered Moodle courses at " + uri
						+ " (current browser URL: " + this.page.url() + ")", e);
			}
		}

		/** Fetch a course after its JavaScript-enhanced content has loaded. */
		public String courseHtml(final URI uri) throws IOException {
			this.navigateAuthenticated(uri);
			try {
				this.page.locator("#region-main .course-content")
						.waitFor(new Locator.WaitForOptions().setTimeout(RENDER_TIMEOUT_MS));
				return this.page.content();
			} catch (final PlaywrightException e) {
				throw new IOException("Moodle did not render the course content for " + uri, e);
			}
		}

		/** Read external URL activities without fetching external pages. */
		public URI externalUrl(final URI uri) throws IOException {
			requireMoodle(uri);
			URI current = uri;
			try {
				for (int i = 0; i < 8; i++) {
					final APIResponse response = this.context.request().get(current.toString(),
							RequestOptions.create().setMaxRedirects(0).setTimeout(NAVIGATION_TIMEOUT_MS));
					try {
						if (response.status() >= 300 && response.status() < 400) {
							final String location = header(response, "location");
							if (location.isBlank()) {
								throw new IOException("Moodle URL redirect has no Location header");
							}
							final URI target = current.resolve(location);
							if (!"https".equalsIgnoreCase(target.getScheme())
									&& !"http".equalsIgnoreCase(target.getScheme())) {
								throw new IOException("Unsupported URL activity redirect scheme");
							}
							if (!isMoodleUri(target)) {
								if ("hers.be".equalsIgnoreCase(target.getHost())
										|| target.getHost() != null && target.getHost().endsWith(".hers.be")) {
									throw new IOException("Moodle URL was redirected to HERS SSO, session may have expired");
								}
								return target;
							}
							current = target;
							continue;
						}
						if (response.status() != 200) {
							throw new IOException("Moodle URL activity returned HTTP " + response.status());
						}
						final Document doc = Jsoup.parse(new String(response.body(), StandardCharsets.UTF_8), current.toString());
						for (final Element anchor : doc.select("#region-main .urlworkaround a[href], #region-main div[role=main] a[href]")) {
							final URI target = current.resolve(anchor.attr("href"));
							if (("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme()))
									&& !target.equals(uri) && !isMoodleUri(target)) {
								return target;
							}
						}
						throw new IOException("Moodle URL activity does not expose an external link: " + uri);
					} finally {
						response.dispose();
					}
				}
				throw new IOException("Too many Moodle URL redirects: " + uri);
			} catch (final PlaywrightException e) {
				throw new IOException("Could not resolve Moodle external URL " + uri, e);
			}
		}

		/** Downloads resource attachments with the authenticated browser's cookies. */
		public MoodleDownload downloadResource(final URI uri) throws IOException, InterruptedException {
			requireMoodle(uri);
			IOException failure = null;
			for (int attempt = 1; attempt <= MoodleBrowserClient.this.retries; attempt++) {
				try {
					return this.downloadResource(uri, 0);
				} catch (final PlaywrightException e) {
					failure = new IOException("Browser request failed for Moodle attachment " + uri.getPath(), e);
				} catch (final IOException e) {
					failure = e;
				}
				log.warn("Moodle attachment {} failed (attempt {}/{}): {}", uri.getPath(), attempt,
						MoodleBrowserClient.this.retries, failure.getMessage());
				if (attempt < MoodleBrowserClient.this.retries) {
					TimeUnit.SECONDS.sleep(Math.min(attempt * 3, 15));
				}
			}
			throw failure == null ? new IOException("Moodle attachment download failed") : failure;
		}

		private MoodleDownload downloadResource(final URI uri, final int depth) throws IOException {
			if (depth >= 5) {
				throw new IOException("Moodle attachment has too many preview/redirect steps");
			}
			final APIResponse response = this.context.request().get(uri.toString(),
					RequestOptions.create().setTimeout(MoodleBrowserClient.this.timeoutMs));
			try {
				if (!response.ok()) {
					throw new IOException("Moodle attachment returned HTTP " + response.status());
				}
				final URI actual = URI.create(response.url());
				requireMoodle(actual);
				final String contentType = header(response, "content-type").toLowerCase(Locale.ROOT);
				final String disposition = header(response, "content-disposition");
				final byte[] bytes = response.body();
				if (bytes.length == 0) {
					throw new IOException("Empty Moodle attachment response");
				}
				if (contentType.contains("text/html") && !disposition.toLowerCase(Locale.ROOT).contains("attachment")) {
					if (bytes.length > 5_000_000) {
						throw new IOException("Unexpected large Moodle HTML preview");
					}
					final Document document = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), actual.toString());
					if (document.selectFirst("form:has(input[type=password]), a.login-identityprovider-btn") != null) {
						throw new IOException("Moodle session expired during attachment download");
					}
					final Element link = document.selectFirst("""
							#region-main iframe[src*='pluginfile.php'], #region-main object[data*='pluginfile.php'],
							#region-main a[href*='pluginfile.php'], .resourceworkaround a[href],
							#region-main a[href*='/mod/resource/download.php']
							""");
					if (link == null) {
						throw new IOException("Moodle attachment returned HTML without a file link: " + uri);
					}
					final String href = link.hasAttr("href") ? link.attr("href")
							: link.hasAttr("src") ? link.attr("src") : link.attr("data");
					final URI target = actual.resolve(href.replace("&amp;", "&"));
					requireMoodle(target);
					if (target.equals(uri)) {
						throw new IOException("Moodle preview links back to the same page: " + uri);
					}
					return this.downloadResource(target, depth + 1);
				}
				final Path temp = Files.createTempFile("moodle-file-", ".download");
				try {
					Files.write(temp, bytes);
					return new MoodleDownload(temp, fileName(disposition, actual));
				} catch (final IOException e) {
					Files.deleteIfExists(temp);
					throw e;
				}
			} finally {
				response.dispose();
			}
		}

		private void navigateAuthenticated(final URI uri) throws IOException {
			requireMoodle(uri);
			try {
				this.page.navigate(uri.toString(), new Page.NavigateOptions()
						.setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(NAVIGATION_TIMEOUT_MS));
				this.completeSamlLogin();
				if (!isMoodleUri(URI.create(this.page.url()))) {
					throw new IOException("Moodle authentication did not return to Moodle");
				}
				// The SAML round-trip usually lands on the original URL, but verify it.
				if (!URI.create(this.page.url()).getPath().equals(uri.getPath())) {
					this.page.navigate(uri.toString(), new Page.NavigateOptions()
								.setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(NAVIGATION_TIMEOUT_MS));
					this.completeSamlLogin();
				}
			} catch (final PlaywrightException e) {
				throw new IOException("Moodle browser navigation failed for " + uri, e);
			}
		}

		private void completeSamlLogin() throws IOException {
			boolean credentialsSubmitted = false;
			for (int i = 0; i < 12; i++) {
				final URI current = URI.create(this.page.url());
				if (isMoodleUri(current)) {
					final URI provider = samlProvider(this.page.content(), current);
					if (provider != null) {
						// Click the actual external-login button rather than Moodle's password form.
						this.page.locator(LOGIN_BUTTON).first().click();
						continue;
					}
					if (this.page.locator("form:has(input[type=password])").count() > 0
							|| "/login/index.php".equals(current.getPath())) {
						throw new IOException("Moodle has no MyHERS SSO button; refusing the local login form");
					}
					return;
				}
				if (isIdentityProviderLogin(current)) {
					final Locator login = this.page.locator("form:has(input[type=password])");
					if (login.count() == 0) {
						this.page.waitForURL("https://moodle.hers.be/**",
								new Page.WaitForURLOptions().setTimeout(NAVIGATION_TIMEOUT_MS)
										.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
						continue;
					}
					if (credentialsSubmitted) {
						throw new IOException("SimpleSAML returned the password form again (invalid credentials or MFA)");
					}
					final Locator form = login.first();
					final Locator user = form.locator("input[name='username'], input[name='user'], input[name='email'], input[type='email']");
					if (user.count() == 0) {
						throw new IOException("SimpleSAML login form has no username field");
					}
					user.first().fill(MoodleBrowserClient.this.username);
					final Locator pass = form.locator("input[type='password']");
					pass.first().fill(MoodleBrowserClient.this.password);
					credentialsSubmitted = true;
					final Locator submit = form.locator("button[type='submit'], input[type='submit']");
					if (submit.count() > 0) {
						submit.first().click();
					} else {
							pass.first().press("Enter");
					}
					continue;
				}
				if (trustedHost(current, IDP_HOST)) {
					// Some IdP responses auto-submit a SAML form with JavaScript.
					this.page.waitForURL("https://moodle.hers.be/**",
							new Page.WaitForURLOptions().setTimeout(NAVIGATION_TIMEOUT_MS)
									.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
					continue;
				}
				throw new IOException("Unexpected host in Moodle SAML flow: " + current.getHost());
			}
			throw new IOException("Too many Moodle SAML authentication redirects");
		}

		@Override
		public void close() {
			try {
				this.context.close();
			} finally {
				try {
					this.browser.close();
				} finally {
					this.playwright.close();
				}
			}
		}
	}

	private static String header(final APIResponse response, final String name) {
		return response.headers().entrySet().stream()
				.filter(entry -> name.equalsIgnoreCase(entry.getKey()))
				.map(Map.Entry::getValue).findFirst().orElse("");
	}

	private static void requireMoodle(final URI uri) throws IOException {
		if (!isMoodleUri(uri)) {
			throw new IOException("Refusing non-Moodle resource URI: " + uri);
		}
	}

	private static String fileName(final String disposition, final URI uri) {
		final Matcher encoded = FILENAME_STAR.matcher(disposition);
		if (encoded.find()) {
			return URLDecoder.decode(encoded.group(1).replace("\"", "").strip(), StandardCharsets.UTF_8);
		}
		final Matcher simple = FILENAME.matcher(disposition);
		if (simple.find()) {
			return (simple.group(1) != null ? simple.group(1) : simple.group(2)).strip();
		}
		final String path = uri.getPath();
		final String last = path.substring(path.lastIndexOf('/') + 1);
		return last.isBlank() || last.endsWith(".php") ? "moodle-resource-" + Integer.toHexString(uri.toString().hashCode()) + ".bin"
				: URLDecoder.decode(last, StandardCharsets.UTF_8);
	}
}
