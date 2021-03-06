/*
 * Bitronix Transaction Manager
 *
 * Copyright (c) 2011, Juergen Kellerer.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */

package bitronix.tm.journal.nio;

import bitronix.tm.journal.nio.util.CompositeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static bitronix.tm.journal.nio.NioJournalFileRecord.readRecords;

/**
 * Low level file handling implementation.
 *
 * @author juergen kellerer, 2011-04-30
 */
class NioJournalFile implements NioJournalConstants {

    private static final Logger log = LoggerFactory.getLogger(NioJournalFile.class);

    static byte[] nameBytes(String nameValue) {
        ByteBuffer encoded = NAME_CHARSET.encode(nameValue);
        if (encoded.remaining() == encoded.capacity() && encoded.hasArray())
            return encoded.array();
        else {
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        }
    }

    private static final String NL = "\r\n";
    private static final String JOURNAL_HEADER_MAGIC_VALUE = "BTM-NTJ-[Version 1.0]";
    private static final byte[] JOURNAL_HEADER_PREFIX = nameBytes(JOURNAL_HEADER_MAGIC_VALUE + NL +
            NL +
            "--------- Bitronix Transaction Manager :: Nio Transaction Journal File ---------" + NL +
            NL +
            "    This is a delimiter based rolling binary file format belonging to BTM." + NL +
            "    The purpose of this file is to persist JTA transaction states for " + NL +
            "    providing crash recovery on broken commits and rollbacks." + NL +
            NL +
            "--------------------------------------------------------------------------------" + NL +
            NL);

    private static final byte[] JOURNAL_HEADER_SUFFIX = nameBytes(NL + NL);

    static final int FIXED_HEADER_SIZE = 1024;

    private volatile UUID previousDelimiter = UUID.randomUUID();
    private volatile UUID delimiter = UUID.randomUUID();

    private ByteBuffer writeBuffer;

    private final File file;
    private final RandomAccessFile randomAccessFile;

    private FileLock lock;
    private FileChannel fileChannel;

    private final AtomicLong journalSize = new AtomicLong();
    private final AtomicLong lastModified = new AtomicLong(), lastForced = new AtomicLong();

    /**
     * Constructs a new journal file instance using the given storage path and initial file size.
     * <p/>
     * If the given file does not exist or is empty, a new journal is created with the given initial size.
     * When the file does exist, new record are appended at the end of the journal and the size is increased
     * if initial size is greater than the current journal size.
     *
     * @param file               the journal file to write to.
     * @param initialJournalSize the initial size to pre-allocated for the journal.
     * @throws IOException if opening the file fails.
     */
    public NioJournalFile(File file, long initialJournalSize) throws IOException {
        this.file = file;
        boolean success = false;
        randomAccessFile = new RandomAccessFile(file, "rw");
        try {
            fileChannel = randomAccessFile.getChannel();
            lock = fileChannel.tryLock();
            if (lock == null)
                throw new IOException("Failed to acquire an exclusive lock on file " + file + ". It seems the journal is opened in another process.");

            final boolean createHeader = randomAccessFile.length() == 0;
            if (!createHeader) {
                try {
                    readJournalHeader();
                } catch (IOException e) {
                    log.error("Failed reading journal header, refusing to open the file " + file + ".", e);
                    throw e;
                }
            }

            // We can increase but not shrink the journal.
            this.journalSize.set(Math.max(initialJournalSize, randomAccessFile.length()));
            growJournal(this.journalSize.get());

            if (createHeader) {
                writeJournalHeader();
                log.info("Created a new transaction journal in file " + file + ", insert position is at offset " + fileChannel.position());
            } else {
                if (log.isDebugEnabled()) { log.debug("Found existing transaction journal in file " + file + " looking after the insert position."); }
                NioJournalFileIterable it = (NioJournalFileIterable) readRecords(delimiter, fileChannel, false);
                long position = it.findPositionAfterLastRecord();
                fileChannel.position(Math.max(FIXED_HEADER_SIZE, position));
                long insertPosition = fileChannel.position();

                log.info("Opened existing transaction journal in file " + file + ", insert position is at offset " + insertPosition + ".");

                if (insertPosition == FIXED_HEADER_SIZE)
                    log.warn("The journal file " + file + " appears to be empty though it was not just created.");
            }

            success = true;
        } finally {
            if (!success)
                close();
        }
    }

