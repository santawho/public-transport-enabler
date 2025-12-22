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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
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
import de.schildbach.pte.dto.PTDate;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.dto.TripShare;
import de.schildbach.pte.exception.AbstractHttpException;
import de.schildbach.pte.exception.BlockedException;
import de.schildbach.pte.exception.InternalErrorException;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.TransferEvaluationProvider;
import de.schildbach.pte.util.ParserUtils;
import okhttp3.HttpUrl;

/**
 * Provider implementation for movas API of Deutsche Bahn (Germany).
 * 
 * @author Andreas Schildbach
 */
public abstract class DbMovasProvider extends DbProvider {
    public static class Fernverkehr extends DbMovasProvider {
        public Fernverkehr() {
            this(NetworkId.DBMOVAS);
        }

        protected Fernverkehr(final NetworkId networkId) {
            super(networkId);
        }

        @Override
        public Set<Product> defaultProducts() {
            return FERNVERKEHR_PRODUCTS;
        }
    }

    public static class Regio extends DbMovasProvider {
        public Regio() {
            this(NetworkId.DBREGIOMOVAS);
        }

        protected Regio(final NetworkId networkId) {
            super(networkId);
        }

        @Override
        public Set<Product> defaultProducts() {
            return REGIO_PRODUCTS;
        }
    }

    public static class DeutschlandTicket extends Regio {
        public DeutschlandTicket() {
            this(NetworkId.DBDEUTSCHLANDTICKETMOVAS);
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
            Capability.TRIP_LINKING,
            Capability.TRIP_DETAILS
        );

    private static final HttpUrl API_BASE = HttpUrl.parse(
            // "https://app.vendo.noncd.db.de/mob/"
            "https://app.services-bahn.de/mob/"
    );
    private final ResultHeader resultHeader;

    private static final Map<String, Product> PRODUCTS_MAP = new LinkedHashMap<String, Product>() {
        private static final long serialVersionUID = 8275557003393170940L;

        {
            put("HOCHGESCHWINDIGKEITSZUEGE", Product.HIGH_SPEED_TRAIN);
            put("INTERCITYUNDEUROCITYZUEGE", Product.HIGH_SPEED_TRAIN);
            put("INTERREGIOUNDSCHNELLZUEGE", Product.HIGH_SPEED_TRAIN);
            put("NAHVERKEHRSONSTIGEZUEGE", Product.REGIONAL_TRAIN);
            put("SBAHNEN", Product.SUBURBAN_TRAIN);
            put("BUSSE", Product.BUS);
            put("SCHIFFE", Product.FERRY);
            put("UBAHN", Product.SUBWAY);
            put("STRASSENBAHN", Product.TRAM);
            put("ANRUFPFLICHTIGEVERKEHRE", Product.ON_DEMAND);
            put("ERSATZVERKEHR", Product.REPLACEMENT_SERVICE);
        }
    };

    private static final Map<String, Product> SHORT_PRODUCTS_MAP = new LinkedHashMap<String, Product>() {
        private static final long serialVersionUID = -3576798382139244659L;

        {
            put("ICE", Product.HIGH_SPEED_TRAIN);
            put("IC_EC", Product.HIGH_SPEED_TRAIN);
            put("IC", Product.HIGH_SPEED_TRAIN);
            put("EC", Product.HIGH_SPEED_TRAIN);
            put("IR", Product.HIGH_SPEED_TRAIN);
            put("RB", Product.REGIONAL_TRAIN);
            put("RE", Product.REGIONAL_TRAIN);
            put("SBAHN", Product.SUBURBAN_TRAIN);
            put("BUS", Product.BUS);
            put("SCHIFF", Product.FERRY);
            put("UBAHN", Product.SUBWAY);
            put("STR", Product.TRAM);
            put("ANRUFPFLICHTIGEVERKEHRE", Product.ON_DEMAND);
            put("ERSATZVERKEHR", Product.REPLACEMENT_SERVICE);
        }
    };

    private static final Map<String, LocationType> ID_LOCATION_TYPE_MAP = new HashMap<String, LocationType>() {
        private static final long serialVersionUID = -1200641123866654217L;

        {
            put("1", LocationType.STATION);
            put("4", LocationType.POI);
            put("2", LocationType.ADDRESS);
        }
    };

