package com.aethergate.web;

import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.TemplateRef;
import com.aethergate.service.project.ProjectService;
import com.aethergate.service.prompt.PromptService;
import com.aethergate.service.prompt.PromptService.Rendered;
import com.aethergate.config.TenantContext;
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
    private final ProjectService projects;
    private final PromptService prompts;

    public PlaygroundController(CurrentTenant currentTenant, GatewayOrchestratorService orchestrator,
                                CompletionResponder responder, AetherGateProperties properties, ProjectService projects,
                                PromptService prompts) {
        this.projects = projects;
        this.prompts = prompts;
        this.currentTenant = currentTenant;
        this.orchestrator = orchestrator;
        this.responder = responder;
        this.limits = properties.rateLimit();
    }

    @PostMapping("/completions")
    @RequiresRole(MemberRole.DEVELOPER)
    public ResponseEntity<StreamingResponseBody> complete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaygroundRequest body) {
        TenantRef tenant = currentTenant.of(jwt);
        if (body.projectId() != null) { // charge the run to a cost centre: it must be an active project of this tenant
            TenantContext.runAs(tenant.id(), () -> projects.ensureActive(body.projectId()));
        }
        GatewayPrincipal principal = GatewayPrincipal.forPlayground(tenant.id(), tenant.name(), limits.playgroundRpm(),
                limits.playgroundTpm(), body.projectId());
        List<ChatMessage> messages = new ArrayList<>();
        TemplateRef template = null;
        if (body.template() != null) { // a stored prompt: rendered server-side, recorded with its version
            Rendered r = TenantContext.callAs(tenant.id(), () -> prompts.render(body.template().id(), body.template().version(),
                    body.template().variables()));
            messages.addAll(r.messages());
            template = r.ref();
        } else {
            if (body.prompt() == null || body.prompt().isBlank()) {
                throw new IllegalArgumentException("Enter a prompt or choose a prompt template");
            }
            if (body.system() != null && !body.system().isBlank()) {
                messages.add(new ChatMessage("system", body.system()));
            }
            messages.add(new ChatMessage("user", body.prompt()));
        }
        GatewayExecution exec = orchestrator.prepare(new CompletionCommand(principal, "auto", messages, body.maxTokens(),
                body.simulateFailure(), template));
        return responder.stream(exec, "auto");
    }
}
