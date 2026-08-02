package com.lsmdb.mvcc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void writesAreInvisibleToTheStoreBeforeCommit() {
        MVCCStore store = new MVCCStore();
        Transaction tx = store.beginTransaction();
        tx.put(b("key1"), b("value1"));

        assertNull(store.get(b("key1")), "uncommitted write must not be visible outside the transaction");
    }

    @Test
    void transactionSeesItsOwnUncommittedWrites() {
        MVCCStore store = new MVCCStore();
        Transaction tx = store.beginTransaction();
        tx.put(b("key1"), b("value1"));

        assertEquals("value1", s(tx.get(b("key1"))),
                "a transaction must be able to read its own uncommitted writes");
    }

    @Test
    void commitMakesAllWritesVisibleAtOnce() {
        MVCCStore store = new MVCCStore();
        Transaction tx = store.beginTransaction();
        tx.put(b("account_a"), b("90"));
        tx.put(b("account_b"), b("110"));
        tx.commit();

        assertEquals("90", s(store.get(b("account_a"))));
        assertEquals("110", s(store.get(b("account_b"))));
    }

    @Test
    void rollbackDiscardsAllPendingWritesEntirely() {
        MVCCStore store = new MVCCStore();
        store.put(b("key1"), b("original"));

        Transaction tx = store.beginTransaction();
        tx.put(b("key1"), b("modified"));
        tx.rollback();

        assertEquals("original", s(store.get(b("key1"))),
                "rollback must leave the store completely untouched");
    }

    @Test
    void operationsAfterCommitThrow() {
        MVCCStore store = new MVCCStore();
        Transaction tx = store.beginTransaction();
        tx.put(b("key1"), b("value1"));
        tx.commit();

        assertThrows(IllegalStateException.class, () -> tx.put(b("key2"), b("value2")));
        assertThrows(IllegalStateException.class, tx::commit);
    }

    @Test
    void operationsAfterRollbackThrow() {
        MVCCStore store = new MVCCStore();
        Transaction tx = store.beginTransaction();
        tx.put(b("key1"), b("value1"));
        tx.rollback();

        assertThrows(IllegalStateException.class, () -> tx.get(b("key1")));
        assertThrows(IllegalStateException.class, tx::commit);
    }

    @Test
    void otherTransactionsDoNotSeeUncommittedWritesEvenMidTransaction() {
        MVCCStore store = new MVCCStore();

        Transaction txA = store.beginTransaction();
        txA.put(b("key1"), b("fromA"));

        Transaction txB = store.beginTransaction();
        assertNull(txB.get(b("key1")), "txB must not see txA's uncommitted write");

        txA.commit();

        assertNull(txB.get(b("key1")),
                "txB's snapshot must stay frozen even after txA commits");

        Transaction txC = store.beginTransaction();
        assertEquals("fromA", s(txC.get(b("key1"))));
    }

    @Test
    void deleteWithinTransactionIsVisibleOnlyAfterCommit() {
        MVCCStore store = new MVCCStore();
        store.put(b("key1"), b("value1"));

        Transaction tx = store.beginTransaction();
        tx.delete(b("key1"));

        assertEquals("value1", s(store.get(b("key1"))), "delete must not be visible before commit");
        assertNull(tx.get(b("key1")), "but the transaction itself must see its own pending delete");

        tx.commit();
        assertNull(store.get(b("key1")), "delete must be visible now that it's committed");
    }
}