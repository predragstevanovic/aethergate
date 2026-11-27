package com.aethergate.web;

import com.aethergate.service.CompletionCommand;
import com.aethergate.service.GatewayExecution;
import com.aethergate.service.GatewayOrchestratorService;
import com.aethergate.service.RequestAttribution;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.TemplateRef;
import com.aethergate.service.prompt.PromptService;
import com.aethergate.service.prompt.PromptService.Rendered;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.KeyPolicyViolationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
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
    private final PromptService prompts;

    public GatewayController(GatewayOrchestratorService orchestrator, CompletionResponder responder, TenantAdminService admin,
                             PromptService prompts) {
        this.orchestrator = orchestrator;
        this.responder = responder;
        this.admin = admin;
        this.prompts = prompts;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<StreamingResponseBody> chatCompletions(@AuthenticationPrincipal GatewayPrincipal principal,
                                             @Valid @RequestBody ChatCompletionRequest request,
                                             @RequestHeader(value = "X-Simulate-Provider-Failure", required = false) String simulate,
                                             @RequestHeader(value = "X-AetherGate-User", required = false) String userHeader,
                                             @RequestHeader(value = "X-AetherGate-Session", required = false) String sessionHeader,
                                             @RequestHeader(value = "X-AetherGate-Tags", required = false) String tagsHeader,
                                             HttpServletRequest http) {
        requireAllowedNetwork(principal, http);
        List<ChatMessage> messages = new ArrayList<>();
        TemplateRef template = null;
        if (request.promptTemplate() != null) { // AetherGate extension: a stored, versioned prompt
            Rendered r = TenantContext.callAs(principal.tenantId(), () -> prompts.render(request.promptTemplate().id(),
                    request.promptTemplate().version(), request.promptTemplate().variables()));
            messages.addAll(r.messages());
            template = r.ref();
        }
        if (request.messages() != null) {
            request.messages().forEach(m -> messages.add(new ChatMessage(m.role(), m.content())));
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Provide messages or a prompt_template");
        }
        RequestAttribution attribution = RequestAttribution.resolve(userHeader, sessionHeader, tagsHeader, request.user(),
                request.metadata());
        GatewayExecution exec = orchestrator.prepare(new CompletionCommand(principal, request.model(), messages,
                request.maxTokens(), "true".equalsIgnoreCase(simulate), template, attribution));
        String model = request.model() == null || request.model().isBlank() ? "auto" : request.model();
        return request.streaming() ? responder.stream(exec, model) : responder.complete(exec, model);
    }

    @GetMapping("/models")
    public Map<String, Object> models(@AuthenticationPrincipal GatewayPrincipal principal, HttpServletRequest http) {
        requireAllowedNetwork(principal, http);
        List<RouteTarget> chain = TenantContext.callAs(principal.tenantId(), admin::routeChain).stream()
                .filter(t -> principal.policy().allowsModel(t.model())).toList();
        List<Map<String, Object>> data = chain.stream()
                .map(t -> Map.<String, Object>of("id", t.model(), "object", "model", "owned_by", t.provider().name(),
                        "priority", t.priority()))
                .toList();
        return Map.of("object", "list", "data", data);
    }

    /** Behind Caddy, the client address comes from X-Forwarded-For (Caddy overwrites any client-sent value). */
    private static void requireAllowedNetwork(GatewayPrincipal principal, HttpServletRequest http) {
        if (!principal.policy().allowsIp(http.getRemoteAddr())) {
            throw new KeyPolicyViolationException("ip-not-allowed",
                    "This API key may not be used from " + http.getRemoteAddr() + " (IP allow-list)");
        }
    }
}
