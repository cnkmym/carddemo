package com.carddemo.batch.interest.io;

import com.carddemo.batch.interest.domain.CardXrefRecord;
import com.carddemo.batch.interest.util.BatchAbendException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class CardXrefFile {

    private final Map<Long, CardXrefRecord> byAcctId;

    private CardXrefFile(Map<Long, CardXrefRecord> byAcctId) {
        this.byAcctId = byAcctId;
    }

    public static CardXrefFile load(Path path) throws IOException {
        Map<Long, CardXrefRecord> byAcctId = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                byte[] padded = FixedWidthFormat.padToLength(
                        line.getBytes(StandardCharsets.ISO_8859_1), CardXrefRecord.RECLN);
                CardXrefRecord record = CardXrefRecord.parse(padded);
                // Mirror VSAM AIX behaviour: first record wins when multiple cards
                // share an account.
                byAcctId.putIfAbsent(record.acctId(), record);
            }
        }
        return new CardXrefFile(byAcctId);
    }

    public CardXrefRecord lookupByAcctId(long acctId) {
        CardXrefRecord record = byAcctId.get(acctId);
        if (record == null) {
            throw new BatchAbendException(999,
                    "ERROR READING XREF FILE: account not found: " + acctId);
        }
        return record;
    }
}
