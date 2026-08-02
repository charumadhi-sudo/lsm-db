package com.lsmdb.engine;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.lsmdb.compaction.CompactionManager;
import com.lsmdb.sstable.SSTableReader;
import com.lsmdb.sstable.SSTableWriter;
import com.lsmdb.storage.MemTable;
import com.lsmdb.wal.WalEntry;
import com.lsmdb.wal.WriteAheadLog;

/**
 * KVEngine: the full write/read path, tying together MemTable (Phase 1),
 * WriteAheadLog (Phase 2), and SSTables (Phase 3).
 *
 * This is now a directory-based engine: it owns a folder containing one
 * WAL file plus zero or more immutable .sst files, one per flush.
 *
 * Write path:
 *   put/delete -> WAL (durable) -> MemTable -> (if MemTable is now too
 *   big) flush MemTable to a new SSTable, then reset MemTable and WAL.
 *
 * Read path:
 *   check MemTable (freshest) -> check SSTables newest to oldest ->
 *   first match found (including a tombstone) wins and we stop looking,
 *   since a newer entry always shadows an older one for the same key.
 */
public class KVEngine implements Closeable {

    private static final String WAL_FILENAME = "wal.log";
    // Once this many SSTable files exist, compact ALL of them into one.
    // Simplified size-tiered strategy: real engines use more nuanced
    // triggers (total size, level-based budgets), but "too many small
    // files" is the same underlying signal this is approximating.
    private static final int MAX_SSTABLES_BEFORE_COMPACTION = 4;

    private final File dbDir;
    private final File walFile;
    private final long flushThresholdBytes;

    private WriteAheadLog wal;
    private MemTable memTable;

    // Newest SSTable first. Order matters directly for read correctness —
    // see get().
    private final List<SSTableReader> sstableReaders;
    private int nextSSTableId;

    private KVEngine(File dbDir, File walFile, long flushThresholdBytes,
                      WriteAheadLog wal, MemTable memTable,
                      List<SSTableReader> sstableReaders, int nextSSTableId) {
        this.dbDir = dbDir;
        this.walFile = walFile;
        this.flushThresholdBytes = flushThresholdBytes;
        this.wal = wal;
        this.memTable = memTable;
        this.sstableReaders = sstableReaders;
        this.nextSSTableId = nextSSTableId;
    }

    /**
     * Opens (or creates) the engine in the given directory.
     *
     * Recovery on startup, in order:
     *   1. Open every existing .sst file found in dbDir — each one is
     *      already durable and immutable, so we just need readers for
     *      them, no replay needed.
     *   2. Replay the (small) WAL into a fresh MemTable — this covers
     *      only writes since the LAST flush, which is exactly why
     *      flushing truncates the WAL: it keeps this replay step cheap
     *      forever, instead of growing with the database's entire history.
     */
    public static KVEngine open(File dbDir, long flushThresholdBytes) throws IOException {
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }

        // --- Step 1: discover existing SSTables ---
        File[] sstFiles = dbDir.listFiles((d, name) -> name.endsWith(".sst"));
        List<File> sortedSstFiles = new ArrayList<>();
        if (sstFiles != null) {
            for (File f : sstFiles) sortedSstFiles.add(f);
        }
        // Zero-padded numeric filenames sort correctly as plain strings —
        // ascending here means oldest-to-newest.
        sortedSstFiles.sort(Comparator.comparing(File::getName));

        List<SSTableReader> readers = new ArrayList<>();
        int maxId = 0;
        for (File f : sortedSstFiles) {
            readers.add(0, SSTableReader.open(f)); // insert at front -> newest ends up first
            int id = Integer.parseInt(f.getName().replace(".sst", ""));
            maxId = Math.max(maxId, id);
        }

        // --- Step 2: replay the WAL into a fresh MemTable ---
        File walFile = new File(dbDir, WAL_FILENAME);
        MemTable memTable = new MemTable();
        List<WalEntry> entries = WriteAheadLog.replay(walFile);
        for (WalEntry entry : entries) {
            if (entry.isPut()) {
                memTable.put(entry.key, entry.value);
            } else if (entry.isDelete()) {
                memTable.delete(entry.key);
            }
        }
        WriteAheadLog wal = new WriteAheadLog(walFile);

