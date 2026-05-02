package com.carddemo.batch.interest;

import com.carddemo.batch.interest.domain.AccountRecord;
import com.carddemo.batch.interest.domain.CardXrefRecord;
import com.carddemo.batch.interest.domain.DisclosureGroupRecord;
import com.carddemo.batch.interest.domain.TransactionCategoryBalanceRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DomainRecordRoundTripTest {

    @Test
    void account_record_roundtrip_preserves_filler_and_decimal_fields() {
        // First account from app/data/ASCII/acctdata.txt, padded to 300 bytes.
        String visible = "00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-202025-05-2000000000000{00000000000{A000000000";
        byte[] bytes = pad(visible, AccountRecord.RECLN);

        AccountRecord record = AccountRecord.parse(bytes);
        assertThat(record.acctId()).isEqualTo(1L);
        assertThat(record.currBal()).isEqualByComparingTo("194.00");
        assertThat(record.currCycCredit()).isEqualByComparingTo("0.00");
        assertThat(record.currCycDebit()).isEqualByComparingTo("0.00");

        assertThat(record.toBytes()).isEqualTo(bytes);
    }

    @Test
    void account_record_with_interest_applied_keeps_filler_intact() {
        String visible = "00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-202025-05-2000000000000{00000000000{A000000000";
        byte[] bytes = pad(visible, AccountRecord.RECLN);
        AccountRecord original = AccountRecord.parse(bytes);

        AccountRecord updated = original.withInterestApplied(new BigDecimal("12.50"));

        assertThat(updated.currBal()).isEqualByComparingTo("206.50");
        assertThat(updated.currCycCredit()).isEqualByComparingTo("0.00");
        assertThat(updated.currCycDebit()).isEqualByComparingTo("0.00");
        // Bytes after the cycle-debit field (offset 102 onward) must be unchanged.
        assertThat(java.util.Arrays.copyOfRange(updated.toBytes(), 102, 300))
                .isEqualTo(java.util.Arrays.copyOfRange(bytes, 102, 300));
    }

    @Test
    void tcatbal_record_roundtrip() {
        String visible = "000000000010100010000000000{0000000000000000000000";
        byte[] bytes = pad(visible, TransactionCategoryBalanceRecord.RECLN);
        TransactionCategoryBalanceRecord r = TransactionCategoryBalanceRecord.parse(bytes);

        assertThat(r.acctId()).isEqualTo(1L);
        assertThat(r.tranTypeCd()).isEqualTo("01");
        assertThat(r.tranCatCd()).isEqualTo("0001");
        assertThat(r.balance()).isEqualByComparingTo("0.00");
        assertThat(r.toBytes()).isEqualTo(bytes);
    }

    @Test
    void cardxref_record_roundtrip() {
        String visible = "050002445376574000000005000000000050";
        byte[] bytes = pad(visible, CardXrefRecord.RECLN);
        CardXrefRecord r = CardXrefRecord.parse(bytes);

        assertThat(r.cardNum()).isEqualTo("0500024453765740");
        assertThat(r.acctId()).isEqualTo(50L);
        assertThat(r.toBytes()).isEqualTo(bytes);
    }

    @Test
    void discgrp_record_roundtrip() {
        String visible = "A00000000001000100150{0000000000000000000000000000";
        byte[] bytes = pad(visible, DisclosureGroupRecord.RECLN);
        DisclosureGroupRecord r = DisclosureGroupRecord.parse(bytes);

        assertThat(r.groupId()).isEqualTo("A000000000");
        assertThat(r.tranTypeCd()).isEqualTo("01");
        assertThat(r.tranCatCd()).isEqualTo("0001");
        assertThat(r.interestRate()).isEqualByComparingTo("15.00");
        assertThat(r.toBytes()).isEqualTo(bytes);
    }

    private static byte[] pad(String visible, int length) {
        byte[] bytes = visible.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length == length) return bytes;
        byte[] padded = new byte[length];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        for (int i = bytes.length; i < length; i++) padded[i] = (byte) ' ';
        return padded;
    }
}
