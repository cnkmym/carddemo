package com.carddemo.batch.interest;

import com.carddemo.batch.interest.io.DisclosureGroupFile;
import com.carddemo.batch.interest.util.BatchAbendException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each fixture line is exactly 50 bytes:
 *   GROUP X(10) | TYPE X(2) | CAT 9(4) | RATE S9(4)V99 [6] | FILLER X(28)
 *
 * Rate encodings (PIC S9(4)V99 = 6 byte positions, sign overpunch on
 * the last digit):
 *   1.50% -> raw 150  -> "000150" -> "00015{"
 *   1.75% -> raw 175  -> "000175" -> "00017E"
 */
class DisclosureGroupFallbackTest {

    @Test
    void direct_lookup_returns_specific_rate(@TempDir Path tmp) throws IOException {
        Path file = writeDiscgrp(tmp,
                "A00000000001000100015{0000000000000000000000000000",
                "DEFAULT   01000100017E0000000000000000000000000000");
        DisclosureGroupFile dg = DisclosureGroupFile.load(file);

        assertThat(dg.lookupWithDefaultFallback("A000000000", "01", "0001").interestRate())
                .isEqualByComparingTo("1.50");
    }

    @Test
    void missing_specific_falls_back_to_default(@TempDir Path tmp) throws IOException {
        Path file = writeDiscgrp(tmp,
                "DEFAULT   01000100017E0000000000000000000000000000");
        DisclosureGroupFile dg = DisclosureGroupFile.load(file);

        assertThat(dg.lookupWithDefaultFallback("A000000000", "01", "0001").interestRate())
                .isEqualByComparingTo("1.75");
    }

    @Test
    void missing_specific_and_missing_default_abends(@TempDir Path tmp) throws IOException {
        Path file = writeDiscgrp(tmp,
                "B00000000001000100015{0000000000000000000000000000");
        DisclosureGroupFile dg = DisclosureGroupFile.load(file);

        assertThatThrownBy(() -> dg.lookupWithDefaultFallback("A000000000", "01", "0001"))
                .isInstanceOf(BatchAbendException.class)
                .hasMessageContaining("DEFAULT");
    }

    private static Path writeDiscgrp(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("discgrp.dat");
        try (var out = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
            for (String line : lines) {
                if (line.length() != 50) {
                    throw new IllegalArgumentException(
                            "Discgrp fixture must be 50 chars, got " + line.length() + ": " + line);
                }
                out.write(line);
                out.write('\n');
            }
        }
        return file;
    }
}
