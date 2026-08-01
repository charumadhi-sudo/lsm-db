package com.lsmdb.engine;

import com.lsmdb.storage.MemTable;
import com.lsmdb.wal.WalEntry;
import com.lsmdb.wal.WriteAheadLog;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * KVEngine: the first "real" end-to-end piece of the database.
 *
 * Ties MemTable (Phase 1) and WriteAheadLog (Phase 2) together into a
 * single unit that is actually crash-recoverable — which neither piece
 * was capable of on its own.
 *
 * The critical invariant this class enforces:
 *   A write is only ever applied to the MemTable AFTER it has been
 *   durably logged to the WAL (fsynced to disk). This guarantees that if
 *   the process crashes at any point, replaying the WAL on the next
 *   startup reconstructs exactly the state that existed at crash time —
 *   no more, no less.
 *
 * This class does NOT yet flush the MemTable to disk as an SSTable, and
 * does NOT yet cap MemTable size — that's Phase 3. Right now, this is
 * "durable in-memory KV store," which is already a meaningful milestone:
 * it survives a process crash, which a bare MemTable cannot.
 */
public class KVEngine implements Closeable {

    private final WriteAheadLog wal;
    private final MemTable memTable;

    private KVEngine(WriteAheadLog wal, MemTable memTable) {
        this.wal = wal;
        this.memTable = memTable;
    }

    /**
     * Opens the engine against a WAL file on disk. If the file already
     * exists (from a previous run, possibly one that crashed), its
     * contents are replayed to rebuild MemTable state before this method
     * returns — so by the time open() completes, the engine is exactly
     * as if it had never gone down.
     */
    public static KVEngine open(File walFile) throws IOException {
        MemTable memTable = new MemTable();

        // Recovery step: replay whatever was already durably logged,
        // in the exact order it was originally written, so MemTable ends
        // up in the same state it was in right before shutdown/crash.
        List<WalEntry> entries = WriteAheadLog.replay(walFile);
        for (WalEntry entry : entries) {
            if (entry.isPut()) {
                memTable.put(entry.key, entry.value);
            } else if (entry.isDelete()) {
                // Must go through delete(), not a raw removal — this
                // writes a tombstone, preserving the same "deleted, not
                // absent" semantics we'd have gotten from a live delete()
                // call. This matters once SSTables exist: a replayed
                // delete must still be able to shadow older on-disk data.
                memTable.delete(entry.key);
            }
        }

        // Only now, after recovery has rebuilt state, do we open the WAL
        // for further appends. It's opened in append mode (see
        // WriteAheadLog constructor), so replayed data is never touched.
        WriteAheadLog wal = new WriteAheadLog(walFile);
        return new KVEngine(wal, memTable);
    }

    /**
     * Writes a key-value pair. Returns only after the write is durable —
     * WAL append + fsync happens before MemTable is touched.
     */
    public void put(byte[] key, byte[] value) throws IOException {
        wal.appendPut(key, value);   // durable first
        memTable.put(key, value);    // then visible to reads
    }

    /**
     * Deletes a key. Same durability-first ordering as put().
     */
    public void delete(byte[] key) throws IOException {
        wal.appendDelete(key);
        memTable.delete(key);
    }

    /**
     * Reads a key. Returns null if the key doesn't exist OR was deleted —
     * from the outside, callers don't need to know about tombstones,
     * that's an internal MemTable/SSTable implementation detail.
     */
    public byte[] get(byte[] key) {
        byte[] value = memTable.get(key);
        if (value == null || MemTable.isTombstone(value)) {
            return null;
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}