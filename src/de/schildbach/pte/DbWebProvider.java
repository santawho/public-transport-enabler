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

package de.schildbach.pte;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Fare;
import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.dto.TripShare;
import de.schildbach.pte.exception.AbstractHttpException;
import de.schildbach.pte.exception.BlockedException;
import de.schildbach.pte.exception.InternalErrorException;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.HttpClient;
import de.schildbach.pte.util.MessagePackUtils;
import de.schildbach.pte.util.ParserUtils;
import okhttp3.HttpUrl;

/**
 * Provider implementation for Web API of Deutsche Bahn (Germany).
 */
public abstract class DbWebProvider extends AbstractNetworkProvider {
    private static final Logger log = LoggerFactory.getLogger(DbWebProvider.class);

    public static class Fernverkehr extends DbWebProvider {
        public Fernverkehr() {
            this(NetworkId.DBWEB);
        }

        protected Fernverkehr(final NetworkId networkId) {
            super(networkId);
        }

        @Override
        public Set<Product> defaultProducts() {
            return DbProvider.FERNVERKEHR_PRODUCTS;
        }
    }

    public static class Regio extends DbWebProvider {
        public Regio() {
            this(NetworkId.DBREGIOWEB);
        }

        protected Regio(final NetworkId networkId) {
            super(networkId);
        }

        @Override
        public Set<Product> defaultProducts() {
            return DbProvider.REGIO_PRODUCTS;
        }
    }

    public static class DeutschlandTicket extends Regio {
        public DeutschlandTicket() {
            this(NetworkId.DBDEUTSCHLANDTICKETWEB);
        }

        protected DeutschlandTicket(final NetworkId networkId) {
            super(networkId);
        }

