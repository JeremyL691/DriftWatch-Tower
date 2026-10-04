package com.driftwatch.source.github;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local HTTP stub for the GitHub events protocol (execution guide, section 6.4). Scripted
 * responses are queued per request; the last scripted response repeats so a test can observe how
 * the poller behaves on consecutive rounds. Only exception paths are exercised here — the real
 * source is used by the acceptance smoke, never by these tests.
 */
final class GithubStubServer {

    record ScriptedResponse(int status, Map<String, String> headers, String body, long delayMillis) {
        static ScriptedResponse of(int status, String body) {
            return new ScriptedResponse(status, Map.of(), body, 0L);
        }

        static ScriptedResponse withHeaders(int status, Map<String, String> headers, String body) {
            return new ScriptedResponse(status, headers, body, 0L);
        }
    }

    private final HttpServer server;
    private final Deque<ScriptedResponse> scripted = new ArrayDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile ScriptedResponse last;

    GithubStubServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            ScriptedResponse response = next();
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] payload = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
            exchange.close();
        });
        server.start();
    }

    private ScriptedResponse next() {
        synchronized (scripted) {
            ScriptedResponse response = scripted.poll();
            if (response != null) {
                last = response;
            }
            return last == null ? ScriptedResponse.of(200, "[]") : response == null ? last : response;
        }
    }

    void script(ScriptedResponse... responses) {
        synchronized (scripted) {
            for (ScriptedResponse response : responses) {
                scripted.add(response);
            }
        }
    }

    void reset() {
        synchronized (scripted) {
            scripted.clear();
            last = null;
        }
        requests.set(0);
    }

    int requestCount() {
        return requests.get();
    }

    void stop() {
        server.stop(0);
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * Minimal upstream event JSON in the shape GitHub's API actually returns: the type-specific
     * fields live inside {@code payload}, and only id/type/created_at/repo/actor/public are
     * top-level. An earlier fixture put {@code action} at the top level, which matched a converter
     * bug and hid the fact that real events yielded null for every type-specific field.
     */
    static String event(String id, String type, String createdAt, String action) {
        String actionField = action == null ? "" : ",\"action\":\"" + action + "\"";
        return """
                {"id":"%s","type":"%s","created_at":"%s",
                 "payload":{"push_id":9001,"ref":"refs/heads/trunk"%s},
                 "repo":{"id":123,"name":"apache/kafka"},
                 "actor":{"id":4242},
                 "public":true}
                """.formatted(id, type, createdAt, actionField);
    }

    static String array(String... events) {
        return "[" + String.join(",", events) + "]";
    }
}