    public File getFile() {
        return file;
    }

    public synchronized long getSize() {
        return journalSize.get();
    }

    public long getPosition() throws IOException {
        return fileChannel.position();
    }

    /**
     * Closes the journal.
     *
     * @throws IOException in case of the operation failed.
     */
    public synchronized void close() throws IOException {
        try {
            if (fileChannel != null) {
                force();
                try {
                    if (lock != null)
                        lock.release();
                } finally {
                    fileChannel.close();
                }
            }
        } finally {
            fileChannel = null;
            lock = null;
        }
    }

    /**
     * Grows the journal to the specified size, does nothing if newSize is smaller than the current journal size.
     *
     * @param newSize the new size to grow the journal to.
     * @throws IOException in case of there's no space available or the underlying device is broken.
     */
    public synchronized void growJournal(long newSize) throws IOException {
        if (newSize >= journalSize.get()) {
            randomAccessFile.setLength(newSize);
            journalSize.set(newSize);
        }
    }

    /**
     * Returns an iterable over all records that are contained in the record.
     *
     * @param includeInvalid specifies whether records that fail the CRC32 checks should be returned as well.
     * @return an iterable over all records that are contained in the record.
     * @throws IOException in case of the file cannot be accessed.
     */
    public synchronized Iterable<NioJournalFileRecord> readAll(boolean includeInvalid) throws IOException {
        final Iterable<NioJournalFileRecord> first = readRecords(previousDelimiter, fileChannel, includeInvalid);
        final Iterable<NioJournalFileRecord> second = readRecords(delimiter, fileChannel, includeInvalid);

        return new Iterable<NioJournalFileRecord>() {
            public Iterator<NioJournalFileRecord> iterator() {
                @SuppressWarnings("unchecked")
                List<Iterable<NioJournalFileRecord>> iterables = Arrays.asList(first, second);
                return new CompositeIterator<NioJournalFileRecord>(iterables);
            }
        };
    }

    private void assertHeaderPartEquals(ByteBuffer buffer, byte[] value) throws IOException {
        byte[] prefix = new byte[value.length];
        buffer.get(prefix);
        if (!Arrays.equals(prefix, value)) {

            // If we'd had multiple version, legacy handling would go in here.

            throw new IOException("Failed opening journal file '" + file + "', expected a file header of <" +
                    NioJournalFileRecord.bufferToString(ByteBuffer.wrap(value)) + "> but was <" +
                    NioJournalFileRecord.bufferToString(ByteBuffer.wrap(prefix)) + ">");
        }
    }

    private void readJournalHeader() throws IOException {
        if (fileChannel.size() == 0)
            return; // new file.

        ByteBuffer buffer = getWriteBuffer(FIXED_HEADER_SIZE);
        fileChannel.read(buffer, 0);
        buffer.flip();

        try {
            assertHeaderPartEquals(buffer, JOURNAL_HEADER_PREFIX);
            previousDelimiter = NioJournalFileRecord.readUUID(buffer);
            delimiter = NioJournalFileRecord.readUUID(buffer);
            assertHeaderPartEquals(buffer, JOURNAL_HEADER_SUFFIX);

            fileChannel.position(FIXED_HEADER_SIZE);
        } catch (IOException e) {
            previousDelimiter = UUID.randomUUID();
            delimiter = UUID.randomUUID();
            throw e;
        }
    }

    private void writeJournalHeader() throws IOException {
        if (fileChannel.position() != 0)
            throw new IllegalStateException("File channel is not positioned at the header location.");

        ByteBuffer buffer = getWriteBuffer(FIXED_HEADER_SIZE);
        buffer.put(JOURNAL_HEADER_PREFIX);
        NioJournalFileRecord.writeUUID(previousDelimiter, buffer);
        NioJournalFileRecord.writeUUID(delimiter, buffer);
        buffer.put(JOURNAL_HEADER_SUFFIX);
        fileChannel.write((ByteBuffer) buffer.flip());

        // Set position to data area
        fileChannel.position(FIXED_HEADER_SIZE);
    }

