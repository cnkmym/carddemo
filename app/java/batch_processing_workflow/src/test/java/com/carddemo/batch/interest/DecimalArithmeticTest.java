package com.carddemo.batch.interest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalArithmeticTest {

    @ParameterizedTest(name = "({0} * {1}) / 1200 = {2}")
    @CsvSource({
            // balance, rate, expected (truncated toward zero)
            "  100.00,  1.50,  0.12",
            "  100.00,  0.07,  0.00",
            " 1234.56,  1.50,  1.54",
            " 1000.00,  0.99,  0.82",
            "10000.00,  5.00, 41.66",
            "    0.00,  5.00,  0.00",
            "  100.00,  0.00,  0.00",
            " -100.00,  1.50, -0.12",
            "  123.45,  1.99,  0.20",
    })
    void mirrors_cobol_truncating_compute(String balance, String rate, String expected) {
        BigDecimal monthly = new BigDecimal(balance.trim())
                .multiply(new BigDecimal(rate.trim()))
                .divide(new BigDecimal("1200"), 2, RoundingMode.DOWN);
        assertThat(monthly).isEqualByComparingTo(expected.trim());
    }

    @Test
    void rounding_mode_down_differs_from_half_up_for_signed_truncation() {
        BigDecimal value = new BigDecimal("0.157");
        assertThat(value.setScale(2, RoundingMode.DOWN)).isEqualByComparingTo("0.15");
        assertThat(value.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("0.16");

        BigDecimal negative = new BigDecimal("-0.157");
        assertThat(negative.setScale(2, RoundingMode.DOWN)).isEqualByComparingTo("-0.15");
        assertThat(negative.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("-0.16");
    }
}
