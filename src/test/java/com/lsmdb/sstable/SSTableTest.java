package com.lsmdb.sstable;

import com.lsmdb.storage.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SSTableTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void flushThenReadBackSingleKey(@TempDir Path tempDir) throws Exception {
        MemTable memTable = new MemTable();
        memTable.put(b("key1"), b("value1"));

        File sstFile = tempDir.resolve("table1.sst").toFile();
        SSTableWriter.flush(memTable, sstFile);

        try (SSTableReader reader = SSTableReader.open(sstFile)) {
            assertEquals("value1", s(reader.get(b("key1"))));
        }
    }

    @Test
    void lookupOfMissingKeyReturnsNull(@TempDir Path tempDir) throws Exception {
        MemTable memTable = new MemTable();
        memTable.put(b("key1"), b("value1"));

        File sstFile = tempDir.resolve("table1.sst").toFile();
        SSTableWriter.flush(memTable, sstFile);

        try (SSTableReader reader = SSTableReader.open(sstFile)) {
            assertNull(reader.get(b("doesNotExist")));
        }
    }

    @Test
    void keySmallerThanEverythingReturnsNull(@TempDir Path tempDir) throws Exception {
        // Exercises the floorIndex() == -1 branch specifically —
        // target is smaller than even the first indexed key.
        MemTable memTable = new MemTable();
        memTable.put(b("m"), b("middle"));

        File sstFile = tempDir.resolve("table1.sst").toFile();
        SSTableWriter.flush(memTable, sstFile);

        try (SSTableReader reader = SSTableReader.open(sstFile)) {
            assertNull(reader.get(b("a")));
        }
    }

    @Test
    void manyKeysSpanningMultipleSparseIndexBucketsAllResolveCorrectly(@TempDir Path tempDir) throws Exception {
        // SPARSE_INTERVAL is 16 — write enough keys to span several
        // index buckets, then verify EVERY key, including ones that sit
        // in the middle of a bucket (requiring the linear scan to work,
        // not just the binary search landing exactly on an index entry).
        MemTable memTable = new MemTable();
        int n = 200;
        for (int i = 0; i < n; i++) {
            // zero-padded so lexicographic byte order == numeric order
            String key = String.format("key%04d", i);
            memTable.put(b(key), b("value" + i));
        }

        File sstFile = tempDir.resolve("table1.sst").toFile();
        SSTableWriter.flush(memTable, sstFile);

        try (SSTableReader reader = SSTableReader.open(sstFile)) {
            for (int i = 0; i < n; i++) {
                String key = String.format("key%04d", i);
                assertEquals("value" + i, s(reader.get(b(key))), "mismatch at " + key);
            }
        }
    }

    @Test
    void tombstoneIsWrittenAndReadableAsEmptyValue(@TempDir Path tempDir) throws Exception {
        // A flushed tombstone must survive as a real, distinguishable
        // entry on disk — this is what lets it later shadow stale values
        // in older SSTables during reads and compaction.
        MemTable memTable = new MemTable();
        memTable.put(b("key1"), b("value1"));
        memTable.delete(b("key1"));

        File sstFile = tempDir.resolve("table1.sst").toFile();
        SSTableWriter.flush(memTable, sstFile);

        try (SSTableReader reader = SSTableReader.open(sstFile)) {
            byte[] result = reader.get(b("key1"));
            assertNotNull(result);
            assertTrue(MemTable.isTombstone(result));
        }
    }

    @Test
    void keyLargerThanEverythingReturnsNull(@TempDir Path tempDir) throws Exception {
        // Exercises the "scan reaches endOffset without a match" path
        // for the LAST bucket in the file.
        MemTable memTable = new MemTable();
        for (int i = 0; i < 20; i++) {
            memTable.put(b(String.format("key%04d", i)), b("v" + i));
        }

        File sstFile = tempDir.resolve("table1.sst").toFile();
        SSTableWriter.flush(memTable, sstFile);

        try (SSTableReader reader = SSTableReader.open(sstFile)) {
            assertNull(reader.get(b("zzz_not_present")));
        }
    }
}