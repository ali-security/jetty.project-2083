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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnMessage;
import jakarta.websocket.PongMessage;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.DataUtils;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSEventTracker;

@ClientEndpoint
public class JsrClientEchoTrackingSocket extends WSEventTracker.Basic
{
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> pongQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();

    public JsrClientEchoTrackingSocket()
    {
        super("@ClientEndpoint");
    }

    @OnMessage(maxMessageSize = 50 * 1024 * 1024)
    public String onText(String msg)
    {
        messageQueue.offer(msg);
        return msg;
    }

    @OnMessage(maxMessageSize = 50 * 1024 * 1024)
    public ByteBuffer onBinary(ByteBuffer buffer)
    {
        ByteBuffer copy = DataUtils.copyOf(buffer);
        bufferQueue.offer(copy);
        return buffer;
    }

    @OnMessage
    public void onPong(PongMessage pong)
    {
        ByteBuffer copy = DataUtils.copyOf(pong.getApplicationData());
        pongQueue.offer(copy);
    }
}
