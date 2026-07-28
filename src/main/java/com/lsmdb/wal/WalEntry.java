package com.lsmdb.wal;

import java.util.Arrays;

/**
 * A single logged operation, as replayed back from the WAL file.
 *
 * This is deliberately a dumb data holder — no logic. Its only job is to
 * carry (opType, key, value) from disk back into memory during recovery,
 * so MemTable.replay() (built in the next step) can rebuild state from it.
 */
public final class WalEntry {

    public static final byte OP_PUT = 1;
    public static final byte OP_DELETE = 2;

    public final byte opType;
    public final byte[] key;
    public final byte[] value; // empty array for DELETE — no value to carry

    public WalEntry(byte opType, byte[] key, byte[] value) {
        this.opType = opType;
        this.key = key;
        this.value = value;
    }

    public boolean isPut() {
        return opType == OP_PUT;
    }

    public boolean isDelete() {
        return opType == OP_DELETE;
    }

    @Override
    public String toString() {
        return (isPut() ? "PUT " : "DELETE ") + new String(key) +
                (isPut() ? "=" + new String(value) : "");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WalEntry other)) return false;
        return opType == other.opType
                && Arrays.equals(key, other.key)
                && Arrays.equals(value, other.value);
    }
}