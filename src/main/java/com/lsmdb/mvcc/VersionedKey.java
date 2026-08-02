package com.lsmdb.mvcc;

import java.util.Arrays;

/**
 * A composite key: (user's key, sequence number). Ordering is the
 * entire trick that makes MVCC snapshot reads fast:
 *
 *   1. Primary order: by userKey, ascending (bytes lexicographically) —
 *      groups all versions of the same key together.
 *   2. Secondary order: by seqNum, DESCENDING — within one key's group,
 *      the NEWEST version sorts first.
 *
 * Why descending seqNum specifically: it means ceilingEntry(key, snap)
 * on a sorted map lands directly on "the newest version of this key
 * that is still <= snap" in one lookup — see MVCCStore.get() for the
 * full reasoning of why that specific search works.
 */
public record VersionedKey(byte[] userKey, long seqNum) implements Comparable<VersionedKey> {

    @Override
    public int compareTo(VersionedKey other) {
        int cmp = Arrays.compare(userKey, other.userKey);
        if (cmp != 0) {
            return cmp;
        }
        // Reversed comparison (other, this) instead of (this, other) is
        // what flips the ordering to descending for seqNum specifically,
        // while userKey above stays normal ascending order.
        return Long.compare(other.seqNum, seqNum);
    }
}