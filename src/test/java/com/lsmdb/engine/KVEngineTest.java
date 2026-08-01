package com.lsmdb.engine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KVEngineTest {

    // Large threshold: MemTable-only behavior, no flush triggered.
    private static final long NO_FLUSH = 10_000_000L;
    // Tiny threshold: forces a flush after just a couple of small writes.
    private static final long TINY_THRESHOLD = 20L;

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void putThenGetWorksWithinSameSession(@TempDir Path tempDir) throws Exception {
        try (KVEngine engine = KVEngine.open(tempDir.toFile(), NO_FLUSH)) {
            engine.put(b("key1"), b("value1"));
            assertEquals("value1", s(engine.get(b("key1"))));
        }
    }

    @Test
    void deleteMakesKeyUnreadableWithinSameSession(@TempDir Path tempDir) throws Exception {
        try (KVEngine engine = KVEngine.open(tempDir.toFile(), NO_FLUSH)) {
            engine.put(b("key1"), b("value1"));
            engine.delete(b("key1"));
            assertNull(engine.get(b("key1")));
        }
    }

    @Test
    void dataSurvivesEngineRestartViaWalReplay(@TempDir Path tempDir) throws Exception {
        // Threshold high enough that nothing flushes — this specifically
        // tests the "recover from WAL alone" path, same as Phase 2.
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, NO_FLUSH)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("key2"), b("value2"));
        }
        try (KVEngine engine = KVEngine.open(dir, NO_FLUSH)) {
            assertEquals("value1", s(engine.get(b("key1"))));
            assertEquals("value2", s(engine.get(b("key2"))));
        }
    }

    @Test
    void writingPastThresholdTriggersFlushToSSTableFile(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            // Each of these pushes approximateSizeBytes up; with a tiny
            // threshold, this should trigger at least one flush.
            engine.put(b("key1"), b("value1"));
            engine.put(b("key2"), b("value2"));
            engine.put(b("key3"), b("value3"));
        }

        File[] sstFiles = dir.listFiles((d, name) -> name.endsWith(".sst"));
        assertNotNull(sstFiles);
        assertTrue(sstFiles.length > 0, "expected at least one .sst file after crossing threshold");
    }

    @Test
    void dataIsReadableFromSSTableAfterFlush(@TempDir Path tempDir) throws Exception {
        // Proves the read path correctly falls through from MemTable to
        // SSTable — this key must survive being flushed out of RAM.
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("key2"), b("value2")); // likely triggers flush of key1's memtable
            engine.put(b("key3"), b("value3"));

            // Regardless of exactly when the flush happened, all three
            // keys must still be readable — some from MemTable, some
            // from an SSTable, transparently to the caller.
            assertEquals("value1", s(engine.get(b("key1"))));
            assertEquals("value2", s(engine.get(b("key2"))));
            assertEquals("value3", s(engine.get(b("key3"))));
        }
    }

    @Test
    void flushedDataSurvivesRestart(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("key2"), b("value2"));
            engine.put(b("key3"), b("value3"));
        }

        // Reopen: this must load SSTable files from disk AND replay
        // whatever small amount of WAL was left since the last flush.
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            assertEquals("value1", s(engine.get(b("key1"))));
            assertEquals("value2", s(engine.get(b("key2"))));
            assertEquals("value3", s(engine.get(b("key3"))));
        }
    }

    @Test
    void newerValueInMemTableShadowsOlderValueInSSTable(@TempDir Path tempDir) throws Exception {
        // The most important correctness property of the whole read
        // path: an update to a key that already exists in an older
        // SSTable must be visible immediately, from MemTable, without
        // needing another flush.
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("original"));
            engine.put(b("padding1"), b("forcesFlush")); // push key1 into an SSTable

            engine.put(b("key1"), b("updated")); // new write, lands in fresh MemTable

            assertEquals("updated", s(engine.get(b("key1"))),
                    "MemTable's newer value must win over the older SSTable's value");
        }
    }

    @Test
    void deleteAfterFlushShadowsOlderSSTableValue(@TempDir Path tempDir) throws Exception {
        // Same idea as above, but for tombstones specifically — this is
        // the scenario that motivated tombstones existing at all the way
        // back in Phase 1.
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("padding1"), b("forcesFlush")); // push key1 into an SSTable

            engine.delete(b("key1")); // tombstone lands in fresh MemTable

            assertNull(engine.get(b("key1")),
                    "MemTable's tombstone must shadow the older SSTable's stale value");
        }
    }

    @Test
    void updatingAKeyThenRestartingKeepsLatestValue(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, NO_FLUSH)) {
            engine.put(b("key1"), b("first"));
            engine.put(b("key1"), b("second"));
        }
        try (KVEngine engine = KVEngine.open(dir, NO_FLUSH)) {
            assertEquals("second", s(engine.get(b("key1"))));
        }
    }
}