package com.lsmdb.sstable;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.lsmdb.bloom.BloomFilter;
import com.lsmdb.storage.MemTable;

/**
 * Writes a sorted sequence of entries to disk as an immutable SSTable file.
 *
 * File layout:
 *   [DATA BLOCK]         sorted entries: [keyLen:4][key][valLen:4][value] ...
 *   [BLOOM FILTER BLOCK] serialized BloomFilter
 *   [INDEX BLOCK]        sparse index: [keyLen:4][key][offset:8] ... (every Nth key)
 *   [FOOTER]             fixed 20 bytes:
 *                         [bloomFilterOffset:8][indexBlockOffset:8][indexEntryCount:4]
 *
 * flushEntries() is the core, format-writing logic. It doesn't care
 * WHERE the sorted entries came from — a MemTable being flushed, or a
 * merged result from compacting several SSTables together. Both callers
 * just need to hand it an already-sorted List<MemTable.Entry>.
 */
public class SSTableWriter {

    private static final int SPARSE_INTERVAL = 16;
    private static final double BLOOM_FALSE_POSITIVE_RATE = 0.01; // 1%

    /**
     * Convenience entry point for the normal flush path: pulls the
     * sorted entries straight out of a MemTable.
     */
    public static void flush(MemTable memTable, File outputFile) throws IOException {
        flushEntries(memTable.entriesInOrder(), outputFile);
    }

    /**
     * Core writer logic: takes ANY already-sorted list of entries and
     * writes a complete, valid SSTable file from it. Used both by
     * flush() above (source: one MemTable) and by CompactionManager
     * (source: the merged output of several SSTables).
     */
    public static void flushEntries(List<MemTable.Entry> entries, File outputFile) throws IOException {
        BloomFilter bloomFilter = BloomFilter.create(entries.size(), BLOOM_FALSE_POSITIVE_RATE);
        for (MemTable.Entry entry : entries) {
            bloomFilter.add(entry.key());
        }

        List<IndexEntry> indexEntries = new ArrayList<>();

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            long offset = 0;
            int count = 0;

            // --- DATA BLOCK ---
            for (MemTable.Entry entry : entries) {
                if (count % SPARSE_INTERVAL == 0) {
                    indexEntries.add(new IndexEntry(entry.key(), offset));
                }
                int written = writeEntry(out, entry.key(), entry.value());
                offset += written;
                count++;
            }

            // --- BLOOM FILTER BLOCK ---
            long bloomFilterOffset = offset;
            byte[] bloomBytes = bloomFilter.toBytes();
            out.write(bloomBytes);
            offset += bloomBytes.length;

            // --- INDEX BLOCK ---
            long indexBlockOffset = offset;
            for (IndexEntry ie : indexEntries) {
                out.writeInt(ie.key().length);
                out.write(ie.key());
                out.writeLong(ie.offset());
            }

            // --- FOOTER (fixed 20 bytes) ---
            out.writeLong(bloomFilterOffset);
            out.writeLong(indexBlockOffset);
            out.writeInt(indexEntries.size());
        }
    }

    private static int writeEntry(DataOutputStream out, byte[] key, byte[] value) throws IOException {
        out.writeInt(key.length);
        out.write(key);
        out.writeInt(value.length);
        out.write(value);
        return 4 + key.length + 4 + value.length;
    }

    record IndexEntry(byte[] key, long offset) {}
}