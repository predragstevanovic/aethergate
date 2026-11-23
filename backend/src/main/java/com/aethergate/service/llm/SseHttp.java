package com.aethergate.service.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Minimal Server-Sent-Events POST helper on the JDK HTTP client (virtual-thread friendly). */
final class SseHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private SseHttp() {}

    /** Posts JSON and feeds each SSE {@code data:} line to {@code onEvent}. Non-2xx → ProviderUnavailableException. */
    static void postStream(String provider, String url, Map<String, String> headers, String jsonBody,
                           Duration timeout, Consumer<SseEvent> onEvent) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(builder::header);
        HttpResponse<Stream<String>> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new ProviderUnavailableException(provider, "connection failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(provider, "interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new ProviderUnavailableException(provider, "upstream returned HTTP " + response.statusCode());
        }
        try (Stream<String> lines = response.body()) {
            String[] event = {null};
            lines.forEach(line -> {
                if (line.startsWith("event:")) {
                    event[0] = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    onEvent.accept(new SseEvent(event[0], line.substring(5).trim()));
                } else if (line.isBlank()) {
                    event[0] = null;
                }
            });
        }
    }

    record SseEvent(String event, String data) {}
}
