package lu.kbra.webcal_cmp.dwn;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
	private final URI index;
	private final String username;
	private final String password;
	private final int downloadTimeoutSeconds;
	private final int retries;

	public DwnPortalClient(
			@Value("${dwn.portal.index:}") final String index,
			@Value("${dwn.portal.username:}") final String username,
			@Value("${dwn.portal.password:}") final String password,
			@Value("${dwn.download-timeout-seconds:900}") final int downloadTimeoutSeconds,
			@Value("${dwn.retries:3}") final int retries) {
		this.index = index == null || index.isBlank() ? null : URI.create(index);
		this.username = username;
		this.password = password;
		this.downloadTimeoutSeconds = Math.max(20, downloadTimeoutSeconds);
		this.retries = Math.max(1, retries);
		this.http = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.connectTimeout(Duration.ofSeconds(30))
				.followRedirects(HttpClient.Redirect.NORMAL)
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
