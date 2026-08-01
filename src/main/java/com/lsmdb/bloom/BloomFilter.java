package com.lsmdb.bloom;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;

/**
 * A bloom filter: a compact, probabilistic structure that answers
 * "might this key be in the set?" in O(k) time using a fixed-size bit
 * array, without storing the actual keys at all.
 *
 * The core guarantee, and why it's safe to use for "should I even open
 * this SSTable file?":
 *   - mightContain() NEVER returns false for a key that was actually
 *     added (no false negatives).
 *   - mightContain() CAN return true for a key that was never added
 *     (false positives are possible, at a tunable rate).
 * That asymmetry is exactly right for our use case: a "maybe" just costs
 * us a wasted disk lookup we'd have done anyway; a wrong "definitely
 * not" would incorrectly hide real data, which we can never allow.
 *
 * How it works: k independent-ish hash functions map a key to k bit
 * positions. add() sets those bits. mightContain() checks whether ALL
 * k bits are set — if even one is unset, the key was definitely never
 * added (since add() always sets every one of its k bits).
 */
public class BloomFilter {

    private final BitSet bits;
    private final int numBits;
    private final int numHashFunctions;

    private BloomFilter(BitSet bits, int numBits, int numHashFunctions) {
        this.bits = bits;
        this.numBits = numBits;
        this.numHashFunctions = numHashFunctions;
    }

    /**
     * Creates a new, empty bloom filter sized for the given expected
     * number of entries and target false-positive rate.
     *
     * Standard formulas (derived from the probability math of random
     * bit collisions across k independent hash functions):
     *   m (bits)          = -(n * ln(p)) / (ln 2)^2
     *   k (hash functions) = (m / n) * ln 2
     */
    public static BloomFilter create(int expectedEntries, double falsePositiveRate) {
        if (expectedEntries <= 0) expectedEntries = 1; // avoid divide-by-zero for an empty flush

        int numBits = optimalNumBits(expectedEntries, falsePositiveRate);
        int numHashFunctions = optimalNumHashFunctions(numBits, expectedEntries);

        return new BloomFilter(new BitSet(numBits), numBits, numHashFunctions);
    }

    private static int optimalNumBits(int n, double p) {
        double m = -(n * Math.log(p)) / (Math.log(2) * Math.log(2));
        return Math.max(8, (int) Math.ceil(m));
    }

    private static int optimalNumHashFunctions(int numBits, int n) {
        int k = (int) Math.round(((double) numBits / n) * Math.log(2));
        return Math.max(1, k);
    }

    /**
     * Records a key as present. Sets all k derived bit positions.
     */
    public void add(byte[] key) {
        long h1 = hash1(key);
        long h2 = hash2(key);
        for (int i = 0; i < numHashFunctions; i++) {
            int position = bitPosition(h1, h2, i);
            bits.set(position);
        }
    }

    /**
     * Returns false only if the key is GUARANTEED absent (at least one
     * of its k bits is unset). Returns true if the key MIGHT be present
     * — either it genuinely is, or this is a false positive.
     */
    public boolean mightContain(byte[] key) {
        long h1 = hash1(key);
        long h2 = hash2(key);
        for (int i = 0; i < numHashFunctions; i++) {
            int position = bitPosition(h1, h2, i);
            if (!bits.get(position)) {
                return false; // definitely never added — one unset bit is proof enough
            }
        }
        return true;
    }

    private int bitPosition(long h1, long h2, int i) {
        // Kirsch–Mitzenmacher trick: derive k hash values from just 2
        // real hashes instead of implementing k separate hash functions.
        long combined = h1 + (long) i * h2;
        // Math.floorMod (not %) so negative hash values still map into
        // a valid, non-negative bit index.
        return (int) Math.floorMod(combined, (long) numBits);
    }

    /**
     * FNV-1a hash — fast, simple, and good enough distribution for a
     * bloom filter's purposes. Two different seeds give us two
     * independent-enough hashes to feed the Kirsch-Mitzenmacher trick.
     * (Production systems often use MurmurHash3 instead — same idea,
     * better statistical properties. FNV-1a is the right level of
     * complexity for understanding the mechanism.)
     */
    private long hash1(byte[] data) {
        return fnv1a(data, 0xcbf29ce484222325L); // standard FNV offset basis
    }

    private long hash2(byte[] data) {
        return fnv1a(data, 0x9E3779B97F4A7C15L); // different seed -> different hash
    }

    private long fnv1a(byte[] data, long seed) {
        long hash = seed;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }

    /**
     * Serializes this filter to bytes for storage inside an SSTable
     * file: [numBits:4][numHashFunctions:4][bitSetByteLen:4][bitSetBytes]
     */
    public byte[] toBytes() throws IOException {
        byte[] bitSetBytes = bits.toByteArray();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(numBits);
        out.writeInt(numHashFunctions);
        out.writeInt(bitSetBytes.length);
        out.write(bitSetBytes);
        return buffer.toByteArray();
    }

    /**
     * Reconstructs a bloom filter from bytes previously produced by
     * toBytes(). Used when opening an existing SSTable file.
     */
    public static BloomFilter fromBytes(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int numBits = in.readInt();
        int numHashFunctions = in.readInt();
        int bitSetByteLen = in.readInt();
        byte[] bitSetBytes = new byte[bitSetByteLen];
        in.readFully(bitSetBytes);
        BitSet bits = BitSet.valueOf(bitSetBytes);
        return new BloomFilter(bits, numBits, numHashFunctions);
    }
}