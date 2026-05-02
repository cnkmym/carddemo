package com.carddemo.batch.interest.io;

import com.carddemo.batch.interest.domain.DisclosureGroupRecord;
import com.carddemo.batch.interest.util.BatchAbendException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class DisclosureGroupFile {

    public static final String DEFAULT_GROUP_ID = "DEFAULT   ";

    private final Map<String, DisclosureGroupRecord> byKey;

    private DisclosureGroupFile(Map<String, DisclosureGroupRecord> byKey) {
        this.byKey = byKey;
    }

    public static DisclosureGroupFile load(Path path) throws IOException {
        Map<String, DisclosureGroupRecord> byKey = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                byte[] padded = FixedWidthFormat.padToLength(
                        line.getBytes(StandardCharsets.ISO_8859_1), DisclosureGroupRecord.RECLN);
                DisclosureGroupRecord record = DisclosureGroupRecord.parse(padded);
                byKey.put(makeKey(record.groupId(), record.tranTypeCd(), record.tranCatCd()), record);
            }
        }
        return new DisclosureGroupFile(byKey);
    }

    public DisclosureGroupRecord lookupWithDefaultFallback(
            String groupId, String typeCd, String catCd) {
        Optional<DisclosureGroupRecord> direct = lookup(groupId, typeCd, catCd);
        if (direct.isPresent()) {
            return direct.get();
        }
        return lookup(DEFAULT_GROUP_ID, typeCd, catCd).orElseThrow(() ->
                new BatchAbendException(999,
                        "ERROR READING DEFAULT DISCLOSURE GROUP: ("
                                + DEFAULT_GROUP_ID + "," + typeCd + "," + catCd + ")"));
    }

    private Optional<DisclosureGroupRecord> lookup(String groupId, String typeCd, String catCd) {
        return Optional.ofNullable(byKey.get(makeKey(groupId, typeCd, catCd)));
    }

    private static String makeKey(String groupId, String typeCd, String catCd) {
        return groupId + "|" + typeCd + "|" + catCd;
    }
}
