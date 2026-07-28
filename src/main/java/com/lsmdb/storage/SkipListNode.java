package com.lsmdb.storage;

/**
 * A single node in the skip list.
 *
 * Why byte[] and not a generic type <K, V>?
 * Real storage engines operate on raw bytes because keys/values will
 * eventually be serialized to disk (WAL, SSTables). Using byte[] from
 * day one avoids a painful refactor later, and forces us to think about
 * serialization now rather than pretending it doesn't exist.
 *
 * Why "forward" as an array of nodes, one per level?
 * This is the core trick of a skip list: instead of one "next" pointer
 * (like a normal linked list), each node has an ARRAY of next-pointers,
 * one for each level it participates in. A node inserted at level 3
 * has forward[0..3], letting searches "jump" across many nodes at
 * higher levels instead of walking one-by-one.
 */
public class SkipListNode {

    final byte[] key;
    byte[] value;
    final SkipListNode[] forward; // forward[i] = next node at level i

    public SkipListNode(byte[] key, byte[] value, int level) {
        this.key = key;
        this.value = value;
        this.forward = new SkipListNode[level + 1];
    }
}

