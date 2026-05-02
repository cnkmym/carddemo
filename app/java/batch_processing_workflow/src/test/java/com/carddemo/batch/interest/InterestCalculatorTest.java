package com.carddemo.batch.interest;

import com.carddemo.batch.interest.domain.AccountRecord;
import com.carddemo.batch.interest.domain.TransactionRecord;
import com.carddemo.batch.interest.io.AccountFile;
import com.carddemo.batch.interest.util.Db2Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture cheat-sheet for the synthetic records below — every field is
 * exactly as wide as its COBOL PIC clause, including sign overpunch on
 * the last digit of any signed numeric:
 *
 *   ACCOUNT-RECORD (300 bytes; 122 visible + 178 trailing pad):
 *     ACCT-ID 9(11) | STATUS X | CURR-BAL S9(10)V99 [12]
 *     | CREDIT-LIMIT [12] | CASH-CREDIT-LIMIT [12]
 *     | OPEN-DATE [10] | EXP-DATE [10] | REISSUE-DATE [10]
 *     | CYC-CREDIT [12] | CYC-DEBIT [12]
 *     | ADDR-ZIP X(10) | GROUP-ID X(10) | FILLER X(178)
 *
 *   TRAN-CAT-BAL-RECORD (50 bytes):
 *     ACCT-ID 9(11) | TYPE X(2) | CAT 9(4) | BAL S9(9)V99 [11] | FILLER [22]
 *
 *   CARD-XREF-RECORD (50 bytes):
 *     CARD X(16) | CUST 9(9) | ACCT 9(11) | FILLER [14]
 *
 *   DIS-GROUP-RECORD (50 bytes):
 *     GROUP X(10) | TYPE X(2) | CAT 9(4) | RATE S9(4)V99 [6] | FILLER [28]
 *
 * Sign overpunch on the last digit of a signed field: '{'=+0,
 * 'A'..'I'=+1..+9, '}'=-0, 'J'..'R'=-1..-9, '0'..'9'=plain digit (sign +).
 */
class InterestCalculatorTest {

    @BeforeEach
    void freezeClock() {
        Instant fixed = LocalDateTime.of(2025, 4, 29, 12, 0, 0, 0)
                .toInstant(ZoneOffset.UTC);
        Db2Timestamp.useFixedClock(fixed, ZoneId.of("UTC"));
    }

    @AfterEach
    void thawClock() {
        Db2Timestamp.useSystemClock();
    }

    @Test
    void single_account_writes_one_interest_transaction(@TempDir Path tmp) throws IOException {
        // 1 account: id=1, balance=$1000.00, group=A000000000.
        writeRecords(tmp.resolve("acct.dat"), AccountRecord.RECLN,
                "00000000001Y00000010000{00000050000{00000020000{2020-01-012030-01-012025-01-0100000000000{00000000000{12345     A000000000");
        // 1 tcatbal row: acct=1 type=01 cat=0001 balance=$1000.00.
        writeRecords(tmp.resolve("tcatbal.dat"), 50,
                "000000000010100010000010000{0000000000000000000000");
        // 1 xref: card 0000000000000001 -> acct 1.
        writeRecords(tmp.resolve("xref.dat"), 50,
                "00000000000000010000000010000000000100000000000000");
        // discgrp (A000000000, 01, 0001) -> 12.00% APR.
        writeRecords(tmp.resolve("discgrp.dat"), 50,
                "A00000000001000100120{0000000000000000000000000000");

        runCalculator(tmp, "2025-04-29");

        // Transaction file: 1 record + \n = 351 bytes.
        byte[] transact = Files.readAllBytes(tmp.resolve("transact.dat"));
        assertThat(transact).hasSize(TransactionRecord.RECLN + 1);
        String txStr = new String(transact, 0, TransactionRecord.RECLN, StandardCharsets.ISO_8859_1);
        assertThat(txStr).startsWith("2025-04-29000001");
        assertThat(txStr.substring(16, 22)).isEqualTo("010005");
        assertThat(txStr.substring(22, 32)).isEqualTo("System    ");
        assertThat(txStr.substring(32, 56)).isEqualTo("Int. for a/c 00000000001");

        // 12% / 12 months on $1000 balance = $10. Final balance $1010.
        AccountFile updated = AccountFile.load(tmp.resolve("acct.out"));
        assertThat(updated.lookup(1L).currBal()).isEqualByComparingTo("1010.00");
    }

