package com.powerbill;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRulesTest {
    private BigDecimal d(String value) { return new BigDecimal(value); }

    @Test void meterReadingsValidateAndHandleRolloverAndReplacement() {
        assertEquals(d("37"), MeterReadingCalculator.normal(d("100"), d("137")));
        assertThrows(IllegalArgumentException.class, () -> MeterReadingCalculator.normal(d("137"), d("100")));
        assertEquals(d("26"), MeterReadingCalculator.rollover(d("95"), d("20"), d("100")));
        assertEquals(d("1"), MeterReadingCalculator.rollover(d("100"), d("0"), d("100")));
        assertEquals(d("0.8"), MeterReadingCalculator.rollover(d("99.5"), d("0.2"), d("100.0")));
        assertEquals(d("25"), MeterReadingCalculator.replacement(d("95"), d("100"), d("20")));
        assertThrows(IllegalArgumentException.class, () -> MeterReadingCalculator.rollover(d("101"), d("2"), d("100")));
        assertThrows(IllegalArgumentException.class, () -> MeterReadingCalculator.rollover(d("50"), d("60"), d("100")));
    }

    @Test void paymentBalancesStatusesAndLateFeesAreCorrect() {
        assertEquals(d("0.00"), PaymentCalculator.outstanding(d("100"), List.of(d("60"), d("40"))));
        assertEquals(d("40.00"), PaymentCalculator.outstanding(d("100"), List.of(d("60"))));
        assertEquals(d("10.00"), PaymentCalculator.lateFee(d("100"), d("0.02"), d("10"), d("500")));
        assertEquals(d("500.00"), PaymentCalculator.lateFee(d("50000"), d("0.02"), d("10"), d("500")));
        assertEquals("PAID", PaymentCalculator.status(d("100"), d("100"), true));
        assertEquals("PARTIALLY_PAID", PaymentCalculator.status(d("100"), d("25"), false));
        assertEquals("PARTIAL_OVERDUE", PaymentCalculator.status(d("100"), d("25"), true));
        assertEquals("UNPAID", PaymentCalculator.status(d("100"), d("0"), false));
    }

    @Test void dueDatesUseGenerationDateAndTwentyDayDefaultWhileRespectingConfiguration() {
        assertEquals(20, Main.configuredDueDays(null));
        assertEquals(7, Main.configuredDueDays("7"));
        assertThrows(IllegalArgumentException.class, () -> Main.configuredDueDays("-1"));
        assertEquals(LocalDate.of(2026, 12, 4),
                Main.calculateDueDate(LocalDate.of(2026, 11, 14), Main.configuredDueDays(null)));
        assertEquals(LocalDate.of(2026, 12, 23),
                Main.calculateDueDate(LocalDate.of(2026, 12, 3), Main.configuredDueDays(null)));
        assertEquals(LocalDate.of(2026, 11, 29),
                Main.calculateDueDate(LocalDate.of(2026, 11, 9), Main.configuredDueDays("20")));
    }

    @Test void billingPeriodsEndOnlyAtTheStartOfTheFollowingMonth() {
        assertFalse(Main.billingPeriodEnded(YearMonth.of(2026, 10), LocalDate.of(2026, 10, 31)));
        assertTrue(Main.billingPeriodEnded(YearMonth.of(2026, 10), LocalDate.of(2026, 11, 1)));
        assertFalse(Main.billingPeriodEnded(YearMonth.of(2026, 11), LocalDate.of(2026, 11, 30)));
        assertTrue(Main.billingPeriodEnded(YearMonth.of(2026, 11), LocalDate.of(2026, 12, 1)));
        assertFalse(Main.billingPeriodEnded(YearMonth.of(2026, 12), LocalDate.of(2026, 12, 31)));
        assertTrue(Main.billingPeriodEnded(YearMonth.of(2026, 12), LocalDate.of(2027, 1, 1)));
        assertNotNull(Main.billValidityIssue("2026-10", "2026-10-31", LocalDate.of(2026, 11, 1)));
        assertNull(Main.billValidityIssue("2026-10", "2026-11-01", LocalDate.of(2026, 11, 1)));
        assertNotNull(Main.billValidityIssue("2026-10", null, LocalDate.of(2026, 11, 1)));
    }

    @Test void comparisonsAndSpikeChecksHandleTrendsAndMissingBaselines() {
        assertEquals("INCREASED", ConsumptionAnalytics.compare(d("100"), d("170")).trend());
        assertEquals(d("70.00"), ConsumptionAnalytics.compare(d("100"), d("170")).percentageChange());
        assertEquals("DECREASED", ConsumptionAnalytics.compare(d("100"), d("50")).trend());
        assertEquals("STABLE", ConsumptionAnalytics.compare(d("0"), d("0")).trend());
        assertNull(ConsumptionAnalytics.compare(d("0"), d("20")).percentageChange());
        assertTrue(ConsumptionAnalytics.isSpike(d("100"), d("170"), d("50")));
        assertTrue(ConsumptionAnalytics.isSpike(d("200000"), d("300009"), d("50")), "Threshold comparison should use unrounded percentages.");
        assertFalse(ConsumptionAnalytics.isSpike(d("200000"), d("300000"), d("50")), "An increase equal to the threshold is not above it.");
        assertFalse(ConsumptionAnalytics.isSpike(d("200000"), d("299999"), d("50")), "An increase just below the threshold is not a spike.");
        assertFalse(ConsumptionAnalytics.isSpike(d("0"), d("170"), d("50")));
        assertFalse(ConsumptionAnalytics.isSpike(null, d("170"), d("50")));
    }
}
