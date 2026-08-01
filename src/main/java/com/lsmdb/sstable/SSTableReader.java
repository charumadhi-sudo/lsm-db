package com.lsmdb.sstable;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads an immutable SSTable file, supporting point lookups (get) via
 * the sparse index — WITHOUT loading the whole data block into memory.
 *
 * Only the (small) sparse index is loaded into RAM on open(); the data
 * block is read via seek()s directly against the file on disk, entry by
 * entry, only for the small range a lookup actually needs.
 */
public class SSTableReader implements Closeable {

    private final RandomAccessFile file;
    private final List<SSTableWriter.IndexEntry> index; // sorted by key, in RAM
    private final long indexBlockOffset; // remembered once at open(), not re-read per lookup

    private SSTableReader(RandomAccessFile file, List<SSTableWriter.IndexEntry> index, long indexBlockOffset) {
        this.file = file;
        this.index = index;
        this.indexBlockOffset = indexBlockOffset;
    }

    /**
     * Opens an SSTable file and loads its (small) sparse index into
     * memory, ready for lookups. Does NOT read the data block yet.
     */
    public static SSTableReader open(File sstableFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(sstableFile, "r");

        // Footer is always the LAST 12 bytes: [offset:8][count:4].
        // This is the one fixed, known location in an otherwise
        // variable-length file — that's precisely why we designed it
        // that way in SSTableWriter.
        long fileLength = raf.length();
        raf.seek(fileLength - 12);
        long indexBlockOffset = raf.readLong();
        int indexEntryCount = raf.readInt();

        // Now that we know exactly where the index starts, jump there
        // and read all indexEntryCount entries into memory. This is the
        // ONLY part of the file we load eagerly — it's small by design
        // (fileSize / SPARSE_INTERVAL entries), unlike the data block.
        raf.seek(indexBlockOffset);
        List<SSTableWriter.IndexEntry> index = new ArrayList<>(indexEntryCount);
        for (int i = 0; i < indexEntryCount; i++) {
            int keyLen = raf.readInt();
            byte[] key = new byte[keyLen];
            raf.readFully(key);
            long offset = raf.readLong();
            index.add(new SSTableWriter.IndexEntry(key, offset));
        }

        return new SSTableReader(raf, index, indexBlockOffset);
    }

    /**
     * Looks up a key. Returns the raw value bytes if found (which may be
     * the empty-array tombstone marker — caller's job to interpret),
     * or null if the key definitely does not exist in this SSTable.
     */
    public synchronized byte[] get(byte[] key) throws IOException {
        if (index.isEmpty()) {
            return null; // empty SSTable, shouldn't normally happen but be safe
        }

        // Step 1: binary search the in-memory sparse index for the
        // LARGEST indexed key that is <= our target key. That tells us
        // "start scanning the data block from here" — the target key,
        // if present, cannot appear before this offset.
        int startIdx = floorIndex(key);
        if (startIdx == -1) {
            // Target key is smaller than every indexed key, meaning
            // smaller than every key in the file at all (index entry 0
            // is always the file's very first key) — definitely absent.
            return null;
        }

        long startOffset = index.get(startIdx).offset();

        // Step 2: determine where to STOP scanning — either the next
        // index entry's offset, or end-of-data-block if this is the
        // last index entry. We must not scan into the index block itself.
        long endOffset = (startIdx + 1 < index.size())
                ? index.get(startIdx + 1).offset()
                : indexBlockOffset;

        // Step 3: linear scan the data block between those two offsets —
        // at most SPARSE_INTERVAL entries, by construction.
        file.seek(startOffset);
        while (file.getFilePointer() < endOffset) {
            int keyLen = file.readInt();
            byte[] candidateKey = new byte[keyLen];
            file.readFully(candidateKey);
            int valueLen = file.readInt();

            int cmp = Arrays.compare(candidateKey, key);
            if (cmp == 0) {
                byte[] value = new byte[valueLen];
                file.readFully(value);
                return value;
            } else if (cmp > 0) {
                // Data block is sorted — once we've passed the target
                // key, it cannot appear later. Stop early.
                return null;
            } else {
                // Not our key yet — skip over its value without reading
                // it into memory, since we don't need it.
                file.seek(file.getFilePointer() + valueLen);
            }
        }
        return null;
    }

    /** Binary search for the rightmost index entry whose key <= target.
     * Returns -1 if target is smaller than every indexed key. */
    private int floorIndex(byte[] target) {
        int lo = 0, hi = index.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int cmp = Arrays.compare(index.get(mid).key(), target);
            if (cmp <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}