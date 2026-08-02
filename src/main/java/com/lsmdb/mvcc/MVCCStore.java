package com.lsmdb.mvcc;

import com.lsmdb.storage.MemTable;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A versioned key-value store supporting snapshot-isolated reads.
 *
 * Every write is stamped with a monotonically increasing sequence
 * number. A "snapshot" is just a sequence number captured at some point
 * in time — reading "as of" that snapshot only ever sees versions
 * written at or before it, regardless of what writes happen afterward.
 * This is what lets reads and writes proceed concurrently without
 * blocking each other: a reader holding an old snapshot is completely
 * unaffected by writes that land after it started.
 */
public class MVCCStore {

    // Ordered by VersionedKey's comparator: same key's versions grouped
    // together, newest (highest seqNum) first within each group.
    private final ConcurrentSkipListMap<VersionedKey, byte[]> data = new ConcurrentSkipListMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    // Guards sequence-number allocation AND the writes that use it.
    // Why beginSnapshot() also takes this lock: without it, a snapshot
    // could observe a just-incremented sequence number BEFORE the
    // corresponding entries finish being written into `data` — seeing
    // "the future" without the data to back it up. Serializing both
    // operations on one lock makes that impossible: a snapshot taken
    // concurrently with a commit either lands fully before it (old,
    // correct value) or fully after it (new value, fully written).
    private final Object commitLock = new Object();

    public long put(byte[] key, byte[] value) {
        synchronized (commitLock) {
            long seq = sequenceCounter.incrementAndGet();
            data.put(new VersionedKey(key, seq), value);
            return seq;
        }
    }

    public long delete(byte[] key) {
        return put(key, MemTable.TOMBSTONE);
    }

    public long beginSnapshot() {
        synchronized (commitLock) {
            return sequenceCounter.get();
        }
    }

    /**
     * Atomically applies every write in a transaction under a SINGLE
     * shared sequence number. This is what gives a transaction real
     * atomicity: any snapshot taken before this call returns sees NONE
     * of these writes; any snapshot taken after sees ALL of them.
     * There is no sequence number at which only some of them exist.
     */
    public long commitAll(Map<byte[], byte[]> writes) {
        synchronized (commitLock) {
            long seq = sequenceCounter.incrementAndGet();
            for (Map.Entry<byte[], byte[]> entry : writes.entrySet()) {
                data.put(new VersionedKey(entry.getKey(), seq), entry.getValue());
            }
            return seq;
        }
    }

    /**
     * Starts a new transaction. The transaction captures a snapshot of
     * the store right now — its reads (for keys it hasn't itself
     * written yet) will be pinned to this exact moment until commit.
     */
    public Transaction beginTransaction() {
        return new Transaction(this);
    }

    /**
     * Reads the LATEST committed value — equivalent to reading with a
     * snapshot taken right now. Most callers that don't care about
     * historical consistency will use this.
     */
    public byte[] get(byte[] key) {
        return get(key, beginSnapshot());
    }

    /**
     * The core MVCC read: returns the value of key as it existed at
     * snapshotSeq — the newest version with seqNum <= snapshotSeq —
     * ignoring any versions written after that point.
     *
     * How ceilingEntry finds it in one lookup: VersionedKey orders
     * versions of the SAME key with descending seqNum. Searching for
     * ceilingEntry(key, snapshotSeq) finds the smallest VersionedKey
     * that is >= our search key under that ordering — which, because
     * of the descending-seqNum trick, is EXACTLY the version with the
     * largest seqNum that's still <= snapshotSeq. Versions written
     * after snapshotSeq sort as "smaller" than our search key (since
     * higher seqNum = earlier in descending order) and are skipped.
     */
    public byte[] get(byte[] key, long snapshotSeq) {
        VersionedKey searchKey = new VersionedKey(key, snapshotSeq);
        Map.Entry<VersionedKey, byte[]> entry = data.ceilingEntry(searchKey);

        if (entry == null) {
            return null; // nothing in the store at or after this point at all
        }
        if (!Arrays.equals(entry.getKey().userKey(), key)) {
            // ceilingEntry had to jump to a DIFFERENT (lexicographically
            // larger) key entirely — meaning THIS key has no version
            // with seqNum <= snapshotSeq. It didn't exist yet as of
            // this snapshot.
            return null;
        }

        byte[] value = entry.getValue();
        return MemTable.isTombstone(value) ? null : value;
    }
}