package com.carddemo.batch.interest;

import com.carddemo.batch.interest.util.Db2Timestamp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("goldenFilesPresent")
class GoldenFileEquivalenceIT {

    private static final String FIXTURE_BASE = "src/test/resources/fixtures";
    private static final String PARM_DATE = "2025-04-29";
    private static final Instant FIXED_INSTANT = LocalDateTime.of(2025, 4, 29, 12, 0, 0, 0)
            .toInstant(ZoneOffset.UTC);

    @BeforeAll
    static void freezeClock() {
        Db2Timestamp.useFixedClock(FIXED_INSTANT, ZoneId.of("UTC"));
    }

    @Test
    void java_output_matches_cobol_reference_byte_for_byte(@TempDir Path tmp) throws IOException {
        Path inputDir = Paths.get(FIXTURE_BASE, "input");
        Path expectedDir = Paths.get(FIXTURE_BASE, "expected");

        Path acctIn = tmp.resolve("acctdata.dat");
        Files.copy(inputDir.resolve("acctdata.txt"), acctIn, StandardCopyOption.REPLACE_EXISTING);

        InterestCalculator.Config cfg = new InterestCalculator.Config(
                PARM_DATE,
                inputDir.resolve("tcatbal.txt"),
                inputDir.resolve("cardxref.txt"),
                acctIn,
                inputDir.resolve("discgrp.txt"),
                tmp.resolve("acctdata.out"),
                tmp.resolve("transact.dat"));

        new InterestCalculator(cfg).run();

        assertBytesMatch(expectedDir.resolve("transact.dat"), tmp.resolve("transact.dat"));
        assertBytesMatch(expectedDir.resolve("acctdata.out"), tmp.resolve("acctdata.out"));
    }

    @SuppressWarnings("unused")
    static boolean goldenFilesPresent() {
        return Files.exists(Paths.get(FIXTURE_BASE, "expected", "transact.dat"))
                && Files.exists(Paths.get(FIXTURE_BASE, "expected", "acctdata.out"));
    }

    private static void assertBytesMatch(Path expected, Path actual) throws IOException {
        long mismatchOffset = Files.mismatch(expected, actual);
        if (mismatchOffset != -1L) {
            byte[] eb = Files.readAllBytes(expected);
            byte[] ab = Files.readAllBytes(actual);
            String context = String.format(
                    "First mismatch at byte offset %d. Expected length=%d, actual length=%d.%nExpected: %s%nActual:   %s",
                    mismatchOffset, eb.length, ab.length,
                    snippet(eb, mismatchOffset),
                    snippet(ab, mismatchOffset));
            assertThat(mismatchOffset).as(context).isEqualTo(-1L);
        }
    }

    private static String snippet(byte[] bytes, long offset) {
        int start = (int) Math.max(0, offset - 8);
        int end = (int) Math.min(bytes.length, offset + 24);
        return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.ISO_8859_1)
                .replace('\n', '¶');
    }
}
