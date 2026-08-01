package com.lsmdb.sstable;

import com.lsmdb.storage.MemTable;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a MemTable's sorted contents to disk as an immutable SSTable file.
 *
 * File layout (see class-level notes for the reasoning):
 *   [DATA BLOCK]   sorted entries: [keyLen:4][key][valLen:4][value] ...
 *   [INDEX BLOCK]  sparse index:   [keyLen:4][key][offset:8] ... (every Nth key)
 *   [FOOTER]       fixed 12 bytes: [indexBlockOffset:8][indexEntryCount:4]
 *
 * Why the footer is fixed-size and always last: a reader opening this
 * file needs to find the index WITHOUT scanning the whole data block.
 * Jumping to (fileLength - 12) always lands exactly on the footer, which
 * then points straight at the index block's start offset.
 */
public class SSTableWriter {

    /** Write one sparse index entry every N data entries. Tunable — a
     * smaller interval means a bigger in-memory index but shorter linear
     * scans per read; a bigger interval is the reverse trade. */
    private static final int SPARSE_INTERVAL = 16;

    /**
     * Flushes the given MemTable to a new SSTable file. The MemTable is
     * NOT modified or cleared by this call — that's the caller's
     * responsibility (typically: swap in a fresh MemTable once this
     * returns successfully).
     */
    public static void flush(MemTable memTable, File outputFile) throws IOException {
        List<IndexEntry> indexEntries = new ArrayList<>();

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            long offset = 0;
            int count = 0;

            // --- DATA BLOCK ---
            for (MemTable.Entry entry : memTable.entriesInOrder()) {
                // Every SPARSE_INTERVAL-th entry gets remembered for the
                // index, along with the BYTE OFFSET it starts at — that
                // offset is what lets a reader seek() straight to it later.
                if (count % SPARSE_INTERVAL == 0) {
                    indexEntries.add(new IndexEntry(entry.key(), offset));
                }

                int written = writeEntry(out, entry.key(), entry.value());
                offset += written;
                count++;
            }

            long indexBlockOffset = offset;

            // --- INDEX BLOCK ---
            for (IndexEntry ie : indexEntries) {
                out.writeInt(ie.key().length);
                out.write(ie.key());
                out.writeLong(ie.offset());
            }

            // --- FOOTER (fixed 12 bytes: 8 for offset, 4 for count) ---
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

    /** A single sparse index entry: a key, and the byte offset in the
     * data block where that key's full entry begins. */
    record IndexEntry(byte[] key, long offset) {}
}