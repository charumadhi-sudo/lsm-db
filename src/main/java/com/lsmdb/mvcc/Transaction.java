package com.lsmdb.mvcc;

import com.lsmdb.storage.MemTable;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * A transaction: a group of writes that become visible to the rest of
 * the store ALL AT ONCE (on commit) or NOT AT ALL (on rollback).
 *
 * How it works:
 *   - On creation, it captures a read snapshot (the store's state right
 *     now) — reads for keys this transaction hasn't itself written yet
 *     are pinned to that snapshot, same guarantee as MVCCStore's plain
 *     snapshot reads.
 *   - Writes are buffered locally (pendingWrites), NOT applied to the
 *     store immediately. This is what makes rollback possible — nothing
 *     external has been touched yet, so rolling back is just discarding
 *     the buffer.
 *   - get() checks the local buffer FIRST ("read your own writes") —
 *     a transaction must see its own uncommitted changes, even though
 *     no other transaction can.
 *   - commit() applies every buffered write to the store in one atomic
 *     operation (MVCCStore.commitAll), under a single shared sequence
 *     number, so external readers never see a partial subset of them.
 */
public class Transaction {

    private final MVCCStore store;
    private final long snapshotSeq;

    // TreeMap with an explicit byte[] comparator, NOT a HashMap — byte[]
    // has no meaningful equals()/hashCode() (it's identity-based), so a
    // HashMap would treat two equal-content-but-different-instance keys
    // as different entries. Arrays::compare gives correct content-based
    // ordering and equality here.
    private final TreeMap<byte[], byte[]> pendingWrites = new TreeMap<>(Arrays::compare);

    private boolean committed = false;
    private boolean rolledBack = false;

    Transaction(MVCCStore store) {
        this.store = store;
        this.snapshotSeq = store.beginSnapshot();
    }

    /**
     * Buffers a write. Not visible to any other transaction or reader
     * until commit() succeeds.
     */
    public void put(byte[] key, byte[] value) {
        requireActive();
        pendingWrites.put(key, value);
    }

    /**
     * Buffers a delete (tombstone), same buffering rule as put().
     */
    public void delete(byte[] key) {
        requireActive();
        pendingWrites.put(key, MemTable.TOMBSTONE);
    }

    /**
     * Reads a key. Checks this transaction's OWN pending writes first —
     * if this transaction already wrote/deleted this key, it must see
     * that change immediately, even though nobody else can yet. If this
     * transaction hasn't touched the key, falls back to a normal
     * snapshot read pinned to when this transaction began.
     */
    public byte[] get(byte[] key) {
        requireActive();
        if (pendingWrites.containsKey(key)) {
            byte[] value = pendingWrites.get(key);
            return MemTable.isTombstone(value) ? null : value;
        }
        return store.get(key, snapshotSeq);
    }

    /**
     * Atomically applies every buffered write to the store. After this
     * returns, all of them are visible together under one sequence
     * number — see MVCCStore.commitAll for why that matters.
     */
    public void commit() {
        requireActive();
        store.commitAll(pendingWrites);
        committed = true;
    }

    /**
     * Discards every buffered write. Since nothing was ever applied to
     * the store, this is just clearing local state — the store is
     * completely untouched by a rolled-back transaction.
     */
    public void rollback() {
        requireActive();
        pendingWrites.clear();
        rolledBack = true;
    }

    private void requireActive() {
        if (committed) {
            throw new IllegalStateException("Transaction already committed");
        }
        if (rolledBack) {
            throw new IllegalStateException("Transaction already rolled back");
        }
    }
}