        return new KVEngine(dbDir, walFile, flushThresholdBytes, wal, memTable, readers, maxId + 1);
    }

    public synchronized void put(byte[] key, byte[] value) throws IOException {
        wal.appendPut(key, value);
        memTable.put(key, value);
        flushIfNeeded();
    }

    public synchronized void delete(byte[] key) throws IOException {
        wal.appendDelete(key);
        memTable.delete(key);
        flushIfNeeded();
    }

    /**
     * Reads a key. Checks MemTable first (it always has the freshest
     * data), then SSTables from NEWEST to OLDEST. The first match found
     * anywhere — even if it's a tombstone — is authoritative, and we
     * stop looking immediately. This is what makes a delete correctly
     * shadow a stale value sitting in an older SSTable: the newer
     * tombstone is found first and wins, and we never even look at the
     * older file.
     */
    public synchronized byte[] get(byte[] key) throws IOException {
        byte[] value = memTable.get(key);
        if (value != null) {
            return MemTable.isTombstone(value) ? null : value;
        }

        for (SSTableReader reader : sstableReaders) {
            value = reader.get(key);
            if (value != null) {
                return MemTable.isTombstone(value) ? null : value;
            }
        }

        return null; // genuinely not found anywhere
    }

    /**
     * Checks whether the MemTable has crossed the configured size
     * threshold, and if so, flushes it to a new immutable SSTable file,
     * then resets both the MemTable and the WAL.
     */
    private void flushIfNeeded() throws IOException {
        if (memTable.approximateSizeBytes() < flushThresholdBytes) {
            return;
        }

        // Write everything currently in the MemTable to a new,
        // immutable SSTable file on disk.
        File sstFile = new File(dbDir, String.format("%010d.sst", nextSSTableId++));
        SSTableWriter.flush(memTable, sstFile);

        // Newest SSTable must be checked FIRST on reads, so it goes at
        // the front of the list.
        sstableReaders.add(0, SSTableReader.open(sstFile));

        // The MemTable's contents are now durable inside the SSTable we
        // just wrote. That means every WAL record that produced this
        // MemTable is now redundant — safe to discard and start clean.
        // This is the step that keeps WAL replay cheap forever, instead
        // of growing with the database's entire lifetime history.
        wal.close();
        if (!walFile.delete()) {
            throw new IOException("Failed to delete old WAL file after flush: " + walFile);
        }
        wal = new WriteAheadLog(walFile);

        memTable = new MemTable();

        compactIfNeeded();
    }

    /**
     * If too many SSTable files have piled up, merge them ALL into a
     * single new one via CompactionManager, then discard the old files.
     *
     * Because this compacts EVERY existing SSTable at once (not a
     * partial subset, as a leveled strategy would), there is no older
     * data left anywhere that a tombstone could still need to shadow —
     * so we pass dropTombstones=true, letting deleted keys finally be
     * reclaimed for real instead of persisting forever as markers.
     */
    private void compactIfNeeded() throws IOException {
        if (sstableReaders.size() < MAX_SSTABLES_BEFORE_COMPACTION) {
            return;
        }

        File mergedFile = new File(dbDir, String.format("%010d.sst", nextSSTableId++));
        CompactionManager.compact(sstableReaders, mergedFile, true);

        // Every old SSTable's data now lives inside mergedFile. Close
        // and delete them — order matters: close (release the file
        // handle) before delete (some platforms, notably Windows,
        // refuse to delete a file that's still open).
        for (SSTableReader oldReader : sstableReaders) {
            File oldFile = oldReader.file();
            oldReader.close();
            if (!oldFile.delete()) {
                throw new IOException("Failed to delete compacted SSTable: " + oldFile);
            }
        }
        sstableReaders.clear();
        sstableReaders.add(SSTableReader.open(mergedFile));
    }

    @Override
    public synchronized void close() throws IOException {
        wal.close();
        for (SSTableReader reader : sstableReaders) {
            reader.close();
        }
    }
}