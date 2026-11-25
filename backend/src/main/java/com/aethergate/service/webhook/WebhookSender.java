package com.aethergate.service.webhook;

import com.aethergate.config.AetherGateProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/** One signed HTTPS POST. Redirects are never followed (a 3xx could point at an internal address). */
@Component
public class WebhookSender {

    public record Result(boolean success, Integer statusCode, String error) {}

    private final HttpClient http;
    private final WebhookUrlPolicy policy;
    private final Duration timeout;
    private final Clock clock;

    public WebhookSender(WebhookUrlPolicy policy, AetherGateProperties properties, Clock clock) {
        this.policy = policy;
        this.timeout = properties.webhooks().timeout();
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public Result send(String url, String secret, String deliveryId, String eventId, String eventType, String body) {
        try {
            policy.checkResolved(url);
            long ts = clock.instant().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "AetherGate-Webhooks/1.0")
                    .header("X-AetherGate-Event", eventType)
                    .header("X-AetherGate-Event-Id", eventId)
                    .header("X-AetherGate-Delivery", deliveryId)
                    .header(WebhookSignature.HEADER, WebhookSignature.header(secret, ts, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 300 ? new Result(true, code, null)
                    : new Result(false, code, "HTTP " + code + (code >= 300 && code < 400 ? " (redirects are not followed)" : ""));
        } catch (WebhookBlockedException e) {
            return new Result(false, null, e.getMessage());
        } catch (HttpTimeoutException e) {
            return new Result(false, null, "Timed out after " + timeout.toSeconds() + " s");
        } catch (IOException | IllegalArgumentException e) {
            return new Result(false, null, truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "Interrupted");
        }
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
