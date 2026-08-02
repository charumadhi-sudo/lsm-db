package com.lsmdb.mvcc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MVCCStoreTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void putThenGetReturnsLatestValue() {
        MVCCStore store = new MVCCStore();
        store.put(b("key1"), b("value1"));
        assertEquals("value1", s(store.get(b("key1"))));
    }

    @Test
    void getMissingKeyReturnsNull() {
        MVCCStore store = new MVCCStore();
        assertNull(store.get(b("missing")));
    }

    @Test
    void latestGetSeesTheNewestWriteAfterMultipleUpdates() {
        MVCCStore store = new MVCCStore();
        store.put(b("key1"), b("first"));
        store.put(b("key1"), b("second"));
        store.put(b("key1"), b("third"));

        assertEquals("third", s(store.get(b("key1"))));
    }

    @Test
    void snapshotReadIsUnaffectedByWritesThatHappenAfterIt() {
        MVCCStore store = new MVCCStore();
        store.put(b("key1"), b("original"));

        long snapshot = store.beginSnapshot();

        store.put(b("key1"), b("updated"));
        store.put(b("key1"), b("updatedAgain"));

        assertEquals("original", s(store.get(b("key1"), snapshot)),
                "snapshot read must be frozen at the moment beginSnapshot() was called");

        assertEquals("updatedAgain", s(store.get(b("key1"))));
    }

    @Test
    void snapshotTakenBeforeKeyExistedSeesItAsAbsent() {
        MVCCStore store = new MVCCStore();
        long snapshotBeforeWrite = store.beginSnapshot();

        store.put(b("key1"), b("value1"));

        assertNull(store.get(b("key1"), snapshotBeforeWrite),
                "a snapshot taken before the key was written must not see it");
        assertEquals("value1", s(store.get(b("key1"))));
    }

    @Test
    void deleteIsVisibleAsAbsentInLaterSnapshotsButNotEarlierOnes() {
        MVCCStore store = new MVCCStore();
        store.put(b("key1"), b("value1"));
        long beforeDelete = store.beginSnapshot();

        store.delete(b("key1"));

        assertEquals("value1", s(store.get(b("key1"), beforeDelete)),
                "snapshot taken before the delete must still see the old value");
        assertNull(store.get(b("key1")), "current read must see the delete");
    }

    @Test
    void multipleSnapshotsAtDifferentPointsSeeDifferentHistoricalValues() {
        MVCCStore store = new MVCCStore();

        store.put(b("key1"), b("v1"));
        long snap1 = store.beginSnapshot();

        store.put(b("key1"), b("v2"));
        long snap2 = store.beginSnapshot();

        store.put(b("key1"), b("v3"));
        long snap3 = store.beginSnapshot();

        assertEquals("v1", s(store.get(b("key1"), snap1)));
        assertEquals("v2", s(store.get(b("key1"), snap2)));
        assertEquals("v3", s(store.get(b("key1"), snap3)));
    }

    @Test
    void independentKeysDoNotInterfereWithEachOthersVersions() {
        MVCCStore store = new MVCCStore();
        store.put(b("a"), b("a1"));
        store.put(b("b"), b("b1"));
        store.put(b("a"), b("a2"));

        assertEquals("a2", s(store.get(b("a"))));
        assertEquals("b1", s(store.get(b("b"))));
    }
}