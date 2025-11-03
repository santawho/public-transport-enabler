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

package de.schildbach.pte.dto;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import androidx.annotation.NonNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * @author Andreas Schildbach
 */
public final class Trip implements Serializable {
    private static final long serialVersionUID = 2508466068307110312L;

    public final Date loadedAt;
    public Date updatedAt;
    private String id;
    public final @Nullable TripRef tripRef;
    public final Location from;
    public final Location to;
    public final List<Leg> legs;
    public final List<Fare> fares;
    public final int[] capacity;
    public final Integer numChanges;
    private @Nullable String uniqueId;
    public boolean isDetailsLoaded;
    public TransferDetails[] transferDetails;

    public Trip(
            final Date loadedAt,
            final String id, final TripRef tripRef,
            final Location from, final Location to, final List<Leg> legs,
            final List<Fare> fares, final int[] capacity, final Integer numChanges) {
        this.loadedAt = loadedAt;
        this.updatedAt = loadedAt;
        this.id = id;
        this.tripRef = tripRef;
        this.from = requireNonNull(from);
        this.to = requireNonNull(to);
        this.legs = requireNonNull(legs);
        this.fares = fares;
        this.capacity = capacity;
        this.numChanges = numChanges;

        checkArgument(!legs.isEmpty());
    }

    public PTDate getFirstDepartureTime() {
        return legs.get(0).getDepartureTime();
    }

    public @Nullable Public getFirstPublicLeg() {
        for (final Leg leg : legs)
            if (leg instanceof Public)
                return (Public) leg;

        return null;
    }

    public @Nullable PTDate getFirstPublicLegDepartureTime() {
        final Public firstPublicLeg = getFirstPublicLeg();
        if (firstPublicLeg != null)
            return firstPublicLeg.getDepartureTime();
        else
            return null;
    }

    public PTDate getLastArrivalTime() {
        return legs.get(legs.size() - 1).getArrivalTime();
    }

    public @Nullable Public getLastPublicLeg() {
        for (int i = legs.size() - 1; i >= 0; i--) {
            final Leg leg = legs.get(i);
            if (leg instanceof Public)
                return (Public) leg;
        }

        return null;
    }

    public @Nullable PTDate getLastPublicLegArrivalTime() {
        final Public lastPublicLeg = getLastPublicLeg();
        if (lastPublicLeg != null)
            return lastPublicLeg.getArrivalTime();
        else
            return null;
    }

    /**
     * Duration of whole trip in milliseconds, including leading and trailing individual legs.
     * 
     * @return duration in ms
     */
    public long getDuration() {
        final PTDate first = getFirstDepartureTime();
        final PTDate last = getLastArrivalTime();
        return last.getTime() - first.getTime();
    }

    /**
     * Duration of the public leg part in milliseconds. This includes individual legs between public legs, but
     * excludes individual legs that lead or trail the trip.
     * 
     * @return duration in ms, or null if there are no public legs
     */
    public @Nullable Long getPublicDuration() {
        final PTDate first = getFirstPublicLegDepartureTime();
        final PTDate last = getLastPublicLegArrivalTime();
        if (first != null && last != null)
            return last.getTime() - first.getTime();
        else
            return null;
    }

    /** Minimum time occurring in this trip. */
    public PTDate getMinTime() {
        PTDate minTime = null;

        for (final Leg leg : legs)
            if (minTime == null || leg.getMinTime().before(minTime))
                minTime = leg.getMinTime();

        return minTime;
    }

    /** Maximum time occurring in this trip. */
    public PTDate getMaxTime() {
        PTDate maxTime = null;

        for (final Leg leg : legs)
            if (maxTime == null || leg.getMaxTime().after(maxTime))
                maxTime = leg.getMaxTime();

        return maxTime;
    }

    /**
     * <p>
     * Number of changes on the trip.
     * </p>
     * 
     * <p>
     * Returns {@link #numChanges} if it isn't null. Otherwise, it tries to compute the number by counting
     * public legs of the trip. The number of changes for a Trip consisting of only individual Legs is null.
     * </p>
     *
     * @return number of changes on the trip, or null if no public legs are involved
     */
    @Nullable
    public Integer getNumChanges() {
        if (numChanges == null) {
            Integer numCount = null;

            for (final Leg leg : legs) {
                if (leg instanceof Public) {
                    if (numCount == null) {
                        numCount = 0;
                    } else {
                        numCount++;
                    }
                }
            }
            return numCount;
        } else {
            return numChanges;
        }
    }

