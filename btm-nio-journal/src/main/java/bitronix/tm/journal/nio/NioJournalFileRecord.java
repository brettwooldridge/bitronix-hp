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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.zip.CRC32;

import static bitronix.tm.journal.nio.NioJournalFile.nameBytes;

/**
 * Low level file record.
 * <p/>
 * Implements methods for finding, reading and writing a single record.
 *
 * @author juergen kellerer, 2011-04-30
 */
class NioJournalFileRecord implements NioJournalConstants {

    private static final Logger log = LoggerFactory.getLogger(NioJournalFileRecord.class);

    private static final byte[] RECORD_DELIMITER_PREFIX = nameBytes("\r\nLR[");
    private static final byte[] RECORD_DELIMITER_SUFFIX = nameBytes("][");
    private static final byte[] RECORD_DELIMITER_TRAILER = nameBytes("]-");

    /**
     * Defines the offset of the 4 byte int value from the beginning of the record that stores the total length of the record itself.
     */
    public static final int RECORD_LENGTH_OFFSET = RECORD_DELIMITER_PREFIX.length + 16;

    /**
     * Defines the offset of the 4 byte int value from the beginning of the record that stores the CRC32 checksum of the record's payload.
     */
    public static final int RECORD_CRC32_OFFSET = RECORD_LENGTH_OFFSET + 4;

    /**
     * Defines the number of bytes consumed by the raw-header of the file-record (this does not include any additional header inside the payload).
     */
    public static final int RECORD_HEADER_SIZE =
            /*                   prefix */ RECORD_DELIMITER_PREFIX.length +
            /* opening delimiter (uuid) */ 16 +
            /*                   length */ 4 +
            /*                    crc32 */ 4 +
            /*                   suffix */ RECORD_DELIMITER_SUFFIX.length;

    /**
     * Defines the number of bytes consumed by the raw-trailer of the file-record.
     */
    public static final int RECORD_TRAILER_SIZE =
            /* closing delimiter (uuid) */ 16 +
            /*           record trailer */ RECORD_DELIMITER_TRAILER.length;

    /**
     * Defines the offset of the 4 byte CRC32 value counted in reverse from the payload position inside a record.
     */
    public static final int REVERSE_RECORD_CRC32_OFFSET = RECORD_CRC32_OFFSET - RECORD_HEADER_SIZE;

    private static final boolean trace = log.isTraceEnabled();

    private UUID delimiter;
    private ByteBuffer payload, recordBuffer;
    private boolean valid = true;

    /**
     * Utility methods that converts the buffer to a string.
     *
     * @param buffer the buffer to convert. (note: use "duplicate" if the buffer should not get consumed)
     * @return the string representation of the buffer using 'ISO-8859-1' charset.
     */
    public static String bufferToString(ByteBuffer buffer) {
        if (buffer == null)
            return "<no-buffer (null)>";

        return NAME_CHARSET.decode(buffer.duplicate()).toString();
    }

    /**
     * Finds the next record inside the given buffer and advances the buffer's position beyond the end of the returned record.
     * <p/>
     * This method consumes any leading and record bytes inside the given buffer. If no record is found the buffer is consumed completely.
     * <p/>
     * Note: If the returned status is {@link ReadStatus#FoundPartialRecord}, the caller must compact the buffer (copy the remaining
     * bytes to the beginning) and read more data into the buffer before retrying the call.
     *
     * @param delimiter the delimiter used to identify a record.
     * @param source    the source buffer to find a record in.
     * @return the result of the search with may contain the decoded record if one was found.
     */
    public static FindResult findNextRecord(UUID delimiter, ByteBuffer source) {
        final byte hook = RECORD_DELIMITER_PREFIX[0];

        if (source.hasRemaining()) {
            do {
                if (source.get() == hook) {
                    int originalSourcePosition = source.position();
                    source.position(originalSourcePosition - 1); // reverting the consumption of the hook byte.

                    final int recordLength = readRecordHeader(source, delimiter);
                    final ReadStatus readStatus = ReadStatus.decode(recordLength);

                    switch (readStatus) {
                        case ReadOk:
                            final int crc32 = extractCrc32FromRecord(source);
                            final int position = source.position();
                            final FindResult findResult = new FindResult(readStatus,
                                    new NioJournalFileRecord(delimiter, (ByteBuffer) source.duplicate().limit(position + recordLength), crc32));

                            // Advance the position to the next record.
                            source.position(position + recordLength + RECORD_TRAILER_SIZE);

                            return findResult;
                        case FoundPartialRecord:
                            return new FindResult(readStatus, null);
                        case FoundHeaderWithDifferentDelimiter:
                            if (trace) { log.trace("Quickly iterating other log entry."); }
                            break;
                        default:
                            // consuming the byte (that was reset before).
                            source.position(Math.max(source.position(), originalSourcePosition));
                    }
                }
            } while (source.hasRemaining());
        }

        return new FindResult(ReadStatus.NoHeaderInBuffer, null);
    }

