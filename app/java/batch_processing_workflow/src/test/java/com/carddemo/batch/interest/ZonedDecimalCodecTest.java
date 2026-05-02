package com.carddemo.batch.interest;

import com.carddemo.batch.interest.io.ZonedDecimalCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZonedDecimalCodecTest {

    @ParameterizedTest(name = "decode \"{0}\" -> {1}")
    @CsvSource({
            "00000001940{,  194.00",
            "00000020200{, 2020.00",
            "00000010200{, 1020.00",
            "00000000000{,    0.00",
            "00000000000A,    0.01",
            "00000000000I,    0.09",
            "00000000000},    0.00",
            "00000000000J,   -0.01",
            "00000000000R,   -0.09",
            "9999999999I,      999999999.99",
    })
    void decodes_known_zoned_decimal_strings(String encoded, String expected) {
        byte[] bytes = encoded.getBytes(StandardCharsets.ISO_8859_1);
        BigDecimal actual = ZonedDecimalCodec.decode(bytes, 0, bytes.length, 2);
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected.trim()));
    }

    @Test
    void roundtrips_every_positive_overpunch() {
        char[] positives = {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};
        for (int digit = 0; digit <= 9; digit++) {
            String encoded = "0000000000" + positives[digit];
            byte[] bytes = encoded.getBytes(StandardCharsets.ISO_8859_1);
            BigDecimal value = ZonedDecimalCodec.decode(bytes, 0, 11, 2);
            byte[] reEncoded = new byte[11];
            ZonedDecimalCodec.encode(value, reEncoded, 0, 11, 2);
            assertThat(new String(reEncoded, StandardCharsets.ISO_8859_1))
                    .as("round-trip for positive overpunch '%s'", positives[digit])
                    .isEqualTo(encoded);
        }
    }

    @Test
    void roundtrips_every_negative_overpunch() {
        char[] negatives = {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};
        for (int digit = 1; digit <= 9; digit++) {
            String encoded = "0000000000" + negatives[digit];
            byte[] bytes = encoded.getBytes(StandardCharsets.ISO_8859_1);
            BigDecimal value = ZonedDecimalCodec.decode(bytes, 0, 11, 2);
            byte[] reEncoded = new byte[11];
            ZonedDecimalCodec.encode(value, reEncoded, 0, 11, 2);
            assertThat(new String(reEncoded, StandardCharsets.ISO_8859_1))
                    .as("round-trip for negative overpunch '%s'", negatives[digit])
                    .isEqualTo(encoded);
        }
    }

    @Test
    void encodes_into_full_buffer_with_offset() {
        byte[] buf = new byte[20];
        java.util.Arrays.fill(buf, (byte) ' ');
        ZonedDecimalCodec.encode(new BigDecimal("194.00"), buf, 4, 12, 2);
        assertThat(new String(buf, StandardCharsets.ISO_8859_1))
                .isEqualTo("    00000001940{    ");
    }

    @Test
    void rejects_invalid_overpunch_byte() {
        byte[] bytes = "0000000000?".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> ZonedDecimalCodec.decode(bytes, 0, 11, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_overflow_on_encode() {
        byte[] buf = new byte[5];
        assertThatThrownBy(() -> ZonedDecimalCodec.encode(
                new BigDecimal("999999999.99"), buf, 0, 5, 2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void encode_truncates_extra_precision_toward_zero() {
        byte[] buf = new byte[6];
        ZonedDecimalCodec.encode(new BigDecimal("1.999"), buf, 0, 6, 2);
        assertThat(new String(buf, StandardCharsets.ISO_8859_1)).isEqualTo("00019I");
    }
}
