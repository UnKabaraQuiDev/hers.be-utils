package lu.kbra.webcal_cmp.dwn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class DwnPortalClientTest {

    @Test
    void fetchesAjaxCourseIndexWithTheExistingSessionCookies() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicReference<String> cookie = new AtomicReference<>();
        final AtomicReference<String> jqueryRequest = new AtomicReference<>();
        final AtomicReference<String> jqueryContext = new AtomicReference<>();
        final AtomicReference<String> requestedWith = new AtomicReference<>();
        final AtomicReference<String> referer = new AtomicReference<>();
        final AtomicReference<String> nocache = new AtomicReference<>();
        server.createContext("/FW/index.do", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=sample-session; Path=/FW; HttpOnly");
            final byte[] body = "<html><body>Portal index</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.createContext("/FW/indexCours", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            jqueryRequest.set(exchange.getRequestHeaders().getFirst("JQUERY_REQUEST"));
            jqueryContext.set(exchange.getRequestHeaders().getFirst("JQUERY_CONTEXT"));
            requestedWith.set(exchange.getRequestHeaders().getFirst("X-Requested-With"));
            referer.set(exchange.getRequestHeaders().getFirst("Referer"));
            nocache.set(exchange.getRequestURI().getRawQuery());
            final byte[] body = "<div class='fwAccueilCoursEtCours'>course-list</div>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        try {
            final String index = "http://127.0.0.1:" + server.getAddress().getPort() + "/FW/index.do";
            final DwnPortalClient client = new DwnPortalClient(index, "sample-user", "sample-password", 90, 1);
            client.openPortal();
            assertTrue(client.getCoursesPage().html().contains("course-list"));
            assertTrue(cookie.get().contains("JSESSIONID=sample-session"));
            assertEquals("TRUE", jqueryRequest.get());
            assertEquals("/FW", jqueryContext.get());
            assertEquals("XMLHttpRequest", requestedWith.get());
            assertEquals(index, referer.get());
            assertTrue(nocache.get().startsWith("nocache="));
            assertTrue(nocache.get().length() > "nocache=".length());
        } finally {
            server.stop(0);
        }
    }
}
