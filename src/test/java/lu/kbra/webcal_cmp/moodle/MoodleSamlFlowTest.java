package lu.kbra.webcal_cmp.moodle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;

class MoodleSamlFlowTest {

	private static final URI MOODLE_LOGIN = URI.create("https://moodle.hers.be/login/index.php");
	private static final URI IDP_LOGIN = URI.create(
			"https://idp.hers.be/simplesaml/module.php/core/loginuserpass?AuthState=secret");
	private static final URI IDP_SSO = URI.create("https://idp.hers.be/simplesaml/saml2/idp/SSOService.php");

	@Test
	void choosesExternalProviderEvenWhenMoodleHasLocalLoginForm() throws IOException {
		final String html = """
				<form><input name="username"><input type="password"></form>
				<div class="login-identityproviders">
				  <a class="btn login-identityprovider-btn" href="https://moodle.hers.be/auth/saml2/login.php?wants&amp;idp=be345f41faf5505674d40c49b2544600&amp;passive=off">
				    Se connecter avec MyHERS
				  </a>
				</div>
				""";
		assertEquals(MoodleBrowserClient.SsoPage.MOODLE_PROVIDER,
				MoodleBrowserClient.ssoPage(MOODLE_LOGIN, html));
	}

	@Test
	void doesNotEnterCredentialsIntoMoodleLocalLogin() throws IOException {
		assertEquals(MoodleBrowserClient.SsoPage.LOCAL_LOGIN,
				MoodleBrowserClient.ssoPage(MOODLE_LOGIN, "<form><input type='password'></form>"));
		assertEquals(MoodleBrowserClient.SsoPage.IDP_CREDENTIALS,
				MoodleBrowserClient.ssoPage(IDP_LOGIN, "<form><input name='username'><input type='password'></form>"));
		assertThrows(IOException.class, () -> MoodleBrowserClient.ssoPage(IDP_SSO,
				"<form><input type='password'></form>"));
	}

	@Test
	void refusesCredentialFormPostingOutsideHersIdentityProvider() throws IOException {
		final String html = """
				<form method="post" action="/simplesaml/module.php/core/loginuserpass?AuthState=secret">
				  <input name="username"><input type="password" name="password">
				</form>
				""";
		assertEquals(URI.create("https://idp.hers.be/simplesaml/module.php/core/loginuserpass?AuthState=secret"),
				MoodleBrowserClient.credentialFormTarget(html, IDP_LOGIN));
		assertThrows(IOException.class, () -> MoodleBrowserClient.credentialFormTarget(
				html.replace("/simplesaml/module.php/core/loginuserpass?AuthState=secret", "https://example.org/collect"),
				IDP_LOGIN));
	}

	@Test
	void recognizesIdpIntermediatePageInsteadOfAssumingImmediateMoodleRedirect() throws IOException {
		assertEquals(MoodleBrowserClient.SsoPage.PENDING,
				MoodleBrowserClient.ssoPage(IDP_SSO, "<html><body>Connecting...</body></html>"));
	}

	@Test
	void recognizesAutoPostedSamlResponseAndChecksItsDestination() throws IOException {
		final String html = """
				<form method="post" action="https://moodle.hers.be/auth/saml2/sp/acs.php">
				  <input type="hidden" name="SAMLResponse" value="opaque-assertion">
				  <input type="hidden" name="RelayState" value="opaque-state">
				</form>
				<script>document.forms[0].submit()</script>
				""";
		assertEquals(MoodleBrowserClient.SsoPage.SAML_FORM,
				MoodleBrowserClient.ssoPage(IDP_SSO, html));
		assertEquals(URI.create("https://moodle.hers.be/auth/saml2/sp/acs.php"),
				MoodleBrowserClient.samlFormTarget(html, IDP_SSO));
		assertThrows(IOException.class, () -> MoodleBrowserClient.samlFormTarget(
				html.replace("https://moodle.hers.be", "https://example.org"), IDP_SSO));
		assertThrows(IOException.class, () -> MoodleBrowserClient.samlFormTarget(
				html.replace("method=\"post\"", "method=\"get\""), IDP_SSO));
	}

	@Test
	void recognizesTrustedSamlRedirectAndRefusesExternalRefresh() throws IOException {
		final String html = "<meta http-equiv='refresh' content=\"0;url='https://moodle.hers.be/my/courses.php'\">";
		assertEquals(MoodleBrowserClient.SsoPage.META_REFRESH,
				MoodleBrowserClient.ssoPage(IDP_SSO, html));
		assertEquals(URI.create("https://moodle.hers.be/my/courses.php"),
				MoodleBrowserClient.samlRefreshTarget(html, IDP_SSO));
		assertThrows(IOException.class, () -> MoodleBrowserClient.samlRefreshTarget(
				html.replace("moodle.hers.be", "other.example"), IDP_SSO));
	}

	@Test
	void coursePageIsAuthenticatedAndSamlCallbackIsNotYetFinal() throws IOException {
		assertEquals(MoodleBrowserClient.SsoPage.AUTHENTICATED,
				MoodleBrowserClient.ssoPage(URI.create("https://moodle.hers.be/my/courses.php"),
						"<main data-region='courses-view'></main>"));
		assertEquals(MoodleBrowserClient.SsoPage.PENDING,
				MoodleBrowserClient.ssoPage(URI.create("https://moodle.hers.be/auth/saml2/sp/acs.php"),
						"<html><body>Connecting...</body></html>"));
	}
}
