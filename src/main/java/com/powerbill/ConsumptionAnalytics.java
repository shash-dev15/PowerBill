package com.powerbill;

import java.math.BigDecimal;

/** Same-month consumption comparison and unusual-increase checks. */
public final class ConsumptionAnalytics {
    private ConsumptionAnalytics() { }
    public record Comparison(BigDecimal difference, BigDecimal percentageChange, String trend) { }
    public static Comparison compare(BigDecimal previous, BigDecimal current) {
        requireUnits(previous); requireUnits(current);
        BigDecimal diff = current.subtract(previous), percent = BillingEngine.percentageChange(previous, current);
        String trend = diff.signum() > 0 ? "INCREASED" : diff.signum() < 0 ? "DECREASED" : "STABLE";
        return new Comparison(diff, percent, trend);
    }
    public static boolean isSpike(BigDecimal previous, BigDecimal current, BigDecimal threshold) {
        requireUnits(current);
        if (threshold == null || threshold.signum() < 0) throw new IllegalArgumentException("Threshold must be non-negative.");
        if (previous == null || previous.signum() <= 0) return false;
        BigDecimal increase = current.subtract(previous);
        return increase.signum() > 0
                && increase.multiply(BigDecimal.valueOf(100)).compareTo(previous.multiply(threshold)) > 0;
    }
    private static void requireUnits(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("Consumption units must be non-negative.");
    }
}