    private static final Map<LocationType, String> LOCATION_TYPE_MAP = new HashMap<LocationType, String>() {
        private static final long serialVersionUID = -2827675532432392114L;

        {
            put(LocationType.ANY, "ALL");
            put(LocationType.STATION, "ST");
            put(LocationType.POI, "POI");
            put(LocationType.ADDRESS, "ADR");
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
    private final BahnvorhersageProvider bahnvorhersageProvider;

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

    protected DbMovasProvider(final NetworkId networkId) {
        super(networkId);
        this.departureEndpoint = API_BASE.newBuilder().addPathSegments("bahnhofstafel/abfahrt").build();
        this.tripEndpoint = API_BASE.newBuilder().addPathSegments("angebote/fahrplan").build();
        this.tripReconEndpoint = API_BASE.newBuilder().addPathSegments("angebote/recon").build();
        this.journeyEndpoint = API_BASE.newBuilder().addPathSegments("zuglauf").build();
        this.locationsEndpoint = API_BASE.newBuilder().addPathSegments("location/search").build();
        this.nearbyEndpoint = API_BASE.newBuilder().addPathSegments("location/nearby").build();
        this.resultHeader = new ResultHeader(network, "movas");

        this.linkSharing = new DbWebProvider.DbWebLinkSharing();
        this.bahnvorhersageProvider = new BahnvorhersageProvider();
    }

    @Override
    public TransferEvaluationProvider getTransferEvaluationProvider() {
        return bahnvorhersageProvider;
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new DbTripRef(network, unpacker);
    }

    @Override
    public TripShare unpackTripShareFromMessage(final MessageUnpacker unpacker) throws IOException {
        final TripRef tripRef = unpackTripRefFromMessage(unpacker);
        return new DbWebProvider.DbWebTripShare(tripRef, unpacker);
    }

    @Override
    protected String[] getValidUserInterfaceLanguages() {
        return new String[] { "en", "de", "fr", "es", "dk", "cz", "it", "nl", "pl" };
    }

    protected boolean isModeDeutschlandTicket() {
        return false;
    }

    private String doRequest(final HttpUrl url, final String body, final String contentType) throws IOException {
        // DB API requires these headers
        // Content-Type must be exactly as passed below,
        // passing it to httpClient.get would add charset suffix
        httpClient.setHeader("X-Correlation-ID", UUID.randomUUID() + "_" + UUID.randomUUID());
        httpClient.setHeader("Accept", contentType);
        httpClient.setHeader("Content-Type", contentType);
        if (this.userInterfaceLanguage != null)
            httpClient.setHeader("Accept-Language", this.userInterfaceLanguage);
        final String page = httpClient.get(url, body, null).toString();
        return page;
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

    private final DateTimeFormatter ISO_DATE_TIME_WITH_OFFSET_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .withZone(timeZone.toZoneId());

    private String formatIso8601WOffset(final Date time) {
        if (time == null)
            return null;
        return ISO_DATE_TIME_WITH_OFFSET_FORMAT.format(
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(time.getTime()), timeZone.toZoneId()));
    }


    private PTDate parseIso8601WOffset(final String time) {
        if (time == null)
            return null;
        final OffsetDateTime offsetDateTime = OffsetDateTime.parse(time, ISO_DATE_TIME_WITH_OFFSET_FORMAT);
        return new PTDate(offsetDateTime.toInstant().toEpochMilli(),
                offsetDateTime.getOffset().getTotalSeconds() * 1000);
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
        for (int i = 0; i < products.length(); i++) {
            final Product p = PRODUCTS_MAP.get(products.optString(i, null));
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private String formatLocationTypes(Set<LocationType> types) {
        if (types == null || types.contains(LocationType.ANY))
            return "\"" + LOCATION_TYPE_MAP.get(LocationType.ANY) + "\"";
        return types.stream()
                .map(t -> LOCATION_TYPE_MAP.get(t))
                .filter(t -> t != null)
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(", "));
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
        final String lidStr = loc.optString("locationId", null);
        final Location lid = parseLid(lidStr);
        final String id = lid.type == LocationType.STATION
                ? Optional.ofNullable(loc.optString("evaNr", null)).orElse(lid.id)
                : lidStr;
        Point coord = null;
        JSONObject pos = loc.optJSONObject("coordinates");
        if (pos == null) {
            pos = loc.optJSONObject("position");
        }
        if (pos != null) {
            coord = Point.fromDouble(pos.optDouble("latitude"), pos.optDouble("longitude"));
        } else {
            coord = lid.coord;
        }
        final String bahnhofsInfoId = loc.optString("stationId", null);

        return createLocation(
                lid.type,
                id,
                coord,
                loc.optString("name", null),
                parseProducts(loc.optJSONArray("products")),
                bahnhofsInfoId);
    }

    private Location parseDirection(final JSONObject dep) {
        final String richtung = dep.optString("richtung", null);
        if (richtung == null)
            return null;
        return createLocation(LocationType.STATION, null, null, richtung, null, null);
    }

    private List<Location> parseLocations(final JSONArray locs) throws JSONException {
        final List<Location> locations = new ArrayList<>();
        for (int iLoc = 0; iLoc < locs.length(); iLoc++) {
            final Location l = parseLocation(locs.getJSONObject(iLoc));
            if (l != null) {
                locations.add(l);
            }
        }
        return locations;
    }

    private void parseMessages(final JSONArray msgs, final List<String> messages, final String prefix, final Integer minPriority)
            throws JSONException {
        if (msgs == null)
            return;
        for (int iMsg = 0; iMsg < msgs.length(); iMsg++) {
            final JSONObject msgObj = msgs.getJSONObject(iMsg);
            final String title = msgObj.optString("ueberschrift", null);
            final String text = msgObj.optString("text", null);
            final String url = msgObj.optString("url", null);
            if (text != null && (minPriority == null || msgObj.optInt("priority", 0) >= minPriority)) {
                String msg = text;
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

    private String parseJourneyMessages(final JSONObject jny, final String operatorName) throws JSONException {
        final List<String> messages = new ArrayList<>();
        parseMessages(jny.optJSONArray("echtzeitNotizen"), messages, null, null);
        parseMessages(jny.optJSONArray("himNotizen"), messages, null, null);
        // show very important static messages (e.g. on demand tel)
        if (operatorName != null)
            messages.add("&#8226; " + operatorName);
        parseMessages(jny.optJSONArray("attributNotizen"), messages, this.messagesAsSimpleHtml ? "&#8226; " : null, 100);
        return messages.isEmpty() ? null : join(this.messagesAsSimpleHtml ? "<br>" : " - ", messages);
    }

    // replace with String.join() at some point
    private static String join(final CharSequence delimiter, final Iterable<? extends CharSequence> elements) {
        final StringJoiner joiner = new StringJoiner(delimiter);
        elements.forEach(joiner::add);
        return joiner.toString();
    }

    private static Set<String> bicycleAttributes = new HashSet<String>() {
        private static final long serialVersionUID = 3738440155820969289L;

        {
            add("FA");
            add("FB");
            add("FR");
            add("FS");
        }
    };

    private static Set<String> wheelChairAttributes = new HashSet<String>() {
        private static final long serialVersionUID = 3738440155820969289L;

        {
            add("RG");
        }
    };

    private Line parseLine(final JSONObject jny) throws JSONException {
        // TODO attrs, messages
        Product product = SHORT_PRODUCTS_MAP.get(jny.optString("produktGattung", null));
        if (product == null)
            product = SHORT_PRODUCTS_MAP.get(jny.optString("produktGattungen", null));
        final String name = Optional.ofNullable(jny.optString("langtext", null)).orElse(jny.optString("mitteltext", null));
        String shortName = jny.optString("mitteltext", null);
        if (shortName != null && (product == Product.BUS || product == Product.TRAM)) {
            shortName = shortName.replaceAll("^[A-Za-z]+ ", "");
        }
        String operator = null;
        final Set<Line.Attr> lineAttrs = new HashSet<>();
        final JSONArray attributNotizen = jny.optJSONArray("attributNotizen");
        if (attributNotizen != null) {
            for (int iAttr = 0; iAttr < attributNotizen.length(); ++iAttr) {
                JSONObject attr = attributNotizen.getJSONObject(iAttr);
                final String key = attr.getString("key");
                if ("OP".equals(key)) {
                    operator = attr.getString("text");
                } else if (wheelChairAttributes.contains(key)) {
                    lineAttrs.add(Line.Attr.WHEEL_CHAIR_ACCESS);
                } else if (bicycleAttributes.contains(key)) {
                    lineAttrs.add(Line.Attr.BICYCLE_CARRIAGE);
                }
            }
        }
        return new Line(
                jny.optString("zuglaufId", null),
                operator,
                product,
                shortName,
                name,
                lineStyle(styles, operator, product, name),
                lineAttrs,
                null);
    }

    private boolean parseCancelled(JSONObject stop) throws JSONException {
        final boolean cancelled = stop.optBoolean("cancelled", false);
        if (cancelled)
            return true;
        final JSONObject ersatzhaltNotiz = stop.optJSONObject("ersatzhaltNotiz");
        if (ersatzhaltNotiz != null) {
            String typ = ersatzhaltNotiz.getString("typ");
            if ("GECANCELT".equals(typ)) {
                return true;
            }
        }
        final JSONArray notices = stop.optJSONArray("echtzeitNotizen");
        if (notices != null) {
            for (int iNotice = 0; iNotice < notices.length(); iNotice++) {
                final JSONObject notice = notices.optJSONObject(iNotice);
                if (notice != null) {
                    final String text = notice.optString("text", null);
                    if ("Halt entfällt".equals(text) || "Stop cancelled".equals(text)) {
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
        return new Stop(
                Optional.ofNullable(parseLocation(stop.optJSONObject("ort"))).orElse(fallbackLocation),
                parseIso8601WOffset(stop.optString("ankunftsDatum", null)),
                parseIso8601WOffset(stop.optString("ezAnkunftsDatum", null)),
                gleis, ezGleis, cancelled,
                parseIso8601WOffset(stop.optString("abgangsDatum", null)),
                parseIso8601WOffset(stop.optString("ezAbgangsDatum", null)),
                gleis, ezGleis, cancelled);
    }

    private List<Stop> parseStops(final JSONArray stops) throws JSONException {
        if (stops == null)
            return null;
        List<Stop> out = new LinkedList<>();
        for (int i = 0; i < stops.length(); i++) {
            out.add(parseStop(stops.getJSONObject(i), null));
        }
        return out;
    }

    private int[] parseCapacity(final JSONObject e) throws JSONException {
        final JSONArray auslastungen = e.optJSONArray("auslastungsInfos");
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

    private Trip.Public parseJourney(final JSONObject journey, final DbJourneyRef journeyRef) throws JSONException {
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
        String operator = journeyRef.line != null ? journeyRef.line.network : null;
        if (operator == null) {
            final JSONArray attributNotizen = journey.optJSONArray("attributNotizen");
            if (attributNotizen != null) {
                for (int iAttr = 0; iAttr < attributNotizen.length(); ++iAttr) {
                    JSONObject attr = attributNotizen.getJSONObject(iAttr);
                    if ("OP".equals(attr.get("key"))) {
                        operator = attr.getString("text");
                        break;
                    }
                }
            }
        }
        final String message = parseJourneyMessages(journey, operator);
        return new Trip.Public(
                journeyRef.line,
                arrivalStop.location,
                departureStop, arrivalStop, intermediateStops,
                null,
                message,
                new DbJourneyRef(journeyRef.journeyId, null, journeyRef.line));
    }

    private Trip.Leg parseLeg(final JSONObject abschnitt, final String journeyRequestId) throws JSONException {
        Stop departureStop = null;
        Stop arrivalStop = null;
        final String typ = abschnitt.optString("typ", null);
        final boolean isPublicTransportLeg = "FAHRZEUG".equals(typ);
        final List<Stop> intermediateStops = parseStops(abschnitt.optJSONArray("halte"));
        if (intermediateStops != null && intermediateStops.size() >= 2 && isPublicTransportLeg) {
            final int size = intermediateStops.size();
            departureStop = intermediateStops.get(0);
            arrivalStop = intermediateStops.get(size - 1);
            intermediateStops.remove(size - 1);
            intermediateStops.remove(0);
        } else {
            departureStop = parseStop(abschnitt, parseLocation(abschnitt.optJSONObject("abgangsOrt")));
            arrivalStop = parseStop(abschnitt, parseLocation(abschnitt.optJSONObject("ankunftsOrt")));
        }
        if (isPublicTransportLeg) {
            final Line line = parseLine(abschnitt);
            final Location destination = parseDirection(abschnitt);
            final String message = parseJourneyMessages(abschnitt, null);
            final String journeyId = abschnitt.optString("zuglaufId", null);
            return new Trip.Public(line, destination, departureStop, arrivalStop, intermediateStops, null, message,
                    journeyId == null ? null : new DbJourneyRef(journeyId, journeyRequestId, line));
        } else {
            final int dist = abschnitt.optInt("distanz");
            if (dist == 0 && departureStop.location.id.equals(arrivalStop.location.id)) {
                // Movas inserts a dummy walk -> skip it
                return null;
            }
            return new Trip.Individual(
                    "TRANSFER".equals(typ) ? Trip.Individual.Type.TRANSFER : Trip.Individual.Type.WALK,
                    departureStop.location,
                    departureStop.getDepartureTime(),
                    arrivalStop.location,
                    arrivalStop.getArrivalTime(),
                    null, dist);
        }
    }

    private List<Fare> parseFares(final JSONObject verbindungParent) {
        List<Fare> fares = new ArrayList<>();
        final Optional<JSONObject> ab = Optional.ofNullable(verbindungParent.optJSONObject("angebote"))
                .map(angebote -> angebote.optJSONObject("preise"))
                .map(preise -> preise.optJSONObject("gesamt"))
                .map(gesamt -> gesamt.optJSONObject("ab"));
        if (ab.isPresent()) {
            fares.add(new Fare(
                    "de".equals(this.userInterfaceLanguage) ? "ab" : "from",
                    Fare.Type.ADULT,
                    ParserUtils.getCurrency(ab.get().optString("waehrung", "EUR")),
                    (float) ab.get().optDouble("betrag"),
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

    private Trip parseTrip(
            final JSONObject verbindungParent,
            final Location from, final Location via, final Location to,
            final boolean limitToDticket, final boolean hasDticket
    ) throws JSONException {
        final JSONObject verbindung = verbindungParent.getJSONObject("verbindung");
        final CtxRecon ctxRecon = new CtxRecon(verbindung.optString("kontext"));
        final JSONArray abschnitte = verbindung.getJSONArray("verbindungsAbschnitte");
        final List<Trip.Leg> legs = new ArrayList<>();
        Location tripFrom = null;
        Location tripTo = null;

        final Iterator<String> itJourneyRequestIds = ctxRecon.journeyRequestIds.iterator();
        Trip.Public prevPublicLegWithArrivalSamePlatform = null;
        for (int iLeg = 0; iLeg < abschnitte.length(); iLeg++) {
            final JSONObject abschnitt = abschnitte.getJSONObject(iLeg);
            final Trip.Leg leg = parseLeg(abschnitt, itJourneyRequestIds.next());
            if (leg == null) continue;
            if (leg instanceof Trip.Public) {
                final Trip.Public publicLeg = (Trip.Public) leg;
                if (prevPublicLegWithArrivalSamePlatform != null) {
                    final Position arrivalPosition = prevPublicLegWithArrivalSamePlatform.arrivalStop.getArrivalPosition();
                    final Position departurePosition = publicLeg.departureStop.getArrivalPosition();
                    if (arrivalPosition != null && departurePosition != null) {
                        arrivalPosition.setSamePlatformAs(departurePosition);
                        departurePosition.setSamePlatformAs(arrivalPosition);
                    }
                }
                if (abschnitt.optBoolean("weiterfahrtAmGleichenBahnsteig")) {
                    prevPublicLegWithArrivalSamePlatform = publicLeg;
                } else {
                    prevPublicLegWithArrivalSamePlatform = null;
                }
            }
            legs.add(leg);
            if (iLeg == 0) {
                tripFrom = leg.departure;
            }
            if (iLeg == abschnitte.length() - 1) {
                tripTo = leg.arrival;
            }
        }
        final List<Fare> fares = parseFares(verbindungParent);
        final int transfers = verbindung.optInt("umstiegeAnzahl", -1);
        final int[] capacity = parseCapacity(verbindung);
        return new Trip(
                new Date(),
                ctxRecon.tripId,
                new DbTripRef(network, ctxRecon.ctxRecon, from, via, to, limitToDticket, hasDticket),
                tripFrom,
                tripTo,
                legs,
                fares,
                capacity,
                transfers == -1 ? null : transfers);
    }

    private QueryTripsResult doQueryTrips(Location from, @Nullable Location via, Location to, Date time, boolean dep,
            @Nullable Set<Product> products, final boolean bike, @Nullable final Integer minUmstiegsdauer,
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
                useProducts = new HashSet<>(products != null ? products : Product.ALL_INCLUDING_HIGHSPEED);
                useProducts.add(Product.HIGH_SPEED_TRAIN);
            }
        } else {
            hasDticket = false;
            limitToDticket = false;
            useProducts = products;
        }
        final String productsStr = "\"verkehrsmittel\":[" + formatProducts(useProducts) + "]";
        final String viaLocations = via != null
                ? "\"viaLocations\":[{\"locationId\": \"" + formatLid(via) + "\"," + productsStr + "}],"
                : "";
        final String bikeStr = bike ? "\"fahrradmitnahme\":true," : "";
        final String minUmstiegsdauerStr = minUmstiegsdauer != null ? "\"minUmstiegsdauer\":" + minUmstiegsdauer + "," : "";
        final String ctxStr = context != null ? "\"context\": \"" + context + "\"," : "";
        final String request = "{\"autonomeReservierung\":false,\"einstiegsTypList\":[\"STANDARD\"],\"klasse\":\"KLASSE_2\"," //
                + "\"reiseHin\":{\"wunsch\":{\"abgangsLocationId\": \"" + formatLid(from) + "\"," //
                + productsStr + "," //
                + viaLocations //
                + bikeStr //
                + minUmstiegsdauerStr //
                + ctxStr //
                + "\"zeitWunsch\":{\"reiseDatum\":\"" + formatIso8601WOffset(time) + "\",\"zeitPunktArt\":\"" + deparr + "\"}," //
                + "\"zielLocationId\": \"" + formatLid(to) + "\"}}," //
                + "\"reisendenProfil\":{\"reisende\":[{\"ermaessigungen\":[\"KEINE_ERMAESSIGUNG KLASSENLOS\"],\"reisendenTyp\":\"ERWACHSENER\"}]}," //
                + "\"fahrverguenstigungen\":{\"deutschlandTicketVorhanden\":" + hasDticket + ",\"nurDeutschlandTicketVerbindungen\":" + limitToDticket + "},"
                + "\"reservierungsKontingenteVorhanden\":false}";

        final HttpUrl url = this.tripEndpoint;
        final String contentType = "application/x.db.vendo.mob.verbindungssuche.v9+json";

        String page = null;
        try {
            page = doRequest(url, request, contentType);
            final JSONObject res = new JSONObject(page);
            final JSONArray verbindungen = res.getJSONArray("verbindungen");
            final List<Trip> trips = new ArrayList<>();

            for (int iTrip = 0; iTrip < verbindungen.length(); iTrip++) {
                final JSONObject verbindungParent = verbindungen.getJSONObject(iTrip);
                final Trip trip = parseTrip(verbindungParent, from, via, to, limitToDticket, hasDticket);
                trips.add(trip);
            }
            if (trips.isEmpty()) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            final DbMovasContext ctx = new DbMovasContext(from, via, to, time, dep, products, bike, minUmstiegsdauer,
                    res.optString("spaeterContext", null), res.optString("frueherContext", null));
            return new QueryTripsResult(this.resultHeader, null, from, via, to, ctx, trips);
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

    private QueryTripsResult doQueryReloadTrip(final DbTripRef tripRef) throws IOException {
        final String request = "{\"autonomeReservierung\":false,\"einstiegsTypList\":[\"STANDARD\"],\"klasse\":\"KLASSE_2\"," //
                + "\"verbindungHin\":{\"kontext\":\"" + tripRef.ctxRecon + "\"},"
                + "\"reisendenProfil\":{\"reisende\":[{\"ermaessigungen\":[\"KEINE_ERMAESSIGUNG KLASSENLOS\"],\"reisendenTyp\":\"ERWACHSENER\"}]}," //
                + "\"fahrverguenstigungen\":{\"deutschlandTicketVorhanden\":" + tripRef.hasDticket + ",\"nurDeutschlandTicketVerbindungen\":" + tripRef.limitToDticket + "},"
                + "\"reservierungsKontingenteVorhanden\":false}";

        final HttpUrl url = this.tripReconEndpoint;
        final String contentType = "application/x.db.vendo.mob.verbindungssuche.v8+json";

        String page = null;
        try {
            page = doRequest(url, request, contentType);
            final JSONObject res = new JSONObject(page);
            if (res.isNull("verbindung")) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            final Trip trip = parseTrip(res, tripRef.from, tripRef.via, tripRef.to, tripRef.limitToDticket, tripRef.hasDticket);
            return new QueryTripsResult(this.resultHeader, null, tripRef.from, tripRef.via, tripRef.to, null, Collections.singletonList(trip));
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
        final String request = "{\"area\":" //
                + "{\"coordinates\":{\"longitude\":" + location.coord.getLonAsDouble() + ",\"latitude\":"
                + location.coord.getLatAsDouble() + "}," //
                + "\"radius\":" + maxDistance + "}," //
                + "\"maxResults\":" + maxLocations + "," //
                + "\"products\":[\"ALL\"]}";

        final HttpUrl url = this.nearbyEndpoint;
        final String contentType = "application/x.db.vendo.mob.location.v3+json";
        String page = null;
        try {
            page = doRequest(url, request, contentType);
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

        final String request = "{\"anfragezeit\": \"" + formatTime(c) + "\"," //
                + "\"datum\": \"" + formatDate(c) + "\"," //
                + "\"ursprungsBahnhofId\": \"" + formatLid(stationId) + "\"," //
                + "\"verkehrsmittel\":[\"ALL\"]}";

        final HttpUrl url = this.departureEndpoint;
        final String contentType = "application/x.db.vendo.mob.bahnhofstafeln.v2+json";

        String page = null;
        try {
            page = doRequest(url, request, contentType);
            final QueryDeparturesResult result = new QueryDeparturesResult(this.resultHeader);
            final JSONObject head = new JSONObject(page);
            final JSONArray deps = head.getJSONArray("bahnhofstafelAbfahrtPositionen");
            int added = 0;
            for (int iDep = 0; iDep < deps.length(); iDep++) {
                final JSONObject dep = deps.getJSONObject(iDep);
                final boolean cancelled = parseCancelled(dep);
//                if (cancelled) {
//                    continue;
//                }
                final Location location = parseLocation(dep.optJSONObject("abfrageOrt"));
                if (!equivs && !stationId.equals(location.id)) {
                    continue;
                }
                StationDepartures stationDepartures = result.findStationDepartures(location.id);
                if (stationDepartures == null) {
                    stationDepartures = new StationDepartures(location, new ArrayList<Departure>(8), null);
                    result.stationDepartures.add(stationDepartures);
                }

                final String journeyId = dep.optString("zuglaufId", null);
                final Line line = parseLine(dep);
                final Stop stop = parseStop(dep, location);
                final Departure departure = new Departure(
                        stop.plannedDepartureTime,
                        stop.predictedDepartureTime,
                        line,
                        stop.plannedDeparturePosition, stop.predictedDeparturePosition,
                        parseDirection(dep),
                        cancelled,
                        null,
                        parseJourneyMessages(dep, null),
                        journeyId == null ? null : new DbJourneyRef(journeyId, null, line));

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
    public SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types,
            int maxLocations)
            throws IOException {
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;

        final String request = "{\"searchTerm\": \"" + constraint + "\"," //
                + "\"locationTypes\":[" + formatLocationTypes(types) + "]," //
                + "\"maxResults\":" + maxLocations + "}";

        final HttpUrl url = this.locationsEndpoint;
        final String contentType = "application/x.db.vendo.mob.location.v3+json";
        String page = null;
        try {
            page = doRequest(url, request, contentType);

            final JSONArray locs = new JSONArray(page);
            final List<SuggestedLocation> locations = new ArrayList<>();
            for (int i = 0; i < locs.length(); i++) {
                final JSONObject jsonL = locs.getJSONObject(i);
                final Location l = parseLocation(jsonL);
                if (l != null) {
                    locations.add(new SuggestedLocation(l, jsonL.optInt("weight", -i)));
                }
            }
            return new SuggestLocationsResult(this.resultHeader, locations);
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
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
        final DbMovasContext ctx = (DbMovasContext) context;
        final String ctxToken;
        if (later && ctx.canQueryLater()) {
            ctxToken = ctx.laterContext;
        } else if (!later && ctx.canQueryEarlier()) {
            ctxToken = ctx.earlierContext;
        } else {
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
        }
        return doQueryTrips(ctx.from, ctx.via, ctx.to, ctx.date, ctx.dep, ctx.products, ctx.bike, ctx.minUmstiegsdauer, ctxToken);
    }

    @Override
    public QueryTripsResult queryReloadTrip(final TripRef tripRef) throws IOException {
        return doQueryReloadTrip((DbTripRef) tripRef);
    }

    @Override
    public QueryJourneyResult queryJourney(final JourneyRef aJourneyRef) throws IOException {
        return doQueryJourney((DbJourneyRef) aJourneyRef);
    }

    private QueryJourneyResult doQueryJourney(final DbJourneyRef journeyRef) throws IOException {
        HttpUrl url = this.journeyEndpoint.newBuilder()
                .addPathSegment(journeyRef.journeyId)
                .addQueryParameter("poly", "true")
                .build();
        final String contentType = "application/x.db.vendo.mob.zuglauf.v2+json";
        String page = null;
        try {
            page = doRequest(url, null, contentType);
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

    private static class DbMovasContext implements QueryTripsContext {
        private static final long serialVersionUID = -2477885850367572930L;

        public final Location from, via, to;
        public final Date date;
        public final boolean dep;
        public final Set<Product> products;
        public final boolean bike;
        public final Integer minUmstiegsdauer;
        public final String laterContext, earlierContext;

        public DbMovasContext(final Location from, final @Nullable Location via, final Location to, final Date date,
                final boolean dep, final Set<Product> products, final boolean bike, final Integer minUmstiegsdauer,
                final String laterContext, final String earlierContext) {
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
            this.dep = dep;
            this.products = products;
            this.bike = bike;
            this.minUmstiegsdauer = minUmstiegsdauer;
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

    final DbWebProvider.DbWebLinkSharing linkSharing;

    @Override
    public String getOpenLink(final Trip trip) throws IOException {
        final DbTripRef tripRef = (DbTripRef) trip.tripRef;
        return linkSharing.getOpenLink(trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public String getShareLink(final Trip trip) throws IOException {
        final DbTripRef tripRef = (DbTripRef) trip.tripRef;
        return linkSharing.getShareLink(httpClient, trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public TripShare shareTrip(final Trip trip) throws IOException {
        final DbTripRef tripRef = (DbTripRef) trip.tripRef;
        return linkSharing.shareTrip(httpClient, trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public QueryTripsResult loadSharedTrip(final TripShare tripShare) throws IOException {
        final DbWebProvider.DbWebTripShare dbWebTripShare = (DbWebProvider.DbWebTripShare) tripShare;
        final String recon = linkSharing.loadSharedTrip(httpClient, dbWebTripShare);
        final DbTripRef tripRef = new DbTripRef((DbTripRef) tripShare.simplifiedTripRef, recon);
        return queryReloadTrip(tripRef);
    }
}
