package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.TenantContext;
import com.aethergate.service.cache.HashingEmbedder;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.TokenEstimator;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.ratelimit.TokenBucket;
import com.aethergate.service.tenant.ApiKeys;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PureLogicTest {

    @Nested
    class CostCalculatorTest {

        @ParameterizedTest(name = "{0} in + {1} out @ ${2}/${3} per 1M = ${4}")
        @CsvSource({
                "1000, 500, 0.15, 0.60, 0.00045000",
                "1, 1, 0.15, 0.60, 0.00000075",
                "1234567, 0, 2.50, 10.00, 3.08641750",
                "0, 0, 1, 5, 0.00000000",
                "37, 112, 1.00, 5.00, 0.00059700"})
        void exactFixedPointCost(long in, long out, String inPrice, String outPrice, String expected) {
            assertThat(CostCalculator.actualCost(in, out, new BigDecimal(inPrice), new BigDecimal(outPrice)))
                    .isEqualByComparingTo(expected)
                    .hasScaleOf(8);
        }

        @Test
        @DisplayName("estimates round up, actuals round half-up: a hold is never smaller than the charge")
        void roundingDirections() {
            BigDecimal price = new BigDecimal("0.15");
            assertThat(CostCalculator.estimatedMaxCost(1, 0, price, BigDecimal.ZERO)).isEqualByComparingTo("0.00000015");
            assertThat(CostCalculator.estimatedMaxCost(1, 0, new BigDecimal("0.001"), BigDecimal.ZERO)).isEqualByComparingTo("0.00000001");
            assertThat(CostCalculator.actualCost(1, 0, new BigDecimal("0.001"), BigDecimal.ZERO)).isEqualByComparingTo("0");
            for (int tokens = 0; tokens < 2000; tokens += 37) {
                BigDecimal est = CostCalculator.estimatedMaxCost(tokens, 512, price, new BigDecimal("0.6"));
                BigDecimal act = CostCalculator.actualCost(tokens, 511, price, new BigDecimal("0.6"));
                assertThat(est).isGreaterThanOrEqualTo(act);
            }
            assertThat(CostCalculator.normalize(new BigDecimal("1.123456789"))).isEqualByComparingTo("1.12345679");
            assertThatThrownBy(() -> CostCalculator.actualCost(-1, 0, price, price)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CostCalculator.actualCost(0, -1, price, price)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TokenEstimatorTest {

        @Test
        void countsWordPiecesAndPunctuation() {
            assertThat(TokenEstimator.count(null)).isZero();
            assertThat(TokenEstimator.count("")).isZero();
            assertThat(TokenEstimator.count("hi")).isEqualTo(1);
            assertThat(TokenEstimator.count("tokenization")).isEqualTo(2);  // cl100k: "token" + "ization"
            assertThat(TokenEstimator.count("Hello, world!")).isEqualTo(4); // cl100k: 4
            assertThat(TokenEstimator.count("abcdef")).isEqualTo(1);
            assertThat(TokenEstimator.count("abcdefg")).isEqualTo(2);
        }

        @Test
        void promptIncludesChatFramingOverhead() {
            List<ChatMessage> msgs = List.of(new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hi"));
            int content = TokenEstimator.count("system") + TokenEstimator.count("Be brief.") + TokenEstimator.count("user") + TokenEstimator.count("Hi");
            assertThat(TokenEstimator.countPrompt(msgs)).isEqualTo(3 + 2 * 4 + content);
            assertThat(TokenEstimator.countPrompt(List.of())).isEqualTo(3);
        }
    }

    @Nested
    class HashingEmbedderTest {

        @Test
        void unitLengthDeterministicVectors() {
            float[] a = HashingEmbedder.embed("How does row level security work?");
            float[] b = HashingEmbedder.embed("How does row level security work?");
            assertThat(a).hasSize(384).containsExactly(b);
            double norm = 0;
            for (float v : a) {
                norm += v * v;
            }
            assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        }

        @Test
        @DisplayName("case, punctuation, whitespace and diacritics do not change the embedding")
        void normalisationIsInvariant() {
            assertThat(HashingEmbedder.normalize("  Ćevapi,   ŽELIM!! ")).isEqualTo("cevapi zelim");
            assertThat(HashingEmbedder.normalize(null)).isEmpty();
            double sim = HashingEmbedder.cosine(HashingEmbedder.embed("What is RLS?"), HashingEmbedder.embed("what is rls"));
            assertThat(sim).isGreaterThan(0.999);
        }

        @Test
        void differentQuestionsAreFarBelowTheCacheThreshold() {
            double sim = HashingEmbedder.cosine(HashingEmbedder.embed("How does row level security work?"),
                    HashingEmbedder.embed("Write a haiku about autumn leaves"));
            assertThat(sim).isLessThan(0.5);
            double related = HashingEmbedder.cosine(HashingEmbedder.embed("How does row level security work in Postgres?"),
                    HashingEmbedder.embed("How does row level security work?"));
            assertThat(related).isBetween(0.6, 0.96);
        }

        @Test
        void emptyTextAndLiteralFormat() {
            float[] zero = HashingEmbedder.embed("   ");
            assertThat(zero).containsOnly(0f);
            assertThat(HashingEmbedder.cosine(zero, HashingEmbedder.embed("x"))).isZero();
            assertThat(HashingEmbedder.toVectorLiteral(new float[]{0.5f, -1f})).isEqualTo("[0.5,-1.0]");
        }
    }

    @Nested
    class TokenBucketTest {

        private static final long SEC = TimeUnit.SECONDS.toNanos(1);

        @Test
        void consumesAndRefillsContinuously() {
            TokenBucket bucket = new TokenBucket(60, 60, 0);
            assertThat(bucket.capacity()).isEqualTo(60);
            assertThat(bucket.canConsume(60, 0)).isTrue();
            bucket.consume(60, 0);
            assertThat(bucket.canConsume(1, 0)).isFalse();
            assertThat(bucket.secondsUntil(1, 0)).isEqualTo(1);
            assertThat(bucket.secondsUntil(30, 0)).isEqualTo(30);
            assertThat(bucket.canConsume(1, SEC)).isTrue();
            assertThat(bucket.available(10 * SEC)).isEqualTo(10);
            assertThat(bucket.available(1000 * SEC)).isEqualTo(60); // capped at capacity
            assertThat(bucket.secondsUntil(1, 1000 * SEC)).isZero();
        }

        @Test
        void overdrawIsAllowedForPostFlightUsageAndOversizedRequestsNeedAFullBucket() {
            TokenBucket bucket = new TokenBucket(100, 100, 0);
            bucket.consume(150, 0);
            assertThat(bucket.available(0)).isEqualTo(-50);
            assertThat(bucket.canConsume(1, 0)).isFalse();
            TokenBucket full = new TokenBucket(100, 100, 0);
            assertThat(full.canConsume(10_000, 0)).isTrue();
            assertThatThrownBy(() -> new TokenBucket(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TokenBucket(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void clockGoingBackwardsDoesNotRefill() {
            TokenBucket bucket = new TokenBucket(10, 10, 5 * SEC);
            bucket.consume(10, 5 * SEC);
            assertThat(bucket.available(SEC)).isZero();
        }
    }

    @Nested
    class ApiKeysAndContextTest {

        @Test
        void generatedKeysAreUniqueWellFormedAndHashedAsHex() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                String key = ApiKeys.generate();
                assertThat(key).startsWith("ag_live_").hasSize(40).matches("ag_live_[0-9A-Za-z]{32}");
                assertThat(seen.add(key)).isTrue();
            }
            String key = ApiKeys.generate();
            assertThat(ApiKeys.displayPrefix(key)).hasSize(12).isEqualTo(key.substring(0, 12));
            assertThat(ApiKeys.displayPrefix("ag_")).isEqualTo("ag_");
            assertThat(ApiKeys.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(ApiKeys.looksValid(key)).isTrue();
            assertThat(ApiKeys.looksValid(null)).isFalse();
            assertThat(ApiKeys.looksValid("sk_live_whatever_long_enough")).isFalse();
            assertThat(ApiKeys.looksValid("ag_live_short")).isFalse();
            assertThat(ApiKeys.looksValid("ag_live_" + "x".repeat(200))).isFalse();
        }

        @Test
        void tenantContextIsStrictlyScoped() {
            UUID outer = UUID.randomUUID();
            UUID inner = UUID.randomUUID();
            assertThat(TenantContext.current()).isEmpty();
            TenantContext.runAs(outer, () -> {
                assertThat(TenantContext.require()).isEqualTo(outer);
                TenantContext.runAs(inner, () -> assertThat(TenantContext.require()).isEqualTo(inner));
                assertThat(TenantContext.require()).isEqualTo(outer);
            });
            assertThat(TenantContext.current()).isEmpty();
            assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> TenantContext.callAs(null, () -> 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
