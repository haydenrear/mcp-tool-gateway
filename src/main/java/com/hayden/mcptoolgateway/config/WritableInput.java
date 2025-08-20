package com.hayden.mcptoolgateway.config;

import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

@Component
public final class WritableInput implements Closeable {
    private final PipedInputStream in;
    private final PipedOutputStream out;
    private final OutputStreamWriter utf8;
    private final PrintWriter lineWriter;

    private final CountDownLatch closed = new CountDownLatch(1);

    public WritableInput() throws IOException {
        this.in = new PipedInputStream(8192);
        this.out = new PipedOutputStream(in);
        this.utf8 = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        this.lineWriter = new PrintWriter(utf8, true);
    }

    /** Hand this to your MCP/Stdio transport as the InputStream to read from. */
    public InputStream input() {

        return new InputStream() {
            @SneakyThrows
            @Override
            public int read() throws IOException {
                closed.await();
                var read = in.read();
                return read;
            }
        };
    }

    /** High-level: write a line (adds '\n', flushes). */
    public void writeLine(String s) {
        lineWriter.println(s);
        closed.countDown();
    }

    /** Signal EOF to the reader. After this, input().read() returns -1. */
    @Override public void close() throws IOException {
        // Close the writer chain first; it propagates to out -> in
        lineWriter.close(); // closes utf8 and out
        in.close();
    }
}
