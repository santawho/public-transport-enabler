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

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.Serializable;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;

/**
 * @author Andreas Schildbach
 */
public final class Stop implements Serializable {
    private static final long serialVersionUID = 5034616799626145715L;

    public final Location location;
    public final @Nullable PTDate plannedArrivalTime;
    public final @Nullable PTDate predictedArrivalTime;
    public final @Nullable Position plannedArrivalPosition;
    public final @Nullable Position predictedArrivalPosition;
    public final boolean arrivalCancelled;
    public final @Nullable PTDate plannedDepartureTime;
    public final @Nullable PTDate predictedDepartureTime;
    public final @Nullable Position plannedDeparturePosition;
    public final @Nullable Position predictedDeparturePosition;
    public final boolean departureCancelled;

    public Stop(final Location location, final PTDate plannedArrivalTime, final PTDate predictedArrivalTime,
                final Position plannedArrivalPosition, final Position predictedArrivalPosition,
                final PTDate plannedDepartureTime, final PTDate predictedDepartureTime, final Position plannedDeparturePosition,
                final Position predictedDeparturePosition) {
        this(location, plannedArrivalTime, predictedArrivalTime, plannedArrivalPosition, predictedArrivalPosition,
                false, plannedDepartureTime, predictedDepartureTime, plannedDeparturePosition,
                predictedDeparturePosition, false);
    }

    public Stop(final Location location, final PTDate plannedArrivalTime, final PTDate predictedArrivalTime,
                final Position plannedArrivalPosition, final Position predictedArrivalPosition,
                final boolean arrivalCancelled, final PTDate plannedDepartureTime, final PTDate predictedDepartureTime,
                final Position plannedDeparturePosition, final Position predictedDeparturePosition,
                final boolean departureCancelled) {
        this.location = checkNotNull(location);
        this.plannedArrivalTime = plannedArrivalTime;
        this.predictedArrivalTime = predictedArrivalTime;
        this.plannedArrivalPosition = plannedArrivalPosition;
        this.predictedArrivalPosition = predictedArrivalPosition;
        this.arrivalCancelled = arrivalCancelled;
        this.plannedDepartureTime = plannedDepartureTime;
        this.predictedDepartureTime = predictedDepartureTime;
        this.plannedDeparturePosition = plannedDeparturePosition;
        this.predictedDeparturePosition = predictedDeparturePosition;
        this.departureCancelled = departureCancelled;
    }

    public Stop(final Location location, final boolean departure, final PTDate plannedTime, final PTDate predictedTime,
                final Position plannedPosition, final Position predictedPosition) {
        this(location, departure, plannedTime, predictedTime, plannedPosition, predictedPosition, false);
    }

    public Stop(final Location location, final boolean departure, final PTDate plannedTime, final PTDate predictedTime,
                final Position plannedPosition, final Position predictedPosition, final boolean cancelled) {
        this.location = checkNotNull(location);
        this.plannedArrivalTime = !departure ? plannedTime : null;
        this.predictedArrivalTime = !departure ? predictedTime : null;
        this.plannedArrivalPosition = !departure ? plannedPosition : null;
        this.predictedArrivalPosition = !departure ? predictedPosition : null;
        this.arrivalCancelled = !departure ? cancelled : false;
        this.plannedDepartureTime = departure ? plannedTime : null;
        this.predictedDepartureTime = departure ? predictedTime : null;
        this.plannedDeparturePosition = departure ? plannedPosition : null;
        this.predictedDeparturePosition = departure ? predictedPosition : null;
        this.departureCancelled = departure ? cancelled : false;
    }

    public Stop(final Location location, final PTDate plannedArrivalTime, final Position plannedArrivalPosition,
                final PTDate plannedDepartureTime, final Position plannedDeparturePosition) {
        this.location = checkNotNull(location);
        this.plannedArrivalTime = plannedArrivalTime;
        this.predictedArrivalTime = null;
        this.plannedArrivalPosition = plannedArrivalPosition;
        this.predictedArrivalPosition = null;
        this.arrivalCancelled = false;
        this.plannedDepartureTime = plannedDepartureTime;
        this.predictedDepartureTime = null;
        this.plannedDeparturePosition = plannedDeparturePosition;
        this.predictedDeparturePosition = null;
        this.departureCancelled = false;
    }

    public PTDate getArrivalTime() {
        return getArrivalTime(false);
    }

    public PTDate getArrivalTime(final boolean preferPlanTime) {
        if (preferPlanTime && plannedArrivalTime != null)
            return plannedArrivalTime;
        else if (predictedArrivalTime != null)
            return predictedArrivalTime;
        else if (plannedArrivalTime != null)
            return plannedArrivalTime;
        else
            return null;
    }

    public boolean isArrivalTimePredicted() {
        return isArrivalTimePredicted(false);
    }

    public boolean isArrivalTimePredicted(final boolean preferPlanTime) {
        if (preferPlanTime && plannedArrivalTime != null)
            return false;
        else
            return predictedArrivalTime != null;
    }

