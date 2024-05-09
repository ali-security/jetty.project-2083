//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.junit.jupiter.api.Test;

public class GeneratorParserRoundTripTest
{
    private final ByteBufferPool bufferPool = new ArrayByteBufferPool();

    @Test
    public void testParserAndGenerator() throws Exception
    {
        Generator gen = new Generator();
        ParserCapture capture = new ParserCapture();

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        RetainableByteBuffer out = bufferPool.acquire(8192, false);
        try
        {
            // Generate Buffer
            ByteBuffer byteBuffer = out.getByteBuffer();
            Frame frame = new Frame(OpCode.TEXT).setPayload(message);
            gen.generateHeader(frame, byteBuffer);
            gen.generatePayload(frame, byteBuffer);

            // Parse Buffer
            capture.parse(byteBuffer);
        }
        finally
        {
            out.release();
        }

        // Validate
        Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Text parsed", txt.getPayloadAsUTF8(), is(message));
    }

    @Test
    public void testParserAndGeneratorMasked() throws Exception
    {
        Generator gen = new Generator();
        ParserCapture capture = new ParserCapture(true, Behavior.SERVER);

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        RetainableByteBuffer out = bufferPool.acquire(8192, false);
        try
        {
            ByteBuffer byteBuffer = out.getByteBuffer();

            // Setup Frame
            Frame frame = new Frame(OpCode.TEXT).setPayload(message);

            // Add masking
            byte[] mask = new byte[4];
            Arrays.fill(mask, (byte)0xFF);
            frame.setMask(mask);

            // Generate Buffer
            gen.generateHeader(frame, byteBuffer);
            gen.generatePayload(frame, byteBuffer);

            // Parse Buffer
            capture.parse(byteBuffer);
        }
        finally
        {
            out.release();
        }

        // Validate
        Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertTrue(txt.isMasked(), "Text.isMasked");
        assertThat("Text parsed", txt.getPayloadAsUTF8(), is(message));
    }
}
