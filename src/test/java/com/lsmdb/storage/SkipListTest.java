package com.lsmdb.storage;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SkipListTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void getOnEmptyListReturnsNull() {
        SkipList list = new SkipList();
        assertNull(list.get(b("missing")));
    }

    @Test
    void putThenGetReturnsSameValue() {
        SkipList list = new SkipList();
        list.put(b("key1"), b("value1"));
        assertEquals("value1", s(list.get(b("key1"))));
    }

    @Test
    void puttingSameKeyTwiceOverwritesValue() {
        SkipList list = new SkipList();
        list.put(b("key1"), b("first"));
        list.put(b("key1"), b("second"));
        assertEquals("second", s(list.get(b("key1"))));
    }

    @Test
    void manyKeysAllRetrievableInAnyInsertOrder() {
        SkipList list = new SkipList();
        // Insert out of order on purpose — the skip list must sort internally.
        String[] keys = {"banana", "apple", "cherry", "date", "elderberry"};
        for (String k : keys) {
            list.put(b(k), b(k.toUpperCase()));
        }
        for (String k : keys) {
            assertEquals(k.toUpperCase(), s(list.get(b(k))));
        }
    }

    @Test
    void removeExistingKeyReturnsTrueAndKeyDisappears() {
        SkipList list = new SkipList();
        list.put(b("key1"), b("value1"));
        assertTrue(list.remove(b("key1")));
        assertNull(list.get(b("key1")));
    }

    @Test
    void removeNonExistentKeyReturnsFalse() {
        SkipList list = new SkipList();
        list.put(b("key1"), b("value1"));
        assertFalse(list.remove(b("missing")));
    }

    @Test
    void removeThenReinsertWorksCorrectly() {
        SkipList list = new SkipList();
        list.put(b("key1"), b("value1"));
        list.remove(b("key1"));
        list.put(b("key1"), b("value2"));
        assertEquals("value2", s(list.get(b("key1"))));
    }

    @Test
    void largeNumberOfInsertsStayCorrect() {
        // Stress test: forces multiple levels to actually be exercised,
        // not just level 0. With 1000 random-level inserts, if the
        // update[] rewiring logic were buggy, this would surface it.
        SkipList list = new SkipList();
        for (int i = 0; i < 1000; i++) {
            list.put(b("key" + i), b("value" + i));
        }
        for (int i = 0; i < 1000; i++) {
            assertEquals("value" + i, s(list.get(b("key" + i))),
                    "mismatch at key" + i);
        }
    }

    @Test
    void deletingMiddleElementDoesNotBreakNeighbors() {
        SkipList list = new SkipList();
        for (int i = 0; i < 10; i++) {
            list.put(b("key" + i), b("value" + i));
        }
        list.remove(b("key5"));
        assertNull(list.get(b("key5")));
        // Neighbors must still be reachable — this fails if forward[]
        // rewiring during remove() is broken.
        assertEquals("value4", s(list.get(b("key4"))));
        assertEquals("value6", s(list.get(b("key6"))));
    }
}