package com.lsmdb.sstable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.lsmdb.bloom.BloomFilter;

/**
 * Reads an immutable SSTable file, supporting point lookups (get) via
 * a bloom filter (cheap "definitely absent?" check) plus the sparse
 * index (binary search + seek + short scan) for everything else.
 */
public class SSTableReader implements Closeable {

    private final RandomAccessFile file;
    private final List<SSTableWriter.IndexEntry> index; // sorted by key, in RAM
    private final long bloomFilterOffset; // == where the data block ends
    private final BloomFilter bloomFilter;

    private SSTableReader(RandomAccessFile file, List<SSTableWriter.IndexEntry> index,
                           long bloomFilterOffset, BloomFilter bloomFilter) {
        this.file = file;
        this.index = index;
        this.bloomFilterOffset = bloomFilterOffset;
        this.bloomFilter = bloomFilter;
    }

    public static SSTableReader open(File sstableFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(sstableFile, "r");

        // Footer is always the LAST 20 bytes:
        // [bloomFilterOffset:8][indexBlockOffset:8][indexEntryCount:4]
        long fileLength = raf.length();
        raf.seek(fileLength - 20);
        long bloomFilterOffset = raf.readLong();
        long indexBlockOffset = raf.readLong();
        int indexEntryCount = raf.readInt();

        // --- Load the bloom filter into memory ---
        // Its byte length is exactly the gap between where it starts
        // and where the index block starts right after it.
        raf.seek(bloomFilterOffset);
        int bloomFilterByteLength = (int) (indexBlockOffset - bloomFilterOffset);
        byte[] bloomBytes = new byte[bloomFilterByteLength];
        raf.readFully(bloomBytes);
        BloomFilter bloomFilter = BloomFilter.fromBytes(bloomBytes);

        // --- Load the sparse index into memory ---
        raf.seek(indexBlockOffset);
        List<SSTableWriter.IndexEntry> index = new ArrayList<>(indexEntryCount);
        for (int i = 0; i < indexEntryCount; i++) {
            int keyLen = raf.readInt();
            byte[] key = new byte[keyLen];
            raf.readFully(key);
            long offset = raf.readLong();
            index.add(new SSTableWriter.IndexEntry(key, offset));
        }

        return new SSTableReader(raf, index, bloomFilterOffset, bloomFilter);
    }

    /**
     * Looks up a key. Returns the raw value bytes if found (which may be
     * the empty-array tombstone marker), or null if the key does not
     * exist in this SSTable.
     */
    public synchronized byte[] get(byte[] key) throws IOException {
        // Cheap first check: if the bloom filter says "definitely not
        // here," skip the binary search, the seek, and the scan
        // entirely — zero disk I/O for the data itself. This is the
        // entire point of adding it: most lookups for a key that isn't
        // in THIS particular file get resolved in O(k) in-memory bit
        // checks instead of a seek + scan.
        if (!bloomFilter.mightContain(key)) {
            return null;
        }

        if (index.isEmpty()) {
            return null;
        }

        int startIdx = floorIndex(key);
        if (startIdx == -1) {
            return null;
        }

        long startOffset = index.get(startIdx).offset();
        // Upper bound for the scan: either the next index entry, or —
        // for the LAST bucket — the point where the data block ends,
        // which is exactly where the bloom filter block begins.
        long endOffset = (startIdx + 1 < index.size())
                ? index.get(startIdx + 1).offset()
                : bloomFilterOffset;

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
                return null;
            } else {
                file.seek(file.getFilePointer() + valueLen);
            }
        }
        return null;
    }

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