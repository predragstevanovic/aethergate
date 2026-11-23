package com.aethergate.web;

import com.aethergate.service.CompletionCommand;
import com.aethergate.service.GatewayExecution;
import com.aethergate.service.GatewayOrchestratorService;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.TenantAdminService;
import com.aethergate.config.TenantContext;
import com.aethergate.web.dto.ChatCompletionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** OpenAI-compatible gateway surface: point any OpenAI SDK at {@code <host>/v1} with an {@code ag_live_} key. */
@RestController
@RequestMapping("/v1")
public class GatewayController {

    private final GatewayOrchestratorService orchestrator;
    private final CompletionResponder responder;
    private final TenantAdminService admin;

    public GatewayController(GatewayOrchestratorService orchestrator, CompletionResponder responder, TenantAdminService admin) {
        this.orchestrator = orchestrator;
        this.responder = responder;
        this.admin = admin;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<StreamingResponseBody> chatCompletions(@AuthenticationPrincipal GatewayPrincipal principal,
                                             @Valid @RequestBody ChatCompletionRequest request,
                                             @RequestHeader(value = "X-Simulate-Provider-Failure", required = false) String simulate) {
        List<ChatMessage> messages = request.messages().stream().map(m -> new ChatMessage(m.role(), m.content())).toList();
        GatewayExecution exec = orchestrator.prepare(new CompletionCommand(principal, request.model(), messages,
                request.maxTokens(), "true".equalsIgnoreCase(simulate)));
        String model = request.model() == null || request.model().isBlank() ? "auto" : request.model();
        return request.streaming() ? responder.stream(exec, model) : responder.complete(exec, model);
    }

    @GetMapping("/models")
    public Map<String, Object> models(@AuthenticationPrincipal GatewayPrincipal principal) {
        List<RouteTarget> chain = TenantContext.callAs(principal.tenantId(), admin::routeChain);
        List<Map<String, Object>> data = chain.stream()
                .map(t -> Map.<String, Object>of("id", t.model(), "object", "model", "owned_by", t.provider().name(),
                        "priority", t.priority()))
                .toList();
        return Map.of("object", "list", "data", data);
    }
}
