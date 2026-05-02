package com.carddemo.batch.interest.domain;

import com.carddemo.batch.interest.io.FixedWidthFormat;
import com.carddemo.batch.interest.io.ZonedDecimalCodec;

import java.math.BigDecimal;

public final class AccountRecord {

    public static final int RECLN = 300;

    private static final int OFF_ACCT_ID = 0;
    private static final int OFF_CURR_BAL = 12;
    private static final int OFF_CURR_CYC_CREDIT = 78;
    private static final int OFF_CURR_CYC_DEBIT = 90;
    private static final int OFF_GROUP_ID = 112;

    private static final int LEN_DECIMAL_FIELD = 12;
    private static final int LEN_GROUP_ID = 10;

    private final byte[] raw;

    private AccountRecord(byte[] raw) {
        this.raw = raw;
    }

    public static AccountRecord parse(byte[] bytes) {
        if (bytes.length != RECLN) {
            throw new IllegalArgumentException(
                    "AccountRecord requires " + RECLN + " bytes, got " + bytes.length);
        }
        return new AccountRecord(bytes.clone());
    }

    public long acctId() {
        return FixedWidthFormat.unsignedNumeric(raw, OFF_ACCT_ID, 11);
    }

    public BigDecimal currBal() {
        return ZonedDecimalCodec.decode(raw, OFF_CURR_BAL, LEN_DECIMAL_FIELD, 2);
    }

    public BigDecimal currCycCredit() {
        return ZonedDecimalCodec.decode(raw, OFF_CURR_CYC_CREDIT, LEN_DECIMAL_FIELD, 2);
    }

    public BigDecimal currCycDebit() {
        return ZonedDecimalCodec.decode(raw, OFF_CURR_CYC_DEBIT, LEN_DECIMAL_FIELD, 2);
    }

    public String groupId() {
        return FixedWidthFormat.text(raw, OFF_GROUP_ID, LEN_GROUP_ID);
    }

    public AccountRecord withInterestApplied(BigDecimal accumulatedInterest) {
        BigDecimal newBalance = currBal().add(accumulatedInterest);
        byte[] copy = raw.clone();
        ZonedDecimalCodec.encode(newBalance, copy, OFF_CURR_BAL, LEN_DECIMAL_FIELD, 2);
        ZonedDecimalCodec.encode(BigDecimal.ZERO.setScale(2),
                copy, OFF_CURR_CYC_CREDIT, LEN_DECIMAL_FIELD, 2);
        ZonedDecimalCodec.encode(BigDecimal.ZERO.setScale(2),
                copy, OFF_CURR_CYC_DEBIT, LEN_DECIMAL_FIELD, 2);
        return new AccountRecord(copy);
    }

    public byte[] toBytes() {
        return raw.clone();
    }
}
