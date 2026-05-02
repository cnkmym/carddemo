package com.carddemo.batch.interest.domain;

import com.carddemo.batch.interest.io.FixedWidthFormat;
import com.carddemo.batch.interest.io.ZonedDecimalCodec;

import java.math.BigDecimal;

public final class TransactionCategoryBalanceRecord {

    public static final int RECLN = 50;

    private static final int OFF_ACCT_ID = 0;
    private static final int LEN_ACCT_ID = 11;
    private static final int OFF_TYPE_CD = 11;
    private static final int LEN_TYPE_CD = 2;
    private static final int OFF_CAT_CD = 13;
    private static final int LEN_CAT_CD = 4;
    private static final int OFF_BALANCE = 17;
    private static final int LEN_BALANCE = 11;

    private final byte[] raw;

    private TransactionCategoryBalanceRecord(byte[] raw) {
        this.raw = raw;
    }

    public static TransactionCategoryBalanceRecord parse(byte[] bytes) {
        if (bytes.length != RECLN) {
            throw new IllegalArgumentException(
                    "TransactionCategoryBalanceRecord requires " + RECLN + " bytes, got " + bytes.length);
        }
        return new TransactionCategoryBalanceRecord(bytes.clone());
    }

    public long acctId() {
        return FixedWidthFormat.unsignedNumeric(raw, OFF_ACCT_ID, LEN_ACCT_ID);
    }

    public String tranTypeCd() {
        return FixedWidthFormat.text(raw, OFF_TYPE_CD, LEN_TYPE_CD);
    }

    public String tranCatCd() {
        return FixedWidthFormat.text(raw, OFF_CAT_CD, LEN_CAT_CD);
    }

    public BigDecimal balance() {
        return ZonedDecimalCodec.decode(raw, OFF_BALANCE, LEN_BALANCE, 2);
    }

    public byte[] toBytes() {
        return raw.clone();
    }
}
