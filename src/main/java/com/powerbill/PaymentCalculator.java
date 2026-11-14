package com.powerbill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/** Pure payment balance and configurable demonstration late-fee calculations. */
public final class PaymentCalculator {
    private PaymentCalculator() { }
    public static BigDecimal outstanding(BigDecimal total, Collection<BigDecimal> payments) {
        if (total == null || total.signum() < 0) throw new IllegalArgumentException("Bill total must be non-negative.");
        BigDecimal paid = BigDecimal.ZERO;
        if (payments != null) for (BigDecimal payment : payments) {
            if (payment == null || payment.signum() < 0) throw new IllegalArgumentException("Payments must be non-negative.");
            paid = paid.add(payment);
        }
        return total.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
    public static BigDecimal lateFee(BigDecimal outstanding, BigDecimal rate, BigDecimal minimum, BigDecimal maximum) {
        if (outstanding == null || rate == null || minimum == null || maximum == null || outstanding.signum() < 0 || rate.signum() < 0 || minimum.signum() < 0 || maximum.compareTo(minimum) < 0)
            throw new IllegalArgumentException("Invalid late-fee inputs.");
        if (outstanding.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return outstanding.multiply(rate).max(minimum).min(maximum).setScale(2, RoundingMode.HALF_UP);
    }
    public static String status(BigDecimal total, BigDecimal paid, boolean overdue) {
        if (paid.compareTo(total) >= 0) return "PAID";
        if (overdue) return paid.signum() > 0 ? "PARTIAL_OVERDUE" : "OVERDUE";
        return paid.signum() > 0 ? "PARTIALLY_PAID" : "UNPAID";
    }
}
