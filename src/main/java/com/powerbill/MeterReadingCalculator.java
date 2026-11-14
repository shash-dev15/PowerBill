package com.powerbill;

import java.math.BigDecimal;

/** Unit calculation rules for normal, replacement, and rollover meter periods. */
public final class MeterReadingCalculator {
    private MeterReadingCalculator() { }
    public static BigDecimal normal(BigDecimal previous, BigDecimal current) {
        validate(previous, "Previous reading"); validate(current, "Current reading");
        if (current.compareTo(previous) < 0) throw new IllegalArgumentException("Current reading is below previous reading.");
        return current.subtract(previous);
    }
    /** Calculates one wrap where maximum is inclusive and its decimal scale sets register resolution. */
    public static BigDecimal rollover(BigDecimal previous, BigDecimal current, BigDecimal maximum) {
        validate(previous, "Previous reading"); validate(current, "Current reading"); validate(maximum, "Register maximum");
        if (maximum.signum() == 0 || previous.compareTo(maximum) > 0 || current.compareTo(maximum) > 0)
            throw new IllegalArgumentException("Readings must fit within the positive register maximum.");
        if (current.compareTo(previous) >= 0)
            throw new IllegalArgumentException("Current reading must be below previous reading for a rollover.");
        BigDecimal rolloverStep=BigDecimal.ONE.scaleByPowerOfTen(-Math.max(0,maximum.scale()));
        return maximum.subtract(previous).add(rolloverStep).add(current);
    }
    public static BigDecimal replacement(BigDecimal previous, BigDecimal oldMeterFinal, BigDecimal newMeterCurrent) {
        validate(previous, "Previous reading"); validate(oldMeterFinal, "Old meter final reading"); validate(newMeterCurrent, "New meter current reading");
        if (oldMeterFinal.compareTo(previous) < 0) throw new IllegalArgumentException("Old meter final reading is below its previous reading.");
        return oldMeterFinal.subtract(previous).add(newMeterCurrent);
    }
    private static void validate(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(label + " must be non-negative.");
    }
}
