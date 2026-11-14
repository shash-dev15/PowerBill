package com.powerbill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Progressive, tariff-driven slab calculations. Amounts use currency precision (2 decimals). */
public final class BillingEngine {
    private BillingEngine() { }

    public record Slab(BigDecimal upperLimit, BigDecimal rate) {
        public Slab {
            if (rate == null || rate.signum() < 0 || (upperLimit != null && upperLimit.signum() <= 0))
                throw new IllegalArgumentException("Slab limits and rates must be non-negative; limits must be positive.");
        }
    }
    public record Line(int slabNumber, BigDecimal units, BigDecimal rate, BigDecimal amount) { }
    public record Result(List<Line> lines, BigDecimal energy, BigDecimal fixed, BigDecimal additional,
                         BigDecimal adjustment, BigDecimal subsidy, BigDecimal total) { }

    public static Result calculate(BigDecimal units, List<Slab> slabs, BigDecimal fixed,
                                   BigDecimal additional, BigDecimal subsidy) {
        return calculate(units, slabs, fixed, additional, BigDecimal.ZERO, subsidy);
    }
    public static Result calculate(BigDecimal units, List<Slab> slabs, BigDecimal fixed,
                                   BigDecimal additional, BigDecimal adjustment, BigDecimal subsidy) {
        if (units == null || units.signum() < 0) throw new IllegalArgumentException("Units cannot be negative.");
        if (slabs == null || slabs.isEmpty()) throw new IllegalArgumentException("At least one tariff slab is required.");
        fixed = nonNegative(fixed, "Fixed charges"); additional = nonNegative(additional, "Additional charges");
        subsidy = nonNegative(subsidy, "Subsidy");
        if (adjustment == null) throw new IllegalArgumentException("Adjustment is required.");
        adjustment = adjustment.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < slabs.size(); i++) {
            Slab slab = slabs.get(i);
            if (slab == null) throw new IllegalArgumentException("Tariff slabs cannot contain null entries.");
            if (i > 0 && (slabs.get(i - 1).upperLimit() == null
                    || slab.upperLimit() != null && slab.upperLimit().compareTo(slabs.get(i - 1).upperLimit()) <= 0))
                throw new IllegalArgumentException("Slab limits must be strictly increasing; the final slab may be unlimited.");
        }
        BigDecimal energy = BigDecimal.ZERO, lower = BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < slabs.size(); i++) {
            Slab slab = slabs.get(i);
            BigDecimal end = slab.upperLimit() == null ? units : units.min(slab.upperLimit());
            BigDecimal slabUnits = end.subtract(lower).max(BigDecimal.ZERO);
            BigDecimal amount = slabUnits.multiply(slab.rate()).setScale(2, RoundingMode.HALF_UP);
            lines.add(new Line(i + 1, slabUnits, slab.rate(), amount));
            energy = energy.add(amount);
            if (slab.upperLimit() == null || units.compareTo(slab.upperLimit()) <= 0) break;
            lower = slab.upperLimit();
        }
        if (slabs.getLast().upperLimit() != null && units.compareTo(slabs.getLast().upperLimit()) > 0)
            throw new IllegalArgumentException("Tariff slabs do not cover the requested consumption.");
        BigDecimal appliedSubsidy = subsidy.min(lines.getFirst().amount());
        BigDecimal total = energy.add(fixed).add(additional).add(adjustment).subtract(appliedSubsidy).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new Result(List.copyOf(lines), energy.setScale(2, RoundingMode.HALF_UP), fixed, additional, adjustment, appliedSubsidy, total);
    }
    private static BigDecimal nonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
        return value.setScale(2, RoundingMode.HALF_UP);
    }
    public static BigDecimal firstSlabEnergy(BigDecimal units, List<Slab> slabs) {
        return calculate(units, slabs, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                .lines().getFirst().amount();
    }
    public static BigDecimal percentageChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null) throw new IllegalArgumentException("Both values are required.");
        if (previous.signum() == 0) return null;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
    }
}
