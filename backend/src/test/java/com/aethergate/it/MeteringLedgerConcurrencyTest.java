package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aethergate.config.TenantContext;
import com.aethergate.service.metering.BudgetLimitExceededException;
import com.aethergate.service.metering.InsufficientQuotaException;
import com.aethergate.service.metering.MeteringLedgerService;
import com.aethergate.service.metering.Reservation;
import com.aethergate.service.metering.Settlement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MeteringLedgerConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    MeteringLedgerService ledger;

    @Test
    @DisplayName("20 concurrent holds of $0.10 on a $1.00 balance: exactly 10 succeed, balance never negative")
    void concurrentReservationsCannotOverdraft() throws Exception {
        UUID tenant = newTenant("Concurrency Co", new BigDecimal("1.00000000"));
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                String requestId = "req_conc_" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        TenantContext.callAs(tenant, () -> ledger.reserveBudget(requestId, new BigDecimal("0.10"), "hold"));
                        ok.incrementAndGet();
                    } catch (InsufficientQuotaException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(ok).hasValue(10);
        assertThat(rejected).hasValue(10);
        assertThat(available(tenant)).isEqualByComparingTo("0");
        assertThat(reserved(tenant)).isEqualByComparingTo("1.00");
        assertThat(count("select count(*) from ledger_entries where tenant_id = ? and entry_type = 'RESERVE'", tenant)).isEqualTo(10);
    }

    @Test
    @DisplayName("settle charges the actual cost and releases the dust: RESERVE = SETTLE + RELEASE")
    void settleReleasesUnusedHold() {
        UUID tenant = newTenant("Settle Co", new BigDecimal("10"));
        Reservation r = TenantContext.callAs(tenant, () -> ledger.reserveBudget("req_settle", new BigDecimal("0.00350000"), "hold"));
        assertThat(r.replayed()).isFalse();
        assertThat(available(tenant)).isEqualByComparingTo("9.9965");

        Settlement s = TenantContext.callAs(tenant, () -> ledger.settleTransaction("req_settle", new BigDecimal("0.00142000"), "used"));
        assertThat(s.settledUsd()).isEqualByComparingTo("0.00142");
        assertThat(s.releasedUsd()).isEqualByComparingTo("0.00208");
        assertThat(s.reservedUsd()).isEqualByComparingTo(s.settledUsd().add(s.releasedUsd()));
        assertThat(available(tenant)).isEqualByComparingTo("9.99858");
        assertThat(reserved(tenant)).isEqualByComparingTo("0");

        // replaying the same request id changes nothing
        Settlement again = TenantContext.callAs(tenant, () -> ledger.settleTransaction("req_settle", new BigDecimal("0.003"), "retry"));
        assertThat(again.replayed()).isTrue();
        assertThat(again.settledUsd()).isEqualByComparingTo("0.00142");
        Reservation reAgain = TenantContext.callAs(tenant, () -> ledger.reserveBudget("req_settle", new BigDecimal("0.0035"), "hold"));
        assertThat(reAgain.replayed()).isTrue();
        assertThat(available(tenant)).isEqualByComparingTo("9.99858");
        assertThat(count("select count(*) from ledger_entries where tenant_id = ? and request_id = 'req_settle'", tenant)).isEqualTo(3);
    }

    @Test
    void actualCostAboveHoldIsCappedAtTheHold() {
        UUID tenant = newTenant("Cap Co", new BigDecimal("1"));
        TenantContext.callAs(tenant, () -> ledger.reserveBudget("req_cap", new BigDecimal("0.01"), "hold"));
        Settlement s = TenantContext.callAs(tenant, () -> ledger.settleTransaction("req_cap", new BigDecimal("0.05"), "over"));
        assertThat(s.settledUsd()).isEqualByComparingTo("0.01");
        assertThat(s.releasedUsd()).isEqualByComparingTo("0");
        assertThat(available(tenant)).isEqualByComparingTo("0.99");
    }

    @Test
    void releaseReturnsTheWholeHoldAndIsIdempotent() {
        UUID tenant = newTenant("Release Co", new BigDecimal("2"));
        TenantContext.callAs(tenant, () -> ledger.reserveBudget("req_rel", new BigDecimal("0.5"), "hold"));
        Settlement s = TenantContext.callAs(tenant, () -> ledger.releaseReservation("req_rel", "providers down"));
        assertThat(s.releasedUsd()).isEqualByComparingTo("0.5");
        assertThat(available(tenant)).isEqualByComparingTo("2");
        Settlement again = TenantContext.callAs(tenant, () -> ledger.releaseReservation("req_rel", "again"));
        assertThat(again.replayed()).isTrue();
        Settlement settleAfter = TenantContext.callAs(tenant, () -> ledger.settleTransaction("req_rel", new BigDecimal("0.1"), "late"));
        assertThat(settleAfter.replayed()).isTrue();
        assertThat(available(tenant)).isEqualByComparingTo("2");
    }

    @Test
    void settlingUnknownRequestFails() {
        UUID tenant = newTenant("Unknown Co", new BigDecimal("1"));
        assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> ledger.settleTransaction("nope", BigDecimal.ONE, "x")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> ledger.releaseReservation("nope", "x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hardMonthlyBudgetBlocksHold() {
        UUID tenant = newTenant("Budget Co", new BigDecimal("50"), new BigDecimal("1"));
        TenantContext.callAs(tenant, () -> ledger.reserveBudget("req_b1", new BigDecimal("0.6"), "hold"));
        TenantContext.callAs(tenant, () -> ledger.settleTransaction("req_b1", new BigDecimal("0.6"), "spent"));
        assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> ledger.reserveBudget("req_b2", new BigDecimal("0.5"), "hold")))
                .isInstanceOf(BudgetLimitExceededException.class)
                .satisfies(e -> assertThat(((BudgetLimitExceededException) e).getProjected()).isEqualByComparingTo("1.1"));
    }

    @Test
    void topUpIsIdempotentAndRejectsNonPositive() {
        UUID tenant = newTenant("TopUp Co", new BigDecimal("1"));
        BigDecimal after = TenantContext.callAs(tenant, () -> ledger.topUp("topup_x", new BigDecimal("50"), "card"));
        assertThat(after).isEqualByComparingTo("51");
        BigDecimal replay = TenantContext.callAs(tenant, () -> ledger.topUp("topup_x", new BigDecimal("50"), "card"));
        assertThat(replay).isEqualByComparingTo("51");
        assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> ledger.topUp("topup_y", BigDecimal.ZERO, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> ledger.reserveBudget("r", null, "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantScopedCallWithoutBoundTenantFailsFast() {
        assertThatThrownBy(() -> ledger.topUp("t", BigDecimal.ONE, "x")).isInstanceOf(IllegalStateException.class);
    }
}
