package com.aethergate.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 401 as RFC 7807 {@code application/problem+json}, for both the API-key and the JWT chain. */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper json;
    private final String detail;

    public ProblemAuthenticationEntryPoint(ObjectMapper json, String detail) {
        this.json = json;
        this.detail = detail;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        Object specific = request.getAttribute(ApiKeyAuthenticationFilter.AUTH_ERROR);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                specific instanceof String s ? s : detail);
        problem.setType(URI.create("https://aethergate.dev/problems/unauthorized"));
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");
        response.setHeader("WWW-Authenticate", request.getRequestURI().startsWith("/v1/") ? "ApiKey" : "Bearer");
        json.writeValue(response.getOutputStream(), problem);
    }
}
