package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.service.prompt.PromptTemplateEngine;
import com.aethergate.service.tenant.Cidr;
import com.aethergate.service.tenant.KeyPolicy;
import com.aethergate.service.webhook.WebhookBlockedException;
import com.aethergate.service.webhook.WebhookSignature;
import com.aethergate.service.webhook.WebhookUrlPolicy;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure logic behind webhooks (SSRF guard, signatures), key policies (CIDR) and prompt templates. */
class TeamWebhookPolicyUnitTest {

    private static WebhookUrlPolicy strict() {
        AetherGateProperties defaults = TestProps.defaults();
        AetherGateProperties p = new AetherGateProperties(defaults.tool(), defaults.cors(), defaults.supabase(), defaults.demo(),
                defaults.metering(), defaults.cache(), defaults.rateLimit(), defaults.providers(), defaults.mock(),
                new AetherGateProperties.Webhooks(false, false, 6, Duration.ofSeconds(5)));
        return new WebhookUrlPolicy(p);
    }

    @Test
    void ssrfGuardRejectsInternalTargetsOnSave() {
        WebhookUrlPolicy policy = strict();
        assertThat(policy.validate(" https://hooks.example.com/aethergate ")).isEqualTo("https://hooks.example.com/aethergate");
        for (String bad : List.of("http://hooks.example.com/x", "https://localhost/x", "https://127.0.0.1/x",
                "https://169.254.169.254/latest/meta-data", "https://10.0.0.5/x", "https://192.168.1.1/x", "https://[::1]/x",
                "https://[fd00::1]/x", "https://metadata.google.internal/x", "https://printer.local/x", "https://intranet/x",
                "https://user:pass@hooks.example.com/x", "ftp://hooks.example.com/x", "not a url", "https:///nohost",
                "https://100.64.0.1/x", "https://0.0.0.0/x")) {
            assertThatThrownBy(() -> policy.validate(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> policy.validate("https://x.example/" + "a".repeat(500))).hasMessageContaining("500");
        assertThat(policy.validate("https://8.8.8.8/hook")).isEqualTo("https://8.8.8.8/hook");
        assertThat(policy.validate("https://[2001:4860:4860::8888]/hook")).contains("2001:4860");
        policy.checkResolved("https://8.8.8.8/hook"); // a public literal resolves to itself
    }

    @Test
    void ssrfGuardResolvesAgainOnSend() {
        WebhookUrlPolicy policy = strict();
        assertThatThrownBy(() -> policy.checkResolved("https://localhost/x")).isInstanceOf(WebhookBlockedException.class)
                .hasMessageContaining("non-public");
        assertThatThrownBy(() -> policy.checkResolved("https://does-not-exist.invalid/x")).isInstanceOf(WebhookBlockedException.class);
    }

    @Test
    void privateAddressClassification() throws Exception {
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.100.1.1",
                "0.1.2.3", "224.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.0.8", "::1", "fe80::1", "fc00::1")) {
            assertThat(WebhookUrlPolicy.isPrivate(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        for (String ip : List.of("8.8.8.8", "138.2.169.195", "100.128.0.1", "2001:4860:4860::8888")) {
            assertThat(WebhookUrlPolicy.isPrivate(InetAddress.getByName(ip))).as(ip).isFalse();
        }
    }

    @Test
    void signaturesVerifyAndRejectTamperingReplayAndGarbage() {
        String secret = WebhookSignature.newSecret();
        assertThat(secret).startsWith("whsec_").hasSize(38);
        long t = 1_700_000_000L;
        String header = WebhookSignature.header(secret, t, "{\"a\":1}");
        assertThat(header).matches("t=1700000000,v1=[0-9a-f]{64}");
        assertThat(WebhookSignature.verify(secret, header, "{\"a\":1}", t + 10, 300)).isTrue();
        assertThat(WebhookSignature.verify(secret, header, "{\"a\":2}", t + 10, 300)).isFalse();
        assertThat(WebhookSignature.verify(secret, header, "{\"a\":1}", t + 301, 300)).isFalse(); // replay window
        assertThat(WebhookSignature.verify(secret, null, "x", t, 300)).isFalse();
        assertThat(WebhookSignature.verify(secret, "t=abc,v1=00", "x", t, 300)).isFalse();
        assertThat(WebhookSignature.verify(secret, "v1=00", "x", t, 300)).isFalse();
        assertThat(WebhookSignature.verify(secret, "garbage,t=1", "x", 1, 300)).isFalse();
    }

    @Test
    void cidrMatching() {
        Cidr v4 = Cidr.parse("203.0.113.0/24");
        assertThat(v4.contains("203.0.113.77")).isTrue();
        assertThat(v4.contains("203.0.114.1")).isFalse();
        assertThat(v4.contains("2001:db8::1")).isFalse();
        assertThat(v4.contains("not-an-ip")).isFalse();
        assertThat(v4.contains(null)).isFalse();
        assertThat(Cidr.parse("198.51.100.7").text()).isEqualTo("198.51.100.7/32");
        assertThat(Cidr.parse("198.51.100.7").contains("198.51.100.7")).isTrue();
        assertThat(Cidr.parse("10.0.0.0/9").contains("10.127.255.255")).isTrue();
        assertThat(Cidr.parse("10.0.0.0/9").contains("10.128.0.0")).isFalse();
        assertThat(Cidr.parse("2001:DB8::/32").contains("2001:db8:ffff::1")).isTrue();
        assertThat(Cidr.parse("0.0.0.0/0").contains("8.8.8.8")).isTrue();
        assertThat(Cidr.parse("1.2.3.4/32")).isEqualTo(Cidr.parse("1.2.3.4")).hasSameHashCodeAs(Cidr.parse("1.2.3.4"));
        assertThat(Cidr.parse("1.2.3.4").toString()).isEqualTo("1.2.3.4/32");
        for (String bad : List.of("example.com", "1.2.3.4/33", "1.2.3.4/x", "", "::1/129")) {
            assertThatThrownBy(() -> Cidr.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void keyPolicyEvaluation() {
        Instant now = Instant.parse("2026-11-25T12:00:00Z");
        KeyPolicy p = KeyPolicy.of(now.plusSeconds(60), "GPT-4o-mini, claude-haiku-4-5", "10.0.0.0/8, 192.0.2.1");
        assertThat(p.expired(now)).isFalse();
        assertThat(p.expired(now.plusSeconds(60))).isTrue();
        assertThat(p.allowsModel("gpt-4o-mini")).isTrue();
        assertThat(p.allowsModel(" Claude-Haiku-4-5 ")).isTrue();
        assertThat(p.allowsModel("gpt-4o")).isFalse();
        assertThat(p.allowsModel(null)).isFalse();
        assertThat(p.allowsIp("10.9.8.7")).isTrue();
        assertThat(p.allowsIp("192.0.2.1")).isTrue();
        assertThat(p.allowsIp("192.0.2.2")).isFalse();
        assertThat(p.restrictsModels()).isTrue();
        assertThat(KeyPolicy.NONE.allowsIp("anything")).isTrue();
        assertThat(KeyPolicy.NONE.allowsModel("any")).isTrue();
        assertThat(KeyPolicy.NONE.expired(now)).isFalse();
        assertThat(KeyPolicy.split(" a, ,b ")).containsExactly("a", "b");
        assertThat(KeyPolicy.split(null)).isEmpty();
    }

    @Test
    void templateEngineRendersSinglePass() {
        assertThat(PromptTemplateEngine.variables("Hi {{ name }}", "{{name}} asked {{question}} {{bad-var}}"))
                .containsExactly("name", "question");
        assertThat(PromptTemplateEngine.render("Dear {{name}}, re: {{topic}} $1 \\o/", Map.of("name", "{{topic}}", "topic", "$5")))
                .isEqualTo("Dear {{topic}}, re: $5 $1 \\o/");
        assertThat(PromptTemplateEngine.render(null, Map.of())).isNull();
        assertThat(PromptTemplateEngine.render("{{missing}}!", Map.of())).isEqualTo("!");
        assertThatThrownBy(() -> PromptTemplateEngine.requireValues(List.of("a", "b"), Map.of("a", " ")))
                .hasMessageContaining("a, b");
        assertThatThrownBy(() -> PromptTemplateEngine.requireValues(List.of("a"), Map.of("a", "x".repeat(4001))))
                .hasMessageContaining("longer");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            many.append("{{v").append(i).append("}}");
        }
        assertThatThrownBy(() -> PromptTemplateEngine.variables(null, many.toString())).hasMessageContaining("20");
    }
}
