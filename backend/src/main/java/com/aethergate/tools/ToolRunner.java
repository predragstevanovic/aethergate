package com.aethergate.tools;

import com.aethergate.config.AetherGateProperties;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Maintenance entry point (no HTTP server):
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--aethergate.tool=seed    # reset + seed demo tenants, then audit
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--aethergate.tool=audit   # preview + invariant audit only
 * </pre>
 * Exit code 1 when any audit check fails, so it can gate a deployment.
 */
@Component
public class ToolRunner implements ApplicationRunner {

    private final AetherGateProperties properties;
    private final DemoDataSeeder seeder;
    private final LedgerAuditor auditor;
    private final ConfigurableApplicationContext context;

    public ToolRunner(AetherGateProperties properties, DemoDataSeeder seeder, LedgerAuditor auditor,
                      ConfigurableApplicationContext context) {
        this.properties = properties;
        this.seeder = seeder;
        this.auditor = auditor;
        this.context = context;
    }

    @Override
    @SuppressWarnings("java:S106") // a CLI tool prints to stdout by design
    public void run(ApplicationArguments args) {
        String tool = properties.tool();
        if (tool == null || tool.isBlank()) {
            return;
        }
        int exit;
        switch (tool) {
            case "seed" -> {
                System.out.println("\n>> Seeding demo tenants (30 days of traffic)...");
                long t0 = System.currentTimeMillis();
                seeder.seed().forEach(line -> System.out.println("  + " + line));
                System.out.printf("  seeded in %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
                exit = report();
            }
            case "audit" -> exit = report();
            default -> {
                System.out.println("Unknown tool '" + tool + "' (use seed or audit)");
                exit = 2;
            }
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    @SuppressWarnings("java:S106")
    private int report() {
        System.out.println("\n>> Preview");
        auditor.preview().forEach(line -> System.out.println("  " + line));
        System.out.println("\n>> Invariant audit");
        List<LedgerAuditor.Check> checks = auditor.audit();
        int failed = 0;
        for (int i = 0; i < checks.size(); i++) {
            LedgerAuditor.Check c = checks.get(i);
            System.out.printf("  %s %2d. %-70s %s%n", c.passed() ? "PASS" : "FAIL", i + 1, c.name(), c.detail());
            if (!c.passed()) {
                failed++;
            }
        }
        System.out.printf("%n  %d/%d checks passed%n%n", checks.size() - failed, checks.size());
        return failed == 0 ? 0 : 1;
    }
}