        @Override
        protected boolean isModeDeutschlandTicket() {
            return true;
        }
    }

    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.SUGGEST_LOCATIONS,
            Capability.NEARBY_LOCATIONS,
            Capability.DEPARTURES,
            Capability.TRIPS,
            Capability.TRIPS_VIA,
            Capability.JOURNEY,
            Capability.TRIP_RELOAD,
            Capability.MIN_TRANSFER_TIMES,
            Capability.BIKE_OPTION,
            Capability.TRIP_SHARING,
            Capability.TRIP_LINKING
        );

    private static final HttpUrl WEB_API_BASE = HttpUrl.parse("https://www.bahn.de/web/api/");
    private final ResultHeader resultHeader;

    private static final Map<String, Product> PRODUCTS_MAP = new LinkedHashMap<String, Product>() {
        private static final long serialVersionUID = 6581845892244269924L;

        {
            put("ICE", Product.HIGH_SPEED_TRAIN);
            put("EC_IC", Product.HIGH_SPEED_TRAIN);
            put("IR", Product.HIGH_SPEED_TRAIN);
            put("REGIONAL", Product.REGIONAL_TRAIN);
            put("SBAHN", Product.SUBURBAN_TRAIN);
            put("BUS", Product.BUS);
            put("SCHIFF", Product.FERRY);
            put("UBAHN", Product.SUBWAY);
            put("TRAM", Product.TRAM);
            put("ANRUFPFLICHTIG", Product.ON_DEMAND);
            put("ERSATZVERKEHR", Product.REGIONAL_TRAIN);
        }
    };

    private static final Map<String, LocationType> ID_LOCATION_TYPE_MAP = new HashMap<String, LocationType>() {
        private static final long serialVersionUID = 295592979187174489L;

        {
            put("1", LocationType.STATION);
            put("4", LocationType.POI);
            put("2", LocationType.ADDRESS);
        }
    };

    private static final int DEFAULT_MAX_DEPARTURES = 100;
    private static final int DEFAULT_MAX_LOCATIONS = 50;
    private static final int DEFAULT_MAX_DISTANCE = 10000;

    private final HttpUrl departureEndpoint;
    private final HttpUrl tripEndpoint;
    private final HttpUrl tripReconEndpoint;
    private final HttpUrl journeyEndpoint;
    private final HttpUrl locationsEndpoint;
    private final HttpUrl nearbyEndpoint;

    private static final TimeZone timeZone = TimeZone.getTimeZone("Europe/Berlin");

    private static final Pattern P_SPLIT_NAME_FIRST_COMMA = Pattern.compile("([^,]*), (.*)");
    private static final Pattern P_SPLIT_NAME_ONE_COMMA = Pattern.compile("([^,]*), ([^,]*)");

    private static final int[] VALID_MIN_TRANSFER_TIMES = { 0, 10, 15, 20, 25, 30, 35, 40, 45 };

    private static int getApplicableMinTransferTime(final int requestedMinTransferTime) {
        if (requestedMinTransferTime <= VALID_MIN_TRANSFER_TIMES[0])
            return VALID_MIN_TRANSFER_TIMES[0];

        if (requestedMinTransferTime >= VALID_MIN_TRANSFER_TIMES[VALID_MIN_TRANSFER_TIMES.length - 1])
            return VALID_MIN_TRANSFER_TIMES[VALID_MIN_TRANSFER_TIMES.length - 1];

        for (int i = VALID_MIN_TRANSFER_TIMES.length; i > 0; --i) {
            final int time = VALID_MIN_TRANSFER_TIMES[i - 1];
            if (time == requestedMinTransferTime)
                return requestedMinTransferTime;
            if (time < requestedMinTransferTime)
                return VALID_MIN_TRANSFER_TIMES[i];
        }

        return VALID_MIN_TRANSFER_TIMES[0];
    }

    protected DbWebProvider(final NetworkId networkId) {
        super(networkId);
        this.departureEndpoint = WEB_API_BASE.newBuilder().addPathSegments("reiseloesung/abfahrten").build();
        this.tripEndpoint = WEB_API_BASE.newBuilder().addPathSegments("angebote/fahrplan").build();
        this.tripReconEndpoint = WEB_API_BASE.newBuilder().addPathSegments("angebote/recon").build();
        this.journeyEndpoint = WEB_API_BASE.newBuilder().addPathSegments("reiseloesung/fahrt").build();
        this.locationsEndpoint = WEB_API_BASE.newBuilder().addPathSegments("reiseloesung/orte").build();
        this.nearbyEndpoint = WEB_API_BASE.newBuilder().addPathSegments("reiseloesung/orte/nearby").build();
        this.resultHeader = new ResultHeader(network, "dbweb");

        this.linkSharing = new DbWebLinkSharing();
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new DbWebTripRef(network, unpacker);
    }

    @Override
    public TripShare unpackTripShareFromMessage(final MessageUnpacker unpacker) throws IOException {
        final TripRef tripRef = unpackTripRefFromMessage(unpacker);
        return new DbWebTripShare(tripRef, unpacker);
    }

    @Override
    protected String[] getValidUserInterfaceLanguages() {
        return new String[] { "en", "de", "fr", "es", "dk", "cz", "it", "nl", "pl" };
    }

    protected boolean isModeDeutschlandTicket() {
        return false;
    }

    private static String doRequest(
            final HttpClient httpClient, final String userInterfaceLanguage,
            final HttpUrl url, final String body, final String contentType) throws IOException {
        // DB API requires these headers
        // Content-Type must be exactly as passed below,
        // passing it to httpClient.get would add charset suffix
        String cType = contentType != null ? contentType : "application/json";
        httpClient.setHeader("X-Correlation-ID", UUID.randomUUID() + "_" + UUID.randomUUID());
        httpClient.setHeader("Accept", cType);
        if (body != null) httpClient.setHeader("Content-Type", cType);
        if (userInterfaceLanguage != null)
            httpClient.setHeader("Accept-Language", userInterfaceLanguage);
        final String page = httpClient.get(url, body, null).toString();
        return page;
    }

    private String doRequest(final HttpUrl url, final String body) throws IOException {
        return doRequest(httpClient, userInterfaceLanguage, url, body, null);
    }

    private String doRequest(final HttpUrl url) throws IOException {
        return doRequest(url, null);
    }

    private CharSequence formatDate(final Calendar time) {
        final int year = time.get(Calendar.YEAR);
        final int month = time.get(Calendar.MONTH) + 1;
        final int day = time.get(Calendar.DAY_OF_MONTH);
        return String.format(Locale.ENGLISH, "%04d-%02d-%02d", year, month, day);
    }

    private CharSequence formatTime(final Calendar time) {
        final int hour = time.get(Calendar.HOUR_OF_DAY);
        final int minute = time.get(Calendar.MINUTE);
        return String.format(Locale.ENGLISH, "%02d:%02d", hour, minute);
    }

    private static final DateFormat ISO_DATE_TIME_NO_OFFSET_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    private static final DateFormat ISO_DATE_TIME_UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    static {
        ISO_DATE_TIME_NO_OFFSET_FORMAT.setTimeZone(timeZone);
        ISO_DATE_TIME_UTC_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private String formatIso8601NoOffset(final Date time) {
        if (time == null)
            return null;
        return ISO_DATE_TIME_NO_OFFSET_FORMAT.format(time);
    }

    private Date parseIso8601NoOffset(final String time) {
        if (time == null)
            return null;
        try {
            return ISO_DATE_TIME_NO_OFFSET_FORMAT.parse(time);
        } catch (final ParseException x) {
            throw new RuntimeException(x);
        }
    }

    private String createLidEntry(final String key, final Object value) {
        return key + "=" + value + "@";
    }

    private String formatLid(final Location loc) {
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
            out.append(createLidEntry("O", loc.name));
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

    private String formatLid(final String stationId) {
        return formatLid(new Location(LocationType.STATION, stationId));
    }

    private Location parseLid(final String loc) {
        if (loc == null)
            return new Location(LocationType.STATION, null);
        final Map<String, String> props = Arrays.stream(loc.split("@"))
                .map(chunk -> chunk.split("="))
                .filter(e -> e.length == 2)
                .collect(Collectors.toMap(e -> e[0], e -> e[1]));
        Point coord = null;
        try {
            coord = Point.from1E6(Integer.parseInt(props.get("Y")), Integer.parseInt(props.get("X")));
        } catch (Exception e) {
        }
        return new Location(
                Optional.ofNullable(ID_LOCATION_TYPE_MAP.get(props.get("A"))).orElse(LocationType.ANY),
                props.get("L"),
                coord,
                null,
                props.get("O"));
    }

    private String formatProducts(final Set<Product> products) {
        if (products == null)
            return "\"ALL\"";
        return products.stream()
                .flatMap(p -> PRODUCTS_MAP.entrySet().stream().filter(e -> e.getValue() == p))
                .map(p -> "\"" + p.getKey() + "\"")
                .collect(Collectors.joining(", "));
    }

    private Set<Product> parseProducts(final JSONArray products) {
        if (products == null)
            return null;
        final Set<Product> out = new HashSet<>();
        for (int iProd = 0; iProd < products.length(); iProd++) {
            String prodStr = products.optString(iProd, null);
            final Product prod = PRODUCTS_MAP.get(prodStr);
            if (prod != null) {
                out.add(prod);
            } else {
                throw new RuntimeException(prodStr);
            }
        }
        return out;
    }

    protected String[] splitPlaceAndName(final String placeAndName, final Pattern p, final int place, final int name) {
        if (placeAndName == null)
            return new String[] { null, null };
        final Matcher m = p.matcher(placeAndName);
        if (m.matches())
            return new String[] { m.group(place), m.group(name) };
        return new String[] { null, placeAndName };
    }

    protected String[] splitStationName(final String name) {
        return splitPlaceAndName(name, P_SPLIT_NAME_ONE_COMMA, 2, 1);
    }

    protected String[] splitAddress(final String address) {
        return splitPlaceAndName(address, P_SPLIT_NAME_FIRST_COMMA, 1, 2);
    }

    private Location createLocation(final LocationType type, final String id, final Point coord, final String name,
                                    final Set<Product> products, final String bahnhofsInfoId) {
        final String[] placeAndName = type == LocationType.STATION ? splitStationName(name) : splitAddress(name);
        final String infoId = bahnhofsInfoId != null ? bahnhofsInfoId : id;
        final String url = infoId == null ? null : (
                "https://www.bahnhof.de"
                    + ("de".equals(this.userInterfaceLanguage) ? "" : "/en")
                    + "/bahnhof-de/id/" + infoId);
        return new Location(type, id, coord, placeAndName[0], placeAndName[1], products, url);
    }

    private Location parseLocation(JSONObject loc) {
        if (loc == null)
            return null;
        final String lidStr = loc.optString("id", null);
        final Location lid = parseLid(lidStr);
        final String id = lid.type == LocationType.STATION
                ? Optional.ofNullable(loc.optString("extId", null)).orElse(lid.id)
                : lidStr;
        Point coord;
        double latitude = loc.optDouble("lat");
        if (!Double.isNaN(latitude)) {
            coord = Point.fromDouble(latitude, loc.optDouble("lon"));
        } else {
            coord = lid.coord;
        }
        final String bahnhofsInfoId = loc.optString("bahnhofsInfoId", null);

        return createLocation(
                lid.type,
                id,
                coord,
                loc.optString("name", null),
                parseProducts(loc.optJSONArray("products")),
                bahnhofsInfoId);
    }

    private Location parseDirection(final JSONObject verkehrsmittel) {
        final String richtung = verkehrsmittel.optString("richtung", null);
        if (richtung == null)
            return null;
        return createLocation(LocationType.STATION, null, null, richtung, null, null);
    }

    private List<Location> parseLocations(final JSONArray locs) throws JSONException {
        final List<Location> locations = new ArrayList<>();
        for (int iLoc = 0; iLoc < locs.length(); iLoc++) {
            final Location loc = parseLocation(locs.getJSONObject(iLoc));
            if (loc != null) {
                locations.add(loc);
            }
        }
        return locations;
    }

    private void parseMessages(
            final JSONArray msgs, final List<String> messages, final String prefix,
            final String defaultTeilstreckenHinweis) throws JSONException {
        if (msgs == null)
            return;
        for (int iMsg = 0; iMsg < msgs.length(); iMsg++) {
            final JSONObject msgObj = msgs.getJSONObject(iMsg);
            final String title = msgObj.optString("ueberschrift", null);
            final String value = msgObj.optString("value", null);
            final String text = msgObj.optString("text", null);
            final String url = msgObj.optString("url", null);
            final String teilstreckenHinweis = msgObj.optString("teilstreckenHinweis", null);
            if (text != null || value != null) {
                String msg = text;
                if (text == null)
                    msg = value;
                if (teilstreckenHinweis != null && !teilstreckenHinweis.equals(defaultTeilstreckenHinweis))
                    msg = msg + " " + teilstreckenHinweis;
                if (prefix != null)
                    msg = prefix + msg;
                if (title != null && this.messagesAsSimpleHtml)
                    msg = "<b>" + title + "</b><br>" + msg;
                if (url != null && this.messagesAsSimpleHtml)
                    msg = msg + "<br><a href=\"" + url + "\">Info&#128279;</a>";
                messages.add(msg);
            }
        }
    }

    private String parseJourneyMessages(
            final JSONObject jny, final JSONArray zugattribute, final String operatorName,
            final String defaultTeilstreckenHinweis) throws JSONException {
        final List<String> messages = new ArrayList<>();
        parseMessages(jny.optJSONArray("meldungen"), messages, null, defaultTeilstreckenHinweis);
        parseMessages(jny.optJSONArray("risNotizen"), messages, null, defaultTeilstreckenHinweis);
        parseMessages(jny.optJSONArray("himMeldungen"), messages, null, defaultTeilstreckenHinweis);
        if (operatorName != null)
            messages.add("&#8226; " + operatorName);
        if (zugattribute != null)
            parseMessages(zugattribute, messages, this.messagesAsSimpleHtml ? "&#8226; " : null, defaultTeilstreckenHinweis);
        return messages.isEmpty() ? null : join(this.messagesAsSimpleHtml ? "<br>" : " - ", messages);
    }

    // replace with String.join() at some point
    private static String join(final CharSequence delimiter, final Iterable<? extends CharSequence> elements) {
        final StringJoiner joiner = new StringJoiner(delimiter);
        elements.forEach(joiner::add);
        return joiner.toString();
    }

    private Line parseLine(final JSONObject verkehrsmittel) throws JSONException {
        // TODO attrs, messages
        final Product product = PRODUCTS_MAP.get(verkehrsmittel.optString("produktGattung", null));
        String shortName = verkehrsmittel.optString("mittelText", null);
        final String name = Optional.ofNullable(verkehrsmittel.optString("langText", null)).orElse(shortName);
        if (shortName != null && (product == Product.BUS || product == Product.TRAM)) {
            shortName = shortName.replaceAll("^[A-Za-z]+ ", "");
        }
        String operator = null;
        final JSONArray attributNotizen = verkehrsmittel.optJSONArray("zugattribute");
        if (attributNotizen != null) {
            for (int iAttr = 0; iAttr < attributNotizen.length(); ++iAttr) {
                JSONObject attr = attributNotizen.getJSONObject(iAttr);
                if ("BEF".equals(attr.get("key"))) {
                    operator = attr.getString("value");
                    break;
                }
            }
        }
        return new Line(
                null,
                operator,
                product,
                shortName,
                name,
                DbProvider.lineStyle(styles, operator, product, name));
    }

    private boolean parseCancelled(JSONObject stop) throws JSONException {
        final JSONArray notices = stop.optJSONArray("risNotizen");
        if (notices != null) {
            for (int iNotice = 0; iNotice < notices.length(); iNotice++) {
                final JSONObject notice = notices.optJSONObject(iNotice);
                if (notice != null) {
                    final String key = notice.optString("key", null);
                    if ("text.realtime.stop.cancelled".equals(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Stop parseStop(final JSONObject stop, final Location fallbackLocation) throws JSONException {
        final Position gleis = parsePosition(stop.optString("gleis", null));
        final Position ezGleis = parsePosition(stop.optString("ezGleis", null));
        final boolean cancelled = parseCancelled(stop);
        Location stopLocation = parseLocation(stop);
        return new Stop(
                stopLocation != null && stopLocation.id != null ? stopLocation : fallbackLocation,
                parseIso8601NoOffset(stop.optString("ankunftsZeitpunkt", null)),
                parseIso8601NoOffset(stop.optString("ezAnkunftsZeitpunkt", null)),
                gleis, ezGleis, cancelled,
                parseIso8601NoOffset(stop.optString("abfahrtsZeitpunkt", null)),
                parseIso8601NoOffset(stop.optString("ezAbfahrtsZeitpunkt", null)),
                gleis, ezGleis, cancelled);
    }

    private List<Stop> parseStops(final JSONArray stops) throws JSONException {
        if (stops == null)
            return null;
        List<Stop> out = new LinkedList<>();
        for (int iStop = 0; iStop < stops.length(); iStop++) {
            out.add(parseStop(stops.getJSONObject(iStop), null));
        }
        return out;
    }

    private int[] parseCapacity(final JSONObject verbindung) throws JSONException {
        final JSONArray auslastungen = verbindung.optJSONArray("auslastungsMeldungen");
        int[] out = { 0, 0 };
        if (auslastungen != null) {
            for (int i = 0; i < auslastungen.length(); i++) {
                final JSONObject auslastung = auslastungen.getJSONObject(i);
                final String klasse = auslastung.optString("klasse");
                out["KLASSE_2".equals(klasse) ? 1 : 0] = auslastung.optInt("stufe", 0);
            }
            if (out[0] == 0 && out[1] == 0) {
                return null;
            }
            return out;
        }
        return null;
    }

    public static class DbWebTripRef extends TripRef {
        private static final long serialVersionUID = -1951536102104578242L;

        public final String ctxRecon;
        public final boolean limitToDticket;
        public final boolean hasDticket;

        public DbWebTripRef(
                final NetworkId network, final String ctxRecon,
                final Location from, final Location via, final Location to,
                final boolean limitToDticket, final boolean hasDticket) {
            super(network, from, via, to);
            this.ctxRecon = ctxRecon;
            this.limitToDticket = limitToDticket;
            this.hasDticket = hasDticket;
        }

        public DbWebTripRef(final DbWebTripRef simplifiedTripRef, final String ctxRecon) {
            super(simplifiedTripRef);
            this.ctxRecon = ctxRecon;
            this.limitToDticket = simplifiedTripRef.limitToDticket;
            this.hasDticket = simplifiedTripRef.hasDticket;
        }

        public DbWebTripRef(final NetworkId network, final MessageUnpacker unpacker) throws IOException {
            super(network, unpacker);
            this.ctxRecon = MessagePackUtils.unpackNullableString(unpacker);
            this.limitToDticket = unpacker.unpackBoolean();
            this.hasDticket = unpacker.unpackBoolean();
        }

        @Override
        public void packToMessage(final MessagePacker packer) throws IOException {
            super.packToMessage(packer);
            MessagePackUtils.packNullableString(packer, ctxRecon);
            packer.packBoolean(limitToDticket);
            packer.packBoolean(hasDticket);
        }

        public DbWebTripRef getSimplified() {
            return new DbWebTripRef(network, null, from, via, to, limitToDticket, hasDticket);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DbWebTripRef)) return false;
            DbWebTripRef that = (DbWebTripRef) o;
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

    public static class DbWebJourneyRef extends JourneyRef {
        private static final long serialVersionUID = 7738174208212249291L;

        public final String journeyId;
        public final Line line;

        public DbWebJourneyRef(final String journeyId, final Line line) {
            this.journeyId = journeyId;
            this.line = line;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DbWebJourneyRef)) return false;
            DbWebJourneyRef that = (DbWebJourneyRef) o;
            return Objects.equals(journeyId, that.journeyId)
                    && Objects.equals(line, that.line);
        }

        @Override
        public int hashCode() {
            return Objects.hash(journeyId, line);
        }
    }

    private Trip.Public parseJourney(final JSONObject journey, final DbWebJourneyRef journeyRef) throws JSONException {
        Stop departureStop = null;
        Stop arrivalStop = null;
        final List<Stop> intermediateStops = parseStops(journey.optJSONArray("halte"));
        if (intermediateStops != null && intermediateStops.size() >= 2) {
            final int size = intermediateStops.size();
            departureStop = intermediateStops.get(0);
            arrivalStop = intermediateStops.get(size - 1);
            intermediateStops.remove(size - 1);
            intermediateStops.remove(0);
        }
        final String defaultTeilstreckenHinweis = String.format("(%s - %s)",
                departureStop.location.name, arrivalStop.location.name);
        final String message = parseJourneyMessages(
                journey, journey.optJSONArray("zugattribute"), journeyRef.line.network,
                defaultTeilstreckenHinweis);
        return new Trip.Public(journeyRef.line, arrivalStop.location, departureStop, arrivalStop, intermediateStops, null, message, journeyRef);
    }

    private Trip.Leg parseLeg(final JSONObject abschnitt, final @Nullable Location fallbackDeparture, final @Nullable Location fallbackArrival) throws JSONException {
        final Stop departureStop;
        final Stop arrivalStop;
        final JSONObject verkehrsmittel = abschnitt.getJSONObject("verkehrsmittel");
        final String typ = verkehrsmittel.optString("typ", null);
        final boolean isPublicTransportLeg = "PUBLICTRANSPORT".equals(typ);
        final List<Stop> intermediateStops = parseStops(abschnitt.optJSONArray("halte"));
        if (intermediateStops != null && intermediateStops.size() >= 2 && isPublicTransportLeg) {
            final int size = intermediateStops.size();
            departureStop = intermediateStops.get(0);
            arrivalStop = intermediateStops.get(size - 1);
            intermediateStops.remove(size - 1);
            intermediateStops.remove(0);
        } else {
            departureStop = parseStop(abschnitt, fallbackDeparture);
            arrivalStop = parseStop(abschnitt, fallbackArrival);
        }
        if (isPublicTransportLeg) {
            final Line line = parseLine(verkehrsmittel);
            final Location destination = parseDirection(verkehrsmittel);
            final String defaultTeilstreckenHinweis = String.format("(%s - %s)",
                    departureStop.location.name, arrivalStop.location.name);
            final String message = parseJourneyMessages(
                    abschnitt, verkehrsmittel.optJSONArray("zugattribute"), null,
                    defaultTeilstreckenHinweis);
            final String journeyId = abschnitt.optString("journeyId", null);
            return new Trip.Public(line, destination, departureStop, arrivalStop, intermediateStops, null, message,
                    journeyId == null ? null : new DbWebJourneyRef(journeyId, line));
        } else {
            final int dist = abschnitt.optInt("distanz");
            return new Trip.Individual(
                    "TRANSFER".equals(typ) ? Trip.Individual.Type.TRANSFER : Trip.Individual.Type.WALK,
                    departureStop.location,
                    departureStop.getDepartureTime(),
                    arrivalStop.location,
                    arrivalStop.getArrivalTime(),
                    null, dist);
        }
    }

    private List<Fare> parseFares(final JSONObject verbindung) {
        List<Fare> fares = new ArrayList<>();
        final Optional<JSONObject> angebotsPreis = Optional.ofNullable(verbindung.optJSONObject("angebotsPreis"));
        if (angebotsPreis.isPresent()) {
            fares.add(new Fare(
                    "de".equals(this.userInterfaceLanguage) ? "ab" : "from",
                    Fare.Type.ADULT,
                    ParserUtils.getCurrency(angebotsPreis.get().optString("waehrung", "EUR")),
                    (float) angebotsPreis.get().optDouble("betrag"),
                    null,
                    null));
        }
        return fares;
    }

    private String parseErrorCode(AbstractHttpException e) {
        String code = null;
        try {
            final JSONObject res = new JSONObject(e.getBodyPeek().toString());
            final JSONObject details = res.optJSONObject("details");
            code = res.optString("code", null);
            if (details != null) {
                code = details.optString("typ", code);
            }
        } catch (final Exception x) {
            // ignore
        }
        return code;
    }

    private QueryTripsResult parseTrips(
            final JSONObject res,
            final DbWebApiContext context,
            final Location from, final Location via, final Location to,
            final boolean limitToDticket, final boolean hasDticket
    ) throws JSONException {
        final JSONArray verbindungen = res.getJSONArray("verbindungen");
        final List<Trip> trips = new ArrayList<>();

        for (int iTrip = 0; iTrip < verbindungen.length(); iTrip++) {
            final JSONObject verbindung = verbindungen.getJSONObject(iTrip);
            final JSONArray abschnitte = verbindung.getJSONArray("verbindungsAbschnitte");
            final List<Trip.Leg> legs = new ArrayList<>();
            Location tripFrom = null;
            Location tripTo = null;

            for (int iLeg = 0; iLeg < abschnitte.length(); iLeg++) {
                final JSONObject abschnitt = abschnitte.getJSONObject(iLeg);
                final Location fallbackDeparture = Optional.ofNullable(abschnitte.optJSONObject(iLeg - 1))
                        .map(prev -> prev.optJSONArray("halte"))
                        .map(halte -> halte.optJSONObject(halte.length() - 1))
                        .map(this::parseLocation)
                        .orElse(
                                from
//                                createLocation(LocationType.ADDRESS, null, null, abschnitt.getString("abfahrtsOrt"), null, null)
                        );
                final Location fallbackArrival = Optional.ofNullable(abschnitte.optJSONObject(iLeg + 1))
                        .map(next -> next.optJSONArray("halte"))
                        .map(halte -> halte.optJSONObject(0))
                        .map(this::parseLocation)
                        .orElse(
                                to
//                                createLocation(LocationType.ADDRESS, null, null, abschnitt.getString("ankunftsOrt"), null, null)
                        );
                final Trip.Leg leg = parseLeg(abschnitt, fallbackDeparture, fallbackArrival);
                legs.add(leg);
                if (iLeg == 0) {
                    tripFrom = leg.departure;
                }
                if (iLeg == abschnitte.length() - 1) {
                    tripTo = leg.arrival;
                }
            }
            final List<Fare> fares = parseFares(verbindung);
            final int transfers = verbindung.optInt("umstiegsAnzahl", -1);
            final int[] capacity = parseCapacity(verbindung);
            final CtxRecon ctxRecon = new CtxRecon(verbindung.optString("ctxRecon"));
            trips.add(new Trip(
                    new Date(),
                    ctxRecon.tripId,
                    new DbWebTripRef(network, ctxRecon.ctxRecon, from, via, to, limitToDticket, hasDticket),
                    tripFrom,
                    tripTo,
                    legs,
                    fares,
                    capacity,
                    transfers == -1 ? null : transfers));
        }
        if (trips.isEmpty()) {
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
        }
        return new QueryTripsResult(this.resultHeader, null, from, via, to, context, trips);
    }

    private QueryTripsResult doQueryTrips(
            final Location from, @Nullable final Location via, final Location to,
            final Date time, final boolean dep,
            @Nullable final Set<Product> products, final boolean bike, @Nullable final Integer minUmstiegszeit,
            final @Nullable String context) throws IOException {
        // accessibility, optimize not supported

        final String deparr = dep ? "ABFAHRT" : "ANKUNFT";
        final Set<Product> useProducts;
        final boolean limitToDticket;
        final boolean hasDticket;
        if (isModeDeutschlandTicket()) {
            hasDticket = true;
            if (products != null && products.contains(Product.HIGH_SPEED_TRAIN)) {
                limitToDticket = false;
                useProducts = products;
            } else if (products != null && (products.size() != defaultProducts().size() || !products.containsAll(defaultProducts()))) {
                // special case, because the web service does not work as expected:
                // when D-Ticket only is active, then deselection of products is not observed
                // so we fix it meanwhile by ignoring the D-Ticket only option
                limitToDticket = false;
                useProducts = products;
            } else {
                limitToDticket = true;
                useProducts = new HashSet<>(products != null ? products : Product.ALL);
                useProducts.add(Product.HIGH_SPEED_TRAIN);
            }
        } else {
            hasDticket = false;
            limitToDticket = false;
            useProducts = products;
        }
        final String productsStr = "\"produktgattungen\":[" + formatProducts(useProducts)
                + "],\"deutschlandTicketVorhanden\":" + hasDticket
                + ",\"nurDeutschlandTicketVerbindungen\":" + limitToDticket + ",";
        final String viaLocations = via == null ? ""
                : "\"zwischenhalte\":[{\"id\": \"" + formatLid(via) + "\"}],";
        final String bikeStr = bike ? "\"bikeCarriage\":true," : "";
        final String minUmstiegszeitStr = minUmstiegszeit != null ? "\"minUmstiegszeit\":" + minUmstiegszeit + "," : "";
        final String ctxStr = context != null ? "\"pagingReference\": \"" + context + "\"," : "";
        final String request = "{\"sitzplatzOnly\":false,\"klasse\":\"KLASSE_2\"," //
                + "\"abfahrtsHalt\": \"" + formatLid(from) + "\"," //
                + productsStr //
                + viaLocations //
                + bikeStr //
                + minUmstiegszeitStr //
                + ctxStr //
                + "\"anfrageZeitpunkt\":\"" + formatIso8601NoOffset(time) + "\",\"ankunftSuche\":\"" + deparr + "\"," //
                + "\"ankunftsHalt\": \"" + formatLid(to) + "\"," //
                + "\"reisende\":[{\"ermaessigungen\":[{\"art\":\"KEINE_ERMAESSIGUNG\",\"klasse\":\"KLASSENLOS\"}],\"typ\":\"ERWACHSENER\",\"alter\":[],\"anzahl\":1}]," //
                + "\"reservierungsKontingenteVorhanden\":false,\"schnelleVerbindungen\":false}";

        final HttpUrl url = this.tripEndpoint;

        String page = null;
        try {
            page = doRequest(url, request);
            final JSONObject res = new JSONObject(page);
            Optional<JSONObject> verbindungReference = Optional.ofNullable(res.optJSONObject("verbindungReference"));
            final DbWebApiContext apiContext = new DbWebApiContext(from, via, to, time, dep, products, bike, minUmstiegszeit,
                    verbindungReference.map(v -> v.optString("later", null)).orElse(null),
                    verbindungReference.map(v -> v.optString("earlier", null)).orElse(null));
            return parseTrips(res, apiContext, from, via, to, limitToDticket, hasDticket);
        } catch (InternalErrorException | BlockedException e) {
            final String code = parseErrorCode(e);
            if ("MDA-AK-MSG-1001".equals(code)) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.INVALID_DATE);
            } else if (code != null) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        } catch (IOException | RuntimeException e) {
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    private QueryTripsResult doQueryReloadTrip(final DbWebTripRef tripRef) throws IOException {
        final String request = "{\"ctxRecon\":\"" + tripRef.ctxRecon
                + "\",\"klasse\":\"KLASSE_2\"" //
                + ",\"deutschlandTicketVorhanden\":" + tripRef.hasDticket
                + ",\"nurDeutschlandTicketVerbindungen\":" + tripRef.limitToDticket //
                + ",\"reisende\":[{\"ermaessigungen\":[{\"art\":\"KEINE_ERMAESSIGUNG\",\"klasse\":\"KLASSENLOS\"}],\"typ\":\"ERWACHSENER\",\"alter\":[],\"anzahl\":1}]," //
                + "\"reservierungsKontingenteVorhanden\":false}";

        final HttpUrl url = this.tripReconEndpoint;

        String page = null;
        try {
            page = doRequest(url, request);
            final JSONObject res = new JSONObject(page);
            return parseTrips(res, null, tripRef.from, tripRef.via, tripRef.to, tripRef.limitToDticket, tripRef.hasDticket);
        } catch (InternalErrorException | BlockedException e) {
            final String code = parseErrorCode(e);
            if ("MDA-AK-MSG-1001".equals(code)) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.INVALID_DATE);
            } else if (code != null) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        } catch (IOException | RuntimeException e) {
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(Set<LocationType> types, Location location, int maxDistance,
            int maxLocations) throws IOException {
        // TODO POIs not supported (?)
        if (maxDistance == 0)
            maxDistance = DEFAULT_MAX_DISTANCE;
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;
        if (location.coord == null) {
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.INVALID_ID);
        }

        HttpUrl.Builder builder = this.nearbyEndpoint.newBuilder()
                .addQueryParameter("lat", Double.toString(location.coord.getLatAsDouble()))
                .addQueryParameter("long", Double.toString(location.coord.getLonAsDouble()))
                .addQueryParameter("radius", Integer.toString(maxDistance))
                .addQueryParameter("maxNo", Integer.toString(maxLocations));
        PRODUCTS_MAP.forEach((key, product) -> builder.addQueryParameter("product[]", key));
        final HttpUrl url = builder.build();
        String page = null;
        try {
            page = doRequest(url);
            final JSONArray locs = new JSONArray(page);
            final List<Location> locations = parseLocations(locs);
            return new NearbyLocationsResult(this.resultHeader, locations);
        } catch (InternalErrorException | BlockedException e) {
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.INVALID_ID);
        } catch (IOException | RuntimeException e) {
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures,
            boolean equivs)
            throws IOException {
        // TODO only 1 hour of results returned, find secret parameter?
        if (maxDepartures == 0)
            maxDepartures = DEFAULT_MAX_DEPARTURES;
        final Calendar c = new GregorianCalendar(timeZone);
        c.setTime(time);

        HttpUrl.Builder builder = this.departureEndpoint.newBuilder()
                .addQueryParameter("datum", formatDate(c).toString())
                .addQueryParameter("zeit", formatTime(c).toString())
                .addQueryParameter("ortExtId", stationId)
                .addQueryParameter("ortId", formatLid(stationId))
                .addQueryParameter("mitVias", "true")
                .addQueryParameter("maxVias", "2");
        PRODUCTS_MAP.forEach((key, product) -> builder.addQueryParameter("verkehrsmittel[]", key));
        final HttpUrl url = builder.build();

        String page = null;
        try {
            page = doRequest(url);
            final QueryDeparturesResult result = new QueryDeparturesResult(this.resultHeader);
            final JSONObject head = new JSONObject(page);
            final JSONArray deps = head.optJSONArray("entries");
            if (deps == null) return result;
            int added = 0;
            for (int iDep = 0; iDep < deps.length(); iDep++) {
                final JSONObject dep = deps.getJSONObject(iDep);
                if (parseCancelled(dep)) {
                    continue;
                }
                final String bahnhofsId = dep.getString("bahnhofsId");
                final JSONArray vias = dep.optJSONArray("ueber");
                final String bahnhofsName = Optional.ofNullable(vias).map(via -> via.optString(0)).orElse(null);
                if (!equivs && !stationId.equals(bahnhofsId)) {
                    continue;
                }
                final Location location = createLocation(LocationType.STATION, bahnhofsId, null, bahnhofsName, null, null);
                StationDepartures stationDepartures = result.findStationDepartures(bahnhofsId);
                if (stationDepartures == null) {
                    stationDepartures = new StationDepartures(location, new ArrayList<Departure>(8), null);
                    result.stationDepartures.add(stationDepartures);
                }

                final String journeyId = dep.optString("journeyId", null);
                final Line line = parseLine(dep.getJSONObject("verkehrmittel"));
                String destinationName = dep.optString("terminus", null);
                if (destinationName == null && vias != null) {
                    destinationName = vias.getString(vias.length() - 1);
                }
                boolean cancelled = false;
                final JSONArray meldungen = dep.optJSONArray("meldungen");
                if (meldungen != null) {
                    for (int iMsg = 0; iMsg < meldungen.length(); iMsg++) {
                        final JSONObject msgObj = meldungen.getJSONObject(iMsg);
                        final String type = msgObj.optString("type", null);
                        if ("HALT_AUSFALL".equals(type))
                            cancelled = true;
                    }
                }
//                if (cancelled)
//                    continue;
                final Departure departure = new Departure(
                        parseIso8601NoOffset(dep.optString("zeit", null)),
                        parseIso8601NoOffset(dep.optString("ezZeit", null)),
                        line,
                        parsePosition(Optional.ofNullable(dep.optString("ezGleis", null)).orElse(dep.optString("gleis", null))),
                        createLocation(LocationType.STATION, null, null, destinationName, null, null),
                        cancelled,
                        null,
                        parseJourneyMessages(dep, null, null, null),
                        journeyId == null ? null : new DbWebJourneyRef(journeyId, line));

                stationDepartures.departures.add(departure);
                added += 1;
                if (added >= maxDepartures) {
                    break;
                }
            }

            for (final StationDepartures stationDepartures : result.stationDepartures)
                Collections.sort(stationDepartures.departures, Departure.TIME_COMPARATOR);
            return result;
        } catch (InternalErrorException | BlockedException e) {
            return new QueryDeparturesResult(this.resultHeader, QueryDeparturesResult.Status.INVALID_STATION);
        } catch (IOException | RuntimeException e) {
            return new QueryDeparturesResult(this.resultHeader, QueryDeparturesResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    @Override
    public SuggestLocationsResult suggestLocations(
            CharSequence constraint,
            @Nullable Set<LocationType> types,
            int maxLocations)
            throws IOException {
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;

        boolean haveStation = false;
        boolean haveAddress = false;
        boolean havePOI = false;
        if (types == null || types.isEmpty()) {
            haveStation = true;
            haveAddress = true;
            havePOI = true;
        } else {
            for (LocationType type : types) {
                switch (type) {
                    case STATION: haveStation = true; break;
                    case ADDRESS: haveAddress = true; break;
                    case POI: havePOI = true; break;
                }
            }
        }

        final String locationTypes;
        if (haveStation && !haveAddress && !havePOI) {
            locationTypes = "HALTESTELLEN";
        } else {
            locationTypes = "ALL";
        }

        final HttpUrl url = this.locationsEndpoint.newBuilder()
                .addQueryParameter("suchbegriff", constraint.toString())
                .addQueryParameter("typ", locationTypes)
                .addQueryParameter("limit", Integer.toString(maxLocations))
                .build();
        String page = null;
        try {
            page = doRequest(url);

            final JSONArray locs = new JSONArray(page);
            final List<SuggestedLocation> locations = new ArrayList<>();
            for (int iLoc = 0; iLoc < locs.length(); iLoc++) {
                final JSONObject jsonL = locs.getJSONObject(iLoc);
                final Location loc = parseLocation(jsonL);
                if (loc != null) {
                    locations.add(new SuggestedLocation(loc, jsonL.optInt("weight", -iLoc)));
                }
            }
            return new SuggestLocationsResult(this.resultHeader, locations);
        } catch (IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new SuggestLocationsResult(this.resultHeader, SuggestLocationsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    @Override
    public QueryTripsResult queryTrips(Location from, @Nullable Location via, Location to, Date date, boolean dep,
            @Nullable TripOptions options) throws IOException {
        return doQueryTrips(from, via, to, date, dep,
                options != null ? options.products : null,
                options != null && options.flags != null && options.flags.contains(TripFlag.BIKE),
                options == null || options.minTransferTimeMinutes == null ? null
                        : getApplicableMinTransferTime(options.minTransferTimeMinutes),
                null);
    }

    @Override
    public QueryTripsResult queryMoreTrips(QueryTripsContext context, boolean later) throws IOException {
        final DbWebApiContext ctx = (DbWebApiContext) context;
        final String ctxToken;
        if (later && ctx.canQueryLater()) {
            ctxToken = ctx.laterContext;
        } else if (!later && ctx.canQueryEarlier()) {
            ctxToken = ctx.earlierContext;
        } else {
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
        }
        return doQueryTrips(ctx.from, ctx.via, ctx.to, ctx.date, ctx.dep, ctx.products, ctx.bike, ctx.minUmstiegszeit, ctxToken);
    }

    @Override
    public QueryTripsResult queryReloadTrip(final TripRef tripRef) throws IOException {
        return doQueryReloadTrip((DbWebTripRef) tripRef);
    }

    @Override
    public QueryJourneyResult queryJourney(final JourneyRef aJourneyRef) throws IOException {
        return doQueryJourney((DbWebJourneyRef) aJourneyRef);
    }

    private QueryJourneyResult doQueryJourney(final DbWebJourneyRef journeyRef) throws IOException {
        HttpUrl url = this.journeyEndpoint.newBuilder()
                .addQueryParameter("journeyId", journeyRef.journeyId)
                .addQueryParameter("poly", "true")
                .build();
        String page = null;
        try {
            page = doRequest(url);
            final JSONObject res = new JSONObject(page);
            Trip.Public leg = parseJourney(res, journeyRef);
            return new QueryJourneyResult(this.resultHeader, url.toString(), journeyRef, leg);
        } catch (InternalErrorException | BlockedException e) {
            final String code = parseErrorCode(e);
            if (code != null) {
                return new QueryJourneyResult(this.resultHeader, QueryJourneyResult.Status.NO_JOURNEY);
            }
            return new QueryJourneyResult(this.resultHeader, QueryJourneyResult.Status.SERVICE_DOWN);
        } catch (IOException | RuntimeException e) {
            return new QueryJourneyResult(this.resultHeader, QueryJourneyResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    @Override
    protected Set<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    private static class DbWebApiContext implements QueryTripsContext {
        private static final long serialVersionUID = 7740081144778106239L;

        public final Location from, via, to;
        public final Date date;
        public final boolean dep;
        public final Set<Product> products;
        public final boolean bike;
        public final Integer minUmstiegszeit;
        public final String laterContext, earlierContext;

        public DbWebApiContext(final Location from, final @Nullable Location via, final Location to, final Date date,
                   final boolean dep, final Set<Product> products, final boolean bike, final Integer minUmstiegszeit,
                   final String laterContext, final String earlierContext) {
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
            this.dep = dep;
            this.products = products;
            this.bike = bike;
            this.minUmstiegszeit = minUmstiegszeit;
            this.laterContext = laterContext;
            this.earlierContext = earlierContext;
        }

        @Override
        public boolean canQueryLater() {
            return laterContext != null;
        }

        @Override
        public boolean canQueryEarlier() {
            return earlierContext != null;
        }
    }

    final DbWebLinkSharing linkSharing;

    @Override
    public String getOpenLink(final Trip trip) throws IOException {
        final DbWebTripRef tripRef = (DbWebTripRef) trip.tripRef;
        return linkSharing.getOpenLink(trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public String getShareLink(final Trip trip) throws IOException {
        final DbWebTripRef tripRef = (DbWebTripRef) trip.tripRef;
        return linkSharing.getShareLink(httpClient, trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public TripShare shareTrip(final Trip trip) throws IOException {
        final DbWebTripRef tripRef = (DbWebTripRef) trip.tripRef;
        return linkSharing.shareTrip(httpClient, trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public QueryTripsResult loadSharedTrip(final TripShare tripShare) throws IOException {
        final DbWebTripShare dbWebTripShare = (DbWebTripShare) tripShare;
        final String recon = linkSharing.loadSharedTrip(httpClient, dbWebTripShare);
        final DbWebTripRef tripRef = new DbWebTripRef((DbWebTripRef) tripShare.simplifiedTripRef, recon);
        return queryReloadTrip(tripRef);
    }

    public static class DbWebTripShare extends TripShare {
        private static final long serialVersionUID = 7612659403554504831L;

        final String vbid;

        public DbWebTripShare(final TripRef tripRef, final String vbid) {
            super(tripRef);
            this.vbid = vbid;
        }

        public DbWebTripShare(
                final TripRef tripRef,
                final MessageUnpacker unpacker) throws IOException {
            super(tripRef, unpacker);
            this.vbid = unpacker.unpackString();
        }

        @Override
        public void packToMessage(final MessagePacker packer) throws IOException {
            super.packToMessage(packer);
            packer.packString(vbid);
        }
    }

    public static class DbWebLinkSharing {
        final HttpUrl saveConnectionEndpoint;
        final HttpUrl loadConnectionEndpoint;

        public DbWebLinkSharing() {
            this.saveConnectionEndpoint = WEB_API_BASE.newBuilder().addPathSegments("angebote/verbindung/teilen").build();
            this.loadConnectionEndpoint = WEB_API_BASE.newBuilder().addPathSegments("angebote/verbindung").build();
        }

        public String getOpenLink(
                final Trip trip,
                final TripRef simplifiedTripRef,
                final String recon) {
            final CtxRecon ctxRecon = new CtxRecon(recon);
            // this URL opens in browser, because DB Navigator does not deep link this pattern
            final String baseUrl = "https://www.bahn.de/buchung/fahrplan/suche";
            // this URL opens in DB Navigator if installed, because it deep links this pattern.
            // However DB Navigator cannot handle these parameters, so drops of to an embedded browser.
            // final String baseUrl = "https://www.bahn.de/buchung/start";
            return HttpUrl.parse(baseUrl).newBuilder()
                    .addQueryParameter("so", simplifiedTripRef.from.uniqueShortName())
                    .addQueryParameter("zo", simplifiedTripRef.to.uniqueShortName())
                    .addQueryParameter("soid", ctxRecon.startLocation)
                    .addQueryParameter("zoid", ctxRecon.endLocation)
                    .addQueryParameter("cbs", "true")
                    .addQueryParameter("hd", ISO_DATE_TIME_UTC_FORMAT.format(new Date()))
                    .addQueryParameter("gh", ctxRecon.shortRecon)
                    .build().toString().replaceFirst("\\?", "#");
        }

        public String getShareLink(
                final HttpClient httpClient,
                final Trip trip,
                final TripRef simplifiedTripRef,
                final String recon) throws IOException {
            final DbWebTripShare tripShare = shareTrip(httpClient, trip, simplifiedTripRef, recon);
            if (tripShare == null)
                return null;
            final String vbid = tripShare.vbid;
            if (vbid == null)
                return null;
            return String.format("https://www.bahn.de/buchung/start?vbid=%1$s", vbid);
        }

        public DbWebTripShare shareTrip(
                final HttpClient httpClient,
                final Trip trip,
                final TripRef simplifiedTripRef, String recon) throws IOException {
            final String request = "{\"hinfahrtDatum\":\"" + ISO_DATE_TIME_UTC_FORMAT.format(trip.getFirstDepartureTime()) + "\"," //
                    + "\"hinfahrtRecon\": \"" + recon + "\"," //
                    + "\"startOrt\": \"" + simplifiedTripRef.from.uniqueShortName() + "\"," //
                    + "\"zielOrt\": \"" + simplifiedTripRef.to.uniqueShortName() + "\"}";

            final HttpUrl url = this.saveConnectionEndpoint;

            String page = null;
            try {
                page = DbWebProvider.doRequest(httpClient, null, url, request, null);
                final JSONObject res = new JSONObject(page);
                final String vbid = res.optString("vbid");
                return new DbWebTripShare(simplifiedTripRef, vbid);
            } catch (InternalErrorException | BlockedException e) {
                return null;
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (final JSONException x) {
                throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
            }
        }

        public String loadSharedTrip(
                final HttpClient httpClient,
                final DbWebTripShare tripShare) throws IOException {
            final HttpUrl url = this.loadConnectionEndpoint.newBuilder()
                    .addEncodedPathSegment(tripShare.vbid)
                    .build();

            String page = null;
            try {
                page = DbWebProvider.doRequest(httpClient, null, url, null, null);
                final JSONObject res = new JSONObject(page);
                return res.optString("hinfahrtRecon");
            } catch (InternalErrorException | BlockedException e) {
                return null;
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (final JSONException x) {
                throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
            }
        }
    }

    public static class CtxRecon {
        public final String ctxRecon;
        public final Map<String, String> entries;
        public final String shortRecon;
        public final String startLocation;
        public final String endLocation;
        public final String tripId;

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
            final List<String> hkiEntries = parseArray("§", tripId);
            if (hkiEntries != null && hkiEntries.size() >= 1) {
                final List<String> firstLeg = parseArray("\\$", hkiEntries.get(0));
                final List<String> lastLeg = parseArray("\\$", hkiEntries.get(hkiEntries.size()-1));
                if (firstLeg != null && firstLeg.size() >= 2)
                    startLocation = firstLeg.get(1);
                if (lastLeg != null && lastLeg.size() >= 3)
                    endLocation = lastLeg.get(2);
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
    }

    public static List<String> parseArray(final String separator, final String value) {
        if (value == null)
            return null;
        return Arrays.asList(value.split(separator));
    }
}
