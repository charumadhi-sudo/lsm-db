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

    private static final long NO_FLUSH = 10_000_000L;
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
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("key2"), b("value2"));
            engine.put(b("key3"), b("value3"));

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

        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            assertEquals("value1", s(engine.get(b("key1"))));
            assertEquals("value2", s(engine.get(b("key2"))));
            assertEquals("value3", s(engine.get(b("key3"))));
        }
    }

    @Test
    void newerValueInMemTableShadowsOlderValueInSSTable(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("original"));
            engine.put(b("padding1"), b("forcesFlush"));

            engine.put(b("key1"), b("updated"));

            assertEquals("updated", s(engine.get(b("key1"))),
                    "MemTable's newer value must win over the older SSTable's value");
        }
    }

    @Test
    void deleteAfterFlushShadowsOlderSSTableValue(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("padding1"), b("forcesFlush"));

            engine.delete(b("key1"));

            assertNull(engine.get(b("key1")),
                    "MemTable's tombstone must shadow the older SSTable's stale value");
        }
    }

    @Test
    void tooManySSTablesTriggersCompactionDownToOneFile(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            for (int i = 0; i < 30; i++) {
                engine.put(b("key" + i), b("value" + i));
            }
        }

        File[] sstFiles = dir.listFiles((d, name) -> name.endsWith(".sst"));
        assertNotNull(sstFiles);
        assertTrue(sstFiles.length < 8,
                "expected compaction to have collapsed many small flushes into fewer files, found " + sstFiles.length);
    }

    @Test
    void allDataRemainsReadableAfterCompactionTriggers(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            for (int i = 0; i < 30; i++) {
                engine.put(b("key" + i), b("value" + i));
            }
            for (int i = 0; i < 30; i++) {
                assertEquals("value" + i, s(engine.get(b("key" + i))), "mismatch at key" + i);
            }
        }
    }

    @Test
    void deletedKeyStaysDeletedAcrossCompaction(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.toFile();
        try (KVEngine engine = KVEngine.open(dir, TINY_THRESHOLD)) {
            engine.put(b("key1"), b("value1"));
            engine.delete(b("key1"));
            for (int i = 0; i < 30; i++) {
                engine.put(b("padding" + i), b("v" + i));
            }
            assertNull(engine.get(b("key1")),
                    "deleted key must stay deleted even after its tombstone gets compacted away");
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