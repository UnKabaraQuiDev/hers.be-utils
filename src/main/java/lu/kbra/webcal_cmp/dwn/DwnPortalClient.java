package lu.kbra.webcal_cmp.dwn;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Profile("files-hers")
@Service
public class DwnPortalClient {

	public record Page(URI uri, String html) {
	}

	private final HttpClient http;
	private final HttpClient noRedirectHttp;
	private final URI index;
	private final String username;
	private final String password;
	private final int downloadTimeoutSeconds;
	private final int retries;

	public DwnPortalClient(
			@Value("${dwn.myhers.index}") final String index,
			@Value("${dwn.portal.username}") final String username,
			@Value("${dwn.portal.password}") final String password,
			@Value("${dwn.download-timeout-seconds}") final int downloadTimeoutSeconds,
			@Value("${dwn.retries}") final int retries) {
		this.index = index == null || index.isBlank() ? null : URI.create(index);
		this.username = username;
		this.password = password;
		this.downloadTimeoutSeconds = Math.max(20, downloadTimeoutSeconds);
		this.retries = Math.max(1, retries);
		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		this.http = HttpClient.newBuilder()
				.cookieHandler(cookies)
				.connectTimeout(Duration.ofSeconds(30))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		this.noRedirectHttp = HttpClient.newBuilder()
				.cookieHandler(cookies)
				.connectTimeout(Duration.ofSeconds(30))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public boolean configured() {
		return this.index != null && !this.username.isBlank() && !this.password.isBlank();
	}

	public Page openPortal() throws IOException, InterruptedException {
		if (!this.configured()) {
			throw new IOException("DWN_PORTAL_INDEX/USERNAME/PASSWORD must be configured");
		}
		final Page page = this.authenticateIfRequired(this.get(this.index));
		if (DwnPortalClient.hasLoginForm(page.html())) {
			throw new IOException("Portal still shows the login form (wrong credentials, MFA or login flow changed)");
		}
		return page;
	}

	public Page getCoursesPage() throws IOException, InterruptedException {
		if (!this.configured()) {
			throw new IOException("DWN_PORTAL_INDEX/USERNAME/PASSWORD must be configured");
		}
		final URI url = this.index.resolve("indexCours?nocache=" + UUID.randomUUID());
		Page page = this.authenticateIfRequired(this.getCourseIndex(url));
		// An expired session may redirect the AJAX request through SSO and end on
		// index.do. After authenticating, explicitly retry the original endpoint.
		if (!url.getPath().equals(page.uri().getPath()) && DwnPortalClient.isHersHost(page.uri())) {
			page = this.authenticateIfRequired(this.getCourseIndex(url));
		}
		if (DwnPortalClient.hasLoginForm(page.html())) {
			throw new IOException("Course list still shows an SSO login form");
		}
		return page;
	}

	private Page getCourseIndex(final URI url) throws IOException, InterruptedException {
		final String context = this.index.getPath().substring(0, this.index.getPath().lastIndexOf('/'));
		final HttpRequest request = this.request(url)
				.setHeader("Accept", "*/*")
				.header("JQUERY_REQUEST", "TRUE")
				.header("JQUERY_CONTEXT", context)
				.header("X-Requested-With", "XMLHttpRequest")
				.header("Referer", this.index.toString())
				.header("Cache-Control", "no-cache")
				.header("Pragma", "no-cache")
				.GET()
				.timeout(Duration.ofSeconds(90))
				.build();
		final HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("HTTP " + response.statusCode() + " loading course index " + url.getPath());
		}
		return new Page(response.uri(), response.body());
	}

	public Page getPage(final URI uri) throws IOException, InterruptedException {
		DwnPortalClient.requirePortalUri(uri);
		final Page page = this.authenticateIfRequired(this.get(uri));
		if (!uri.getPath().equals(page.uri().getPath()) && DwnPortalClient.isHersHost(page.uri())) {
			return this.authenticateIfRequired(this.get(uri));
		}
		return page;
	}

