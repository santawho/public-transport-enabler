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

package de.schildbach.pte.provider.db;

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import de.schildbach.pte.Standard;
import de.schildbach.pte.util.MessagePackUtils;

/**
 * Provider implementation for movas API of Deutsche Bahn (Germany).
 * 
 * @author Andreas Schildbach
 */
public abstract class DbProvider extends AbstractNetworkProvider {
    public static final class Default extends DbWebProvider.Fernverkehr {
        public Default() {
            super(NetworkId.DB);
        }
    }

    public static final class Fernverkehr extends DbWebProvider.Fernverkehr {
        public Fernverkehr() {
            super(NetworkId.DB);
        }
    }

    public static final class Regio extends DbWebProvider.Regio {
        public Regio() {
            super(NetworkId.DBREGIO);
        }
    }

    public static final class International extends DbMovasProvider.Fernverkehr {
        public International() {
            super(NetworkId.DBINTERNATIONAL);
        }
    }

    public static final class DeutschlandTicket extends DbWebProvider.DeutschlandTicket {
        public DeutschlandTicket() {
            super(NetworkId.DBDEUTSCHLANDTICKET);
        }
    }

    public static final Set<Product> FERNVERKEHR_PRODUCTS = Product.ALL_INCLUDING_HIGHSPEED;

    public static final Set<Product> REGIO_PRODUCTS = Product.ALL_EXCEPT_HIGHSPEED;

    public DbProvider(final NetworkId networkId) {
        super(networkId);
    }

    public static final String OPERATOR_DB_FERNVERKEHR = "DB Fernverkehr AG";
    public static final int COLOR_BACKGROUND_NON_DB_HIGH_SPEED_TRAIN = Style.parseColor("#e8d1be");
    public static final Style STYLE_NON_DB_HIGH_SPEED_TRAIN = new Style(Style.Shape.RECT, COLOR_BACKGROUND_NON_DB_HIGH_SPEED_TRAIN, Style.RED, Style.RED);

    public static Style lineStyle(
            final @Nullable Map<String, Style> styles,
            @Nullable final String network,
            @Nullable final Product product,
            @Nullable final String label) {
        Style styleFromNetwork = null;
        if (product != null && product.equals(Product.HIGH_SPEED_TRAIN)) {
            if (network != null) {
                if (!OPERATOR_DB_FERNVERKEHR.equals(network))
                    styleFromNetwork = STYLE_NON_DB_HIGH_SPEED_TRAIN;
            } else {
                if (label == null || !(label.startsWith("ICE ") || label.startsWith("IC ")))
                    styleFromNetwork = STYLE_NON_DB_HIGH_SPEED_TRAIN;
            }
        }
        return Standard.resolveLineStyle(styles, network, product, label, styleFromNetwork);
    }

    public static Description getDbDescription() {
        return new Description.Base() {
            @Override
            public String getName() {
                return "Deutsche Bahn AG";
            }

            @Override
            public String getDescriptionText() {
                return "Federal German railways operator";
            }

            @Override
            public String getUrl() {
                return "https://bahn.de";
            }
        };
    }

