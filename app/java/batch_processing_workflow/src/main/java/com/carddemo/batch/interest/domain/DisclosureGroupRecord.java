package com.carddemo.batch.interest.domain;

import com.carddemo.batch.interest.io.FixedWidthFormat;
import com.carddemo.batch.interest.io.ZonedDecimalCodec;

import java.math.BigDecimal;

public final class DisclosureGroupRecord {

    public static final int RECLN = 50;

    private static final int OFF_GROUP_ID = 0;
    private static final int LEN_GROUP_ID = 10;
    private static final int OFF_TYPE_CD = 10;
    private static final int LEN_TYPE_CD = 2;
    private static final int OFF_CAT_CD = 12;
    private static final int LEN_CAT_CD = 4;
    private static final int OFF_INT_RATE = 16;
    private static final int LEN_INT_RATE = 6;

    private final byte[] raw;

    private DisclosureGroupRecord(byte[] raw) {
        this.raw = raw;
    }

    public static DisclosureGroupRecord parse(byte[] bytes) {
        if (bytes.length != RECLN) {
            throw new IllegalArgumentException(
                    "DisclosureGroupRecord requires " + RECLN + " bytes, got " + bytes.length);
        }
        return new DisclosureGroupRecord(bytes.clone());
    }

    public String groupId() {
        return FixedWidthFormat.text(raw, OFF_GROUP_ID, LEN_GROUP_ID);
    }

    public String tranTypeCd() {
        return FixedWidthFormat.text(raw, OFF_TYPE_CD, LEN_TYPE_CD);
    }

    public String tranCatCd() {
        return FixedWidthFormat.text(raw, OFF_CAT_CD, LEN_CAT_CD);
    }

    public BigDecimal interestRate() {
        return ZonedDecimalCodec.decode(raw, OFF_INT_RATE, LEN_INT_RATE, 2);
    }

    public byte[] toBytes() {
        return raw.clone();
    }
}
