package com.aethergate.unit;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.config.AetherGateProperties.Provider;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** Builds {@link AetherGateProperties} for plain unit tests (no Spring context). */
final class TestProps {

    private TestProps() {}

    static AetherGateProperties with(String supabaseUrl, String anonKey, String serviceKey, String demoPassword,
                                     Provider openai, Provider anthropic, Provider gemini) {
        return new AetherGateProperties("",
                new AetherGateProperties.Cors(List.of("http://localhost:5173")),
                new AetherGateProperties.Supabase(supabaseUrl, anonKey, serviceKey),
                new AetherGateProperties.Demo("demo@aethergate.dev", demoPassword, "vortex-ai-labs"),
                new AetherGateProperties.Metering(512, new BigDecimal("1000")),
                new AetherGateProperties.Cache(0.96, Duration.ofHours(24), false),
                new AetherGateProperties.RateLimit(30, 60_000, Duration.ofSeconds(5)),
                new AetherGateProperties.Providers(openai, anthropic, gemini),
                new AetherGateProperties.Mock(0, 0, 0, 0));
    }

    static AetherGateProperties defaults() {
        Provider none = new Provider("", "");
        return with("http://localhost:1", "", "", "", none, none, none);
    }
}
