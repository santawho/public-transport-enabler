/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.pte.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.pte.exception.RateLimitException;
import okhttp3.HttpUrl;

public abstract class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public static RateLimiter multi(final RateLimiter... limiters) {
        return new MultiIntervalLimiter(limiters);
    }

    public static RateLimiter delaying(final long intervalMillis, final int maxRequestsPerInterval, long bucketMillis) {
        return new SingleIntervalLimiter(intervalMillis, maxRequestsPerInterval, bucketMillis, false);
    }

    public static RateLimiter failing(final long intervalMillis, final int maxRequestsPerInterval, int bucketMillis) {
        return new SingleIntervalLimiter(intervalMillis, maxRequestsPerInterval, bucketMillis, true);
    }

    public void check(final HttpUrl url) throws RateLimitException {
        check(System.currentTimeMillis(), url);
    }

    public abstract void check(final long nowMillis, final HttpUrl url) throws RateLimitException;

    public static class MultiIntervalLimiter extends RateLimiter {
        final RateLimiter[] limiters;

        private MultiIntervalLimiter(final RateLimiter[] limiters) {
            this.limiters = limiters;
        }

        @Override
        public void check(final long nowMillis, final HttpUrl url) throws RateLimitException {
            for (final RateLimiter limiter : limiters) {
                limiter.check(nowMillis, url);
            }
        }
    }

    public static class SingleIntervalLimiter extends RateLimiter {
        private final long bucketIntervalMillis;
        private final int maxRequestsPerInterval;
        private final long bucketMillis;
        private final int numBuckets;
        private final boolean isFailing;

        private final int[] bucketNumRequests;
        private int currentBucketIndex;
        private int numRequestTotal;
        private long currentSemiIntervalEndMillis;

        private SingleIntervalLimiter(
                final long intervalMillis,
                final int maxRequestsPerInterval,
                final long bucketMillis,
                final boolean isFailing) {
            numBuckets = (int) (intervalMillis / bucketMillis);
            this.bucketIntervalMillis = intervalMillis / numBuckets;
            this.maxRequestsPerInterval = maxRequestsPerInterval;
            this.bucketMillis = bucketMillis;
            this.isFailing = isFailing;

            bucketNumRequests = new int[numBuckets];
        }

        @Override
        public synchronized void check(final long nowMillis, final HttpUrl url) throws RateLimitException {
            long leftMillis = currentSemiIntervalEndMillis - nowMillis;
            if (leftMillis <= 0) {
                int numBucketsFinished = (int) (-leftMillis / bucketIntervalMillis + 1);
                if (numBucketsFinished > numBuckets)
                    numBucketsFinished = numBuckets;
                while (--numBucketsFinished >= 0) {
                    if (++currentBucketIndex >= numBuckets)
                        currentBucketIndex = 0;
                    numRequestTotal -= bucketNumRequests[currentBucketIndex];
                    bucketNumRequests[currentBucketIndex] = 0;
                }
                currentSemiIntervalEndMillis = nowMillis + bucketIntervalMillis;
                leftMillis = bucketIntervalMillis;
            }

            bucketNumRequests[currentBucketIndex] += 1;
            numRequestTotal += 1;
            if (numRequestTotal <= maxRequestsPerInterval) {
                log.debug("number of requests {} within last {} millis still below limit of {}",
                        numRequestTotal,
                        bucketIntervalMillis * numBuckets,
                        maxRequestsPerInterval);
                return;
            }

            if (isFailing) {
                log.warn("failing due to request rate limit, more than {} requests per {} millis",
                        maxRequestsPerInterval,
                        bucketIntervalMillis * numBuckets);
                throw new RateLimitException(false, url, null);
            }

            try {
                log.warn("limiting request rate after more than {} requests per {} millis",
                        maxRequestsPerInterval,
                        bucketIntervalMillis * numBuckets);
                Thread.sleep(leftMillis);
            } catch (final InterruptedException ie) {
                throw new RateLimitException(false, url, null);
            }
        }
    }
}
