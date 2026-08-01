package com.lsmdb.bloom;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BloomFilterTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void addedKeysAreAlwaysReportedAsMightContain() {
        // The one guarantee that MUST hold, always: no false negatives.
        // If this ever fails, the whole "safe to skip a file" premise
        // behind using a bloom filter in a storage engine breaks.
        BloomFilter filter = BloomFilter.create(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            filter.add(b("key" + i));
        }
        for (int i = 0; i < 1000; i++) {
            assertTrue(filter.mightContain(b("key" + i)),
                    "false negative on key" + i + " — this must never happen");
        }
    }

    @Test
    void neverAddedKeyIsUsuallyReportedAbsent() {
        // Not a hard guarantee (false positives are allowed), but with
        // only a few keys added and a low target false-positive rate,
        // an obviously different key should almost always be correctly
        // reported absent.
        BloomFilter filter = BloomFilter.create(100, 0.01);
        filter.add(b("apple"));
        filter.add(b("banana"));
        filter.add(b("cherry"));

        assertFalse(filter.mightContain(b("zzz_definitely_not_added")));
    }

    @Test
    void falsePositiveRateStaysRoughlyWithinConfiguredBound() {
        // Statistical test, not a strict guarantee — add exactly as many
        // keys as the filter was sized for, then check the observed
        // false-positive rate on a large batch of definitely-absent
        // keys stays in a sane ballpark around the configured 1% target.
        int n = 1000;
        BloomFilter filter = BloomFilter.create(n, 0.01);
        for (int i = 0; i < n; i++) {
            filter.add(b("present" + i));
        }

        int falsePositives = 0;
        int trials = 5000;
        for (int i = 0; i < trials; i++) {
            if (filter.mightContain(b("absent" + i))) {
                falsePositives++;
            }
        }
        double observedRate = (double) falsePositives / trials;

        // Generous upper bound (5x target) — this is a statistical test,
        // not an exact one, so we allow real headroom to avoid flakiness.
        assertTrue(observedRate < 0.05,
                "observed false positive rate too high: " + observedRate);
    }

    @Test
    void serializationRoundTripPreservesMembership() throws Exception {
        BloomFilter original = BloomFilter.create(100, 0.01);
        original.add(b("key1"));
        original.add(b("key2"));
        original.add(b("key3"));

        byte[] bytes = original.toBytes();
        BloomFilter restored = BloomFilter.fromBytes(bytes);

        assertTrue(restored.mightContain(b("key1")));
        assertTrue(restored.mightContain(b("key2")));
        assertTrue(restored.mightContain(b("key3")));
        assertFalse(restored.mightContain(b("neverAdded")));
    }

    @Test
    void emptyFilterReportsEverythingAbsent() {
        BloomFilter filter = BloomFilter.create(100, 0.01);
        assertFalse(filter.mightContain(b("anything")));
    }
}