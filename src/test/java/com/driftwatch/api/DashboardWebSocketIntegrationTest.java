package com.driftwatch.api;

import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardWebSocketIntegrationTest extends ContainerIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    @Test
    void nativeUpgradeEstablishesStompWithoutBasicCredentials() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var connected = new CompletableFuture<String>();
            var socket = socket(client)
                    .header("Origin", "http://127.0.0.1:" + port)
                    .buildAsync(wsUri("?ticket=" + ticket(client)), new WebSocket.Listener() {
                        private final StringBuilder frame = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            frame.append(data);
                            if (last) {
                                connected.complete(frame.toString());
                            }
                            return WebSocket.Listener.super.onText(socket, data, last);
                        }
                    }).get(10, TimeUnit.SECONDS);
            try {
                assertThat(socket.getSubprotocol()).isEqualTo("v11.stomp");
                socket.sendText("CONNECT\naccept-version:1.1\nheart-beat:0,0\n\n\0", true)
                        .get(10, TimeUnit.SECONDS);
                assertThat(connected.get(10, TimeUnit.SECONDS)).startsWith("CONNECTED\n");
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void nativeUpgradeRejectsMissingOrInvalidTicketsAndCrossOriginRequests() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertForbidden(socket(client), wsUri(""));
            assertForbidden(socket(client), wsUri("?ticket=invalid"));
            assertForbidden(socket(client).header("Origin", "https://other.example"),
                    wsUri("?ticket=" + ticket(client)));
        }
    }

    private String ticket(HttpClient client) throws Exception {
        var credentials = Base64.getEncoder().encodeToString(
                (adminUsername + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/dashboard/api/ws-ticket"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).required("ticket").asText();
    }

    private WebSocket.Builder socket(HttpClient client) {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).subprotocols("v11.stomp");
    }

    private URI wsUri(String query) {
        return URI.create("ws://127.0.0.1:" + port + "/ws" + query);
    }

    private void assertForbidden(WebSocket.Builder builder, URI uri) {
        var failure = assertThrows(ExecutionException.class, () -> builder
                .buildAsync(uri, new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS));
        assertThat(failure.getCause()).isInstanceOfSatisfying(WebSocketHandshakeException.class,
                handshake -> assertThat(handshake.getResponse().statusCode()).isEqualTo(403));
    }
}
