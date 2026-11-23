package com.aethergate.web;

import com.aethergate.service.guardrail.GuardrailBlockedException;
import com.aethergate.service.tenant.DemoReadOnlyException;
import com.aethergate.service.metering.ProjectBudgetExceededException;
import com.aethergate.service.project.ProjectArchivedException;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.StaleVersionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.aethergate.service.auth.DemoUnavailableException;
import com.aethergate.service.llm.AllProvidersFailedException;
import com.aethergate.service.llm.StreamInterruptedException;
import com.aethergate.service.metering.BudgetLimitExceededException;
import com.aethergate.service.metering.InsufficientQuotaException;
import com.aethergate.service.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves the gateway as RFC 7807 {@code application/problem+json} with a stable type URI. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientQuotaException.class)
    ProblemDetail insufficientQuota(InsufficientQuotaException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.PAYMENT_REQUIRED, "insufficient-quota", e.getMessage(), req);
        p.setProperty("available_usd", e.getAvailable());
        p.setProperty("required_usd", e.getRequired());
        return p;
    }

    @ExceptionHandler(BudgetLimitExceededException.class)
    ProblemDetail budgetLimit(BudgetLimitExceededException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.PAYMENT_REQUIRED, "budget-limit-exceeded", e.getMessage(), req);
        p.setProperty("hard_budget_limit_usd", e.getLimit());
        p.setProperty("projected_usd", e.getProjected());
        return p;
    }

    @ExceptionHandler(ProjectBudgetExceededException.class)
    ProblemDetail projectBudget(ProjectBudgetExceededException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.PAYMENT_REQUIRED, "project-budget-exceeded", e.getMessage(), req);
        p.setProperty("project", e.getProjectName());
        p.setProperty("monthly_budget_usd", e.getBudget());
        p.setProperty("projected_usd", e.getProjected());
        return p;
    }

    @ExceptionHandler(GuardrailBlockedException.class)
    ProblemDetail guardrailBlocked(GuardrailBlockedException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "guardrail-blocked", e.getMessage(), req);
        p.setProperty("rule", e.getRule());
        p.setProperty("matches", e.getMatches());
        return p;
    }

    @ExceptionHandler(DemoReadOnlyException.class)
    ProblemDetail demoReadOnly(DemoReadOnlyException e, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, "demo-read-only", e.getMessage(), req);
    }

    @ExceptionHandler(ProjectArchivedException.class)
    ProblemDetail projectArchived(ProjectArchivedException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "project-archived", e.getMessage(), req);
    }

    @ExceptionHandler(ResourceConflictException.class)
    ProblemDetail conflict(ResourceConflictException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "conflict", e.getMessage(), req);
    }

    /** Unique-index races (two tabs creating the same project name at once) end here, not in a 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.info("Integrity violation on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "conflict", "The change conflicts with existing data; reload and retry", req);
    }

    @ExceptionHandler(StaleVersionException.class)
    ProblemDetail stale(StaleVersionException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.PRECONDITION_FAILED, "stale-version", e.getMessage(), req);
        p.setProperty("current_version", e.getCurrentVersion());
        return p;
    }

    /** Two writers passed the version check at the same instant: JPA @Version rejects the loser at commit. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(ObjectOptimisticLockingFailureException e, HttpServletRequest req) {
        return problem(HttpStatus.PRECONDITION_FAILED, "stale-version", "The resource was modified concurrently; reload and retry", req);
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException e, HttpServletRequest req) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required", e.getMessage(), req);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", e.getMessage(), req);
        p.setProperty("limit_type", e.getLimitType());
        p.setProperty("retry_after_seconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(p);
    }

    @ExceptionHandler(AllProvidersFailedException.class)
    ProblemDetail providersDown(AllProvidersFailedException e, HttpServletRequest req) {
        ProblemDetail p = problem(HttpStatus.SERVICE_UNAVAILABLE, "providers-unavailable", e.getMessage(), req);
        p.setProperty("attempts", e.getAttempts());
        return p;
    }

    @ExceptionHandler(StreamInterruptedException.class)
    ProblemDetail interrupted(StreamInterruptedException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_GATEWAY, "upstream-interrupted", e.getMessage(), req);
    }

    @ExceptionHandler(DemoUnavailableException.class)
    ProblemDetail demoUnavailable(DemoUnavailableException e, HttpServletRequest req) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "demo-unavailable", e.getMessage(), req);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage(), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error", req);
    }

    static ProblemDetail problem(HttpStatus status, String type, String detail, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://aethergate.dev/problems/" + type));
        p.setTitle(status.getReasonPhrase());
        p.setInstance(URI.create(req.getRequestURI()));
        return p;
    }
}
