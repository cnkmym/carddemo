package com.carddemo.batch.interest.io;

import java.nio.charset.StandardCharsets;

public final class FixedWidthFormat {

    private FixedWidthFormat() {}

    public static String text(byte[] src, int offset, int length) {
        return new String(src, offset, length, StandardCharsets.ISO_8859_1);
    }

    public static long unsignedNumeric(byte[] src, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            int digit = src[offset + i] & 0xFF;
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException(
                        "Non-digit at offset " + (offset + i) + ": 0x"
                                + Integer.toHexString(digit));
            }
            value = value * 10 + (digit - '0');
        }
        return value;
    }

    public static void writeText(String value, byte[] dest, int offset, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > length) {
            System.arraycopy(bytes, 0, dest, offset, length);
        } else {
            System.arraycopy(bytes, 0, dest, offset, bytes.length);
            for (int i = bytes.length; i < length; i++) {
                dest[offset + i] = (byte) ' ';
            }
        }
    }

    public static void writeUnsignedNumeric(long value, byte[] dest, int offset, int length) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "writeUnsignedNumeric requires non-negative value, got " + value);
        }
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            dest[offset + i] = (byte) ('0' + (int) (remaining % 10));
            remaining /= 10;
        }
        if (remaining != 0) {
            throw new ArithmeticException(
                    "Value " + value + " does not fit in " + length + " digits");
        }
    }

    public static byte[] padToLength(byte[] src, int length) {
        if (src.length == length) {
            return src;
        }
        byte[] dest = new byte[length];
        if (src.length < length) {
            System.arraycopy(src, 0, dest, 0, src.length);
            for (int i = src.length; i < length; i++) {
                dest[i] = (byte) ' ';
            }
        } else {
            System.arraycopy(src, 0, dest, 0, length);
        }
        return dest;
    }
}
