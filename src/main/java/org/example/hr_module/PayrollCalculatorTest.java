package org.example.hr_module;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayrollCalculatorTest {
    private static BigDecimal n(String v) { return new BigDecimal(v); }
    private PayrollCalculator.Result pay(String method, String rate, String days, String units, String deductions) {
        return PayrollCalculator.calculate(method,n(rate),n(days),n(units),n("2"),n("100000"),n("500000"),n("300000"),n(deductions));
    }
    @Test void monthlyProratesAndAddsExtrasThenDeducts() {
        var result=pay("MONTHLY","26000000","26","20","1000000");
        assertEquals(n("21000000.00"),result.gross());
        assertEquals(n("20000000.00"),result.net());
    }
    @Test void hourlySupportsFractionalHours() { assertEquals(n("1525000.00"),pay("HOURLY","100000","26","5.25","0").net()); }
    @Test void taskPayUsesVerifiedUnits() { assertEquals(n("2000000.00"),pay("TASK","250000","26","4","0").net()); }
    @Test void roundsMoneyHalfUp() { assertEquals(n("1000000.33"),pay("MONTHLY","1","3","1","0").net()); }
    @Test void rejectsZeroStandardDays() { assertThrows(IllegalArgumentException.class,()->pay("MONTHLY","100","0","1","0")); }
    @Test void rejectsNegativeAndOverPrecision() {
        assertThrows(IllegalArgumentException.class,()->pay("HOURLY","-1","26","1","0"));
        assertThrows(IllegalArgumentException.class,()->pay("HOURLY","1.001","26","1","0"));
    }
    @Test void rejectsOverpaidDaysAndExcessDeductions() {
        assertThrows(IllegalArgumentException.class,()->pay("MONTHLY","100","26","27","0"));
        assertThrows(IllegalArgumentException.class,()->pay("TASK","100","26","1","99999999"));
    }
    @Test void rejectsUnsupportedMethod() { assertThrows(IllegalArgumentException.class,()->pay("CUSTOM","100","26","1","0")); }
}