    /**
     * Reads the record contained a the current position of the given source.
     *
     * @param delimiter the expected record delimiter.
     * @param source    the source to read from.
     * @return the record, never 'null'. (throw IllegalArgumentException if the source is invalid.)
     */
    public static NioJournalFileRecord readRecord(UUID delimiter, ByteBuffer source) {
        int payloadLength = readRecordHeader(source, delimiter);
        if (ReadStatus.decode(payloadLength) == ReadStatus.ReadOk) {
            int crc32 = extractCrc32FromRecord(source);
            return new NioJournalFileRecord(delimiter, (ByteBuffer) source.duplicate().limit(source.position() + payloadLength), crc32);
        } else
            throw new IllegalArgumentException("The provided source buffer " + bufferToString(source) + " did not contain a valid record.");
    }

    /**
     * Reads all records contained in the given file channel.
     *
     * @param delimiter      the delimiter used to identify records.
     * @param channel        the channel to read from.
     * @param includeInvalid include those records that do not pass CRC checks.
     * @return a new iterable that returns a repeatable iteration over records.
     * @throws IOException in case of the IO operation fails initially.
     */
    public static Iterable<NioJournalFileRecord> readRecords(UUID delimiter, FileChannel channel, boolean includeInvalid) throws IOException {
        return new NioJournalFileIterable(delimiter, channel, includeInvalid);
    }

    /**
     * Returns the number of bytes required to write the given records.
     *
     * @param records the records to use for the calculation.
     * @return the number of bytes required to write the given records.
     */
    public static int calculateRequiredBytes(Collection<NioJournalFileRecord> records) {
        int requiredBytes = 0;
        for (NioJournalFileRecord source : records)
            requiredBytes += source.getRecordSize();
        return requiredBytes;
    }

    /**
     * Disposes all records.
     *
     * @param records the records to dispose.
     */
    public static void disposeAll(Collection<NioJournalFileRecord> records) {
        int idx = 0;
        final ByteBuffer[] buffers = new ByteBuffer[records.size()];
        for (NioJournalFileRecord record : records) {
            buffers[idx++] = record.recordBuffer;
            record.dispose(false);
        }
        NioBufferPool.getInstance().recycleBuffers(Arrays.asList(buffers));
    }

    /**
     * Creates an empty record for the given delimiter.
     *
     * @param delimiter the delimiter to create the record for.
     */
    public NioJournalFileRecord(UUID delimiter) {
        if (delimiter == null)
            throw new IllegalArgumentException("The parameter 'delimiter' cannot be left empty when creating a NioJournalFileRecord.");
        this.delimiter = delimiter;
    }

    /**
     * warning: Constructor for internal use only.
     *
     * @param delimiter    the delimiter to create the record for.
     * @param payload      the payload of this record.
     * @param payloadCrc32 the payload's CRC32 value.
     */
    NioJournalFileRecord(UUID delimiter, ByteBuffer payload, int payloadCrc32) {
        this(delimiter);
        if (payload == null)
            throw new IllegalArgumentException("The parameter 'payload' cannot be left empty when creating a filled NioJournalFileRecord.");

        this.payload = payload.duplicate();
        valid = calculateCrc32() == payloadCrc32;
    }

    /**
     * Dispose all held resources and recycle any contained buffers.
     */
    public void dispose() {
        dispose(true);
    }

