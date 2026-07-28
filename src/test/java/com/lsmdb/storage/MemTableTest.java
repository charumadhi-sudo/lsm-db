package com.lsmdb.storage;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MemTableTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putThenGetReturnsValue() {
        MemTable table = new MemTable();
        table.put(b("key1"), b("value1"));
        assertArrayEquals(b("value1"), table.get(b("key1")));
    }

    @Test
    void getMissingKeyReturnsNull() {
        MemTable table = new MemTable();
        assertNull(table.get(b("missing")));
    }

    @Test
    void deleteWritesTombstoneNotNull() {
        // This is the key LSM-tree semantic: delete() must NOT make the
        // key disappear from the structure — it must leave a marker
        // behind. Downstream (SSTable flush), that marker needs to exist
        // so it can shadow older stale values in older files.
        MemTable table = new MemTable();
        table.put(b("key1"), b("value1"));
        table.delete(b("key1"));

        byte[] result = table.get(b("key1"));
        assertNotNull(result, "tombstone should still be a real, retrievable entry");
        assertTrue(MemTable.isTombstone(result));
    }

    @Test
    void deletingNeverWrittenKeyStillCreatesTombstone() {
        // Deleting a key that was never written is valid — it just means
        // "if an older SSTable has this key, treat it as deleted."
        MemTable table = new MemTable();
        table.delete(b("neverExisted"));
        assertTrue(MemTable.isTombstone(table.get(b("neverExisted"))));
    }

    @Test
    void approximateSizeGrowsWithWrites() {
        MemTable table = new MemTable();
        assertEquals(0, table.approximateSizeBytes());
        table.put(b("key1"), b("value1"));
        assertTrue(table.approximateSizeBytes() > 0);
    }

    @Test
    void updatingExistingKeyReturnsNewValue() {
        MemTable table = new MemTable();
        table.put(b("key1"), b("first"));
        table.put(b("key1"), b("second"));
        assertArrayEquals(b("second"), table.get(b("key1")));
    }
}