    /**
     * Rollover to the beginning of the journal file.
     *
     * @throws IOException in case of the operation failed.
     */
    public synchronized void rollover() throws IOException {
        eraseRemainingBytesInJournal();

        fileChannel.position(0);
        previousDelimiter = delimiter;
        delimiter = UUID.randomUUID();
        writeJournalHeader();
    }

    private void eraseRemainingBytesInJournal() throws IOException {
        final int blockSize = 4 * 1024;
        final ByteBuffer buffer = getWriteBuffer(blockSize);
        while (buffer.hasRemaining())
            buffer.put((byte) ' ');

        do {
            buffer.flip().limit((int) Math.min(remainingCapacity(), blockSize));
        } while (fileChannel.write(buffer) != 0);
    }

    /**
     * Creates an empty record that may be used to write it to the journal.
     *
     * @return an empty record that can be written to the journal.
     */
    public NioJournalFileRecord createEmptyRecord() {
        return new NioJournalFileRecord(delimiter);
    }

    /**
     * Writes the given records to this journal.
     *
     * @param records the records to write.
     * @return the number of written bytes.
     * @throws IOException in case of the operation failed.
     */
    public synchronized long write(List<NioJournalFileRecord> records) throws IOException {
        try {
            final int requiredBytes = NioJournalFileRecord.calculateRequiredBytes(records);
            final long remainingBytes = remainingCapacity();
            if (requiredBytes > remainingBytes) {
                throw new IOException("Journal requires a rollover (remaining capacity: " + remainingBytes +
                        ", required: " + requiredBytes + "). Manually trigger this before writing new content.");
            }

            // the implementation of gathering and scattering byte channels is not very fast.
            // using an intermediate buffer improves speed by factor 4 to 5 (direct buffer is ~25% improvement on top).

            final UUID targetDelimiter = delimiter;
            final ByteBuffer writeBuffer = getWriteBuffer(requiredBytes);
            for (NioJournalFileRecord record : records)
                record.writeRecord(targetDelimiter, writeBuffer);

            writeBuffer.flip();

            return fileChannel.write(writeBuffer);
        } finally {
            lastModified.set(System.currentTimeMillis());
        }
    }

    private ByteBuffer getWriteBuffer(int requiredBytes) {
        ByteBuffer buffer = writeBuffer;
        if (buffer == null || buffer.capacity() < requiredBytes)
            writeBuffer = buffer = USE_DIRECT_BUFFERS ? ByteBuffer.allocateDirect(requiredBytes) : ByteBuffer.allocate(requiredBytes);

        buffer.clear().limit(requiredBytes);
        return buffer;
    }

    /**
     * Returns the remaining capacity in this journal until the rollover happens.
     *
     * @return the remaining capacity in this journal until the rollover happens.
     * @throws IOException in case of the operation failed.
     */
    public long remainingCapacity() throws IOException {
        return Math.max(0, journalSize.get() - fileChannel.position());
    }

    /**
     * Forces the journal to disk (fsync).
     *
     * @throws IOException in case of the operation failed.
     */
    public void force() throws IOException {
        final boolean debug = log.isDebugEnabled();
        if (lastForced.get() != lastModified.get()) {
            if (debug) { log.debug("Forcing (fsync) the file " + file + " now. Insert position is at " + fileChannel.position()); }

            fileChannel.force(false);
            lastForced.set(lastModified.get());
        } else {
            if (debug) { log.debug("Force not required on file " + file + " as no modifications were written since last call."); }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NioJournalFile{" +
                "previousDelimiter=" + previousDelimiter +
                ", delimiter=" + delimiter +
                ", lastModified=" + lastModified +
                ", lastForced=" + lastForced +
                ", file=" + file +
                ", journalSize=" + journalSize +
                '}';
    }
}
