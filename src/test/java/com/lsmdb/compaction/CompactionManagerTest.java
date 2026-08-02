package com.lsmdb.compaction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lsmdb.sstable.SSTableReader;
import com.lsmdb.sstable.SSTableWriter;
import com.lsmdb.storage.MemTable;

class CompactionManagerTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private SSTableReader writeAndOpen(Path dir, String filename, MemTable memTable) throws Exception {
        File f = dir.resolve(filename).toFile();
        SSTableWriter.flush(memTable, f);
        return SSTableReader.open(f);
    }

    @Test
    void mergingNonOverlappingKeysProducesAllOfThem(@TempDir Path tempDir) throws Exception {
        MemTable table1 = new MemTable();
        table1.put(b("a"), b("1"));
        table1.put(b("c"), b("3"));

        MemTable table2 = new MemTable();
        table2.put(b("b"), b("2"));
        table2.put(b("d"), b("4"));

        // try-with-resources: every SSTableReader we open here holds an
        // OS-level file handle open (RandomAccessFile). On Windows,
        // an open file cannot be deleted — so if we don't explicitly
        // close these, JUnit's @TempDir cleanup fails afterward trying
        // to remove the temp folder. This bit us for real in testing —
        // worth remembering as a general rule: every SSTableReader you
        // open needs a matching close(), just like the WAL and MemTable
        // resources elsewhere in this project.
        try (SSTableReader r1 = writeAndOpen(tempDir, "1.sst", table1);
             SSTableReader r2 = writeAndOpen(tempDir, "2.sst", table2)) {

            File output = tempDir.resolve("merged.sst").toFile();
            CompactionManager.compact(List.of(r1, r2), output, true);

            try (SSTableReader merged = SSTableReader.open(output)) {
                assertEquals("1", s(merged.get(b("a"))));
                assertEquals("2", s(merged.get(b("b"))));
                assertEquals("3", s(merged.get(b("c"))));
                assertEquals("4", s(merged.get(b("d"))));
            }
        }
    }

    @Test
    void newestFileWinsOnDuplicateKey(@TempDir Path tempDir) throws Exception {
        MemTable newer = new MemTable();
        newer.put(b("key1"), b("newValue"));

        MemTable older = new MemTable();
        older.put(b("key1"), b("oldValue"));

        try (SSTableReader newerReader = writeAndOpen(tempDir, "newer.sst", newer);
             SSTableReader olderReader = writeAndOpen(tempDir, "older.sst", older)) {

            File output = tempDir.resolve("merged.sst").toFile();
            CompactionManager.compact(List.of(newerReader, olderReader), output, true);

            try (SSTableReader merged = SSTableReader.open(output)) {
                assertEquals("newValue", s(merged.get(b("key1"))),
                        "compaction must keep the NEWEST file's value on a duplicate key");
            }
        }
    }

    @Test
    void tombstoneDropsBothItselfAndTheStaleValueItShadows(@TempDir Path tempDir) throws Exception {
        MemTable newer = new MemTable();
        newer.delete(b("key1"));

        MemTable older = new MemTable();
        older.put(b("key1"), b("staleValue"));

        try (SSTableReader newerReader = writeAndOpen(tempDir, "newer.sst", newer);
             SSTableReader olderReader = writeAndOpen(tempDir, "older.sst", older)) {

            File output = tempDir.resolve("merged.sst").toFile();
            CompactionManager.compact(List.of(newerReader, olderReader), output, true);

            try (SSTableReader merged = SSTableReader.open(output)) {
                assertNull(merged.get(b("key1")),
                        "key must be entirely absent — tombstone dropped, stale value discarded");
            }
        }
    }

    @Test
    void tombstoneIsKeptWhenDropTombstonesIsFalse(@TempDir Path tempDir) throws Exception {
        MemTable table = new MemTable();
        table.delete(b("key1"));

        try (SSTableReader reader = writeAndOpen(tempDir, "1.sst", table)) {
            File output = tempDir.resolve("merged.sst").toFile();
            CompactionManager.compact(List.of(reader), output, false);

            try (SSTableReader merged = SSTableReader.open(output)) {
                byte[] result = merged.get(b("key1"));
                assertNotNull(result);
                assertTrue(MemTable.isTombstone(result));
            }
        }
    }

    @Test
    void mergingManyFilesWithInterleavedKeysStaysCorrect(@TempDir Path tempDir) throws Exception {
        MemTable t1 = new MemTable();
        t1.put(b("b"), b("t1-b"));
        t1.put(b("e"), b("t1-e"));

        MemTable t2 = new MemTable();
        t2.put(b("a"), b("t2-a"));
        t2.put(b("c"), b("t2-c"));

        MemTable t3 = new MemTable();
        t3.put(b("d"), b("t3-d"));
        t3.put(b("f"), b("t3-f"));

        try (SSTableReader r1 = writeAndOpen(tempDir, "1.sst", t1);
             SSTableReader r2 = writeAndOpen(tempDir, "2.sst", t2);
             SSTableReader r3 = writeAndOpen(tempDir, "3.sst", t3)) {

            File output = tempDir.resolve("merged.sst").toFile();
            CompactionManager.compact(List.of(r1, r2, r3), output, true);

            try (SSTableReader merged = SSTableReader.open(output)) {
                assertEquals("t2-a", s(merged.get(b("a"))));
                assertEquals("t1-b", s(merged.get(b("b"))));
                assertEquals("t2-c", s(merged.get(b("c"))));
                assertEquals("t3-d", s(merged.get(b("d"))));
                assertEquals("t1-e", s(merged.get(b("e"))));
                assertEquals("t3-f", s(merged.get(b("f"))));
            }
        }
    }
}