package com.carddemo.batch.interest.domain;

import com.carddemo.batch.interest.io.FixedWidthFormat;
import com.carddemo.batch.interest.io.ZonedDecimalCodec;

import java.math.BigDecimal;

public final class TransactionRecord {

    public static final int RECLN = 350;

    private static final int OFF_TRAN_ID = 0;
    private static final int LEN_TRAN_ID = 16;
    private static final int OFF_TYPE_CD = 16;
    private static final int LEN_TYPE_CD = 2;
    private static final int OFF_CAT_CD = 18;
    private static final int LEN_CAT_CD = 4;
    private static final int OFF_SOURCE = 22;
    private static final int LEN_SOURCE = 10;
    private static final int OFF_DESC = 32;
    private static final int LEN_DESC = 100;
    private static final int OFF_AMT = 132;
    private static final int LEN_AMT = 11;
    private static final int OFF_MERCH_ID = 143;
    private static final int LEN_MERCH_ID = 9;
    private static final int OFF_MERCH_NAME = 152;
    private static final int LEN_MERCH_NAME = 50;
    private static final int OFF_MERCH_CITY = 202;
    private static final int LEN_MERCH_CITY = 50;
    private static final int OFF_MERCH_ZIP = 252;
    private static final int LEN_MERCH_ZIP = 10;
    private static final int OFF_CARD_NUM = 262;
    private static final int LEN_CARD_NUM = 16;
    private static final int OFF_ORIG_TS = 278;
    private static final int LEN_ORIG_TS = 26;
    private static final int OFF_PROC_TS = 304;
    private static final int LEN_PROC_TS = 26;
    private static final int OFF_FILLER = 330;
    private static final int LEN_FILLER = 20;

    private final byte[] raw;

    private TransactionRecord(byte[] raw) {
        this.raw = raw;
    }

    public static TransactionRecord build(
            String tranId,
            String typeCd,
            String catCd,
            String source,
            String desc,
            BigDecimal amount,
            String cardNum,
            String origTimestamp,
            String procTimestamp) {
        byte[] buf = new byte[RECLN];
        for (int i = 0; i < RECLN; i++) {
            buf[i] = (byte) ' ';
        }
        FixedWidthFormat.writeText(tranId, buf, OFF_TRAN_ID, LEN_TRAN_ID);
        FixedWidthFormat.writeText(typeCd, buf, OFF_TYPE_CD, LEN_TYPE_CD);
        FixedWidthFormat.writeText(catCd, buf, OFF_CAT_CD, LEN_CAT_CD);
        FixedWidthFormat.writeText(source, buf, OFF_SOURCE, LEN_SOURCE);
        FixedWidthFormat.writeText(desc, buf, OFF_DESC, LEN_DESC);
        ZonedDecimalCodec.encode(amount, buf, OFF_AMT, LEN_AMT, 2);
        FixedWidthFormat.writeUnsignedNumeric(0L, buf, OFF_MERCH_ID, LEN_MERCH_ID);
        FixedWidthFormat.writeText("", buf, OFF_MERCH_NAME, LEN_MERCH_NAME);
        FixedWidthFormat.writeText("", buf, OFF_MERCH_CITY, LEN_MERCH_CITY);
        FixedWidthFormat.writeText("", buf, OFF_MERCH_ZIP, LEN_MERCH_ZIP);
        FixedWidthFormat.writeText(cardNum, buf, OFF_CARD_NUM, LEN_CARD_NUM);
        FixedWidthFormat.writeText(origTimestamp, buf, OFF_ORIG_TS, LEN_ORIG_TS);
        FixedWidthFormat.writeText(procTimestamp, buf, OFF_PROC_TS, LEN_PROC_TS);
        FixedWidthFormat.writeText("", buf, OFF_FILLER, LEN_FILLER);
        return new TransactionRecord(buf);
    }

    public byte[] toBytes() {
        return raw.clone();
    }
}
