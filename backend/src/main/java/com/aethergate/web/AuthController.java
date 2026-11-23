package com.aethergate.web;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.service.auth.DemoSessionService;
import com.aethergate.service.auth.DemoSessionService.DemoSession;
import com.aethergate.service.ratelimit.RateLimitExceededException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public auth helpers for the console: feature flags and the one-click demo session. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final int DEMO_LOGINS_PER_MINUTE = 10;

    private final DemoSessionService demo;
    private final AetherGateProperties.Demo demoConfig;
    private final Cache<String, AtomicInteger> perIp = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100_000).build();

    public AuthController(DemoSessionService demo, AetherGateProperties properties) {
        this.demo = demo;
        this.demoConfig = properties.demo();
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("demo_available", demo.isConfigured(), "demo_email", demoConfig.email());
    }

    @PostMapping("/demo-session")
    public DemoSession demoSession(HttpServletRequest request) {
        int n = perIp.get(request.getRemoteAddr(), ip -> new AtomicInteger()).incrementAndGet();
        if (n > DEMO_LOGINS_PER_MINUTE) {
            throw new RateLimitExceededException("demo logins/min", DEMO_LOGINS_PER_MINUTE, 60);
        }
        return demo.createSession();
    }
}