    /**
     * Dispose all held resources and recycle any contained buffers.
     *
     * @param recycle specified whether the kept buffer is recycled or not.
     */
    void dispose(final boolean recycle) {
        if (recycle)
            NioBufferPool.getInstance().recycleBuffer(recordBuffer);
        recordBuffer = null;
        payload = null;
    }

    /**
     * Creates an empty payload buffer of the given size and returns it for writing.
     *
     * @param payloadSize the size of the payload to create.
     * @return the created buffer which may be used to store the payload into.
     */
    public ByteBuffer createEmptyPayload(int payloadSize) {
        if (payloadSize < 0)
            throw new IllegalArgumentException("Cannot specify a negative capacity when creating the payload.");

        final int requiredCapacity = payloadSize + RECORD_HEADER_SIZE + RECORD_TRAILER_SIZE;

        if (requiredCapacity > JOURNAL_MAX_RECORD_SIZE) {
            throw new IllegalArgumentException("Exceeding the maximum allowed record size of " +
                    JOURNAL_MAX_RECORD_SIZE + " bytes. Requested a size of " + requiredCapacity);
        }

        recordBuffer = NioBufferPool.getInstance().poll(requiredCapacity);

        writeRecordHeaderFor(payloadSize, delimiter, recordBuffer);
        payload = (ByteBuffer) recordBuffer.slice().limit(payloadSize);
        writeRecordTrailerFor(delimiter, (ByteBuffer) recordBuffer.position(recordBuffer.position() + payloadSize));

        recordBuffer.flip();

        return payload.duplicate();
    }

    /**
     * Writes this record to the given target buffer.
     *
     * @param targetDelimiter the target delimiter used to delimit records.
     * @param target          the target to write to.
     */
    public void writeRecord(UUID targetDelimiter, ByteBuffer target) {
        if (!targetDelimiter.equals(delimiter)) {
            if (log.isDebugEnabled())
                log.debug("Correcting delimiter from " + delimiter + " to " + targetDelimiter + ", the target changed in the meantime.");
            delimiter = targetDelimiter;
            recordBuffer = null;
        }

        if (recordBuffer == null || payload == null) {
            if (payload != null) {
                // Must be assigned to a local var as "createEmptyPayload(..)" re-initialized the field as a sub-region of the record buffer.
                final ByteBuffer pl = payload.duplicate();
                // Creating the record buffer and write the payload into the reserved region.
                createEmptyPayload(pl.remaining()).put(pl);
            } else
                throw new IllegalStateException("The payload was not yet written. Cannot write this record.");
        }

        // Calculate CRC32
        recordBuffer.putInt(RECORD_CRC32_OFFSET, calculateCrc32());

        recordBuffer.mark();
        try {
            target.put(recordBuffer);
        } finally {
            recordBuffer.reset();
        }
    }

    int calculateCrc32() {
        if (!payload.hasArray())
            throw new IllegalArgumentException("The payload contained in this record uses an invalid ByteBuffer format not backed with a heap array.");
        final CRC32 crc = new CRC32();
        crc.update(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining());
        return (int) crc.getValue();
    }

    /**
     * Returns the total size of the serialized record in bytes (including header, trailer and payload).
     *
     * @return the total size of the serialized record in bytes (including header, trailer and payload).
     */
    public int getRecordSize() {
        return recordBuffer != null ? recordBuffer.remaining() : (payload == null ? 0 : payload.remaining()) + RECORD_HEADER_SIZE + RECORD_TRAILER_SIZE;
    }

    /**
     * Returns a readonly, fixed size buffer containing the payload.
     *
     * @return a readonly, fixed size buffer containing the payload.
     */
    public ByteBuffer getPayload() {
        return payload.asReadOnlyBuffer();
    }

    /**
     * Returns the delimiter used to separate log records belonging to the same list.
     *
     * @return the delimiter used to separate log records belonging to the same list.
     */
    public UUID getDelimiter() {
        return delimiter;
    }

    /**
     * Returns true if this record can be considered valid.
     *
     * @return true if this record can be considered valid.
     */
    public boolean isValid() {
        return valid;
    }

    @Override
    public String toString() {
        return "NioJournalFileRecord{" +
                "delimiter=" + delimiter +
                ", valid=" + valid +
                ", payload=" + bufferToString(payload) +
                '}';
    }

