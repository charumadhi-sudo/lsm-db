package com.lsmdb;

import com.lsmdb.engine.KVEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A runnable, narrated tour of the whole storage engine: basic CRUD,
 * an automatic flush to disk, an automatic compaction, and a full
 * process restart proving crash recovery actually works.
 *
 * Run with: mvn compile exec:java -Dexec.mainClass=com.lsmdb.Main
 * (or just run this class directly from your IDE)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Path dbDir = Files.createTempDirectory("lsmdb-demo");
        long flushThresholdBytes = 100;

        section("1. Opening a fresh database at: " + dbDir);
        KVEngine engine = KVEngine.open(dbDir.toFile(), flushThresholdBytes);

        section("2. Basic put / get");
        engine.put(key("user:1"), value("Alice"));
        engine.put(key("user:2"), value("Bob"));
        System.out.println("user:1 -> " + asString(engine.get(key("user:1"))));
        System.out.println("user:2 -> " + asString(engine.get(key("user:2"))));

        section("3. Update — MemTable value shadows nothing yet, still in RAM");
        engine.put(key("user:1"), value("Alice Smith"));
        System.out.println("user:1 -> " + asString(engine.get(key("user:1"))));

        section("4. Delete — writes a tombstone, not a real removal");
        engine.delete(key("user:2"));
        System.out.println("user:2 -> " + asString(engine.get(key("user:2"))) + " (expected: null)");

        section("5. Writing enough data to force a flush to an SSTable file");
        for (int i = 0; i < 10; i++) {
            engine.put(key("padding:" + i), value("this value exists just to grow the memtable past the flush threshold"));
        }
        printSSTableFiles(dbDir);

        section("6. Writing more to force compaction (auto-triggers past 4 SSTable files)");
        for (int i = 10; i < 40; i++) {
            engine.put(key("padding:" + i), value("more padding to trigger multiple flushes and a compaction"));
        }
        printSSTableFiles(dbDir);

        section("7. Data is still correct after all those flushes/compactions");
        System.out.println("user:1 -> " + asString(engine.get(key("user:1"))) + " (expected: Alice Smith)");
        System.out.println("user:2 -> " + asString(engine.get(key("user:2"))) + " (expected: null, stays deleted)");

        section("8. Simulating a crash: closing the engine without warning");
        engine.close();
        System.out.println("Engine closed. In a real crash, the JVM would have just died here instead.");

        section("9. Reopening the SAME directory — this is crash recovery in action");
        KVEngine recovered = KVEngine.open(dbDir.toFile(), flushThresholdBytes);
        System.out.println("user:1 -> " + asString(recovered.get(key("user:1"))) + " (expected: Alice Smith, recovered from SSTable + WAL)");
        System.out.println("user:2 -> " + asString(recovered.get(key("user:2"))) + " (expected: null, delete survived restart)");
        recovered.close();

        section("Done. This directory can be deleted; it was just for the demo:");
        System.out.println(dbDir);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void printSSTableFiles(Path dbDir) {
        File[] files = dbDir.toFile().listFiles((d, name) -> name.endsWith(".sst"));
        int count = files == null ? 0 : files.length;
        System.out.println("Current .sst files on disk: " + count);
    }

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String asString(byte[] b) {
        return b == null ? "null" : new String(b, StandardCharsets.UTF_8);
    }
}