package com.lsmdb.wal;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log: an append-only file that records every mutation
 * (PUT/DELETE) before it's applied to the in-memory MemTable.
 *
 * Why this exists: MemTable lives in RAM. If the process crashes, RAM
 * is gone. The WAL is what lets us reconstruct the MemTable's exact
 * state on restart, by replaying every operation from disk in order.
 *
 * Durability strategy for this version: fsync after every single append.
 * This is the simplest, safest option — every acknowledged write is
 * guaranteed durable before we return control to the caller. It's also
 * the slowest option; production engines often batch multiple writes
 * before fsync-ing ("group commit") to trade a little durability window
 * for much higher throughput. We start with the safe version and can
 * add batching later as a deliberate, measurable optimization.
 */
public class WriteAheadLog implements Closeable {

    private final File file;
    private final FileOutputStream fileOutputStream;
    private final DataOutputStream out;

    public WriteAheadLog(File file) throws IOException {
        this.file = file;
        // "true" = append mode. We never overwrite an existing WAL —
        // if one exists on startup, it's from before a restart/crash,
        // and its contents must be replayed, not discarded.
        this.fileOutputStream = new FileOutputStream(file, true);
        this.out = new DataOutputStream(new BufferedOutputStream(fileOutputStream));
    }

    /**
     * Appends a PUT operation to the log and forces it to physical disk
     * before returning. Only after this returns is the write considered
     * durable — safe to apply to the MemTable.
     */
    public synchronized void appendPut(byte[] key, byte[] value) throws IOException {
        appendRecord(WalEntry.OP_PUT, key, value);
    }

    /**
     * Appends a DELETE operation. Value is stored as an empty array —
     * the WAL doesn't need MemTable's TOMBSTONE convention, since opType
     * already unambiguously says "this is a delete."
     */
    public synchronized void appendDelete(byte[] key) throws IOException {
        appendRecord(WalEntry.OP_DELETE, key, new byte[0]);
    }

    private void appendRecord(byte opType, byte[] key, byte[] value) throws IOException {
        // Build the record payload (everything EXCEPT the checksum) into
        // an in-memory buffer first, so we can compute the checksum over
        // exactly these bytes before writing anything to the real stream.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream bufferOut = new DataOutputStream(buffer);
        bufferOut.writeByte(opType);
        bufferOut.writeInt(key.length);
        bufferOut.write(key);
        bufferOut.writeInt(value.length);
        bufferOut.write(value);

        byte[] payload = buffer.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(payload);
        long checksum = crc.getValue();

        // Now write the real record: payload, then checksum.
        out.write(payload);
        out.writeLong(checksum);

        // Flush our BufferedOutputStream into the OS, then force the OS
        // to write it to physical disk. Skipping fsync would mean the
        // data could sit in the OS page cache and be lost on a crash,
        // even though write() already "succeeded" from Java's point of view.
        out.flush();
        fileOutputStream.getFD().sync();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /**
     * Replays every valid record in a WAL file, in the order they were
     * written. Used on startup to reconstruct MemTable state.
     *
     * Crash-safety handling: if the process crashed mid-append, the LAST
     * record in the file may be physically incomplete (a "torn write") —
     * for example, only 6 of the expected 20 bytes of the key made it to
     * disk before the crash. We must detect this and stop cleanly rather
     * than crash or silently read garbage as if it were valid data.
     *
     * We detect this two ways:
     *   1. Running out of bytes mid-record (EOFException) — clearly torn.
     *   2. A checksum mismatch — the bytes are all there, but corrupted
     *      (e.g. disk-level bit rot, or a very unlucky torn write that
     *      happened to leave the right number of bytes).
     * Either case: we stop reading and return only the valid records
     * that came before it. We do NOT throw — a torn tail record is an
     * expected, recoverable situation, not a fatal error.
     */
    public static List<WalEntry> replay(File file) throws IOException {
        List<WalEntry> entries = new ArrayList<>();
        if (!file.exists()) {
            return entries; // no WAL yet — fresh database, nothing to replay
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                WalEntry entry;
                try {
                    entry = readOneRecord(in);
                } catch (EOFException e) {
                    // Ran out of bytes mid-record: torn write at the tail.
                    // Stop here; everything read so far is valid.
                    break;
                }
                if (entry == null) {
                    // Checksum mismatch: corrupted record. Stop here too.
                    break;
                }
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Reads and validates exactly one record. Returns null (not an
     * exception) specifically on checksum mismatch, so the caller can
     * distinguish "clean end of valid data" from "stream literally ran
     * out of bytes" — both are handled as "stop replaying," but keeping
     * them distinct makes future debugging much easier.
     */
    private static WalEntry readOneRecord(DataInputStream in) throws IOException {
        byte opType = in.readByte();
        int keyLen = in.readInt();
        byte[] key = new byte[keyLen];
        in.readFully(key);
        int valueLen = in.readInt();
        byte[] value = new byte[valueLen];
        in.readFully(value);
        long storedChecksum = in.readLong();

        // Recompute the checksum over exactly the same bytes we wrote
        // it over originally, to verify nothing was corrupted.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream bufferOut = new DataOutputStream(buffer);
        bufferOut.writeByte(opType);
        bufferOut.writeInt(keyLen);
        bufferOut.write(key);
        bufferOut.writeInt(valueLen);
        bufferOut.write(value);

        CRC32 crc = new CRC32();
        crc.update(buffer.toByteArray());

        if (crc.getValue() != storedChecksum) {
            return null; // corrupted record
        }
        return new WalEntry(opType, key, value);
    }

    public File file() {
        return file;
    }
}