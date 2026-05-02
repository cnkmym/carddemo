package com.carddemo.batch.interest.util;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class Db2Timestamp {

    private static volatile Clock clock = Clock.systemDefaultZone();

    private Db2Timestamp() {}

    public static String now() {
        LocalDateTime t = LocalDateTime.now(clock);
        // CURRENT-DATE in COBOL produces hundredths of a second (2 digits).
        // The original program then pads with '0000' to fill the 6-digit
        // subsecond field of the DB2 timestamp format.
        int hundredths = t.getNano() / 10_000_000;
        return String.format(
                "%04d-%02d-%02d-%02d.%02d.%02d.%02d0000",
                t.getYear(),
                t.getMonthValue(),
                t.getDayOfMonth(),
                t.getHour(),
                t.getMinute(),
                t.getSecond(),
                hundredths);
    }

    public static void useFixedClock(Instant instant, ZoneId zone) {
        clock = Clock.fixed(instant, zone);
    }

    public static void useSystemClock() {
        clock = Clock.systemDefaultZone();
    }
}
