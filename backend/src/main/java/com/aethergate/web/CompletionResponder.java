package com.aethergate.web;

import com.aethergate.service.CompletionOutcome;
import com.aethergate.service.GatewayExecution;
import com.aethergate.service.llm.AllProvidersFailedException;
import com.aethergate.service.llm.StreamInterruptedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Turns an admitted {@link GatewayExecution} into an SSE stream or a single JSON completion. */
@Component
public class CompletionResponder {

    private static final Logger log = LoggerFactory.getLogger(CompletionResponder.class);

    private final ObjectMapper json;

    public CompletionResponder(ObjectMapper json) {
        this.json = json;
    }

    public ResponseEntity<StreamingResponseBody> stream(GatewayExecution exec, String model) {
        StreamingResponseBody body = out -> {
            SseCompletionWriter writer = new SseCompletionWriter(out, json, exec.requestId(), model);
            try {
                CompletionOutcome outcome = exec.run(writer);
                writer.finish(outcome);
            } catch (StreamInterruptedException e) {
                if (!e.isClientAborted()) {
                    writer.error(problem(HttpStatus.BAD_GATEWAY, "upstream-interrupted", e.getMessage()));
                }
            } catch (AllProvidersFailedException e) {
                writer.error(problem(HttpStatus.SERVICE_UNAVAILABLE, "providers-unavailable", e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Stream {} failed", exec.requestId(), e);
                safeError(writer, problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected gateway error"));
            }
        };
        return ResponseEntity.ok()
                .headers(headers(exec))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    /**
     * Non-streaming: the completion runs to the end here (so errors still map to proper status
     * codes), then the finished JSON is written out.
     */
    public ResponseEntity<StreamingResponseBody> complete(GatewayExecution exec, String model) {
        CompletionOutcome outcome = exec.run(delta -> { });
        ObjectNode body = json.createObjectNode()
                .put("id", "chatcmpl-" + outcome.requestId())
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", model);
        ObjectNode choice = body.putArray("choices").addObject().put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", outcome.text());
        choice.put("finish_reason", outcome.finishReason());
        body.putObject("usage")
                .put("prompt_tokens", outcome.promptTokens())
                .put("completion_tokens", outcome.completionTokens())
                .put("total_tokens", outcome.promptTokens() + outcome.completionTokens());
        ObjectNode telemetry = json.valueToTree(outcome);
        telemetry.remove("text");
        body.set("aethergate", telemetry);
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok().headers(headers(exec)).contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytes.length)
                .body(out -> out.write(bytes));
    }

    private static HttpHeaders headers(GatewayExecution exec) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Request-Id", exec.requestId());
        h.add("X-Cache", exec.cacheHit() ? "HIT" : "MISS");
        h.add("X-RateLimit-Limit-Requests", String.valueOf(exec.rateLimitUsage().rpmLimit()));
        h.add("X-RateLimit-Remaining-Requests", String.valueOf(exec.rateLimitUsage().rpmRemaining()));
        h.add("X-RateLimit-Limit-Tokens", String.valueOf(exec.rateLimitUsage().tpmLimit()));
        h.add("X-RateLimit-Remaining-Tokens", String.valueOf(exec.rateLimitUsage().tpmRemaining()));
        return h;
    }

    static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://aethergate.dev/problems/" + type));
        p.setTitle(status.getReasonPhrase());
        return p;
    }

    private static void safeError(SseCompletionWriter writer, ProblemDetail problem) {
        try {
            writer.error(problem);
        } catch (RuntimeException ignored) {
            // client already gone
        }
    }
}
