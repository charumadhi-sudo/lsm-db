package com.lsmdb.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KVEngineTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void putThenGetWorksWithinSameSession(@TempDir Path tempDir) throws Exception {
        File walFile = tempDir.resolve("wal.log").toFile();
        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("key1"), b("value1"));
            assertEquals("value1", s(engine.get(b("key1"))));
        }
    }

    @Test
    void deleteMakesKeyUnreadableWithinSameSession(@TempDir Path tempDir) throws Exception {
        File walFile = tempDir.resolve("wal.log").toFile();
        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("key1"), b("value1"));
            engine.delete(b("key1"));
            assertNull(engine.get(b("key1")));
        }
    }

    @Test
    void dataSurvivesEngineRestart(@TempDir Path tempDir) throws Exception {
        // This is the entire point of Phase 2+3 combined: simulate a
        // clean shutdown and restart, and confirm data is still there —
        // proving the WAL -> MemTable recovery path actually works
        // end-to-end, not just in isolated unit tests of each piece.
        File walFile = tempDir.resolve("wal.log").toFile();

        // "Session 1" — write some data, then close (simulating shutdown).
        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("key1"), b("value1"));
            engine.put(b("key2"), b("value2"));
        }

        // "Session 2" — reopen against the SAME wal file. This triggers
        // replay, which must reconstruct exactly what session 1 wrote.
        try (KVEngine engine = KVEngine.open(walFile)) {
            assertEquals("value1", s(engine.get(b("key1"))));
            assertEquals("value2", s(engine.get(b("key2"))));
        }
    }

    @Test
    void deleteSurvivesEngineRestart(@TempDir Path tempDir) throws Exception {
        // A delete must remain a delete across a restart — the tombstone
        // itself must be replayed from the WAL, not just forgotten.
        File walFile = tempDir.resolve("wal.log").toFile();

        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("key1"), b("value1"));
            engine.delete(b("key1"));
        }

        try (KVEngine engine = KVEngine.open(walFile)) {
            assertNull(engine.get(b("key1")), "delete must survive restart, not silently revert");
        }
    }

    @Test
    void multipleRestartsAccumulateCorrectly(@TempDir Path tempDir) throws Exception {
        // Simulates 3 separate process lifetimes writing to the same WAL,
        // confirming replay correctly stitches ALL of history together,
        // not just the most recent session.
        File walFile = tempDir.resolve("wal.log").toFile();

        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("a"), b("1"));
        }
        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("b"), b("2"));
        }
        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("c"), b("3"));
        }

        try (KVEngine engine = KVEngine.open(walFile)) {
            assertEquals("1", s(engine.get(b("a"))));
            assertEquals("2", s(engine.get(b("b"))));
            assertEquals("3", s(engine.get(b("c"))));
        }
    }

    @Test
    void updatingAKeyThenRestartingKeepsLatestValue(@TempDir Path tempDir) throws Exception {
        File walFile = tempDir.resolve("wal.log").toFile();

        try (KVEngine engine = KVEngine.open(walFile)) {
            engine.put(b("key1"), b("first"));
            engine.put(b("key1"), b("second"));
        }

        try (KVEngine engine = KVEngine.open(walFile)) {
            // Replay must apply operations IN ORDER, so the later write
            // wins — same as it would have live, before any restart.
            assertEquals("second", s(engine.get(b("key1"))));
        }
    }
}