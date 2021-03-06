/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc.block;

import com.facebook.presto.orc.OrcCorruptionException;
import com.facebook.presto.orc.StreamDescriptor;
import com.facebook.presto.orc.metadata.ColumnEncoding;
import com.facebook.presto.orc.stream.BooleanStream;
import com.facebook.presto.orc.stream.LongStream;
import com.facebook.presto.orc.stream.StreamSources;
import com.facebook.presto.spi.block.BlockBuilder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

import static com.facebook.presto.orc.metadata.Stream.StreamKind.DATA;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.PRESENT;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.SECONDARY;
import static com.facebook.presto.orc.reader.TimestampStreamReader.decodeTimestamp;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;

public class TimestampBlockReader
        implements BlockReader
{
    private final StreamDescriptor streamDescriptor;

    private final long baseTimestampInSeconds;

    @Nullable
    private BooleanStream presentStream;

    @Nullable
    private LongStream secondsStream;

    @Nullable
    private LongStream nanosStream;

    public TimestampBlockReader(StreamDescriptor streamDescriptor, DateTimeZone hiveStorageTimeZone)
    {
        this.streamDescriptor = checkNotNull(streamDescriptor, "stream is null");
        this.baseTimestampInSeconds = new DateTime(2015, 1, 1, 0, 0, checkNotNull(hiveStorageTimeZone, "hiveStorageTimeZone is null")).getMillis() / 1000;
    }

    @Override
    public boolean readNextValueInto(BlockBuilder builder, boolean skipNull)
            throws IOException
    {
        if (presentStream != null && !presentStream.nextBit()) {
            if (!skipNull) {
                builder.appendNull();
                return true;
            }
            return false;
        }

        if (secondsStream == null) {
            throw new OrcCorruptionException("Value is not null but seconds stream is not present");
        }
        if (nanosStream == null) {
            throw new OrcCorruptionException("Value is not null but nanos stream is not present");
        }

        long timestamp = decodeTimestamp(secondsStream.next(), nanosStream.next(), baseTimestampInSeconds);
        TIMESTAMP.writeLong(builder, timestamp);
        return true;
    }

    @Override
    public void skip(int skipSize)
            throws IOException
    {
        // skip nulls
        if (presentStream != null) {
            skipSize = presentStream.countBitsSet(skipSize);
        }

        if (skipSize == 0) {
            return;
        }

        if (secondsStream == null) {
            throw new OrcCorruptionException("Value is not null but seconds stream is not present");
        }
        if (nanosStream == null) {
            throw new OrcCorruptionException("Value is not null but nanos stream is not present");
        }

        // skip non-null values
        secondsStream.skip(skipSize);
        nanosStream.skip(skipSize);
    }

    @Override
    public void openStripe(StreamSources dictionaryStreamSources, List<ColumnEncoding> encoding)
            throws IOException
    {
        presentStream = null;
        secondsStream = null;
        nanosStream = null;
    }

    @Override
    public void openRowGroup(StreamSources dataStreamSources)
            throws IOException
    {
        presentStream = dataStreamSources.getStreamSource(streamDescriptor, PRESENT, BooleanStream.class).openStream();
        secondsStream = dataStreamSources.getStreamSource(streamDescriptor, DATA, LongStream.class).openStream();
        nanosStream = dataStreamSources.getStreamSource(streamDescriptor, SECONDARY, LongStream.class).openStream();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(streamDescriptor)
                .toString();
    }
}