    public static class DbTripRef extends TripRef
            implements BahnvorhersageProvider.BahnvorhersageTripRef {
        private static final long serialVersionUID = -1951536102104578242L;

        public final String ctxRecon;
        public final boolean limitToDticket;
        public final boolean hasDticket;

        public DbTripRef(
                final NetworkId network, final String ctxRecon,
                final Location from, final Location via, final Location to,
                final boolean limitToDticket, final boolean hasDticket) {
            super(network, from, via, to);
            this.ctxRecon = ctxRecon;
            this.limitToDticket = limitToDticket;
            this.hasDticket = hasDticket;
        }

        public DbTripRef(final DbTripRef simplifiedTripRef, final String ctxRecon) {
            super(simplifiedTripRef);
            this.ctxRecon = ctxRecon;
            this.limitToDticket = simplifiedTripRef.limitToDticket;
            this.hasDticket = simplifiedTripRef.hasDticket;
        }

        public DbTripRef(final NetworkId network, final MessageUnpacker unpacker) throws IOException {
            super(network, unpacker);
            this.ctxRecon = MessagePackUtils.unpackNullableString(unpacker);
            this.limitToDticket = unpacker.unpackBoolean();
            this.hasDticket = unpacker.unpackBoolean();
        }

        @Override
        public String getBahnvorhersageRefreshToken() {
            return ctxRecon;
        }

        @Override
        public void packToMessage(final MessagePacker packer) throws IOException {
            super.packToMessage(packer);
            MessagePackUtils.packNullableString(packer, ctxRecon);
            packer.packBoolean(limitToDticket);
            packer.packBoolean(hasDticket);
        }

        public DbTripRef getSimplified() {
            return new DbTripRef(network, null, from, via, to, limitToDticket, hasDticket);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DbTripRef)) return false;
            DbTripRef that = (DbTripRef) o;
            return super.equals(that)
                    && Objects.equals(ctxRecon, that.ctxRecon)
                    && limitToDticket == that.limitToDticket
                    && hasDticket == that.limitToDticket;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), ctxRecon, limitToDticket, hasDticket);
        }
    }

    public static class DbJourneyRef extends JourneyRef
            implements BahnvorhersageProvider.BahnvorhersageJourneyRef {
        private static final long serialVersionUID = 7738174208212249291L;

        public final String journeyId;
        public final String journeyRequestId;
        public final Line line;

        public DbJourneyRef(final String journeyId, final String journeyRequestId, final Line line) {
            this.journeyId = journeyId;
            this.journeyRequestId = journeyRequestId;
            this.line = line;
        }

        @Override
        public String getBahnvorhersageRefreshJourneyId() {
            return journeyRequestId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DbJourneyRef)) return false;
            DbJourneyRef that = (DbJourneyRef) o;
            return Objects.equals(journeyId, that.journeyId)
                    && Objects.equals(line, that.line);
        }

        @Override
        public int hashCode() {
            return Objects.hash(journeyId, line);
        }
    }

    @Override
    public TripRef createTripRefFromPreviousTripWithNewLegs(final Trip trip, final List<Trip.Leg> newLegs) {
        final TripRef prevTripRef = trip.tripRef;
        if (!(prevTripRef instanceof DbTripRef))
            return null;
        return new DbTripRef((DbTripRef) prevTripRef, refreshTokenFromPublicLegs(newLegs));
    }

    public static String refreshTokenFromPublicLegs(final List<Trip.Leg> legs) {
        final StringBuilder builder = new StringBuilder();
        builder.append("¶HKI¶");
        boolean isNotFirst = false;
        for (final Trip.Leg leg : legs) {
            if (leg instanceof Trip.Public) {
                final Trip.Public publicLeg = (Trip.Public) leg;
                final JourneyRef journeyRef = publicLeg.journeyRef;
                if (!(journeyRef instanceof BahnvorhersageProvider.BahnvorhersageJourneyRef))
                    return null;
                final BahnvorhersageProvider.BahnvorhersageJourneyRef bahnvorhersageJourneyRef = (BahnvorhersageProvider.BahnvorhersageJourneyRef) journeyRef;
                final String bahnvorhersageRefreshJourneyId = bahnvorhersageJourneyRef.getBahnvorhersageRefreshJourneyId();
                if (bahnvorhersageRefreshJourneyId == null)
                    return null;
                if (isNotFirst)
                    builder.append("§");
                isNotFirst = true;
                builder.append(bahnvorhersageRefreshJourneyId);
            }
        }
        return builder.toString();
    }

    public static class CtxRecon {
        public final String ctxRecon;
        public final Map<String, String> entries;
        public final String shortRecon;
        public final String startLocation;
        public final String endLocation;
        public final String tripId;
        public final List<String> journeyRequestIds;

        public CtxRecon(final String ctxRecon) {
            this.ctxRecon = ctxRecon;
            this.entries = parseMap("¶", ctxRecon);
            if (entries != null) {
                final StringBuilder sb = new StringBuilder();
                for (String key : entries.keySet()) {
                    if ("KCC".equals(key) || "SC".equals(key))
                        continue;
                    final String value = entries.get(key);
                    sb.append("¶");
                    sb.append(key);
                    sb.append("¶");
                    sb.append(value);
                }
                shortRecon = sb.toString();
            } else {
                shortRecon = null;
            }
            String startLocation = null;
            String endLocation = null;
            this.tripId = getEntry("HKI");
            journeyRequestIds = parseArray("§", tripId);
            if (journeyRequestIds != null) {
                if (!journeyRequestIds.isEmpty()) {
                    final List<String> firstLeg = parseArray("\\$", journeyRequestIds.get(0));
                    final List<String> lastLeg = parseArray("\\$", journeyRequestIds.get(journeyRequestIds.size() - 1));
                    if (firstLeg != null && firstLeg.size() >= 2)
                        startLocation = firstLeg.get(1);
                    if (lastLeg != null && lastLeg.size() >= 3)
                        endLocation = lastLeg.get(2);
                }
            }
            this.startLocation = startLocation;
            this.endLocation = endLocation;
        }

        public String getEntry(final String key) {
            if (key == null || entries == null)
                return null;
            return entries.get(key);
        }

        public static Map<String, String> parseMap(final String separator, final String value) {
            if (value == null)
                return null;
            final HashMap<String, String> entries = new HashMap<>();
            final String[] split = value.split(separator);
            for (int i = 2, splitLength = split.length; i < splitLength; i += 2) {
                final String k = split[i-1];
                final String v = split[i];
                entries.put(k, v);
            }
            return entries;
        }

        public static List<String> parseArray(final String separator, final String value) {
            if (value == null)
                return null;
            return Arrays.asList(value.split(separator));
        }
    }

    @Override
    public Description getDescription() {
        return getDbDescription();
    }
}
