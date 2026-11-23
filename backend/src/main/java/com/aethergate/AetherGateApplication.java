package com.aethergate;

import com.aethergate.config.AetherGateProperties;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AetherGateProperties.class)
public class AetherGateApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AetherGateApplication.class);
        if (isToolInvocation(args)) {
            // seed / audit tools: same wiring and migrations, no HTTP server
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }

    static boolean isToolInvocation(String[] args) {
        String env = System.getenv("AETHERGATE_TOOL");
        return (env != null && !env.isBlank()) || Arrays.stream(args).anyMatch(a -> a.startsWith("--aethergate.tool="));
    }
}
