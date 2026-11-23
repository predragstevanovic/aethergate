package com.aethergate.service.metering;

import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.LlmRequestLog;
import com.aethergate.domain.repository.LlmRequestLogRepository;
import org.springframework.stereotype.Service;

/** Persists per-request telemetry (tokens, cost, latency, cache and failover outcome) under RLS. */
@Service
public class RequestLogService {

    private final LlmRequestLogRepository logs;

    public RequestLogService(LlmRequestLogRepository logs) {
        this.logs = logs;
    }

    @TenantScoped
    public LlmRequestLog record(LlmRequestLog entry) {
        return logs.save(entry);
    }
}
