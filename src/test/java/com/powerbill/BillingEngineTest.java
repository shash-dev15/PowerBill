package com.powerbill;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BillingEngineTest {
    private BigDecimal d(String s) { return new BigDecimal(s); }
    @Test void calculatesProgressiveSlabsAndCharges() {
        var r=BillingEngine.calculate(d("250"),List.of(new BillingEngine.Slab(d("100"),d("2")),new BillingEngine.Slab(null,d("4"))),d("20"),d("5"),d("10"));
        assertEquals(d("800.00"),r.energy()); assertEquals(d("815.00"),r.total()); assertEquals(d("100"),r.lines().getFirst().units()); assertEquals(d("150"),r.lines().get(1).units());
    }
    @Test void roundsCurrencyAndFloorsAtZero() {
        var r=BillingEngine.calculate(d("1"),List.of(new BillingEngine.Slab(null,d("1.235"))),d("0"),d("0"),d("5"));
        assertEquals(d("1.24"),r.energy()); assertEquals(d("0.00"),r.total());
    }
    @Test void appliesPositiveAndNegativeAdjustments() {
        var r=BillingEngine.calculate(d("100"),List.of(new BillingEngine.Slab(null,d("2"))),d("20"),d("5"),d("-3"),d("0"));
        assertEquals(d("222.00"),r.total()); assertEquals(d("-3.00"),r.adjustment());
    }
    @Test void rejectsMissingCoverageAndNegativeInputs() {
        assertThrows(IllegalArgumentException.class,()->BillingEngine.calculate(d("150"),List.of(new BillingEngine.Slab(d("100"),d("2"))),d("0"),d("0"),d("0")));
        assertThrows(IllegalArgumentException.class,()->BillingEngine.calculate(d("-1"),List.of(new BillingEngine.Slab(null,d("2"))),d("0"),d("0"),d("0")));
    }
    @Test void acceptsConsumptionExactlyAtFinalFiniteSlabLimit() {
        var result = BillingEngine.calculate(d("300"),
                List.of(new BillingEngine.Slab(d("100"), d("2")), new BillingEngine.Slab(d("300"), d("4"))),
                d("0"), d("0"), d("0"));
        assertEquals(d("1000.00"), result.energy());
        assertThrows(IllegalArgumentException.class, () -> BillingEngine.calculate(d("301"),
                List.of(new BillingEngine.Slab(d("100"), d("2")), new BillingEngine.Slab(d("300"), d("4"))),
                d("0"), d("0"), d("0")));
    }
    @Test void validatesEverySlabAndHandlesZeroAndVeryHighConsumption() {
        var zero = BillingEngine.calculate(d("0"), List.of(new BillingEngine.Slab(d("100"), d("2")), new BillingEngine.Slab(null, d("4"))), d("10"), d("0"), d("0"));
        assertEquals(d("0.00"), zero.energy());
        assertEquals(d("10.00"), zero.total());
        var high = BillingEngine.calculate(d("100000000000000000000"), List.of(new BillingEngine.Slab(d("100"), d("2")), new BillingEngine.Slab(d("300"), d("4")), new BillingEngine.Slab(null, d("6"))), d("0"), d("0"), d("0"));
        assertEquals(d("599999999999999999200.00"), high.energy());
        assertThrows(IllegalArgumentException.class, () -> BillingEngine.calculate(d("1"), List.of(new BillingEngine.Slab(d("100"), d("1")), new BillingEngine.Slab(d("50"), d("2")), new BillingEngine.Slab(null, d("3"))), d("0"), d("0"), d("0")));
    }
    @Test void capsSubsidyAtFirstSlabEnergyBelowAtAndAboveTheSlabLimit() {
        var slabs = List.of(new BillingEngine.Slab(d("100"), d("2")),
                new BillingEngine.Slab(d("300"), d("4")), new BillingEngine.Slab(null, d("6")));

        var below = BillingEngine.calculate(d("50"), slabs, d("50"), d("10"), d("0"), d("500"));
        assertEquals(d("100.00"), below.subsidy());
        assertEquals(d("60.00"), below.total());

        var atLimit = BillingEngine.calculate(d("100"), slabs, d("50"), d("10"), d("0"), d("500"));
        assertEquals(d("200.00"), atLimit.subsidy());
        assertEquals(d("60.00"), atLimit.total());

        var above = BillingEngine.calculate(d("150"), slabs, d("50"), d("10"), d("0"), d("500"));
        assertEquals(d("400.00"), above.energy());
        assertEquals(d("200.00"), above.subsidy());
        assertEquals(d("260.00"), above.total());
    }
    @Test void computesPercentAndHandlesZeroBaseline() { assertEquals(d("70.00"),BillingEngine.percentageChange(d("100"),d("170"))); assertNull(BillingEngine.percentageChange(d("0"),d("20"))); }
}