    public Long getArrivalDelay() {
        final PTDate plannedArrivalTime = this.plannedArrivalTime;
        final PTDate predictedArrivalTime = this.predictedArrivalTime;
        if (plannedArrivalTime != null && predictedArrivalTime != null)
            return predictedArrivalTime.getTime() - plannedArrivalTime.getTime();
        else
            return null;
    }

    public Position getArrivalPosition() {
        if (predictedArrivalPosition != null)
            return predictedArrivalPosition;
        else if (plannedArrivalPosition != null)
            return plannedArrivalPosition;
        else
            return null;
    }

    public boolean isArrivalPositionPredicted() {
        return predictedArrivalPosition != null;
    }

    public PTDate getDepartureTime() {
        return getDepartureTime(false);
    }

    public PTDate getDepartureTime(final boolean preferPlanTime) {
        if (preferPlanTime && plannedDepartureTime != null)
            return plannedDepartureTime;
        else if (predictedDepartureTime != null)
            return predictedDepartureTime;
        else if (plannedDepartureTime != null)
            return plannedDepartureTime;
        else
            return null;
    }

    public boolean isDepartureTimePredicted() {
        return isDepartureTimePredicted(false);
    }

    public boolean isDepartureTimePredicted(final boolean preferPlanTime) {
        if (preferPlanTime && plannedDepartureTime != null)
            return false;
        else
            return predictedDepartureTime != null;
    }

    public Long getDepartureDelay() {
        final PTDate plannedDepartureTime = this.plannedDepartureTime;
        final PTDate predictedDepartureTime = this.predictedDepartureTime;
        if (plannedDepartureTime != null && predictedDepartureTime != null)
            return predictedDepartureTime.getTime() - plannedDepartureTime.getTime();
        else
            return null;
    }

    public Position getDeparturePosition() {
        if (predictedDeparturePosition != null)
            return predictedDeparturePosition;
        else if (plannedDeparturePosition != null)
            return plannedDeparturePosition;
        else
            return null;
    }

    public boolean isDeparturePositionPredicted() {
        return predictedDeparturePosition != null;
    }

    public PTDate getMinTime() {
        if (plannedDepartureTime == null)
            return predictedDepartureTime;

        if (predictedDepartureTime == null)
            return plannedDepartureTime;

        return predictedDepartureTime.before(plannedDepartureTime)
                ? predictedDepartureTime
                : plannedDepartureTime;
    }

    public PTDate getMaxTime() {
        if (plannedArrivalTime == null)
            return predictedArrivalTime;

        if (predictedArrivalTime == null)
            return plannedArrivalTime;

        return predictedArrivalTime.after(plannedArrivalTime)
                ? predictedArrivalTime
                : plannedArrivalTime;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Stop))
            return false;
        final Stop other = (Stop) o;
        if (!Objects.equal(this.location, other.location))
            return false;
        if (!Objects.equal(this.plannedArrivalTime, other.plannedArrivalTime))
            return false;
        if (!Objects.equal(this.predictedArrivalTime, other.predictedArrivalTime))
            return false;
        if (!Objects.equal(this.plannedArrivalPosition, other.plannedArrivalPosition))
            return false;
        if (!Objects.equal(this.predictedArrivalPosition, other.predictedArrivalPosition))
            return false;
        if (this.arrivalCancelled != other.arrivalCancelled)
            return false;
        if (!Objects.equal(this.plannedDepartureTime, other.plannedDepartureTime))
            return false;
        if (!Objects.equal(this.predictedDepartureTime, other.predictedDepartureTime))
            return false;
        if (!Objects.equal(this.plannedDeparturePosition, other.plannedDeparturePosition))
            return false;
        if (!Objects.equal(this.predictedDeparturePosition, other.predictedDeparturePosition))
            return false;
        if (this.departureCancelled != other.departureCancelled)
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(location, plannedArrivalTime, predictedArrivalTime, plannedArrivalPosition,
                predictedArrivalPosition, arrivalCancelled, plannedDepartureTime, predictedDepartureTime,
                plannedDeparturePosition, predictedDeparturePosition, departureCancelled);
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this).addValue(location);
        if (plannedArrivalTime != null)
            helper.add("plannedArrivalTime", String.format(Locale.US, "%ta %<tR", plannedArrivalTime));
        if (arrivalCancelled)
            helper.addValue("cancelled");
        else if (predictedArrivalTime != null)
            helper.add("predictedArrivalTime", String.format(Locale.US, "%ta %<tR", predictedArrivalTime));
        if (plannedDepartureTime != null)
            helper.add("plannedDepartureTime", String.format(Locale.US, "%ta %<tR", plannedDepartureTime));
        if (departureCancelled)
            helper.addValue("cancelled");
        else if (predictedDepartureTime != null)
            helper.add("predictedDepartureTime", String.format(Locale.US, "%ta %<tR", predictedDepartureTime));
        return helper.toString();
    }
}
