package com.carddemo.batch.interest.domain;

import com.carddemo.batch.interest.io.FixedWidthFormat;

public final class CardXrefRecord {

    public static final int RECLN = 50;

    private static final int OFF_CARD_NUM = 0;
    private static final int LEN_CARD_NUM = 16;
    private static final int OFF_ACCT_ID = 25;
    private static final int LEN_ACCT_ID = 11;

    private final byte[] raw;

    private CardXrefRecord(byte[] raw) {
        this.raw = raw;
    }

    public static CardXrefRecord parse(byte[] bytes) {
        if (bytes.length != RECLN) {
            throw new IllegalArgumentException(
                    "CardXrefRecord requires " + RECLN + " bytes, got " + bytes.length);
        }
        return new CardXrefRecord(bytes.clone());
    }

    public String cardNum() {
        return FixedWidthFormat.text(raw, OFF_CARD_NUM, LEN_CARD_NUM);
    }

    public long acctId() {
        return FixedWidthFormat.unsignedNumeric(raw, OFF_ACCT_ID, LEN_ACCT_ID);
    }

    public byte[] toBytes() {
        return raw.clone();
    }
}
