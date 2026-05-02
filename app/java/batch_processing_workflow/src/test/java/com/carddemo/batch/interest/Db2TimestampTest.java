package com.carddemo.batch.interest;

import com.carddemo.batch.interest.util.Db2Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class Db2TimestampTest {

    @AfterEach
    void resetClock() {
        Db2Timestamp.useSystemClock();
    }

    @Test
    void format_is_exactly_26_chars_with_subsecond_padding() {
        Instant fixed = LocalDateTime.of(2025, 4, 29, 12, 0, 0, 230_000_000)
                .toInstant(ZoneOffset.UTC);
        Db2Timestamp.useFixedClock(fixed, ZoneId.of("UTC"));
        String ts = Db2Timestamp.now();

        assertThat(ts).isEqualTo("2025-04-29-12.00.00.230000");
        assertThat(ts).hasSize(26);
    }

    @Test
    void midnight_renders_correctly() {
        Instant fixed = LocalDateTime.of(2022, 7, 18, 0, 0, 0, 0)
                .toInstant(ZoneOffset.UTC);
        Db2Timestamp.useFixedClock(fixed, ZoneId.of("UTC"));
        assertThat(Db2Timestamp.now()).isEqualTo("2022-07-18-00.00.00.000000");
    }

    @Test
    void hundredths_of_second_only_two_digits_shown() {
        // 99 hundredths = 990,000,000 nanos
        Instant fixed = LocalDateTime.of(2025, 1, 1, 1, 2, 3, 990_000_000)
                .toInstant(ZoneOffset.UTC);
        Db2Timestamp.useFixedClock(fixed, ZoneId.of("UTC"));
        assertThat(Db2Timestamp.now()).isEqualTo("2025-01-01-01.02.03.990000");
    }
}
