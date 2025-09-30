/*
 * Copyright 2010-2015 the original author or authors.
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

package de.schildbach.pte.dto;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import javax.annotation.Nonnull;

public class Timestamp
        implements Serializable, Cloneable, Comparable<Timestamp> {
    private static final long serialVersionUID = 5427597351571580029L;

    public static int UNKNOWN_LOCATION_SPECIFIC_OFFSET = Integer.MIN_VALUE + 1;
    public static int SYSTEM_OFFSET = Integer.MIN_VALUE + 2;
    public static int NETWORK_OFFSET = Integer.MIN_VALUE + 3;

    public static Timestamp fromDateAndTimezone(final Date date, TimeZone timeZone) {
        if (date == null)
            return null;

        return new Timestamp(date, timeZone.getOffset(date.getTime()));
    }

    public static Timestamp fromDateAndOffset(final Date date, int offset) {
        if (date == null)
            return null;

        return new Timestamp(date, offset);
    }

    public static Timestamp fromDateAndUnknownLocationSpecificOffset(final Date date) {
        return fromDateAndOffset(date, UNKNOWN_LOCATION_SPECIFIC_OFFSET);
    }

    public static Timestamp fromDateAndSystemOffset(final Date date) {
        return fromDateAndOffset(date, SYSTEM_OFFSET);
    }

    public static Timestamp fromDateAndNetworkOffset(final Date date) {
        return fromDateAndOffset(date, NETWORK_OFFSET);
    }

    public static Timestamp fromCalendar(final Calendar calendar) {
        final Date date = calendar.getTime();
        final int offset = calendar.getTimeZone().getOffset(date.getTime());
        return fromDateAndOffset(date, offset);
    }

    private Date date;
    private int offset;

    private Timestamp(@Nonnull final Date date, int offset) {
        this.date = date;
        this.offset = offset;
    }

    public Date getDate() {
        return date;
    }

    public long getTime() {
        return date.getTime();
    }

    public boolean isOffsetUnknownLocationSpecific() {
        return offset == UNKNOWN_LOCATION_SPECIFIC_OFFSET;
    }

    public static boolean isOffsetUnknownLocationSpecific(final int offset) {
        return offset == UNKNOWN_LOCATION_SPECIFIC_OFFSET;
    }

    public boolean isOffsetSystem() {
        return offset == SYSTEM_OFFSET;
    }

    public static boolean isOffsetSystem(final int offset) {
        return offset == SYSTEM_OFFSET;
    }

    public boolean isOffsetNetwork() {
        return offset == NETWORK_OFFSET;
    }

    public static boolean isOffsetNetwork(final int offset) {
        return offset == NETWORK_OFFSET;
    }

    public int getOffset() {
        return offset;
    }

    @Override
    public int compareTo(final Timestamp other) {
        return date.compareTo(other.date);
    }

    public static Date toDate(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.date;
    }
}
