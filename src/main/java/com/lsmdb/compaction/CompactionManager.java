package com.lsmdb.compaction;

import com.lsmdb.sstable.SSTableReader;
import com.lsmdb.sstable.SSTableWriter;
import com.lsmdb.storage.MemTable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Merges several SSTables into one, using a k-way merge — the same
 * algorithm as merging k sorted linked lists, since every SSTable's
 * entries are already sorted by key.
 *
 * Correctness rules for the merge:
 *   1. If the same key appears in multiple input files, only the value
 *      from the NEWEST file wins — older duplicates are discarded.
 *   2. If dropTombstones is true (used when compacting ALL existing
 *      SSTables at once — there's nothing older left for a tombstone
 *      to still be shadowing), a winning tombstone is discarded
 *      entirely instead of being written to the output file.
 */
public class CompactionManager {

    /**
     * Merges the given readers (ordered NEWEST FIRST — same convention
     * KVEngine uses for its sstableReaders list) into a single new
     * SSTable file at outputFile.
     */
    public static void compact(List<SSTableReader> readersNewestFirst, File outputFile,
                                boolean dropTombstones) throws IOException {
        // Load every input file's full sorted entry list. (For a
        // learning-scale project this is fine; a production engine
        // would stream entries lazily instead of materializing every
        // file fully in RAM — a real optimization opportunity to
        // mention if asked "how would you scale this further?")
        List<List<MemTable.Entry>> allEntries = new ArrayList<>();
        for (SSTableReader reader : readersNewestFirst) {
            allEntries.add(reader.readAllEntries());
        }

        List<MemTable.Entry> merged = mergeSorted(allEntries, dropTombstones);
        SSTableWriter.flushEntries(merged, outputFile);
    }

    /**
     * The actual k-way merge. sourceIndex 0 = newest file, matching the
     * "newest first" ordering CompactionManager.compact() requires —
     * this is what lets us resolve duplicate keys by simply preferring
     * the smallest sourceIndex.
     */
    private static List<MemTable.Entry> mergeSorted(List<List<MemTable.Entry>> sources,
                                                      boolean dropTombstones) {
        // Min-heap ordered by (key, sourceIndex) — smallest key first,
        // and among equal keys, the NEWEST source (lowest index) first.
        // This ordering is what makes "resolve duplicates by recency"
        // fall out naturally from how we pop the heap below.
        PriorityQueue<HeapItem> heap = new PriorityQueue<>((a, b) -> {
            int cmp = Arrays.compare(a.entry.key(), b.entry.key());
            if (cmp != 0) return cmp;
            return Integer.compare(a.sourceIndex, b.sourceIndex);
        });

        // Seed the heap with the first (smallest-key) entry from every
        // non-empty source — standard k-way merge initialization.
        for (int i = 0; i < sources.size(); i++) {
            if (!sources.get(i).isEmpty()) {
                heap.add(new HeapItem(sources.get(i).get(0), i, 0));
            }
        }

        List<MemTable.Entry> result = new ArrayList<>();

        while (!heap.isEmpty()) {
            HeapItem winner = heap.poll();
            byte[] currentKey = winner.entry.key();

            // The heap's ordering guarantees `winner` is the correct
            // pick for this key: it has the smallest key, and among any
            // other heap entries that ALSO have this exact key, it has
            // the smallest sourceIndex (newest) — because that's exactly
            // how the comparator orders ties. So we don't need to
            // manually scan for duplicates; the heap already resolved it.
            if (!(dropTombstones && MemTable.isTombstone(winner.entry.value()))) {
                result.add(winner.entry);
            }

            // Advance the winner's own source list, push its next entry.
            advanceAndPush(heap, sources, winner.sourceIndex, winner.posInSource);

            // Every OTHER heap entry that shares this same key is now
            // stale — a duplicate we must discard without writing, but
            // we still need to advance ITS source list too, so that
            // source's next key eventually gets considered.
            List<HeapItem> discardedDuplicates = new ArrayList<>();
            while (!heap.isEmpty() && Arrays.equals(heap.peek().entry.key(), currentKey)) {
                discardedDuplicates.add(heap.poll());
            }
            for (HeapItem dup : discardedDuplicates) {
                advanceAndPush(heap, sources, dup.sourceIndex, dup.posInSource);
            }
        }

        return result;
    }

    private static void advanceAndPush(PriorityQueue<HeapItem> heap,
                                        List<List<MemTable.Entry>> sources,
                                        int sourceIndex, int posInSource) {
        int nextPos = posInSource + 1;
        List<MemTable.Entry> source = sources.get(sourceIndex);
        if (nextPos < source.size()) {
            heap.add(new HeapItem(source.get(nextPos), sourceIndex, nextPos));
        }
    }

    /** One heap element: an entry, which source list it came from, and
     * its position within that list (so we know how to advance it). */
    private record HeapItem(MemTable.Entry entry, int sourceIndex, int posInSource) {}
}