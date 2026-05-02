package com.carddemo.batch.interest.io;

import com.carddemo.batch.interest.domain.AccountRecord;
import com.carddemo.batch.interest.util.BatchAbendException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AccountFile {

    private static final byte[] LINE_TERMINATOR = {'\n'};

    private final List<Long> insertionOrder;
    private final Map<Long, AccountRecord> records;

    private AccountFile(List<Long> insertionOrder, Map<Long, AccountRecord> records) {
        this.insertionOrder = insertionOrder;
        this.records = records;
    }

    public static AccountFile load(Path path) throws IOException {
        List<Long> order = new ArrayList<>();
        Map<Long, AccountRecord> records = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                byte[] padded = FixedWidthFormat.padToLength(
                        line.getBytes(StandardCharsets.ISO_8859_1), AccountRecord.RECLN);
                AccountRecord record = AccountRecord.parse(padded);
                order.add(record.acctId());
                records.put(record.acctId(), record);
            }
        }
        return new AccountFile(order, records);
    }

    public AccountRecord lookup(long acctId) {
        AccountRecord record = records.get(acctId);
        if (record == null) {
            throw new BatchAbendException(999,
                    "ERROR READING ACCOUNT FILE: account not found: " + acctId);
        }
        return record;
    }

    public void update(AccountRecord updated) {
        long key = updated.acctId();
        if (!records.containsKey(key)) {
            throw new BatchAbendException(999,
                    "ERROR RE-WRITING ACCOUNT FILE: account not found: " + key);
        }
        records.put(key, updated);
    }

    public void flush(Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            for (Long acctId : insertionOrder) {
                out.write(records.get(acctId).toBytes());
                out.write(LINE_TERMINATOR);
            }
        }
    }
}