    static void writeUUID(UUID source, ByteBuffer target) {
        target.putLong(source.getMostSignificantBits());
        target.putLong(source.getLeastSignificantBits());
    }

    static UUID readUUID(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static void writeRecordHeaderFor(int payloadSize, UUID delimiter, ByteBuffer target) {
        target.put(RECORD_DELIMITER_PREFIX);
        writeUUID(delimiter, target);
        target.putInt(payloadSize); // record length
        target.putInt(0); // reserved for CRC32 (comes later)
        target.put(RECORD_DELIMITER_SUFFIX);
    }

    private static void writeRecordTrailerFor(UUID delimiter, ByteBuffer target) {
        target.put(RECORD_DELIMITER_TRAILER);
        writeUUID(delimiter, target);
    }

    /**
     * Reads and verifies the record header, positions the buffer at the payload position and returns the length
     * of the records payload if the header is valid.
     * <p/>
     * Does not advance the buffers position if no record is found at the current position.
     * Steps over the header of a broken record if the header itself is intact (= does advance if a broken record is found).
     *
     * @param source    the buffer whose current position is at the beginning of the header.
     * @param delimiter the expected delimiter that should be contained in the header.
     * @return the length of the record or a negative integer which may be decoded to a {@link ReadStatus} other than ReadOK.
     */
    private static int readRecordHeader(ByteBuffer source, UUID delimiter) {
        source.mark();
        final boolean willBePartial = source.remaining() < RECORD_HEADER_SIZE;
        try {
            int similarBytes = bufferContainsSequence(source, RECORD_DELIMITER_PREFIX);

            if (willBePartial) {
                if (similarBytes == RECORD_DELIMITER_PREFIX.length || (similarBytes < 0 && !source.hasRemaining())) {
                    if (trace) { log.trace("Read the first bytes of a potential partial header, reporting ReadStatus.FoundPartialRecord."); }
                    return ReadStatus.FoundPartialRecord.encode();
                } else
                    return ReadStatus.NoHeaderAtCurrentPosition.encode();
            } else if (similarBytes != RECORD_DELIMITER_PREFIX.length)
                return ReadStatus.NoHeaderAtCurrentPosition.encode();

            final UUID uuid = readUUID(source);
            final int recordLength = source.getInt();

            if (recordLength > JOURNAL_MAX_RECORD_SIZE || recordLength < 0) {
                log.warn("Found a record with an invalid record length of " + recordLength + " bytes where only " + JOURNAL_MAX_RECORD_SIZE +
                        " is allowed. Will skip this entry " + bufferToString(source) + ".");
                return ReadStatus.NoHeaderAtCurrentPosition.encode();
            }

            // jump over crc32
            source.getInt(); // checksum is not needed here, we'll come back and evaluate it later.

            if (bufferContainsSequence(source, RECORD_DELIMITER_SUFFIX) <= 0)
                return ReadStatus.NoHeaderAtCurrentPosition.encode();

            if (recordLength + RECORD_TRAILER_SIZE > source.remaining()) {
                if (trace) { log.trace("Found partial record, the length " + recordLength + " exceeds the remaining bytes " + source.remaining() + "."); }
                return ReadStatus.FoundPartialRecord.encode();
            }

            // Marking the beginning of the payload.
            source.mark();

            // Advancing the buffer to the position of the record trailer.
            source.position(source.position() + recordLength);

            final boolean recordTrailerIsInvalid = bufferContainsSequence(source, RECORD_DELIMITER_TRAILER) <= 0 || !uuid.equals(readUUID(source));
            if (recordTrailerIsInvalid) {
                if (log.isDebugEnabled()) {
                    log.debug("Found an invalid record trailer for delimiter " + uuid + ". Will skip the entry " + bufferToString(source) + ".");
                }
                return ReadStatus.NoHeaderAtCurrentPosition.encode();
            }

            if (!delimiter.equals(uuid)) {
                final ByteBuffer copyOfSource = (ByteBuffer) source.duplicate().reset();
                copyOfSource.limit(copyOfSource.position() + recordLength);

                if (new NioJournalFileRecord(uuid, copyOfSource, extractCrc32FromRecord(copyOfSource)).isValid()) {
                    if (trace) { log.trace("Found a record header of delimiter " + uuid + ", while expecting " + delimiter + ", skipping it."); }
                    source.mark(); // marking the end of the record.
                    return ReadStatus.FoundHeaderWithDifferentDelimiter.encode();
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Found a record header of delimiter " + uuid + ", while expecting " + delimiter + ", that cannot be skipped as it " +
                                "did not pass the CRC32 check. Will retry reading from this record's payload position: " + bufferToString(source));
                    }
                    return ReadStatus.NoHeaderAtCurrentPosition.encode();
                }
            } else {
                return recordLength;
            }
        } finally {
            source.reset();
        }
    }

    /**
     * Extracts the CRC32 value from a source buffer that was previously position at the payload using the method {@link #findNextRecord(UUID, ByteBuffer)}.
     * <p/>
     * Note: This is a low level method that does not verify any state. The returned value will be incorrect if the method is used on un-positioned buffers.
     *
     * @param sourceAtPayloadPosition the positioned buffer.
     * @return the CRC32 value contained in the record header.
     */
    private static int extractCrc32FromRecord(ByteBuffer sourceAtPayloadPosition) {
        return sourceAtPayloadPosition.getInt(sourceAtPayloadPosition.position() + REVERSE_RECORD_CRC32_OFFSET);
    }

    /**
     * Verifies whether the given sequence is contained inside the buffer and advances the buffer's position by the matched characters.
     *
     * @param source   the buffer to search in.
     * @param sequence the sequence of bytes to compare.
     * @return a positive number of matched bytes if the whole sequence was matched. A negative number of matched bytes if only a subset matched.
     */
    private static int bufferContainsSequence(final ByteBuffer source, final byte[] sequence) {
        final int maxCount = source.remaining();
        int count = 0;

        for (byte b : sequence) {
            if (maxCount == count || source.get() != b) {
                if (maxCount != count)
                    source.position(source.position() - 1); // revert the last consumed byte.
                return -count;
            }
            count++;
        }

        return count;
    }

    /**
     * Carries the result of an attempt to find the next record inside a ByteBuffer.
     */
    public static final class FindResult {

        private final ReadStatus status;
        private final NioJournalFileRecord record;

        private FindResult(ReadStatus status, NioJournalFileRecord record) {
            this.status = status;
            this.record = record;
        }

        public ReadStatus getStatus() {
            return status;
        }

        public NioJournalFileRecord getRecord() {
            return record;
        }

        @Override
        public String toString() {
            return "FindResult{" +
                    "status=" + status +
                    ", record=" + record +
                    '}';
        }
    }

    /**
     * Enumerates the possible states when reading a record starting from an arbitrary position inside the file.
     */
    public static enum ReadStatus {
        /**
         * Header was successfully read, the record is fully contained in the buffer and it is valid.
         */
        ReadOk,
        /**
         * There's no header at the current buffer position.
         */
        NoHeaderAtCurrentPosition,
        /**
         * There's no header in the whole buffer.
         */
        NoHeaderInBuffer,
        /**
         * There's a header but it doesn't belong to the current delimiter.
         */
        FoundHeaderWithDifferentDelimiter,
        /**
         * There's a valid header but the record is not complete.
         */
        FoundPartialRecord,;

        /**
         * Decodes the read status from a integer return value.
         *
         * @param recordLength the return value of methods that provide the recordLength.
         * @return the read status assigned with the integer return value.
         */
        static ReadStatus decode(int recordLength) {
            if (recordLength >= 0)
                return ReadOk;
            return values()[-recordLength];
        }

        /**
         * Encodes the given read status to be returned as integer.
         *
         * @param status the status to encode.
         * @return the given read status to be returned as integer.
         */
        static int encode(ReadStatus status) {
            if (status == ReadOk)
                throw new IllegalArgumentException("Cannot encode ReadOK, the calling method should return the record length instead.");
            return -status.ordinal();
        }

        /**
         * Encodes this read status to be returned as integer.
         *
         * @return this read status to be returned as integer.
         */
        int encode() {
            return encode(this);
        }
    }
}
