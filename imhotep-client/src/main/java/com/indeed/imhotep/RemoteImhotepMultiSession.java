/*
 * Copyright (C) 2014 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package com.indeed.imhotep;

import com.google.common.base.Throwables;
import com.google.common.io.Closer;
import com.indeed.imhotep.api.FTGSIterator;
import com.indeed.imhotep.api.HasSessionId;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.api.ImhotepSession;
import com.indeed.imhotep.api.PerformanceStats;
import com.indeed.imhotep.api.RawFTGSIterator;
import com.indeed.imhotep.marshal.ImhotepClientMarshaller;
import com.indeed.imhotep.protobuf.GroupMultiRemapMessage;
import com.indeed.util.core.Pair;
import com.indeed.util.core.io.Closeables2;
import org.apache.log4j.Logger;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jsgroth
 */
public class RemoteImhotepMultiSession extends AbstractImhotepMultiSession<ImhotepSession> implements HasSessionId {
    private static final Logger log = Logger.getLogger(RemoteImhotepMultiSession.class);

    private final String sessionId;
    private final InetSocketAddress[] nodes;

    private final long localTempFileSizeLimit;

    public RemoteImhotepMultiSession(final ImhotepSession[] sessions,
                                     final String sessionId,
                                     final InetSocketAddress[] nodes,
                                     final long localTempFileSizeLimit,
                                     final AtomicLong tempFileSizeBytesLeft) {
        super(sessions, tempFileSizeBytesLeft);

        this.sessionId = sessionId;
        this.nodes = nodes;
        this.localTempFileSizeLimit = localTempFileSizeLimit;
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields) {
        return getFTGSIterator(intFields, stringFields, 0);
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields, final long termLimit) {
        return getFTGSIterator(intFields, stringFields, termLimit, -1);
    }

    @Override
    public FTGSIterator getFTGSIterator(final String[] intFields, final String[] stringFields, final long termLimit, final int sortStat) {
        if (sessions.length == 1) {
            return sessions[0].getFTGSIterator(intFields, stringFields, termLimit, sortStat);
        }
        final RawFTGSIterator[] mergers = getFTGSIteratorSplits(intFields, stringFields, termLimit, sortStat);
        RawFTGSIterator interleaver = new FTGSInterleaver(mergers);
        if(termLimit > 0) {
            if (sortStat >= 0) {
                interleaver = FTGSIteratorUtil.getTopTermsFTGSIterator(interleaver, termLimit, numStats, sortStat);
            } else {
                interleaver = new TermLimitedRawFTGSIterator(interleaver, termLimit);
            }
        }
        return interleaver;
    }

    @Override
    public RawFTGSIterator[] getFTGSIteratorSplits(final String[] intFields, final String[] stringFields, final long termLimit) {
        return getFTGSIteratorSplits(intFields, stringFields, termLimit, -1);
    }

    public RawFTGSIterator[] getFTGSIteratorSplits(final String[] intFields, final String[] stringFields, final long termLimit, final int sortStat) {
        final Pair<Integer, ImhotepSession>[] indexesAndSessions = new Pair[sessions.length];
        for (int i = 0; i < sessions.length; i++) {
            indexesAndSessions[i] = Pair.of(i, sessions[i]);
        }
        final RawFTGSIterator[] mergers = new RawFTGSIterator[sessions.length];
        final Closer closer = Closer.create();
        closer.register(Closeables2.forArray(log, mergers));
        try {
            execute(mergers, indexesAndSessions, new ThrowingFunction<Pair<Integer, ImhotepSession>, RawFTGSIterator>() {
                public RawFTGSIterator apply(final Pair<Integer, ImhotepSession> indexSessionPair) {
                    final ImhotepSession session = indexSessionPair.getSecond();
                    final int index = indexSessionPair.getFirst();
                    return session.mergeFTGSSplit(intFields, stringFields, sessionId, nodes, index, termLimit, sortStat);
                }
            });
        } catch (final Throwable t) {
            Closeables2.closeQuietly(closer, log);
            throw Throwables.propagate(t);
        }
        return mergers;
    }

