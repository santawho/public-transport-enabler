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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import de.schildbach.pte.Standard;
import de.schildbach.pte.provider.TransferEvaluationProvider;
import de.schildbach.pte.provider.db.bahnvorhersage.AbstractBahnvorhersageProvider;
import de.schildbach.pte.util.MessagePackUtils;

/**
 * abstract provider implementation for Deutsche Bahn (Germany).
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

    private final AbstractBahnvorhersageProvider bahnvorhersageProvider;

    public DbProvider(final NetworkId networkId) {
        super(networkId);
        this.bahnvorhersageProvider = AbstractBahnvorhersageProvider.createInstance(this);
    }

    @Override
    public TransferEvaluationProvider getTransferEvaluationProvider() {
        return bahnvorhersageProvider;
    }

    @Override
    public QueryJourneyResult queryJourney(
            final JourneyRef journeyRef,
            final boolean loadPath) throws IOException {
        cleanupJourneyCache();
        return doQueryJourneyAndCache((DbJourneyRef) journeyRef, loadPath);
    }

    private Map<DbJourneyRef, QueryJourneyResult> journeyCache = new ConcurrentHashMap<>();
    private static long MAX_CACHE_KEEP_MILLIS = 50 * 1000;

    public QueryJourneyResult queryJourneyWithCache(final DbJourneyRef journeyRef) throws IOException {
        cleanupJourneyCache();
        final QueryJourneyResult result = journeyCache.get(journeyRef);
        if (result != null)
            return result;
        return doQueryJourneyAndCache(journeyRef, false);
    }

    private QueryJourneyResult doQueryJourneyAndCache(
            final DbJourneyRef journeyRef,
            final boolean loadPath) throws IOException {
        final QueryJourneyResult result = doQueryJourney(journeyRef, loadPath);
        if (result != null && result.status == QueryJourneyResult.Status.OK)
            journeyCache.put(journeyRef, result);
        return result;
    }

    private void cleanupJourneyCache() {
        final long loadedAtLimit = System.currentTimeMillis() - MAX_CACHE_KEEP_MILLIS;
        journeyCache.entrySet().removeIf(entry ->
                entry.getValue().journeyLeg.loadedAt.getTime() < loadedAtLimit);
    }

    protected abstract QueryJourneyResult doQueryJourney(
            final DbJourneyRef journeyRef,
            final boolean loadPath) throws IOException;

    public static final String OPERATOR_DB_FERNVERKEHR = "DB Fernverkehr AG";
    public static Style.Shape DB_DEFAULT_STYLE_SHAPE = Style.Shape.ROUNDED;
    public static final Style STYLE_NON_DB_HIGH_SPEED_TRAIN = new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(232,209,190), Style.RED, Style.RED);

    private static final Map<Product, Style> PRODUCT_STYLES;

    static {
        PRODUCT_STYLES = new HashMap<>();
        PRODUCT_STYLES.put(Product.HIGH_SPEED_TRAIN, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(70,75,85), Style.WHITE));
        PRODUCT_STYLES.put(Product.REGIONAL_TRAIN, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(175,180,187), Style.BLACK, Style.rgb(135,140,150)));
        PRODUCT_STYLES.put(Product.SUBURBAN_TRAIN, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(64,131,53), Style.WHITE));
        PRODUCT_STYLES.put(Product.SUBWAY, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(20,85,192), Style.WHITE));
        PRODUCT_STYLES.put(Product.TRAM, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(169,69,93), Style.WHITE));
        PRODUCT_STYLES.put(Product.BUS, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(129,73,151), Style.WHITE));
        PRODUCT_STYLES.put(Product.ON_DEMAND, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(255,216,0), Style.BLACK, Style.rgb(140,118,0)));
        PRODUCT_STYLES.put(Product.REPLACEMENT_SERVICE, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(155,27,96), Style.WHITE));
        PRODUCT_STYLES.put(Product.FERRY, new Style(DB_DEFAULT_STYLE_SHAPE, Style.rgb(48,159,209), Style.BLACK));
    }

    public static Style lineStyle(
            final @Nullable Map<String, Style> styles,
            @Nullable final String network,
            @Nullable final Product product,
            @Nullable final String label) {
        Style styleFromNetwork = null;
        if (product != null) {
            if (product.equals(Product.HIGH_SPEED_TRAIN)) {
                if (network != null) {
                    if (!OPERATOR_DB_FERNVERKEHR.equals(network))
                        styleFromNetwork = STYLE_NON_DB_HIGH_SPEED_TRAIN;
                } else {
                    if (label == null || !(label.startsWith("ICE ") || label.startsWith("IC ")))
                        styleFromNetwork = STYLE_NON_DB_HIGH_SPEED_TRAIN;
                }
            }
            if (styleFromNetwork == null) {
                styleFromNetwork = PRODUCT_STYLES.get(product);
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
            implements AbstractBahnvorhersageProvider.BahnvorhersageTripRef {
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
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof DbTripRef)) return false;
            final DbTripRef that = (DbTripRef) o;
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
            implements AbstractBahnvorhersageProvider.BahnvorhersageJourneyRef {
        private static final long serialVersionUID = 7738174208212249291L;

        public final String journeyId;
        public final String journeyRequestId;
        public final String adminCode;
        public final String productName;
        public final String serviceNumber;
        public final Line line;

        public DbJourneyRef(
                final String journeyId,
                final String journeyRequestId,
                final String adminCode,
                final String productName,
                final String serviceNumber,
                final Line line) {
            this.journeyId = journeyId;
            this.journeyRequestId = journeyRequestId;
            this.adminCode = adminCode;
            this.productName = productName;
            this.serviceNumber = serviceNumber;
            this.line = line;
        }

        @Override
        public String getUniqueId() {
            return journeyId;
        }

        @Override
        public String getBahnvorhersageRefreshJourneyId() {
            return journeyRequestId;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof DbJourneyRef)) return false;
            final DbJourneyRef that = (DbJourneyRef) o;
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
                if (!(journeyRef instanceof AbstractBahnvorhersageProvider.BahnvorhersageJourneyRef))
                    return null;
                final AbstractBahnvorhersageProvider.BahnvorhersageJourneyRef bahnvorhersageJourneyRef = (AbstractBahnvorhersageProvider.BahnvorhersageJourneyRef) journeyRef;
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
                for (final String key : entries.keySet()) {
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

    public static String getSaneLineShortName(final Product product, final String shortName) {
        if (shortName == null)
            return null;
        if (product == Product.BUS || product == Product.TRAM || product == Product.SUBWAY) {
            return shortName.replaceAll("^[A-Za-z]+ ", "");
        }
        return shortName;
    }

    @Override
    public Description getDescription() {
        return getDbDescription();
    }

    protected String[] splitPlaceAndName(final String placeAndName, final Pattern p, final int place, final int name) {
        if (placeAndName == null)
            return new String[] { null, null };
        final Matcher m = p.matcher(placeAndName);
        if (m.matches())
            return new String[] { m.group(place), m.group(name) };
        return new String[] { null, placeAndName };
    }

    private static final Pattern P_SPLIT_NAME_ONE_COMMA = Pattern.compile("([^,(]*(\\([^)]*\\))?), ([^,]*)");
    protected String[] splitStationName(final String name) {
        return splitPlaceAndName(name, P_SPLIT_NAME_ONE_COMMA, 3, 1);
    }

    private static final Pattern P_SPLIT_NAME_FIRST_COMMA = Pattern.compile("([^,]*), (.*)");
    protected String[] splitAddress(final String address) {
        return splitPlaceAndName(address, P_SPLIT_NAME_FIRST_COMMA, 1, 2);
    }

    protected Location createLocation(
            final LocationType type, final String id, final Point coord, final String name,
            final Set<Product> products, final String bahnhofsInfoId) {
        final String[] placeAndName =
                type == LocationType.STATION ? splitStationName(name)
                : type == LocationType.DIRECTION ? splitStationName(name)
                : splitAddress(name);
        return new Location(type, id, coord, placeAndName[0], placeAndName[1], products);
    }

    @Override
    public String getLocationInfoUrl(final Location location) {
        return getLocationInfoUrl(location.id, null);
    }

    protected String getLocationInfoUrl(final String id, final String bahnhofsInfoId) {
        final String infoId = bahnhofsInfoId != null ? bahnhofsInfoId : (id != null && id.length() <= 10) ? id : null;
        return infoId == null ? null : (
                "https://www.bahnhof.de"
                + ("de".equals(this.userInterfaceLanguage) ? "" : "/en")
                + "/bahnhof-de/id/" + infoId);
    }

    private String createLidEntry(final String key, final Object value) {
        return key + "=" + value + "@";
    }

    protected String formatLid(final Location loc) {
        if (loc.id != null && loc.id.startsWith("A=") && loc.id.contains("@")) {
            return loc.id;
        }
        final String typeId = ID_LOCATION_TYPE_MAP
                .entrySet()
                .stream()
                .filter(e -> e.getValue() == loc.type)
                .findFirst()
                .map(e -> e.getKey())
                .orElse("0");

        final StringBuilder out = new StringBuilder();
        out.append(createLidEntry("A", typeId));
        if (loc.name != null) {
            out.append(createLidEntry("O", loc.place != null ? loc.name + ", " + loc.place : loc.name));
        }
        if (loc.coord != null) {
            out.append(createLidEntry("X", loc.coord.getLonAs1E6()));
            out.append(createLidEntry("Y", loc.coord.getLatAs1E6()));
        }
        if (loc.id != null) {
            out.append(createLidEntry("L", normalizeStationId(loc.id)));
        }
        return out.toString();
    }

    protected String formatLid(final String stationId) {
        return formatLid(new Location(LocationType.STATION, stationId));
    }

    private static final Map<String, LocationType> ID_LOCATION_TYPE_MAP = new HashMap<String, LocationType>() {
        private static final long serialVersionUID = 295592979187174489L;

        {
            put("1", LocationType.STATION);
            put("4", LocationType.POI);
            put("2", LocationType.ADDRESS);
        }
    };

    protected Location parseLid(final String loc) {
        if (loc == null)
            return new Location(LocationType.STATION, null);
        final Map<String, String> props = Arrays.stream(loc.split("@"))
                .map(chunk -> chunk.split("="))
                .filter(e -> e.length == 2)
                .collect(Collectors.toMap(e -> e[0], e -> e[1]));
        Point coord = null;
        try {
            coord = Point.from1E6(Integer.parseInt(props.get("Y")), Integer.parseInt(props.get("X")));
        } catch (final Exception e) {
            // ignore
        }
        return new Location(
                Optional.ofNullable(ID_LOCATION_TYPE_MAP.get(props.get("A"))).orElse(LocationType.ANY),
                props.get("L"),
                coord,
                null,
                props.get("O"));
    }
}
