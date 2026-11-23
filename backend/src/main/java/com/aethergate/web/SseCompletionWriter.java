package com.aethergate.web;

import com.aethergate.service.CompletionOutcome;
import com.aethergate.service.llm.ClientAbortedException;
import com.aethergate.service.llm.TokenSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ProblemDetail;

/**
 * Writes OpenAI-compatible {@code chat.completion.chunk} events, then a usage chunk, a named
 * {@code aethergate.telemetry} event (ignored by OpenAI SDKs) and {@code [DONE]}.
 * A write failure means the client is gone: it surfaces as {@link ClientAbortedException}.
 */
final class SseCompletionWriter implements TokenSink {

    private final OutputStream out;
    private final ObjectMapper json;
    private final String requestId;
    private final String model;
    private final long created = System.currentTimeMillis() / 1000;
    private boolean roleSent;

    SseCompletionWriter(OutputStream out, ObjectMapper json, String requestId, String model) {
        this.out = out;
        this.json = json;
        this.requestId = requestId;
        this.model = model;
    }

    @Override
    public void onToken(String delta) {
        ObjectNode chunk = base();
        ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
        ObjectNode d = choice.putObject("delta");
        if (!roleSent) {
            d.put("role", "assistant");
            roleSent = true;
        }
        d.put("content", delta);
        choice.putNull("finish_reason");
        write(null, chunk.toString());
    }

    void finish(CompletionOutcome outcome) {
        ObjectNode last = base();
        ObjectNode choice = last.putArray("choices").addObject().put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", outcome.finishReason());
        write(null, last.toString());

        ObjectNode usage = base();
        usage.putArray("choices");
        usage.putObject("usage")
                .put("prompt_tokens", outcome.promptTokens())
                .put("completion_tokens", outcome.completionTokens())
                .put("total_tokens", outcome.promptTokens() + outcome.completionTokens());
        write(null, usage.toString());
        write("aethergate.telemetry", toJson(outcome));
        done();
    }

    void error(ProblemDetail problem) {
        write("error", toJson(problem));
        done();
    }

    private void done() {
        write(null, "[DONE]");
    }

    private ObjectNode base() {
        return json.createObjectNode()
                .put("id", "chatcmpl-" + requestId)
                .put("object", "chat.completion.chunk")
                .put("created", created)
                .put("model", model);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String event, String data) {
        StringBuilder frame = new StringBuilder();
        if (event != null) {
            frame.append("event: ").append(event).append('\n');
        }
        frame.append("data: ").append(data).append("\n\n");
        try {
            out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ClientAbortedException(e);
        }
    }
}
