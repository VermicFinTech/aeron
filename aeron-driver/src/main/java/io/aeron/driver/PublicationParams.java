/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.driver.buffer.RawLog;
import io.aeron.ChannelUri;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.LogBufferDescriptor;
import org.agrona.SystemUtil;

import static io.aeron.ChannelUri.INVALID_TAG;
import static io.aeron.CommonContext.*;

class PublicationParams
{
    long lingerTimeoutNs = 0;
    long tag = ChannelUri.INVALID_TAG;
    int termLength = 0;
    int mtuLength = 0;
    int initialTermId = 0;
    int termId = 0;
    int termOffset = 0;
    int sessionId = 0;
    boolean isReplay = false;
    boolean hasSessionId = false;
    boolean hasTag = false;
    boolean isSessionIdTagReference = false;

    static int getTermBufferLength(
        final ChannelUri channelUri,
        final DriverConductor driverConductor,
        final MediaDriver.Context context,
        final boolean isIpc)
    {
        final String termLengthParam = channelUri.get(TERM_LENGTH_PARAM_NAME);
        int termLength = isIpc ? context.ipcTermBufferLength() : context.publicationTermBufferLength();
        if (null != termLengthParam)
        {
            termLength =
                ChannelUri.isTagReference(termLengthParam) ?
                (int)resolveTagReferencedValue(TERM_LENGTH_PARAM_NAME, termLengthParam, driverConductor, isIpc) :
                (int)SystemUtil.parseSize(TERM_LENGTH_PARAM_NAME, termLengthParam);
            LogBufferDescriptor.checkTermLength(termLength);
        }

        return termLength;
    }

    static int getMtuLength(
        final ChannelUri channelUri,
        final DriverConductor driverConductor,
        final MediaDriver.Context context,
        final boolean isIpc)
    {
        int mtuLength = isIpc ? context.ipcMtuLength() : context.mtuLength();
        final String mtuParam = channelUri.get(MTU_LENGTH_PARAM_NAME);
        if (null != mtuParam)
        {
            mtuLength =
                ChannelUri.isTagReference(mtuParam) ?
                (int)resolveTagReferencedValue(MTU_LENGTH_PARAM_NAME, mtuParam, driverConductor, isIpc) :
                (int)SystemUtil.parseSize(MTU_LENGTH_PARAM_NAME, mtuParam);
            Configuration.validateMtuLength(mtuLength);
        }

        return mtuLength;
    }

    static long getLingerTimeoutNs(
        final ChannelUri channelUri,
        final MediaDriver.Context context)
    {
        long lingerTimeoutNs = context.publicationLingerTimeoutNs();
        final String lingerParam = channelUri.get(LINGER_PARAM_NAME);
        if (null != lingerParam)
        {
            lingerTimeoutNs = SystemUtil.parseDuration(LINGER_PARAM_NAME, lingerParam);
            Configuration.validatePublicationLingerTimeoutNs(lingerTimeoutNs, lingerTimeoutNs);
        }

        return lingerTimeoutNs;
    }

    static void validateMtuForMaxMessage(final PublicationParams params, final boolean isExclusive)
    {
        final int termLength = params.termLength;
        final int maxMessageLength = isExclusive ?
            FrameDescriptor.computeExclusiveMaxMessageLength(termLength) :
            FrameDescriptor.computeMaxMessageLength(termLength);

        if (params.mtuLength > maxMessageLength)
        {
            throw new IllegalStateException("MTU greater than max message length for term length: mtu=" +
                params.mtuLength + " maxMessageLength=" + maxMessageLength + " termLength=" + termLength);
        }
    }

    static void confirmMatch(
        final ChannelUri uri, final PublicationParams params, final RawLog rawLog, final int existingSessionId)
    {
        final int mtuLength = LogBufferDescriptor.mtuLength(rawLog.metaData());
        if (uri.containsKey(MTU_LENGTH_PARAM_NAME) && mtuLength != params.mtuLength)
        {
            throw new IllegalStateException("Existing publication has different MTU length: existing=" +
                mtuLength + " requested=" + params.mtuLength);
        }

        if (uri.containsKey(TERM_LENGTH_PARAM_NAME) && rawLog.termLength() != params.termLength)
        {
            throw new IllegalStateException("Existing publication has different term length: existing=" +
                rawLog.termLength() + " requested=" + params.termLength);
        }

        if (uri.containsKey(SESSION_ID_PARAM_NAME) && params.sessionId != existingSessionId)
        {
            throw new IllegalStateException("Existing publication has different session id: existing=" +
                existingSessionId + " requested=" + params.sessionId);
        }
    }

