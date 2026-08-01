package com.lsmdb.storage;

import java.util.Arrays;
import java.util.Random;

/**
 * A skip list ordered by key (byte[] compared lexicographically).
 *
 * Why a skip list instead of a Java TreeMap?
 * TreeMap (red-black tree) would honestly work fine functionally.
 * We build our own skip list because:
 *   1. It's what real LSM engines use internally (LevelDB, RocksDB) —
 *      understanding *why* matters for interviews.
 *   2. Skip lists are much easier to make lock-free / concurrent than
 *      balanced trees, which matters once multiple threads read/write
 *      the memtable at once (a later phase).
 *   3. It's a genuinely good DS to have implemented by hand.
 */
public class SkipList {

    private static final int MAX_LEVEL = 16; // supports up to ~2^16 elements efficiently
    private static final double P = 0.5;      // probability used for random leveling

    private final SkipListNode head;
    private int level; // current highest level actually in use
    private final Random random = new Random();

    public SkipList() {
        // head is a sentinel node: never holds real data, exists purely
        // as a fixed starting point for traversal at every level.
        this.head = new SkipListNode(null, null, MAX_LEVEL);
        this.level = 0;
    }

    /**
     * Compares two byte[] keys lexicographically (like String.compareTo,
     * but at the byte level — this is how real storage engines order keys).
     */
    private int compare(byte[] a, byte[] b) {
        return Arrays.compare(a, b);
    }

    /**
     * Randomly decides how many levels a newly inserted node should span.
     *
     * Why coin flips? This is the probabilistic trick that replaces tree
     * rotations. Each level "up" has a 50% (P) chance of extending further.
     * Result: ~50% of nodes are only on level 0, ~25% reach level 1, etc.
     * This gives expected O(log n) height distribution WITHOUT needing to
     * rebalance anything on insert/delete — that's the whole appeal.
     */
    private int randomLevel() {
        int lvl = 0;
        while (random.nextDouble() < P && lvl < MAX_LEVEL) {
            lvl++;
        }
        return lvl;
    }

    /**
     * Inserts or updates a key. If the key already exists, its value is
     * overwritten in place (this is fine for the in-memory memtable —
     * immutability only matters once we're writing to disk as SSTables).
     */
    public void put(byte[] key, byte[] value) {
        // "update" tracks, at each level, the last node BEFORE the
        // insertion point. We need this because after finding where to
        // insert, we must go back and rewire forward[] pointers at every
        // level the new node participates in.
        SkipListNode[] update = new SkipListNode[MAX_LEVEL + 1];
        SkipListNode current = head;

        // Start at the highest active level, walk right while possible,
        // drop down a level when we can't go further right.
        for (int i = level; i >= 0; i--) {
            while (current.forward[i] != null && compare(current.forward[i].key, key) < 0) {
                current = current.forward[i];
            }
            update[i] = current; // remember: this is where level i "hands off"
        }

        // current now sits just before where the key belongs at level 0.
        current = current.forward[0];

        if (current != null && compare(current.key, key) == 0) {
            // Key already exists — just overwrite the value.
            current.value = value;
            return;
        }

        // Key doesn't exist yet — create a new node with a random height.
        int newLevel = randomLevel();
        if (newLevel > level) {
            // This node reaches higher than anything before it —
            // the sentinel head needs to "know" about it at those levels too.
            for (int i = level + 1; i <= newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }

        SkipListNode newNode = new SkipListNode(key, value, newLevel);
        for (int i = 0; i <= newLevel; i++) {
            // Standard linked-list insertion, done at every level this node spans.
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }
    }

    /**
     * Looks up a key. Returns null if not found.
     * (Tombstone handling — distinguishing "not found" from "found, but
     * deleted" — happens one layer up, in MemTable. The skip list itself
     * doesn't know what a tombstone means; it just stores bytes.)
     */
    public byte[] get(byte[] key) {
        SkipListNode current = head;
        for (int i = level; i >= 0; i--) {
            while (current.forward[i] != null && compare(current.forward[i].key, key) < 0) {
                current = current.forward[i];
            }
        }
        current = current.forward[0];
        if (current != null && compare(current.key, key) == 0) {
            return current.value;
        }
        return null;
    }

    /**
     * Removes a key entirely from the structure.
     *
     * NOTE: In the MemTable layer above this, "delete" will actually call
     * put(key, TOMBSTONE) instead of this method — because in an LSM tree,
     * deletes need to be remembered (to shadow older values in SSTables),
     * not physically erased. This raw removal is here because a correct
     * skip list should support it, and it's useful for internal testing.
     */
    public boolean remove(byte[] key) {
        SkipListNode[] update = new SkipListNode[MAX_LEVEL + 1];
        SkipListNode current = head;

        for (int i = level; i >= 0; i--) {
            while (current.forward[i] != null && compare(current.forward[i].key, key) < 0) {
                current = current.forward[i];
            }
            update[i] = current;
        }

        current = current.forward[0];
        if (current == null || compare(current.key, key) != 0) {
            return false; // key not found
        }

        // Rewire pointers at every level this node participated in,
        // effectively splicing it out.
        for (int i = 0; i <= level; i++) {
            if (update[i].forward[i] != current) break;
            update[i].forward[i] = current.forward[i];
        }

        // Shrink "level" if the topmost levels are now empty.
        while (level > 0 && head.forward[level] == null) {
            level--;
        }
        return true;
    }

    /**
     * Returns every node in ascending key order, by walking level 0 —
     * the "bottom rail" that always contains every single element,
     * regardless of what shortcuts exist on higher levels.
     *
     * This is what makes flushing a MemTable to an SSTable possible:
     * an SSTable's data block must be written in sorted order, and this
     * gives us exactly that, in O(n) with no extra sorting needed — the
     * skip list is already sorted by construction.
     */
    public Iterable<SkipListNode> entriesInOrder() {
        return () -> new java.util.Iterator<>() {
            SkipListNode current = head.forward[0];

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public SkipListNode next() {
                SkipListNode node = current;
                current = current.forward[0];
                return node;
            }
        };
    }
}