package com.carddemo.batch.interest.io;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class ZonedDecimalCodec {

    private static final char[] POS_OVERPUNCH = {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};
    private static final char[] NEG_OVERPUNCH = {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    private ZonedDecimalCodec() {}

    public static BigDecimal decode(byte[] src, int offset, int length, int scale) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1");
        }

        boolean negative = false;
        StringBuilder digits = new StringBuilder(length);

        for (int i = 0; i < length - 1; i++) {
            char c = (char) (src[offset + i] & 0xFF);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "Non-digit at offset " + (offset + i) + ": '" + c + "'");
            }
            digits.append(c);
        }

        char last = (char) (src[offset + length - 1] & 0xFF);
        int lastDigit = decodeOverpunch(last);
        if (lastDigit < 0) {
            negative = true;
            lastDigit = -lastDigit - 1;
        } else if (last == '{') {
            // already handled — positive zero
        } else if (last == '}') {
            negative = true;
            lastDigit = 0;
        }
        digits.append((char) ('0' + lastDigit));

        BigInteger raw = new BigInteger(digits.toString());
        if (negative) {
            raw = raw.negate();
        }
        return new BigDecimal(raw, scale);
    }

    public static void encode(BigDecimal value, byte[] dest, int offset, int length, int scale) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1");
        }

        BigDecimal scaled = value.setScale(scale, RoundingMode.DOWN);
        BigInteger unscaled = scaled.unscaledValue();
        boolean negative = unscaled.signum() < 0;
        if (negative) {
            unscaled = unscaled.negate();
        }

        String digits = unscaled.toString();
        if (digits.length() > length) {
            throw new ArithmeticException(
                    "Value " + scaled + " does not fit in " + length + " digit positions");
        }

        StringBuilder buf = new StringBuilder(length);
        for (int i = digits.length(); i < length; i++) {
            buf.append('0');
        }
        buf.append(digits);

        int lastDigit = buf.charAt(length - 1) - '0';
        char overpunch = negative ? NEG_OVERPUNCH[lastDigit] : POS_OVERPUNCH[lastDigit];
        buf.setCharAt(length - 1, overpunch);

        for (int i = 0; i < length; i++) {
            dest[offset + i] = (byte) buf.charAt(i);
        }
    }

    private static int decodeOverpunch(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c == '{') {
            return 0;
        }
        if (c >= 'A' && c <= 'I') {
            return c - 'A' + 1;
        }
        if (c == '}') {
            return -1;
        }
        if (c >= 'J' && c <= 'R') {
            return -(c - 'J' + 1) - 1;
        }
        throw new IllegalArgumentException("Bad zoned-decimal overpunch: '" + c + "' (0x"
                + Integer.toHexString(c) + ")");
    }
}
