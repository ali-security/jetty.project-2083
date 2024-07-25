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

package org.eclipse.jetty.server.handler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * A Denial of Service Handler.
 * <p>Protect from denial of service attacks by limiting the request rate from remote hosts</p>
 */
public class DosHandler extends ConditionalHandler.ElseNext
{
    private final boolean _useAddress;
    private final boolean _usePort;
    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final int _maxRequestsPerSecond;
    private final int _maxTrackers;
    private final long _samplePeriod;
    private final double _alpha;
    private final int _maxDelayQueueSize;
    private Scheduler _scheduler;

    public DosHandler()
    {
        this(null, true, true, 100, -1, -1, -1.0, -1);
    }

    public DosHandler(int maxRequestsPerSecond)
    {
        this(null, true, true, maxRequestsPerSecond, -1, -1, -1.0, -1);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}/
     * @param useAddress {@code true} if the {@link InetSocketAddress#getAddress()} portion of the {@link ConnectionMetaData#getRemoteSocketAddress()} should be used when tracking remote clients.
     * @param usePort {@code true} if the {@link InetSocketAddress#getPort()} portion of the {@link ConnectionMetaData#getRemoteSocketAddress()} should be used when tracking remote clients.
     * @param maxRequestsPerSecond The maximum number of requests per second to allow
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     * @param samplePeriodMs The period in MS to sample to request rate over, or -1 for the 100ms default.
     * @param alpha The factor for the <a href="https://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average">exponential moving average</a> or -1.0 for the default of 0.2
     * @param maxDelayQueueSize The maximum number of request to hold in a delay queue before rejecting them.  Delaying rejection can slow some DOS attackers.
     */
    public DosHandler(Handler handler, boolean useAddress, boolean usePort, int maxRequestsPerSecond, int maxTrackers, int samplePeriodMs, Double alpha, int maxDelayQueueSize)
    {
        super(handler);
        installBean(_trackers);
        _useAddress = useAddress;
        _usePort = usePort;
        _maxRequestsPerSecond = maxRequestsPerSecond;
        _maxTrackers = maxTrackers <= 0 ? 10_000 : maxTrackers;
        _samplePeriod = TimeUnit.MILLISECONDS.toNanos(samplePeriodMs <= 0 ? 100 : samplePeriodMs);
        _alpha = alpha <= 0.0 ? 0.2 : alpha;
        _maxDelayQueueSize = maxDelayQueueSize <= 0 ? 1_000 : maxDelayQueueSize;

        if (_samplePeriod > TimeUnit.SECONDS.toNanos(1))
            throw new IllegalArgumentException("Sample period must be less than or equal to 1 second");

        if (_alpha > 1.0)
            throw new IllegalArgumentException("Alpha " + _alpha + " is too large");
    }

    public boolean isUseAddress()
    {
        return _useAddress;
    }

    public boolean isUsePort()
    {
        return _usePort;
    }

    @Override
    protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        // Reject if we have too many Trackers
        if (_maxTrackers > 0 && _trackers.size() > _maxTrackers)
        {
            reject(request, response, callback);
            return true;
        }

        // Calculate an id for the request (which may be global empty string)
        String id;
        id = getId(request);

        // Obtain a tracker
        Tracker tracker = _trackers.computeIfAbsent(id, Tracker::new);

        // If we are not over-limit then handle normally
        if (!tracker.isRateExceeded(request, response, callback))
            return nextHandler(request, response, callback);

        // Otherwise the Tracker will reject the request
        return true;
    }

    protected String getId(Request request)
    {
        String id;
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
        {
            if (isUseAddress() && isUsePort())
                return inetSocketAddress.toString();
            if (isUseAddress())
                return inetSocketAddress.getAddress().toString();
            if (isUsePort())
                return Integer.toString(inetSocketAddress.getPort());

            return "";
        }

        return remoteSocketAddress.toString();
    }

    @Override
    protected void doStart() throws Exception
    {
        _scheduler = getServer().getScheduler();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _scheduler = null;
    }

    protected void reject(Request request, Response response, Callback callback)
    {
        Response.writeError(request, response, callback, HttpStatus.ENHANCE_YOUR_CALM_420);
    }

    Tracker testTracker()
    {
        return new Tracker("id");
    }

    /**
     * A RateTracker is associated with a connection, and stores request rate data.
     */
    class Tracker extends CyclicTimeout
    {
        protected record Exchange(Request request, Response response, Callback callback)
        {
        }

        private final AutoLock _lock = new AutoLock();
        private final String _id;
        private double _exponentialMovingAverage;
        private int _sampleCount;
        private long _sampleStart;
        private Queue<Exchange> _delayQueue;

        Tracker(String id)
        {
            super(_scheduler);
            _id = id;
            _sampleStart = System.nanoTime();
        }

        public String getId()
        {
            return _id;
        }

        public int getCurrentRatePerSecond()
        {
            return getCurrentRatePerSecond(System.nanoTime());
        }

        long getSampleStart()
        {
            return _sampleStart;
        }

        int getCurrentRatePerSecond(long now)
        {
            try (AutoLock l = _lock.lock())
            {
                updateRateLocked(now);
                return (int)_exponentialMovingAverage;
            }
        }

        public boolean isRateExceeded()
        {
            try (AutoLock l = _lock.lock())
            {
                updateRateLocked(System.nanoTime());
                return _exponentialMovingAverage > _maxRequestsPerSecond;
            }
        }

        public boolean isRateExceeded(Request request, Response response, Callback callback)
        {
            final long last;

            // Use the request begin time as now. This might not monotonically increase, so algorithm needs
            // to be robust for some jitter.
            long now = request.getBeginNanoTime();
            boolean exceeded;
            try (AutoLock l = _lock.lock())
            {
                exceeded = addSampleAndCheckRateExceededLocked(now);
                if (exceeded)
                {
                    // Add the request to the delay queue
                    if (_delayQueue == null)
                        _delayQueue = new ArrayDeque<>();
                    _delayQueue.add(new Exchange(request, response, callback));

                    // If the delay queue is getting too large, then reject oldest requests
                    while (_delayQueue.size() > _maxDelayQueueSize)
                    {
                        Exchange oldest = _delayQueue.remove();
                        reject(oldest.request, oldest.response, oldest.callback);
                    }
                }
            }

            // Schedule a check on the Tracker to either reject delayed requests, or remove the tracker if idle.
            schedule(2, TimeUnit.SECONDS);

            return exceeded;
        }

        boolean addSampleAndCheckRateExceeded(long now)
        {
            try (AutoLock l = _lock.lock())
            {
                return addSampleAndCheckRateExceededLocked(now);
            }
        }

        private boolean addSampleAndCheckRateExceededLocked(long now)
        {
            assert _lock.isHeldByCurrentThread();

            // Count the request
            _sampleCount++;

            long elapsedTime = now - _sampleStart;

            // We calculate the race if:
            //    + the sample exceeds the rate
            //    + the sample period has been exceeded
            if (_sampleCount > _maxRequestsPerSecond || (_sampleStart != 0 && elapsedTime > _samplePeriod))
                updateRateLocked(now);

            // if the rate has been exceeded?
            return _exponentialMovingAverage > _maxRequestsPerSecond;
        }

        private void updateRateLocked(long now)
        {
            assert _lock.isHeldByCurrentThread();

            if (_sampleStart == 0)
            {
                _sampleStart = now;
                return;
            }

            double elapsedTime = (double)(now - _sampleStart);
            double count = _sampleCount;

            if (elapsedTime > 0.0)
            {
                double currentRate = (count * TimeUnit.SECONDS.toNanos(1L)) / elapsedTime;
                // Adjust alpha based on the ratio of elapsed time to the interval to allow for long and short intervals
                double adjustedAlpha = _alpha * (elapsedTime / _samplePeriod);
                if (adjustedAlpha > 1.0)
                    adjustedAlpha = 1.0; // Ensure adjustedAlpha does not exceed 1.0

                _exponentialMovingAverage = (adjustedAlpha * currentRate + (1.0 - adjustedAlpha) * _exponentialMovingAverage);
            }
            else
            {
                // assume count as the rate for the sample.
                double guessedRate = count * TimeUnit.SECONDS.toNanos(1) / _samplePeriod;
                _exponentialMovingAverage = (_alpha * guessedRate + (1.0 - _alpha) * _exponentialMovingAverage);
            }

            // restart the sample
            _sampleStart = now;
            _sampleCount = 0;
        }

        @Override
        public void onTimeoutExpired()
        {
            try (AutoLock l = _lock.lock())
            {
                if (_delayQueue == null || _delayQueue.isEmpty())
                {
                    // Has the Tracker has gone idle, so remove it
                    if (_sampleCount > 0)
                        updateRateLocked(System.nanoTime());
                    else
                        _trackers.remove(_id);
                }
                else
                {
                    // Reject the delayed over-limit requests
                    for (Exchange exchange : _delayQueue)
                        reject(exchange.request, exchange.response, exchange.callback);
                    _delayQueue.clear();
                }
            }
        }

        @Override
        public String toString()
        {
            try (AutoLock l = _lock.lock())
            {
                return "Tracker@%s{ema=%d/s}".formatted(_id, (long)_exponentialMovingAverage);
            }
        }
    }
}