    /**
     * Returns true if it looks like the trip can be traveled. Returns false if legs overlap, important
     * departures or arrivals are cancelled and that sort of thing.
     */
    public boolean isTravelable() {
        Date time = null;

        for (final Leg leg : legs) {
            if (leg instanceof Public) {
                final Public publicLeg = (Public) leg;
                if (publicLeg.departureStop.departureCancelled || publicLeg.arrivalStop.arrivalCancelled)
                    return false;
            }

            final Date departureTime = leg.getDepartureTime();
            if (time != null && departureTime.before(time))
                return false;
            time = departureTime;

            final Date arrivalTime = leg.getArrivalTime();
            if (time != null && arrivalTime.before(time))
                return false;
            time = arrivalTime;
        }

        return true;
    }

    /** If an individual leg overlaps, try to adjust so that it doesn't. */
    public void adjustUntravelableIndividualLegs() {
        final int numLegs = legs.size();
        if (numLegs < 1)
            return;

        for (int i = 1; i < numLegs; i++) {
            final Trip.Leg leg = legs.get(i);

            if (leg instanceof Trip.Individual) {
                final Trip.Leg previous = legs.get(i - 1);

                if (leg.getDepartureTime().before(previous.getArrivalTime()))
                    legs.set(i, ((Trip.Individual) leg).movedClone(previous.getArrivalTime()));
            }
        }
    }

    public Set<Product> products() {
        final Set<Product> products = EnumSet.noneOf(Product.class);

        for (final Leg leg : legs)
            if (leg instanceof Public)
                products.add(((Public) leg).line.product);

        return products;
    }

    public String getUniqueId() {
        if (uniqueId == null) {
            final StringBuilder builder = new StringBuilder();
            int n = 0;
            for (final Trip.Leg leg: legs) {
                if (leg instanceof Trip.Public) {
                    Public pubLeg = (Public) leg;
                    final JourneyRef journeyRef = pubLeg.journeyRef;
                    if (n++ > 0) builder.append("/");
                    final String journeyId;
                    if (journeyRef != null) {
                        journeyId = Integer.toString(journeyRef.hashCode());
                    } else {
                        journeyId = pubLeg.line.id + "~" + (pubLeg.departureStop.plannedDepartureTime.getTime() / 60000);
                    }
                    builder.append(journeyId);
                    builder.append("@");
                    builder.append(pubLeg.departureStop.location.id);
                    builder.append(",");
                    builder.append(pubLeg.departureStop.plannedDepartureTime.getTime() / 60000);
                    builder.append(",");
                    builder.append(pubLeg.arrivalStop.location.id);
                    builder.append(",");
                    builder.append(pubLeg.arrivalStop.plannedArrivalTime.getTime() / 60000);
                }
            }
            uniqueId = builder.toString();
        }
        return uniqueId;
    }

    public String getId() {
        if (id == null)
            id = buildSubstituteId();

        return id;
    }

