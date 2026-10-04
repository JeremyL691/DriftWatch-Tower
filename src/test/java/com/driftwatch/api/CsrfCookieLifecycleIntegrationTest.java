package com.driftwatch.api;

import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP cookies avoid MockMvc csrf() replacing the application token repository. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfCookieLifecycleIntegrationTest extends ContainerIntegrationTest {
    @LocalServerPort int port;

    @Test
    void consecutiveAuthenticatedReadsAndMutationsLeaveAUsableCookie() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertThat(send(client, "GET", "/api/v1/alerts", null).statusCode()).isEqualTo(200);
        for (int attempt = 0; attempt < 3; attempt++) {
            var token = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow();
            assertThat(token.isHttpOnly()).isFalse();
            assertThat(send(client, "POST", "/api/v1/alerts/999999/resolve", token.getValue()).statusCode())
                    .isEqualTo(404);
            assertThat(cookies.getCookieStore().getCookies()).anyMatch(c -> c.getName().equals("XSRF-TOKEN"));
            assertThat(send(client, "GET", "/api/v1/alerts", null).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void missingOrIncorrectCsrfStillRejectsAuthenticatedMutation() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertThat(send(client, "GET", "/api/v1/alerts", null).statusCode()).isEqualTo(200);
        assertThat(send(client, "POST", "/api/v1/alerts/999999/resolve", null).statusCode()).isEqualTo(403);
        assertThat(send(client, "POST", "/api/v1/alerts/999999/resolve", "invalid-token").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (adminUsername + ":" + adminPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (token != null) request.header("X-XSRF-TOKEN", token);
        if (method.equals("POST")) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
