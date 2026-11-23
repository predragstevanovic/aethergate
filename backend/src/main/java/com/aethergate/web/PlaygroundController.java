package com.aethergate.web;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.service.CompletionCommand;
import com.aethergate.service.GatewayExecution;
import com.aethergate.service.GatewayOrchestratorService;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.TenantRef;
import com.aethergate.web.dto.PlaygroundRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The console playground runs the exact gateway pipeline (rate limit, cache, reserve, route,
 * settle) as the signed-in tenant, without ever exposing a raw API key to the browser.
 */
@RestController
@RequestMapping("/api/v1/playground")
public class PlaygroundController {

    private final CurrentTenant currentTenant;
    private final GatewayOrchestratorService orchestrator;
    private final CompletionResponder responder;
    private final AetherGateProperties.RateLimit limits;

    public PlaygroundController(CurrentTenant currentTenant, GatewayOrchestratorService orchestrator,
                                CompletionResponder responder, AetherGateProperties properties) {
        this.currentTenant = currentTenant;
        this.orchestrator = orchestrator;
        this.responder = responder;
        this.limits = properties.rateLimit();
    }

    @PostMapping("/completions")
    public ResponseEntity<StreamingResponseBody> complete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaygroundRequest body) {
        TenantRef tenant = currentTenant.of(jwt);
        GatewayPrincipal principal = GatewayPrincipal.forPlayground(tenant.id(), tenant.name(), limits.playgroundRpm(), limits.playgroundTpm());
        List<ChatMessage> messages = new ArrayList<>();
        if (body.system() != null && !body.system().isBlank()) {
            messages.add(new ChatMessage("system", body.system()));
        }
        messages.add(new ChatMessage("user", body.prompt()));
        GatewayExecution exec = orchestrator.prepare(new CompletionCommand(principal, "auto", messages, body.maxTokens(),
                body.simulateFailure()));
        return responder.stream(exec, "auto");
    }
}