    private String buildSubstituteId() {
        final StringBuilder builder = new StringBuilder();

        for (final Leg leg : legs) {
            builder.append(leg.departure.hasId() ? leg.departure.id : leg.departure.coord).append('-');
            builder.append(leg.arrival.hasId() ? leg.arrival.id : leg.arrival.coord).append('-');

            if (leg instanceof Individual) {
                builder.append("individual");
            } else if (leg instanceof Public) {
                final Public publicLeg = (Public) leg;
                final PTDate plannedDepartureTime = publicLeg.departureStop.plannedDepartureTime;
                if (plannedDepartureTime != null)
                    builder.append(plannedDepartureTime.getTime()).append('-');
                final PTDate plannedArrivalTime = publicLeg.arrivalStop.plannedArrivalTime;
                if (plannedArrivalTime != null)
                    builder.append(plannedArrivalTime.getTime()).append('-');
                final Line line = publicLeg.line;
                builder.append(line.productCode());
                builder.append(line.label);
            }

            builder.append('|');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Trip))
            return false;
        final Trip other = (Trip) o;
        return Objects.equals(this.getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    @NonNull
    @Override
    public String toString() {
        final PTDate firstPublicLegDepartureTime = getFirstPublicLegDepartureTime();
        final PTDate lastPublicLegArrivalTime = getLastPublicLegArrivalTime();
        return getClass().getSimpleName() + "{" +
                getId() + "," +
                (firstPublicLegDepartureTime != null ?
                        "first=" + String.format(Locale.US, "%ta %<tR", firstPublicLegDepartureTime) + "," : "") +
                (lastPublicLegArrivalTime != null ?
                        "last=" + String.format(Locale.US, "%ta %<tR", lastPublicLegArrivalTime) + "," : "") +
                "numChanges=" + numChanges + "}";
    }

    public abstract static class Leg implements Serializable {
        private static final long serialVersionUID = 8498461220084523265L;

        public final Location departure;
        public final Location arrival;
        public transient List<Point> path; // custom serialization, to save space

        public Leg(final Location departure, final Location arrival, final List<Point> path) {
            this.departure = requireNonNull(departure);
            this.arrival = requireNonNull(arrival);
            this.path = path;
        }

        /**
         * Coarse departure time.
         */
        public abstract PTDate getDepartureTime();

        /**
         * Coarse arrival time.
         */
        public abstract PTDate getArrivalTime();

        /**
         * Minimum time occurring in this leg.
         */
        public abstract PTDate getMinTime();

        /**
         * Maximum time occurring in this leg.
         */
        public abstract PTDate getMaxTime();

        private void writeObject(final ObjectOutputStream os) throws IOException {
            os.defaultWriteObject();
            if (path != null) {
                os.writeInt(path.size());
                for (final Point p : path) {
                    os.writeInt(p.getLatAs1E6());
                    os.writeInt(p.getLonAs1E6());
                }
            } else {
                os.writeInt(-1);
            }
        }

        private void readObject(final ObjectInputStream is) throws ClassNotFoundException, IOException {
            is.defaultReadObject();
            try {
                final int pathSize = is.readInt();
                if (pathSize >= 0) {
                    path = new ArrayList<>(pathSize);
                    for (int i = 0; i < pathSize; i++)
                        path.add(Point.from1E6(is.readInt(), is.readInt()));
                } else {
                    path = null;
                }
            } catch (final EOFException x) {
                path = null;
            }
        }
    }

    public final static class Public extends Leg {
        private static final long serialVersionUID = 1312066446239817422L;

        public final Date loadedAt;
        public Date updateDelayedUntil;
        public final Line line;
        public final @Nullable Location destination;
        public final Stop departureStop;
        public final Stop arrivalStop;
        public final @Nullable List<Stop> intermediateStops;
        public final @Nullable String message;
        public final @Nullable JourneyRef journeyRef;
        public @Nullable Location entryLocation;
        public @Nullable Location exitLocation;

        public Public(
                final Line line, final Location destination, final Stop departureStop, final Stop arrivalStop,
                final List<Stop> intermediateStops, final List<Point> path, final String message,
                final JourneyRef journeyRef, final Date loadedAt) {
            super(departureStop.location, arrivalStop.location, path);

            this.loadedAt = loadedAt;
            this.line = requireNonNull(line);
            this.destination = destination;
            this.departureStop = requireNonNull(departureStop);
            this.arrivalStop = requireNonNull(arrivalStop);
            this.intermediateStops = intermediateStops;
            this.message = message;
            this.journeyRef = journeyRef;

            requireNonNull(departureStop.getDepartureTime());
            requireNonNull(arrivalStop.getArrivalTime());
        }

        public Public(
                final Line line, final Location destination, final Stop departureStop, final Stop arrivalStop,
                final List<Stop> intermediateStops, final List<Point> path, final String message, final JourneyRef journeyRef) {
            this(line, destination, departureStop, arrivalStop, intermediateStops, path, message, journeyRef, new Date());
        }

        public Public(
                final Line line, final Location destination, final Stop departureStop, final Stop arrivalStop,
                final List<Stop> intermediateStops, final List<Point> path, final String message) {
            this(line, destination, departureStop, arrivalStop, intermediateStops, path, message, null);
        }

        public void setEntryAndExit(final Location entryLocation, final Location exitLocation) {
            // some providers (like some Hafas) return different location IDs in the original trip leg and the journey
            // try to find by ID or fallback to name comparison
            if (entryLocation != null) {
                Location realEntry = findRealStopLocation(entryLocation, departureStop.location);
                if (exitLocation == null && realEntry == null)
                    realEntry = findRealStopLocation(entryLocation, arrivalStop.location);
                this.entryLocation = (realEntry != null) ? realEntry : entryLocation;
            }
            if (exitLocation != null) {
                Location realExit = findRealStopLocation(exitLocation, arrivalStop.location);
                this.exitLocation = (realExit != null) ? realExit : exitLocation;
            }
        }

        public Stop findStopByLocation(final Location location) {
            String locId = location.id;
            if (locId.equals(departure.id))
                return departureStop;

            if (locId.equals(arrival.id))
                return arrivalStop;

            for (final Stop stop: intermediateStops) {
                if (locId.equals(stop.location.id))
                    return stop;
            }

            return null;
        }

        public boolean isStopAfterOther(final Stop stop, final Location other) {
            String stopId = stop.location.id;
            String otherId = other.id;
            if (stopId.equals(otherId))
                return false;

            if (stopId.equals(departure.id))
                return false;

            if (stopId.equals(arrival.id))
                return true;

            boolean otherFound = false;
            boolean stopFound = false;
            for (final Stop iStop: intermediateStops) {
                String locId = iStop.location.id;
                if (locId.equals(otherId)) {
                    if (stopFound) return false;
                    otherFound = true;
                }
                if (locId.equals(stopId)) {
                    if (otherFound) return true;
                    stopFound = true;
                }
            }

            return false;
        }

        private Location findRealStopLocation(final Location location, final Location additionalLocation) {
            final String locId = location.id;
            if (locId == null) {
                return null;
            }
            if (locId.equals(additionalLocation.id))
                return additionalLocation;
            if (intermediateStops != null) {
                for (final Stop iStop: intermediateStops) {
                    Location loc = iStop.location;
                    if (locId.equals(loc.id))
                        return loc;
                }
            }
            final String locPlace = location.place;
            final String locName = location.name;
            if (java.util.Objects.equals(locName, additionalLocation.name)
                    && java.util.Objects.equals(locPlace, additionalLocation.place))
                return additionalLocation;
            for (final Stop iStop: intermediateStops) {
                Location iLoc = iStop.location;
                if (java.util.Objects.equals(locName, iLoc.name) && java.util.Objects.equals(locPlace, iLoc.place))
                    return iLoc;
            }
            return null;
        }

        @Override
        public PTDate getDepartureTime() {
            return departureStop.getDepartureTime(false);
        }

        public PTDate getDepartureTime(final boolean preferPlanTime) {
            return departureStop.getDepartureTime(preferPlanTime);
        }

        public boolean isDepartureTimePredicted() {
            return departureStop.isDepartureTimePredicted(false);
        }

        public Long getDepartureDelay() {
            return departureStop.getDepartureDelay();
        }

        public Position getDeparturePosition() {
            return departureStop.getDeparturePosition();
        }

        public boolean isDeparturePositionPredicted() {
            return departureStop.isDeparturePositionPredicted();
        }

        @Override
        public PTDate getArrivalTime() {
            return arrivalStop.getArrivalTime(false);
        }

        public PTDate getArrivalTime(final boolean preferPlanTime) {
            return arrivalStop.getArrivalTime(preferPlanTime);
        }

        public boolean isArrivalTimePredicted() {
            return arrivalStop.isArrivalTimePredicted(false);
        }

        public Long getArrivalDelay() {
            return arrivalStop.getArrivalDelay();
        }

        public Position getArrivalPosition() {
            return arrivalStop.getArrivalPosition();
        }

        public boolean isArrivalPositionPredicted() {
            return arrivalStop.isArrivalPositionPredicted();
        }

        @Override
        public PTDate getMinTime() {
            return departureStop.getMinTime();
        }

        @Override
        public PTDate getMaxTime() {
            return arrivalStop.getMaxTime();
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{" +
                    "line=" + line + "," +
                    (destination != null ? "destination=" + destination + "," : "") +
                    "departureStop=" + departureStop + "," +
                    "arrivalStop=" + arrivalStop + "}";
        }
    }

    public final static class Individual extends Leg {
        public enum Type {
            WALK, BIKE, CAR, TRANSFER, CHECK_IN, CHECK_OUT
        }

        private static final long serialVersionUID = -6651381862837233925L;

        public final Type type;
        public final PTDate departureTime;
        public final PTDate arrivalTime;
        public final int min;
        public final int distance;

        public Individual(final Type type, final Location departure, final PTDate departureTime, final Location arrival,
                          final PTDate arrivalTime, final List<Point> path, final int distance) {
            super(departure, arrival, path);

            this.type = requireNonNull(type);
            this.departureTime = requireNonNull(departureTime);
            this.arrivalTime = requireNonNull(arrivalTime);
            this.min = (int) ((arrivalTime.getTime() - departureTime.getTime()) / 1000 / 60);
            this.distance = distance;
        }

        public Individual movedClone(final PTDate departureTime) {
            final PTDate arrivalTime = new PTDate(
                    new Date(departureTime.getTime() + this.arrivalTime.getTime() - this.departureTime.getTime()),
                    departureTime.getOffset());
            return new Trip.Individual(this.type, this.departure, departureTime, this.arrival, arrivalTime, this.path,
                    this.distance);
        }

        @Override
        public PTDate getDepartureTime() {
            return departureTime;
        }

        @Override
        public PTDate getArrivalTime() {
            return arrivalTime;
        }

        @Override
        public PTDate getMinTime() {
            return departureTime;
        }

        @Override
        public PTDate getMaxTime() {
            return arrivalTime;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{" +
                    type + "," +
                    "departure=" + departure + "," +
                    "arrival=" + arrival + "," +
                    "min=" + min + "," +
                    "distance=" + distance + "}";
        }
    }
}
