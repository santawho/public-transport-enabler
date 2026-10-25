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

public class EventCounter {
    public static class Factory {
        private final int numIntervals;
        private final long intervalMillis;

        public Factory(final int numIntervals, final long intervalMillis) {
            this.numIntervals = numIntervals;
            this.intervalMillis = intervalMillis;
        }

        public EventCounter newCounter() {
            return new EventCounter(numIntervals, intervalMillis);
        }
    }

    private final int numIntervals;
    private final long intervalMillis;
    private final int[] intervalCounts;
    private int currentTotalCount;
    private int currentIntervalIndex;
    private long currentIntervalEndMillis;

    public EventCounter(final int numIntervals, final long intervalMillis) {
        this.numIntervals = numIntervals;
        this.intervalMillis = intervalMillis;

        intervalCounts = new int[numIntervals];
    }

    public void addEvent(final long nowMillis) {
        update(nowMillis);
        intervalCounts[currentIntervalIndex] += 1;
        currentTotalCount += 1;
    }

    public void update(final long nowMillis) {
        final long leftMillis = currentIntervalEndMillis - nowMillis;
        if (leftMillis <= 0) {
            int numBucketsFinished = (int) (-leftMillis / intervalMillis + 1);
            if (numBucketsFinished > numIntervals)
                numBucketsFinished = numIntervals;
            while (--numBucketsFinished >= 0) {
                if (++currentIntervalIndex >= numIntervals)
                    currentIntervalIndex = 0;
                currentTotalCount -= intervalCounts[currentIntervalIndex];
                intervalCounts[currentIntervalIndex] = 0;
            }
            currentIntervalEndMillis = nowMillis + intervalMillis;
        }
    }

    public int getCurrentTotalCount() {
        return currentTotalCount;
    }

    public long getTimeSpanMillis() {
        return numIntervals * intervalMillis;
    }
}
