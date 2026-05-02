package com.carddemo.batch.interest.io;

import com.carddemo.batch.interest.domain.TransactionCategoryBalanceRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TcatBalReader implements Iterable<TransactionCategoryBalanceRecord> {

    private final List<TransactionCategoryBalanceRecord> records;

    private TcatBalReader(List<TransactionCategoryBalanceRecord> records) {
        this.records = records;
    }

    public static TcatBalReader load(Path path) throws IOException {
        List<TransactionCategoryBalanceRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                byte[] padded = FixedWidthFormat.padToLength(
                        line.getBytes(StandardCharsets.ISO_8859_1),
                        TransactionCategoryBalanceRecord.RECLN);
                records.add(TransactionCategoryBalanceRecord.parse(padded));
            }
        }
        return new TcatBalReader(records);
    }

    @Override
    public Iterator<TransactionCategoryBalanceRecord> iterator() {
        return records.iterator();
    }

    public int size() {
        return records.size();
    }
}
