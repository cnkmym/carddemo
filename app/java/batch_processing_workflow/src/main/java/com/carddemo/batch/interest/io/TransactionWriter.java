package com.carddemo.batch.interest.io;

import com.carddemo.batch.interest.domain.TransactionRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TransactionWriter implements AutoCloseable {

    private static final byte[] LINE_TERMINATOR = {'\n'};

    private final OutputStream out;

    public TransactionWriter(Path path) throws IOException {
        this.out = Files.newOutputStream(path);
    }

    public void write(TransactionRecord record) throws IOException {
        out.write(record.toBytes());
        out.write(LINE_TERMINATOR);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
