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
 * Writes a MemTable's sorted contents to disk as an immutable SSTable file.
 *
 * File layout:
 *   [DATA BLOCK]         sorted entries: [keyLen:4][key][valLen:4][value] ...
 *   [BLOOM FILTER BLOCK] serialized BloomFilter (see BloomFilter.toBytes)
 *   [INDEX BLOCK]        sparse index: [keyLen:4][key][offset:8] ... (every Nth key)
 *   [FOOTER]             fixed 20 bytes:
 *                         [bloomFilterOffset:8][indexBlockOffset:8][indexEntryCount:4]
 *
 * Why the bloom filter sits BETWEEN the data block and the index: order
 * doesn't actually matter for correctness (the footer stores explicit
 * offsets for both, so a reader never has to guess), but this ordering
 * keeps "how do I find X" symmetric: jump to footer, read two offsets,
 * seek directly to either block. Nothing needs to be scanned to find
 * either one.
 */
public class SSTableWriter {

    private static final int SPARSE_INTERVAL = 16;
    private static final double BLOOM_FALSE_POSITIVE_RATE = 0.01; // 1%

    public static void flush(MemTable memTable, File outputFile) throws IOException {
        List<MemTable.Entry> entries = memTable.entriesInOrder();

        // Build the bloom filter sized for exactly how many keys we're
        // about to write, so its false-positive rate matches what we
        // configured, regardless of how big or small this flush is.
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