    @Override
    public FTGSIterator getSubsetFTGSIterator(final Map<String, long[]> intFields, final Map<String, String[]> stringFields) {
        if (sessions.length == 1) {
            return sessions[0].getSubsetFTGSIterator(intFields, stringFields);
        }
        final RawFTGSIterator[] mergers = getSubsetFTGSIteratorSplits(intFields, stringFields);
        return new FTGSInterleaver(mergers);
    }

    @Override
    public RawFTGSIterator[] getSubsetFTGSIteratorSplits(final Map<String, long[]> intFields, final Map<String, String[]> stringFields) {
        final Pair<Integer, ImhotepSession>[] indexesAndSessions = new Pair[sessions.length];
        for (int i = 0; i < sessions.length; i++) {
            indexesAndSessions[i] = Pair.of(i, sessions[i]);
        }
        final RawFTGSIterator[] mergers = new RawFTGSIterator[sessions.length];
        final Closer closer = Closer.create();
        closer.register(Closeables2.forArray(log, mergers));
        try {
            execute(mergers, indexesAndSessions, new ThrowingFunction<Pair<Integer, ImhotepSession>, RawFTGSIterator>() {
                public RawFTGSIterator apply(final Pair<Integer, ImhotepSession> indexSessionPair) {
                    final ImhotepSession session = indexSessionPair.getSecond();
                    final int index = indexSessionPair.getFirst();
                    return session.mergeSubsetFTGSSplit(intFields, stringFields, sessionId, nodes, index);
                }
            });
        } catch (final Throwable t) {
            Closeables2.closeQuietly(closer, log);
            throw Throwables.propagate(t);
        }
        return mergers;
    }

    // Overrides the AbstractImhotepMultiSession implementation to avoid each sub-session constructing a separate copy
    // of the rules protobufs using too much RAM.
    @Override
    public int regroup(final GroupMultiRemapRule[] rawRules, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        final GroupMultiRemapMessage[] groupMultiRemapMessages = new GroupMultiRemapMessage[rawRules.length];
        for(int i = 0; i < rawRules.length; i++) {
            groupMultiRemapMessages[i] = ImhotepClientMarshaller.marshal(rawRules[i]);
        }

        return regroupWithProtos(groupMultiRemapMessages, errorOnCollisions);
    }

    @Override
    public int regroupWithProtos(final GroupMultiRemapMessage[] rawRuleMessages, final boolean errorOnCollisions) throws ImhotepOutOfMemoryException {
        executeMemoryException(integerBuf, new ThrowingFunction<ImhotepSession, Integer>() {
            @Override
            public Integer apply(final ImhotepSession session) throws ImhotepOutOfMemoryException{
                return session.regroupWithProtos(rawRuleMessages, errorOnCollisions);
            }
        });

        numGroups = Collections.max(Arrays.asList(integerBuf));
        return numGroups;
    }

    /**
     * Returns the number of bytes written to the temp files for this session locally.
     * Returns -1 if tempFileSizeBytesLeft was set to null.
     */
    public long getTempFilesBytesWritten() {
        if(tempFileSizeBytesLeft == null || localTempFileSizeLimit <= 0) {
            return -1;
        }
        return localTempFileSizeLimit - tempFileSizeBytesLeft.get();
    }

    @Override
    public void writeFTGSIteratorSplit(final String[] intFields, final String[] stringFields, final int splitIndex, final int numSplits, final long termLimit, final Socket socket) {
        throw new UnsupportedOperationException("");
    }

    @Override
    protected ImhotepRemoteSession createImhotepRemoteSession(final InetSocketAddress address, final String sessionId, final AtomicLong tempFileSizeBytesLeft) {
        throw new UnsupportedOperationException("RemoteImhotepMultiSession doesn't open any remote imhotep connections!");
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    // Combination rules are different for remote sessions vs what is done in AbstractImhotepMultiSession for local sessions
    @Override
    protected PerformanceStats combinePerformanceStats(boolean reset, PerformanceStats[] stats) {
        if(stats == null) {
            return null;
        }
        final PerformanceStats.Builder builder = PerformanceStats.builder();
        for (final PerformanceStats stat : stats) {
            if(stat != null) {
                builder.add(stat);
            }
        }
        return builder.build();
    }
}
