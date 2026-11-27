package com.aethergate.service.finops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.service.RequestAttribution;
import com.aethergate.service.finops.FinOpsViews.Window;
import com.aethergate.service.metering.SpendCapExceededException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinOpsUnitTest {

    @Test
    @DisplayName("attribution: headers win over body, metadata.session_id becomes the session, the rest are tags")
    void attributionResolve() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("session_id", "body-session");
        meta.put("Feature", "chat");
        meta.put("end_user", "meta-user");
        RequestAttribution a = RequestAttribution.resolve(null, null, "env=prod, ,team = core", null, meta);
        assertThat(a.endUserId()).isEqualTo("meta-user");
        assertThat(a.sessionId()).isEqualTo("body-session");
        assertThat(a.tags()).containsEntry("feature", "chat").containsEntry("env", "prod").containsEntry("team", "core");
        assertThat(a.isEmpty()).isFalse();

        RequestAttribution h = RequestAttribution.resolve("hdr-user", "hdr-session", null, "body-user", Map.of("session", "x"));
        assertThat(h.endUserId()).isEqualTo("hdr-user");
        assertThat(h.sessionId()).isEqualTo("hdr-session");
        assertThat(RequestAttribution.resolve(null, " ", "", "u", Map.of("user", "ignored")).endUserId()).isEqualTo("u");
        assertThat(RequestAttribution.NONE.isEmpty()).isTrue();
        assertThat(new RequestAttribution(null, null, null).tags()).isEmpty();
    }

    @Test
    @DisplayName("attribution: invalid ids, tag keys, values and too many tags are rejected")
    void attributionValidation() {
        assertThatThrownBy(() -> RequestAttribution.resolve("a b", null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, "x".repeat(129), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, "=v", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, "Key!=v", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, "k=", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, "session=x", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, null, null, Map.of("k", "bad\nvalue")))
                .isInstanceOf(IllegalArgumentException.class);
        String eleven = String.join(",", java.util.stream.IntStream.range(0, 11).mapToObj(i -> "k" + i + "=v").toList());
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, eleven, null, null)).hasMessageContaining("At most 10");
        Map<String, String> nullKey = new java.util.HashMap<>();
        nullKey.put(null, "v");
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, null, null, nullKey)).isInstanceOf(IllegalArgumentException.class);
        Map<String, String> nullValue = new java.util.HashMap<>();
        nullValue.put("k", null);
        assertThatThrownBy(() -> RequestAttribution.resolve(null, null, null, null, nullValue)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("robust baseline: a spike is anomalous, noise and a cent are not, the spike cannot hide in its own baseline")
    void robustBaseline() {
        double[] week = new double[168];
        Arrays.fill(week, 0.010);
        for (int i = 0; i < week.length; i += 7) {
            week[i] = 0.014;
        }
        RobustBaseline.Verdict spike = RobustBaseline.evaluate(week, 0.30);
        assertThat(spike.anomalous()).isTrue();
        assertThat(spike.severity()).isEqualTo("CRITICAL");
        assertThat(spike.median()).isEqualTo(0.010);
        RobustBaseline.Verdict mild = RobustBaseline.evaluate(week, 0.016);
        assertThat(mild.anomalous()).isFalse();
        assertThat(mild.severity()).isNull();
        double[] noisy = new double[168];
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] = i % 2 == 0 ? 0.010 : 0.020;
        }
        RobustBaseline.Verdict warning = RobustBaseline.evaluate(noisy, 0.06);
        assertThat(warning.severity()).isEqualTo("WARNING");
        assertThat(warning.threshold()).isGreaterThan(warning.median());

        double[] quiet = new double[168]; // all zero: one cent is still below the absolute floor
        assertThat(RobustBaseline.evaluate(quiet, 0.009).anomalous()).isFalse();
        assertThat(RobustBaseline.evaluate(quiet, 0.05).anomalous()).isTrue();
        assertThat(RobustBaseline.evaluate(new double[0], 5).anomalous()).isFalse();
        assertThat(RobustBaseline.median(new double[]{3, 1, 2, 4})).isEqualTo(2.5);
        assertThat(RobustBaseline.median(new double[]{3, 1, 2})).isEqualTo(2);
    }

    @Test
    @DisplayName("forecast maths: run-rate projection, band, linear projection, limit date, sigma")
    void forecastMaths() {
        ForecastService.Projection p = ForecastService.project(new BigDecimal("10"), new BigDecimal("2"), 10, 30, 1.0);
        assertThat(p.projected()).isEqualByComparingTo("50");
        assertThat(p.linear()).isEqualByComparingTo("30");
        assertThat(p.high().subtract(p.projected())).isEqualByComparingTo(p.projected().subtract(p.low()));
        ForecastService.Projection wide = ForecastService.project(new BigDecimal("1"), BigDecimal.ZERO, 0, 30, 100);
        assertThat(wide.low()).isEqualByComparingTo("1"); // never below what is already spent
        assertThat(wide.linear()).isEqualByComparingTo("1");

        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        LocalDate end = LocalDate.parse("2026-11-30");
        assertThat(ForecastService.reachDate(now, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("2"), end))
                .isEqualTo(LocalDate.parse("2026-11-15"));
        assertThat(ForecastService.reachDate(now, new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("2"), end))
                .isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(ForecastService.reachDate(now, new BigDecimal("1"), new BigDecimal("20"), BigDecimal.ZERO, end)).isNull();
        assertThat(ForecastService.reachDate(now, new BigDecimal("1"), new BigDecimal("2000"), BigDecimal.ONE, end)).isNull();
        assertThat(ForecastService.reachDate(now, new BigDecimal("1"), null, BigDecimal.ONE, end)).isNull();
        assertThat(ForecastService.reachDate(now, new BigDecimal("1"), new BigDecimal("2000"), BigDecimal.ONE, null)).isNotNull();
        assertThat(ForecastService.sigma(List.of(BigDecimal.ONE))).isZero();
        assertThat(ForecastService.sigma(List.of(new BigDecimal("1"), new BigDecimal("3")))).isEqualTo(Math.sqrt(2));
    }

    @Test
    @DisplayName("savings advisor: right-sizing needs short answers and a >= 30 % cheaper model; prompt bloat needs 6x input")
    void advisorRules() {
        List<SavingsAdvisor.Price> catalog = List.of(
                new SavingsAdvisor.Price("big", new BigDecimal("3"), new BigDecimal("15")),
                new SavingsAdvisor.Price("small", new BigDecimal("0.15"), new BigDecimal("0.60")));
        SavingsAdvisor.ModelUsage shortAnswers = new SavingsAdvisor.ModelUsage("ANTHROPIC", "big", 1000, 1_000_000, 100_000,
                new BigDecimal("4.5"));
        assertThat(SavingsAdvisor.rightSize(shortAnswers, catalog)).isNotNull()
                .satisfies(r -> {
                    assertThat(r.severity()).isEqualTo("HIGH");
                    assertThat(r.monthlySavingsUsd()).isGreaterThan(new BigDecimal("4"));
                });
        SavingsAdvisor.ModelUsage longAnswers = new SavingsAdvisor.ModelUsage("ANTHROPIC", "big", 10, 1000, 10_000, BigDecimal.ONE);
        assertThat(SavingsAdvisor.rightSize(longAnswers, catalog)).isNull();
        assertThat(SavingsAdvisor.rightSize(new SavingsAdvisor.ModelUsage("X", "big", 0, 0, 0, BigDecimal.ZERO), catalog)).isNull();
        assertThat(SavingsAdvisor.rightSize(shortAnswers, List.of(catalog.getFirst()))).isNull(); // nothing else to move to
        SavingsAdvisor.ModelUsage cheapAlready = new SavingsAdvisor.ModelUsage("OPENAI", "small", 1000, 1_000_000, 100_000,
                new BigDecimal("0.21"));
        assertThat(SavingsAdvisor.rightSize(cheapAlready, catalog)).isNull();
        SavingsAdvisor.ModelUsage tiny = new SavingsAdvisor.ModelUsage("ANTHROPIC", "big", 1, 100, 10, new BigDecimal("0.0005"));
        assertThat(SavingsAdvisor.rightSize(tiny, catalog)).isNull();

        assertThat(SavingsAdvisor.promptBloat(shortAnswers, catalog.getFirst())).isNotNull()
                .satisfies(r -> assertThat(r.monthlySavingsUsd()).isEqualByComparingTo("0.9"));
        assertThat(SavingsAdvisor.promptBloat(shortAnswers, null)).isNull();
        assertThat(SavingsAdvisor.promptBloat(longAnswers, catalog.getFirst())).isNull();
        assertThat(SavingsAdvisor.promptBloat(new SavingsAdvisor.ModelUsage("A", "big", 30, 100, 0, BigDecimal.ONE),
                catalog.getFirst())).isNull();
        assertThat(SavingsAdvisor.promptBloat(new SavingsAdvisor.ModelUsage("A", "big", 30, 700, 100, BigDecimal.ONE),
                catalog.getFirst())).isNull(); // saving below one cent
    }

    @Test
    @DisplayName("FOCUS cells are RFC 4180 quoted and never start a spreadsheet formula")
    void focusCells() {
        assertThat(FocusExporter.cell(null)).isEmpty();
        assertThat(FocusExporter.cell("plain")).isEqualTo("plain");
        assertThat(FocusExporter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(FocusExporter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(FocusExporter.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(FocusExporter.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(FocusExporter.cell(new BigDecimal("-1.50"))).isEqualTo("-1.50");
        assertThat(FocusExporter.cell(-3L)).isEqualTo("-3");
        assertThat(FocusExporter.cell("")).isEmpty();
        assertThat(List.of("OPENAI", "ANTHROPIC", "GEMINI", "CACHE", "MOCK_LLM").stream().map(FocusExporter::vendor).toList())
                .containsExactly("OpenAI", "Anthropic", "Google", "AetherGate", "AetherGate Simulator");
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T10:00:00Z"), ZoneOffset.UTC);
        assertThat(FocusExporter.parseMonth(null, clock)).isEqualTo(YearMonth.of(2026, 11));
        assertThat(FocusExporter.parseMonth(" 2026-10 ", clock)).isEqualTo(YearMonth.of(2026, 10));
        assertThatThrownBy(() -> FocusExporter.parseMonth("Nov", clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("windows, dimensions, ratios and cap kinds")
    void smallPieces() {
        assertThat(Window.parse(null)).isEqualTo(Window.D30);
        assertThat(Window.parse(" 7D ")).isEqualTo(Window.D7);
        assertThat(Window.parse("90d").duration().toDays()).isEqualTo(90);
        assertThat(Window.H24.label()).isEqualTo("24h");
        assertThatThrownBy(() -> Window.parse("1y")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AnalyticsService.dimension(null).name()).isEqualTo("project");
        assertThat(AnalyticsService.dimension("tag:feature").tagKey()).isEqualTo("feature");
        assertThatThrownBy(() -> AnalyticsService.dimension("tag:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalyticsService.dimension("colour")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AnalyticsService.ratio(BigDecimal.ONE, BigDecimal.ZERO)).isZero();
        assertThat(AnalyticsService.ratio(null, BigDecimal.ONE)).isZero();
        assertThat(AnalyticsService.ratio(BigDecimal.ONE, null)).isZero();
        assertThat(AnalyticsService.ratio(BigDecimal.ONE, new BigDecimal("4"))).isEqualTo(0.25);
        assertThat(SpendCapExceededException.Kind.SESSION_STEPS.status()).isEqualTo(429);
        assertThat(SpendCapExceededException.Kind.KEY_MONTHLY_BUDGET.problemType()).isEqualTo("key-budget-exceeded");
    }
}
