package com.aethergate.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    /** Semantic-cache writes happen off the response path on virtual threads (inline when disabled, e.g. in tests). */
    @Bean(name = "cacheWriteExecutor", destroyMethod = "")
    public Executor cacheWriteExecutor(AetherGateProperties properties) {
        return properties.cache().asyncStore() ? Executors.newVirtualThreadPerTaskExecutor() : Runnable::run;
    }
}