    @Test
    void zero_rate_writes_no_transaction(@TempDir Path tmp) throws IOException {
        writeRecords(tmp.resolve("acct.dat"), AccountRecord.RECLN,
                "00000000001Y00000010000{00000050000{00000020000{2020-01-012030-01-012025-01-0100000000000{00000000000{12345     A000000000");
        writeRecords(tmp.resolve("tcatbal.dat"), 50,
                "000000000010100010000010000{0000000000000000000000");
        writeRecords(tmp.resolve("xref.dat"), 50,
                "00000000000000010000000010000000000100000000000000");
        // 0% rate -> no transaction written, balance unchanged.
        writeRecords(tmp.resolve("discgrp.dat"), 50,
                "A00000000001000100000{0000000000000000000000000000");

        runCalculator(tmp, "2025-04-29");

        assertThat(Files.size(tmp.resolve("transact.dat"))).isZero();

        AccountFile updated = AccountFile.load(tmp.resolve("acct.out"));
        assertThat(updated.lookup(1L).currBal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void multiple_accounts_each_get_their_own_update(@TempDir Path tmp) throws IOException {
        // Two accounts, each with one tcatbal row.
        writeRecords(tmp.resolve("acct.dat"), AccountRecord.RECLN,
                "00000000001Y00000010000{00000050000{00000020000{2020-01-012030-01-012025-01-0100000000000{00000000000{12345     A000000000",
                "00000000002Y00000020000{00000050000{00000020000{2020-01-012030-01-012025-01-0100000000000{00000000000{12345     A000000000");
        writeRecords(tmp.resolve("tcatbal.dat"), 50,
                "000000000010100010000010000{0000000000000000000000",
                "000000000020100010000020000{0000000000000000000000");
        writeRecords(tmp.resolve("xref.dat"), 50,
                "00000000000000010000000010000000000100000000000000",
                "00000000000000020000000020000000000200000000000000");
        writeRecords(tmp.resolve("discgrp.dat"), 50,
                "A00000000001000100120{0000000000000000000000000000");

        runCalculator(tmp, "2025-04-29");

        byte[] transact = Files.readAllBytes(tmp.resolve("transact.dat"));
        assertThat(transact).hasSize(2 * (TransactionRecord.RECLN + 1));

        // 12%/12 = 1% monthly: $1000 -> +$10 -> $1010 ; $2000 -> +$20 -> $2020.
        AccountFile updated = AccountFile.load(tmp.resolve("acct.out"));
        assertThat(updated.lookup(1L).currBal()).isEqualByComparingTo("1010.00");
        assertThat(updated.lookup(2L).currBal()).isEqualByComparingTo("2020.00");
    }

    @Test
    void default_group_fallback_when_specific_missing(@TempDir Path tmp) throws IOException {
        // Account uses group ZZZZZZZZZZ which isn't in the discgrp file.
        writeRecords(tmp.resolve("acct.dat"), AccountRecord.RECLN,
                "00000000001Y00000010000{00000050000{00000020000{2020-01-012030-01-012025-01-0100000000000{00000000000{12345     ZZZZZZZZZZ");
        writeRecords(tmp.resolve("tcatbal.dat"), 50,
                "000000000010100010000010000{0000000000000000000000");
        writeRecords(tmp.resolve("xref.dat"), 50,
                "00000000000000010000000010000000000100000000000000");
        // Specific (ZZZZZZZZZZ, 01, 0001) is missing; DEFAULT (with trailing
        // spaces to fill 10-byte group key) is present at 24.00% APR.
        writeRecords(tmp.resolve("discgrp.dat"), 50,
                "DEFAULT   01000100240{0000000000000000000000000000");

        runCalculator(tmp, "2025-04-29");

        // 24%/12 = 2% monthly on $1000 = $20. Final balance $1020.
        AccountFile updated = AccountFile.load(tmp.resolve("acct.out"));
        assertThat(updated.lookup(1L).currBal()).isEqualByComparingTo("1020.00");
    }

    private static void runCalculator(Path tmp, String parmDate) throws IOException {
        InterestCalculator.Config cfg = new InterestCalculator.Config(
                parmDate,
                tmp.resolve("tcatbal.dat"),
                tmp.resolve("xref.dat"),
                tmp.resolve("acct.dat"),
                tmp.resolve("discgrp.dat"),
                tmp.resolve("acct.out"),
                tmp.resolve("transact.dat"));
        new InterestCalculator(cfg).run();
    }

    private static void writeRecords(Path file, int recLen, String... visibleParts) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            for (String visible : visibleParts) {
                byte[] bytes = visible.getBytes(StandardCharsets.ISO_8859_1);
                if (bytes.length > recLen) {
                    throw new IllegalArgumentException(
                            "Fixture string is " + bytes.length + " bytes but record length is "
                                    + recLen + ": " + visible);
                }
                out.write(bytes);
                if (bytes.length < recLen) {
                    byte[] pad = new byte[recLen - bytes.length];
                    java.util.Arrays.fill(pad, (byte) ' ');
                    out.write(pad);
                }
                out.write('\n');
            }
        }
    }
}
