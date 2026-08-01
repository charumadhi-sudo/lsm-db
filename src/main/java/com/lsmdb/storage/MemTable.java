package com.lsmdb.storage;

/**
 * MemTable: the in-memory write buffer for the LSM engine.
 *
 * This is a thin layer on top of SkipList that adds LSM-specific meaning:
 *   1. Tombstones — deletes are recorded, not physically removed, so that
 *      once this memtable is flushed to an SSTable, the "this key is
 *      deleted" fact survives on disk and can shadow older, stale values
 *      sitting in older SSTables.
 *   2. Approximate size tracking — real engines flush a memtable to disk
 *      once it crosses a size threshold. We track that here so a future
 *      "should I flush?" check has something to look at.
 *
 * SkipList itself deliberately knows nothing about any of this — it just
 * stores bytes. Keeping that separation makes SkipList simpler to test
 * and reuse, and keeps LSM-specific policy in one place.
 */
public class MemTable {

    /**
     * Sentinel value marking a deleted key. Using a zero-length array
     * (rather than null) means "tombstone" is an unambiguous, distinct
     * signal from "key not found" (which get() represents as null).
     * A real value can never legitimately be this exact same array
     * reference, since we control it internally.
     */
    public static final byte[] TOMBSTONE = new byte[0];

    private final SkipList skipList = new SkipList();
    private long approximateSizeBytes = 0;

    /**
     * Inserts or updates a key. Size accounting is approximate on purpose —
     * we don't need byte-perfect accuracy, just a reasonable signal for
     * "is it time to flush this memtable to disk yet?"
     */
    public void put(byte[] key, byte[] value) {
        skipList.put(key, value);
        approximateSizeBytes += key.length + value.length;
    }

    /**
     * Marks a key as deleted by writing a tombstone, rather than removing
     * it. This is the core LSM-tree delete semantic — see class javadoc.
     */
    public void delete(byte[] key) {
        put(key, TOMBSTONE);
    }

    /**
     * Returns the value for a key, or null if the key was never written.
     * Callers MUST separately check isTombstone() on the result if they
     * need to distinguish "deleted" from "never existed" — we return the
     * raw tombstone marker here rather than collapsing both cases to null,
     * so callers building on top (like a future storage engine facade)
     * can make that distinction themselves.
     */
    public byte[] get(byte[] key) {
        return skipList.get(key);
    }

    public static boolean isTombstone(byte[] value) {
        return value != null && value.length == 0;
    }

    public long approximateSizeBytes() {
        return approximateSizeBytes;
    }

    /**
     * A single (key, value) pair, used when iterating the whole MemTable
     * in sorted order — e.g. to flush it to an SSTable. Value may be
     * the TOMBSTONE marker; callers that care must check isTombstone().
     */
    public record Entry(byte[] key, byte[] value) {}

    /**
     * Returns every entry in ascending key order, tombstones included.
     * Tombstones are deliberately NOT filtered out here — when this gets
     * flushed to an SSTable, the tombstone must be written to disk too,
     * so it can shadow stale values sitting in older SSTables.
     */
    public java.util.List<Entry> entriesInOrder() {
        java.util.List<Entry> result = new java.util.ArrayList<>();
        for (SkipListNode node : skipList.entriesInOrder()) {
            result.add(new Entry(node.key, node.value));
        }
        return result;
    }
}