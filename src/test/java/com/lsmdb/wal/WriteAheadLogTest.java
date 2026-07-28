package com.lsmdb.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteAheadLogTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void replayingEmptyOrMissingFileReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("wal.log").toFile(); // never created
        List<WalEntry> entries = WriteAheadLog.replay(file);
        assertTrue(entries.isEmpty());
    }

    @Test
    void writtenEntriesReplayInOrder(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("wal.log").toFile();

        try (WriteAheadLog wal = new WriteAheadLog(file)) {
            wal.appendPut(b("key1"), b("value1"));
            wal.appendPut(b("key2"), b("value2"));
            wal.appendDelete(b("key1"));
        }

        List<WalEntry> entries = WriteAheadLog.replay(file);

        assertEquals(3, entries.size());
        assertEquals(new WalEntry(WalEntry.OP_PUT, b("key1"), b("value1")), entries.get(0));
        assertEquals(new WalEntry(WalEntry.OP_PUT, b("key2"), b("value2")), entries.get(1));
        assertEquals(new WalEntry(WalEntry.OP_DELETE, b("key1"), new byte[0]), entries.get(2));
    }

    @Test
    void reopeningAndAppendingMoreDoesNotLoseEarlierEntries(@TempDir Path tempDir) throws Exception {
        // Simulates: process wrote some data, restarted (or just reopened
        // the WAL), then wrote more. Nothing from before should be lost —
        // the WAL is append-only across the file's lifetime, not just
        // within a single WriteAheadLog object's lifetime.
        File file = tempDir.resolve("wal.log").toFile();

        try (WriteAheadLog wal = new WriteAheadLog(file)) {
            wal.appendPut(b("key1"), b("value1"));
        }
        try (WriteAheadLog wal = new WriteAheadLog(file)) {
            wal.appendPut(b("key2"), b("value2"));
        }

        List<WalEntry> entries = WriteAheadLog.replay(file);
        assertEquals(2, entries.size());
    }

    @Test
    void tornWriteAtTailIsIgnoredNotThrown(@TempDir Path tempDir) throws Exception {
        // This is the important crash-safety test: simulate a process
        // that crashed midway through appending its LAST record, leaving
        // a physically incomplete record dangling at the end of the file.
        File file = tempDir.resolve("wal.log").toFile();

        try (WriteAheadLog wal = new WriteAheadLog(file)) {
            wal.appendPut(b("goodKey"), b("goodValue")); // this one is complete
        }

        // Manually simulate a torn write: append a handful of garbage
        // bytes that look like the START of a record but cut off partway
        // through — exactly what a crash mid-append would leave behind.
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(raf.length());
            raf.writeByte(WalEntry.OP_PUT); // opType
            raf.writeInt(100);              // claims a 100-byte key...
            raf.write(b("only5"));          // ...but only 5 bytes actually follow
            // process "crashes" here — no more bytes written
        }

        // Replay must NOT throw, and must return exactly the one good
        // record from before the torn write — the corrupted tail is
        // silently dropped, which is the correct, expected behavior.
        List<WalEntry> entries = WriteAheadLog.replay(file);

        assertEquals(1, entries.size());
        assertEquals(new WalEntry(WalEntry.OP_PUT, b("goodKey"), b("goodValue")), entries.get(0));
    }

    @Test
    void corruptedChecksumIsDetectedAndStopsReplay(@TempDir Path tempDir) throws Exception {
        // Different failure mode than a torn write: the record is the
        // right LENGTH, but a byte got flipped (e.g. disk bit rot), so
        // the checksum no longer matches. This must also be caught.
        File file = tempDir.resolve("wal.log").toFile();

        try (WriteAheadLog wal = new WriteAheadLog(file)) {
            wal.appendPut(b("key1"), b("value1"));
        }

        // Flip a byte inside the key region of the record on disk.
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            // record layout: [opType:1][keyLen:4][key:4]["value1":6][checksum:8]
            // key bytes start at offset 5 ("key1" = 4 bytes)
            raf.seek(5);
            raf.writeByte('X'); // corrupt the first byte of the key
        }

        List<WalEntry> entries = WriteAheadLog.replay(file);
        assertTrue(entries.isEmpty(), "corrupted record should be dropped, not returned");
    }
}