    static void validateTag(final long tag, final DriverConductor driverConductor, final boolean isIpc)
    {
        if (INVALID_TAG == tag)
        {
            throw new IllegalArgumentException("tag of " + INVALID_TAG + " is reserved");
        }

        if (null != driverConductor.findNetworkPublicationByTag(tag) ||
            null != driverConductor.findIpcPublicationByTag(tag))
        {
            throw new IllegalArgumentException("tag of " + tag + " already in use");
        }
    }

    static long resolveTagReferencedValue(
        final String paramName,
        final String paramValue,
        final DriverConductor driverConductor,
        final boolean isIpc)
    {
        final long tag = ChannelUri.tagReferenced(paramValue);
        final NetworkPublication networkPublication =
            isIpc ? null : driverConductor.findNetworkPublicationByTag(tag);
        final IpcPublication ipcPublication =
            isIpc ? driverConductor.findIpcPublicationByTag(tag) : null;

        if (null != networkPublication || null != ipcPublication)
        {
            switch (paramName)
            {
                case TERM_LENGTH_PARAM_NAME:
                    return isIpc ? ipcPublication.termBufferLength() : networkPublication.termBufferLength();

                case MTU_LENGTH_PARAM_NAME:
                    return isIpc ? ipcPublication.mtuLength() : networkPublication.mtuLength();

                case SESSION_ID_PARAM_NAME:
                    return isIpc ? ipcPublication.sessionId() : networkPublication.sessionId();
            }
        }

        throw new IllegalArgumentException(paramName + "=" + paramValue + " must reference a network publication");
    }

    @SuppressWarnings("ConstantConditions")
    static PublicationParams getPublicationParams(
        final MediaDriver.Context context,
        final ChannelUri channelUri,
        final DriverConductor driverConductor,
        final boolean isExclusive,
        final boolean isIpc)
    {
        final PublicationParams params = new PublicationParams();

        params.termLength = getTermBufferLength(channelUri, driverConductor, context, isIpc);
        params.mtuLength = getMtuLength(channelUri, driverConductor, context, isIpc);
        params.lingerTimeoutNs = getLingerTimeoutNs(channelUri, context);

        final String tagStr = channelUri.entityTag();
        if (null != tagStr)
        {
            final long tag = Long.parseLong(tagStr);
            validateTag(tag, driverConductor, isIpc);
            params.tag = tag;
            params.hasTag = true;
        }

        final String sessionIdStr = channelUri.get(SESSION_ID_PARAM_NAME);
        if (null != sessionIdStr)
        {
            params.isSessionIdTagReference = ChannelUri.isTagReference(sessionIdStr);
            params.sessionId =
                params.isSessionIdTagReference ?
                (int)resolveTagReferencedValue(SESSION_ID_PARAM_NAME, sessionIdStr, driverConductor, isIpc) :
                Integer.parseInt(sessionIdStr);
            params.hasSessionId = true;
        }

        if (isExclusive)
        {
            int count = 0;

            final String initialTermIdStr = channelUri.get(INITIAL_TERM_ID_PARAM_NAME);
            count = initialTermIdStr != null ? count + 1 : count;

            final String termIdStr = channelUri.get(TERM_ID_PARAM_NAME);
            count = termIdStr != null ? count + 1 : count;

            final String termOffsetStr = channelUri.get(TERM_OFFSET_PARAM_NAME);
            count = termOffsetStr != null ? count + 1 : count;

            if (count > 0)
            {
                if (count < 3)
                {
                    throw new IllegalArgumentException("Params must be used as a complete set: " +
                        INITIAL_TERM_ID_PARAM_NAME + " " + TERM_ID_PARAM_NAME + " " + TERM_OFFSET_PARAM_NAME);
                }

                params.initialTermId = Integer.parseInt(initialTermIdStr);
                params.termId = Integer.parseInt(termIdStr);
                params.termOffset = Integer.parseInt(termOffsetStr);

                if (params.termOffset > params.termLength)
                {
                    throw new IllegalArgumentException(
                        TERM_OFFSET_PARAM_NAME + "=" + params.termOffset + " > " +
                        TERM_LENGTH_PARAM_NAME + "=" + params.termLength);
                }

                if (params.termOffset < 0)
                {
                    throw new IllegalArgumentException(
                        TERM_OFFSET_PARAM_NAME + "=" + params.termOffset + " must be greater than zero");
                }

                if ((params.termOffset & (FrameDescriptor.FRAME_ALIGNMENT - 1)) != 0)
                {
                    throw new IllegalArgumentException(
                        TERM_OFFSET_PARAM_NAME + "=" + params.termOffset + " must be a multiple of FRAME_ALIGNMENT");
                }

                params.isReplay = true;
            }
        }

        return params;
    }
}
