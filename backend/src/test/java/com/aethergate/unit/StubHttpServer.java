package com.aethergate.unit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process HTTP server for contract tests of outbound integrations (LLM providers, Supabase Auth). */
final class StubHttpServer implements AutoCloseable {

    record Response(int status, String contentType, String body) {}

    record Recorded(String method, String path, Map<String, List<String>> headers, String body) {}

    private final HttpServer server;
    private final Map<String, List<Response>> routes = new ConcurrentHashMap<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Queue responses for "METHOD /path" (query string ignored); the last one repeats. */
    StubHttpServer on(String methodAndPath, Response... responses) {
        routes.put(methodAndPath, new CopyOnWriteArrayList<>(List.of(responses)));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders(), body));
        List<Response> queue = routes.get(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
        Response r = queue == null || queue.isEmpty() ? new Response(404, "text/plain", "no route")
                : queue.size() > 1 ? queue.removeFirst() : queue.getFirst();
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", r.contentType());
        ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
