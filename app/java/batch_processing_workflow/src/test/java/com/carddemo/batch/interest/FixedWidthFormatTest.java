package com.carddemo.batch.interest;

import com.carddemo.batch.interest.io.FixedWidthFormat;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedWidthFormatTest {

    @Test
    void unsignedNumeric_parses_zero_padded_digits() {
        byte[] bytes = "00000000123".getBytes(StandardCharsets.ISO_8859_1);
        long value = FixedWidthFormat.unsignedNumeric(bytes, 0, 11);
        assertThat(value).isEqualTo(123L);
    }

    @Test
    void unsignedNumeric_rejects_non_digit() {
        byte[] bytes = "0000000012X".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> FixedWidthFormat.unsignedNumeric(bytes, 0, 11))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeUnsignedNumeric_zero_pads() {
        byte[] buf = new byte[11];
        FixedWidthFormat.writeUnsignedNumeric(123L, buf, 0, 11);
        assertThat(new String(buf, StandardCharsets.ISO_8859_1)).isEqualTo("00000000123");
    }

    @Test
    void writeUnsignedNumeric_rejects_overflow() {
        byte[] buf = new byte[3];
        assertThatThrownBy(() -> FixedWidthFormat.writeUnsignedNumeric(9999L, buf, 0, 3))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void writeText_pads_with_spaces() {
        byte[] buf = new byte[10];
        FixedWidthFormat.writeText("AB", buf, 0, 10);
        assertThat(new String(buf, StandardCharsets.ISO_8859_1)).isEqualTo("AB        ");
    }

    @Test
    void writeText_truncates_when_value_too_long() {
        byte[] buf = new byte[5];
        FixedWidthFormat.writeText("ABCDEFG", buf, 0, 5);
        assertThat(new String(buf, StandardCharsets.ISO_8859_1)).isEqualTo("ABCDE");
    }

    @Test
    void padToLength_pads_short_input() {
        byte[] padded = FixedWidthFormat.padToLength("ABC".getBytes(StandardCharsets.ISO_8859_1), 5);
        assertThat(new String(padded, StandardCharsets.ISO_8859_1)).isEqualTo("ABC  ");
    }

    @Test
    void padToLength_truncates_long_input() {
        byte[] padded = FixedWidthFormat.padToLength("ABCDEFG".getBytes(StandardCharsets.ISO_8859_1), 5);
        assertThat(new String(padded, StandardCharsets.ISO_8859_1)).isEqualTo("ABCDE");
    }
}
