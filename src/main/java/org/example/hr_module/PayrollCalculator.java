package org.example.hr_module;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public final class PayrollCalculator {
    private PayrollCalculator() {}
    public record Result(BigDecimal gross, BigDecimal net) {}
    public static Result calculate(String method, BigDecimal rate, BigDecimal standardDays,
            BigDecimal units, BigDecimal overtimeHours, BigDecimal overtimeRate,
            BigDecimal allowance, BigDecimal bonus, BigDecimal deductions) {
        if (method == null || !Set.of("MONTHLY", "HOURLY", "TASK").contains(method))
            throw new IllegalArgumentException("Choose a valid salary method.");
        for (BigDecimal n : new BigDecimal[]{rate, standardDays, units, overtimeHours, overtimeRate, allowance, bonus, deductions})
            if (n == null || n.signum() < 0 || n.scale() > 2 || n.compareTo(new BigDecimal("999999999")) > 0)
                throw new IllegalArgumentException("Amounts must be between 0 and 999999999 with at most 2 decimal places.");
        if (standardDays.signum() == 0 || standardDays.compareTo(new BigDecimal("31")) > 0)
            throw new IllegalArgumentException("Standard days must be greater than 0 and at most 31.");
        if (units.compareTo(new BigDecimal("99999")) > 0 || overtimeHours.compareTo(new BigDecimal("744")) > 0)
            throw new IllegalArgumentException("Units cannot exceed 99999; overtime hours cannot exceed 744.");
        if (method.equals("MONTHLY") && units.compareTo(standardDays) > 0)
            throw new IllegalArgumentException("Paid days cannot exceed standard days. Enter overtime separately.");
        BigDecimal base = method.equals("MONTHLY") ? rate.multiply(units).divide(standardDays, 2, RoundingMode.HALF_UP) : rate.multiply(units);
        BigDecimal gross = base.add(overtimeHours.multiply(overtimeRate)).add(allowance).add(bonus).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(deductions).setScale(2, RoundingMode.HALF_UP);
        if (net.signum() < 0) throw new IllegalArgumentException("Deductions cannot exceed gross pay.");
        return new Result(gross, net);
    }
}