	private Page get(final URI uri) throws IOException, InterruptedException {
		final HttpRequest request = this.request(uri).GET().timeout(Duration.ofSeconds(90)).build();
		final HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("HTTP " + response.statusCode() + " opening " + uri.getPath());
		}
		return new Page(response.uri(), response.body());
	}

	private Page authenticateIfRequired(Page page) throws IOException, InterruptedException {
		boolean submittedCredentials = false;
		for (int i = 0; i < 12; i++) {
			Files.writeString(Paths.get("" + System.currentTimeMillis() + ".html"), page.html());
			final Document doc = Jsoup.parse(page.html(), page.uri().toString());
			final Element login = doc.selectFirst("form:has(input[type=password])");
			if (login != null) {
				if (submittedCredentials) {
					throw new IOException("Login form returned after submitting credentials (invalid credentials or MFA)");
				}
				DwnPortalClient.requireTrustedLoginUri(page.uri());
				page = this.submitForm(login, page.uri(), true);
				submittedCredentials = true;
				continue;
			}
			final Element saml = doc.selectFirst("form:has(input[name=SAMLResponse]), form:has(input[name=SAMLRequest])");
			if (saml != null) {
				page = this.submitForm(saml, page.uri(), false);
				continue;
			}
			final Element meta = doc.selectFirst("meta[http-equiv=refresh][content]");
			if (meta != null) {
				final String content = meta.attr("content");
				final int at = content.toLowerCase().indexOf("url=");
				if (at >= 0) {
					final URI target = page.uri().resolve(content.substring(at + 4).replace("\"", "").replace("'", "").strip());
					if (!DwnPortalClient.isHersHost(target)) {
						throw new IOException("Refusing unexpected login redirect host");
					}
					page = this.get(target);
					continue;
				}
			}
			return page;
		}
		throw new IOException("Too many SimpleSAMLphp login transitions");
	}

	private Page submitForm(final Element form, final URI current, final boolean credentials) throws IOException, InterruptedException {
		final String action = form.hasAttr("action") && !form.attr("action").isBlank() ? form.attr("action") : current.toString();
		final URI target = current.resolve(action.replace("&amp;", "&"));
		if (credentials) {
			DwnPortalClient.requireTrustedLoginUri(target);
		} else if (!DwnPortalClient.isHersHost(target)) {
			throw new IOException("Unexpected SAML form target host: " + target.getHost());
		}
		final Map<String, String> inputs = new LinkedHashMap<>();
		boolean userFound = false;
		boolean passwordFound = false;
		for (final Element input : form.select("input[name]")) {
			final String name = input.attr("name");
			if (name.isBlank() || "checkbox".equalsIgnoreCase(input.attr("type")) && !input.hasAttr("checked")) {
				continue;
			}
			String value = input.attr("value");
			if (credentials) {
				final String type = input.attr("type").toLowerCase();
				if ("password".equals(type) || name.matches("(?i).*(password|passwd|passcode).*")) {
					value = this.password;
					passwordFound = true;
				} else if ("email".equals(type) || name.matches("(?i)(username|user|email|login|j_username|user_id)")) {
					value = this.username;
					userFound = true;
				}
			}
			inputs.put(name, value);
		}
		if (credentials) {
			if (!userFound) {
				inputs.put("username", this.username);
			}
			if (!passwordFound) {
				inputs.put("password", this.password);
			}
		}
		final StringBuilder formBody = new StringBuilder();
		inputs.forEach((key, value) -> {
			if (!formBody.isEmpty()) {
				formBody.append('&');
			}
			formBody.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
		});
		final boolean getForm = "get".equalsIgnoreCase(form.attr("method"));
		final HttpRequest request = getForm
				? this.request(URI.create(target + (target.getRawQuery() == null ? "?" : "&") + formBody))
						.GET()
						.timeout(Duration.ofSeconds(90))
						.build()
				: this.request(target)
						.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(HttpRequest.BodyPublishers.ofString(formBody.toString()))
						.timeout(Duration.ofSeconds(90))
						.build();
		final HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("SSO form returned HTTP " + response.statusCode());
		}
		return new Page(response.uri(), response.body());
	}

	/** Follow the HERS-hosted Moodle SAML button, then reopen the requested page. */
	public Page getMoodlePage(final URI uri) throws IOException, InterruptedException {
		DwnPortalClient.requireMoodleUri(uri);
		Page page = this.authenticateIfRequired(this.get(uri));
		for (int step = 0; step < 3; step++) {
			final Document doc = Jsoup.parse(page.html(), page.uri().toString());
			final Element button = doc.selectFirst("a[href*='/auth/saml2/login.php']");
			final Element form = doc.selectFirst("form[action*='/auth/saml2/login.php']");
			if (button == null && form == null) {
				break;
			}
			if (button != null) {
				final URI saml = page.uri().resolve(button.attr("href"));
				DwnPortalClient.requireMoodleUri(saml);
				this.authenticateIfRequired(this.get(saml));
			} else {
				this.authenticateIfRequired(this.submitForm(form, page.uri(), false));
			}
			page = this.authenticateIfRequired(this.get(uri));
		}
		if (!DwnPortalClient.isMoodleHost(page.uri()) || DwnPortalClient.hasLoginForm(page.html()) || Jsoup.parse(page.html())
				.selectFirst("a[href*='/auth/saml2/login.php'], form[action*='/auth/saml2/login.php']") != null) {
			throw new IOException("Moodle SAML login did not complete: " + page.uri().getPath());
		}
		return page;
	}

	public record MoodleDownload(Path path, String filename) {
	}

	/** Resolve an authenticated Moodle resource to its actual pluginfile attachment. */
	public MoodleDownload downloadMoodleResource(final URI uri) throws IOException, InterruptedException {
		DwnPortalClient.requireMoodleUri(uri);
		IOException last = null;
		for (int i = 1; i <= this.retries; i++) {
			try {
				return this.downloadMoodleResource(uri, 0);
			} catch (final IOException e) {
				last = e;
				DwnPortalClient.log.warn("Moodle download {} failed (attempt {}/{}): {}", uri.getPath(), i, this.retries, e.getMessage());
				if (i < this.retries) {
					TimeUnit.SECONDS.sleep(Math.min(15, i * 3));
				}
			}
		}
		throw last == null ? new IOException("Could not download Moodle resource") : last;
	}

	private MoodleDownload downloadMoodleResource(final URI uri, final int depth) throws IOException, InterruptedException {
		if (depth > 4) {
			throw new IOException("Too many Moodle resource redirects/previews");
		}
		final Path temp = Files.createTempFile("moodle-resource-", ".download");
		try {
			final HttpRequest request = this.request(uri).GET().timeout(Duration.ofSeconds(this.downloadTimeoutSeconds)).build();
			final HttpResponse<Path> response = this.http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Moodle resource returned HTTP " + response.statusCode());
			}
			DwnPortalClient.requireMoodleUri(response.uri());
			if (Files.size(temp) == 0) {
				throw new IOException("Empty Moodle resource response");
			}
			final long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
			if (expected >= 0 && expected != Files.size(temp)) {
				throw new IOException("Incomplete Moodle resource download");
			}
			final String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
			final String disposition = response.headers().firstValue("Content-Disposition").orElse("");
			final String name = DwnPortalClient.filename(disposition, response.uri());
			if (type.contains("text/html") && !disposition.toLowerCase(java.util.Locale.ROOT).contains("attachment")) {
				if (Files.size(temp) > 5_000_000) {
					throw new IOException("Unexpected large HTML Moodle preview");
				}
				final String html = Files.readString(temp, StandardCharsets.UTF_8);
				final Document doc = Jsoup.parse(html, response.uri().toString());
				if (DwnPortalClient.hasLoginForm(html)
						|| doc.selectFirst("a[href*='/auth/saml2/login.php'], form[action*='/auth/saml2/login.php']") != null) {
					throw new IOException("Moodle session expired while downloading a resource");
				}
				final Element file = doc.selectFirst("""
						#region-main iframe[src*='pluginfile.php'], \
						#region-main object[data*='pluginfile.php'], #region-main a[href*='pluginfile.php'], \
						.resourceworkaround a[href], #region-main a[href*='/mod/resource/download.php']""");
				if (file != null) {
					final String href = file.hasAttr("href") ? file.attr("href")
							: file.hasAttr("src") ? file.attr("src")
							: file.attr("data");
					final URI target = response.uri().resolve(href.replace("&amp;", "&"));
					DwnPortalClient.requireMoodleUri(target);
					try {
						return this.downloadMoodleResource(target, depth + 1);
					} finally {
						Files.deleteIfExists(temp);
					}
				}
				throw new IOException("Moodle resource returned HTML without a downloadable attachment: " + uri);
			}
			return new MoodleDownload(temp, name);
		} catch (final IOException | InterruptedException | RuntimeException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
	}

	/** Obtain the external target of a Moodle URL activity without following it. */
	public URI getMoodleExternalUrl(final URI uri) throws IOException, InterruptedException {
		DwnPortalClient.requireMoodleUri(uri);
		URI current = uri;
		for (int redirects = 0; redirects < 5; redirects++) {
			final HttpRequest request = this.request(current).GET().timeout(Duration.ofSeconds(90)).build();
			final HttpResponse<String> response = this.noRedirectHttp.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 300 || response.statusCode() >= 400) {
				break;
			}
			final String location = response.headers()
					.firstValue("Location")
					.orElseThrow(() -> new IOException("Moodle URL redirect has no Location"));
			final URI target = current.resolve(location);
			if (!"http".equalsIgnoreCase(target.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
				throw new IOException("Unsupported Moodle URL redirect scheme");
			}
			if (!DwnPortalClient.isMoodleHost(target)) {
				if (DwnPortalClient.isHersHost(target)) {
					throw new IOException("Moodle URL activity redirected to HERS login instead of a course link");
				}
				return target;
			}
			current = target;
		}
		final Page page = this.getMoodlePage(uri);
		final Document doc = Jsoup.parse(page.html(), page.uri().toString());
		for (final Element anchor : doc.select("#region-main .urlworkaround a[href], #region-main div[role=main] a[href]")) {
			final URI target = page.uri().resolve(anchor.attr("href"));
			if (("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme())) && !target.equals(uri)) {
				return target;
			}
		}
		throw new IOException("No external target found for Moodle URL activity: " + uri);
	}

	private static String filename(final String disposition, final URI uri) {
		final Matcher utf8 = Pattern.compile("(?i)filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)").matcher(disposition);
		if (utf8.find()) {
			return URLDecoder.decode(utf8.group(1).replace("\"", "").strip(), StandardCharsets.UTF_8);
		}
		final Matcher normal = Pattern.compile("(?i)filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))").matcher(disposition);
		if (normal.find()) {
			return (normal.group(1) != null ? normal.group(1) : normal.group(2)).strip();
		}
		final String path = uri.getPath();
		final String segment = path.substring(path.lastIndexOf('/') + 1);
		return segment.isBlank() || segment.endsWith(".php") ? "resource-" + Integer.toHexString(uri.toString().hashCode()) + ".bin"
				: URLDecoder.decode(segment, StandardCharsets.UTF_8);
	}

	private static boolean isMoodleHost(final URI uri) {
		return "https".equalsIgnoreCase(uri.getScheme()) && "moodle.hers.be".equalsIgnoreCase(uri.getHost());
	}

	private static void requireMoodleUri(final URI uri) throws IOException {
		if (!DwnPortalClient.isMoodleHost(uri)) {
			throw new IOException("Refusing non-Moodle URL: " + uri);
		}
	}

	private static boolean hasLoginForm(final String html) {
		return Jsoup.parse(html).selectFirst("form:has(input[type=password])") != null;
	}

	private static boolean isHersHost(final URI uri) {
		final String host = uri.getHost();
		return "https".equalsIgnoreCase(uri.getScheme()) && host != null
				&& ("hers.be".equalsIgnoreCase(host) || host.toLowerCase().endsWith(".hers.be"));
	}

	private static void requireTrustedLoginUri(final URI uri) throws IOException {
		if (!DwnPortalClient.isHersHost(uri)) {
			throw new IOException("Refusing to submit SSO credentials outside HTTPS hers.be: " + uri.getHost());
		}
	}

	private static void requirePortalUri(final URI uri) throws IOException {
		if (!DwnPortalClient.isHersHost(uri)) {
			throw new IOException("Refusing untrusted course resource page: " + uri.getHost());
		}
	}

	private HttpRequest.Builder request(final URI uri) {
		return HttpRequest.newBuilder(uri)
				.header("User-Agent", "Mozilla/5.0 (compatible; HersCourseArchiver/1.0)")
				.header("Accept", "text/html,application/xhtml+xml,application/octet-stream,*/*")
				.header("Accept-Language", "fr-BE,fr;q=0.9,en;q=0.5");
	}

	public Path download(final URI uri, final String expectedName) throws IOException, InterruptedException {
		DwnPortalClient.requirePortalUri(uri);
		IOException last = null;
		for (int attempt = 1; attempt <= this.retries; attempt++) {
			final Path temp = Files.createTempFile("hers-portal-", ".download");
			try {
				final HttpRequest request = this.request(uri).GET().timeout(Duration.ofSeconds(this.downloadTimeoutSeconds)).build();
				final HttpResponse<Path> response = this.http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
				final int status = response.statusCode();
				if (status >= 200 && status < 300) {
					final String type = response.headers().firstValue("Content-Type").orElse("");
					if (type.toLowerCase().contains("text/html") || type.toLowerCase().contains("application/xhtml")) {
						final String lowerName = expectedName.toLowerCase(java.util.Locale.ROOT);
						final boolean htmlExpected = lowerName.endsWith(".html") || lowerName.endsWith(".htm")
								|| lowerName.endsWith(".xhtml");
						if (!htmlExpected || Files.size(temp) < 2_000_000 && DwnPortalClient.hasLoginForm(Files.readString(temp))) {
							throw new IOException("Expected a downloadable file, got HTML (probably session expired)");
						}
					}
					if (Files.size(temp) == 0) {
						throw new IOException("Remote download was empty");
					}
					final long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
					if (length >= 0 && Files.size(temp) != length) {
						throw new IOException("Incomplete HTTP download");
					}
					return temp;
				}
				if (status == 401 || status == 403) {
					throw new IOException("Portal refused file access (HTTP " + status + ")");
				}
				if (status != 408 && status != 429 && status < 500) {
					throw new IOException("File returned permanent HTTP " + status);
				}
				throw new IOException("Transient download HTTP " + status);
			} catch (final InterruptedException e) {
				Files.deleteIfExists(temp);
				Thread.currentThread().interrupt();
				throw e;
			} catch (final IOException e) {
				last = e;
				if (e.getMessage() != null && e.getMessage().contains("got HTML") && attempt < this.retries) {
					try {
						this.openPortal();
					} catch (final IOException authError) {
						e.addSuppressed(authError);
					}
				}
				DwnPortalClient.log.warn("Download attempt {}/{} failed for file ID {}: {}",
						attempt,
						this.retries,
						PortalHtmlParser.query(uri, "ai_idFile"),
						e.getMessage());
			}
			Files.deleteIfExists(temp);
			if (attempt < this.retries) {
				TimeUnit.SECONDS.sleep(Math.min(15, attempt * 3));
			}
		}
		throw last == null ? new IOException("Download failed") : last;
	}
}
