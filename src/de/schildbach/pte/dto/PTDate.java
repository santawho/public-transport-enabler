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

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import javax.annotation.Nonnull;

public class PTDate extends Date {
    private static final long serialVersionUID = 5427597351571580029L;

    public static int UNKNOWN_LOCATION_SPECIFIC_OFFSET = Integer.MIN_VALUE + 1;
    public static int SYSTEM_OFFSET = Integer.MIN_VALUE + 2;
    public static int NETWORK_OFFSET = Integer.MIN_VALUE + 3;

    public static PTDate withUnknownLocationSpecificOffset(final long time) {
        return new PTDate(time, UNKNOWN_LOCATION_SPECIFIC_OFFSET);
    }

    public static PTDate withSystemOffset(final long time) {
        return new PTDate(time, SYSTEM_OFFSET);
    }

    public static PTDate withNetworkOffset(final long time) {
        return new PTDate(time, NETWORK_OFFSET);
    }

    public static PTDate fromCalendar(final Calendar calendar) {
        if (calendar == null)
            return null;
        final Date date = calendar.getTime();
        final int offset = calendar.getTimeZone().getOffset(date.getTime());
        return new PTDate(date, offset);
    }

    private final int offset;

    public PTDate(@Nonnull final Date date, int offset) {
        this(date.getTime(), offset);
    }

    public PTDate(final long time, int offset) {
        super(time);
        this.offset = offset;
    }

    public PTDate(final Date date, final TimeZone timeZone) {
        this(date.getTime(), timeZone);
    }

    public PTDate(final long time, final TimeZone timeZone) {
        this(time, timeZone.getOffset(time));
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
    public int compareTo(final Date other) {
        return super.compareTo(other);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "super=" + super.toString() + ", " +
                "offset=" + offset +
                '}';
    }
}
