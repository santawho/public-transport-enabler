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
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
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
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Destination;
import de.schildbach.pte.dto.Fare;
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
import de.schildbach.pte.util.GeoUtils;
import de.schildbach.pte.util.HttpClient;
import de.schildbach.pte.util.ParserUtils;
import okhttp3.HttpUrl;

/**
 * Provider implementation for Web API of Deutsche Bahn (Germany).
 */
public abstract class DbWebProvider extends DbProvider {
    public static class Fernverkehr extends DbWebProvider {
        public Fernverkehr() {
            this(NetworkId.DBWEB);
        }

        protected Fernverkehr(final NetworkId networkId) {
            super(networkId);
        }

        @Override
        public Set<Product> defaultProducts() {
            return FERNVERKEHR_PRODUCTS;
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
            return REGIO_PRODUCTS;
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
            Capability.DIRECT_OPTION,
            Capability.BIKE_OPTION,
            Capability.TRIP_SHARING,
            Capability.TRIP_LINKING,
            Capability.TRIP_DETAILS
        );

    private static final String BASE_URL = "https://www.bahn.de";
    private static final HttpUrl WEB_API_BASE = HttpUrl.parse(BASE_URL + "/web/api/");
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
            put("ERSATZVERKEHR", Product.REPLACEMENT_SERVICE);
            put("UNKNOWN", Product.UNKNOWN);
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

        httpClient.setReferer(BASE_URL);
        httpClient.setOrigin(BASE_URL);

        // somehow the DB web server waits for 10 seconds if this was enabled
        // httpClient.setCompressionDeflate(false);
        // ... now still enabled, because we changed the order of offered compression types
        // the DB server only fails with this order: gzip, deflate, br, zstd
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new DbTripRef(network, unpacker);
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
            final DbProvider dbProvider,
            final String userInterfaceLanguage,
            final HttpUrl url,
            final String body,
            final String contentType,
            final long callTimeoutSecs) throws IOException {
        final HttpClient httpClient = dbProvider.getHttpClient();
        // DB API requires these headers
        // Content-Type must be exactly as passed below,
        // passing it to httpClient.get would add charset suffix
        final String cType = contentType != null ? contentType : "application/json";
        httpClient.setHeader("X-Correlation-ID", UUID.randomUUID() + "_" + UUID.randomUUID());
        httpClient.setHeader("Accept", cType);
        if (body != null) httpClient.setHeader("Content-Type", cType);
        if (userInterfaceLanguage != null)
            httpClient.setHeader("Accept-Language", userInterfaceLanguage);
        final String page = httpClient.get(url, body, null, callTimeoutSecs).toString();
        return page;
    }

    private static String doRequest(
            final DbProvider dbProvider,
            final String userInterfaceLanguage,
            final HttpUrl url,
            final String body,
            final String contentType) throws IOException {
        return doRequest(dbProvider, userInterfaceLanguage, url, body, contentType, 0);
    }

    private String doRequest(final HttpUrl url, final String body, final long callTimeoutSecs) throws IOException {
        return doRequest(this, userInterfaceLanguage, url, body, null, callTimeoutSecs);
    }

    private String doRequest(final HttpUrl url, final String body) throws IOException {
        return doRequest(url, body, 0);
    }

    private String doRequest(final HttpUrl url) throws IOException {
        return doRequest(url, null, 0);
    }

    private String doRequest(final HttpUrl url, final long callTimeoutSecs) throws IOException {
        return doRequest(url, null, callTimeoutSecs);
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
        ISO_DATE_TIME_NO_OFFSET_FORMAT.setTimeZone(TimeZone.getTimeZone("Europe/Berlin"));
        ISO_DATE_TIME_UTC_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private String formatIso8601NoOffset(final Date time) {
        if (time == null)
            return null;
        return ISO_DATE_TIME_NO_OFFSET_FORMAT.format(time);
    }

    private PTDate parseIso8601NoOffset(final String time) {
        if (time == null)
            return null;
        try {
            return PTDate.withUnknownLocationSpecificOffset(ISO_DATE_TIME_NO_OFFSET_FORMAT.parse(time).getTime());
        } catch (final ParseException x) {
            throw new RuntimeException(x);
        }
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
            final String prodStr = products.optString(iProd, null);
            final Product prod = PRODUCTS_MAP.get(prodStr);
            if (prod != null) {
                out.add(prod);
            } else {
                throw new RuntimeException(prodStr);
            }
        }
        return out;
    }

    private Location parseLocation(final JSONObject loc) {
        if (loc == null)
            return null;
        final String lidStr = loc.optString("id", null);
        final Location lid = parseLid(lidStr);
        final String id;
        final String bahnhofsInfoId;
        if (lid.type == LocationType.STATION) {
            id = Optional.ofNullable(loc.optString("extId", null)).orElse(lid.id);
            bahnhofsInfoId = Optional.ofNullable(loc.optString("bahnhofsInfoId", null)).orElse(id);
        } else {
            id = lidStr;
            bahnhofsInfoId = null;
        }
        Point coord = lid.coord;
        final double latitude = loc.optDouble("lat");
        if (coord == null && !Double.isNaN(latitude)) {
            coord = Point.fromDouble(latitude, loc.optDouble("lon"));
        }

        return createLocation(
                lid.type,
                id,
                coord,
                loc.optString("name", null),
                parseProducts(loc.optJSONArray("products")),
                bahnhofsInfoId);
    }

    private Destination parseDirection(final JSONObject verkehrsmittel, final LocationType type) {
        final String richtung = verkehrsmittel.optString("richtung", null);
        if (richtung == null)
            return null;
        return new Destination(createLocation(type, null, null, richtung, null, null));
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
        final int numImportant = messages.size();
        if (this.messagesAsSimpleHtml)
            messages.add(LESS_IMPORTANT_HTML_SPLIT_MARKER);
        parseMessages(jny.optJSONArray("himMeldungen"), messages, null, defaultTeilstreckenHinweis);
        if (operatorName != null)
            messages.add("&#8226; " + operatorName);
        if (zugattribute != null)
            parseMessages(zugattribute, messages, this.messagesAsSimpleHtml ? "&#8226; " : null, defaultTeilstreckenHinweis);
        if (messages.isEmpty())
            return null;
        final String s = join(this.messagesAsSimpleHtml ? "<br>" : " - ", messages);
        if (numImportant == 0)
            return s.replace(LESS_IMPORTANT_HTML_SPLIT_MARKER + "<br>", LESS_IMPORTANT_HTML_SPLIT_MARKER);
        return s.replace("<br>" + LESS_IMPORTANT_HTML_SPLIT_MARKER, LESS_IMPORTANT_HTML_SPLIT_MARKER);
    }

    // replace with String.join() at some point
    private static String join(final CharSequence delimiter, final Iterable<? extends CharSequence> elements) {
        final StringJoiner joiner = new StringJoiner(delimiter);
        elements.forEach(joiner::add);
        return joiner.toString();
    }

    static class x {
        x(final int i) {}
    }

    private static final Set<String> bicycleAttributes = new HashSet<String>() {
        private static final long serialVersionUID = 3738440155820969289L;

        {
            add("FA");
            add("FB");
            add("FR");
            add("FS");
        }
    };

    private static final Set<String> wheelChairAttributes = new HashSet<String>() {
        private static final long serialVersionUID = 3738440155820969289L;

        {
            add("RG");
        }
    };

    private Line parseLine(final JSONObject verkehrsmittel, final String produktGattung) throws JSONException {
        // TODO attrs, messages
        final Product product = PRODUCTS_MAP.get(produktGattung);
        final String shortName = verkehrsmittel.optString("mittelText", null);
        final String name = Optional.ofNullable(verkehrsmittel.optString("langText", null)).orElse(shortName);
        String operator = null;
        final Set<Line.Attr> lineAttrs = new HashSet<>();
        final JSONArray attributNotizen = verkehrsmittel.optJSONArray("zugattribute");
        if (attributNotizen != null) {
            for (int iAttr = 0; iAttr < attributNotizen.length(); ++iAttr) {
                final JSONObject attr = attributNotizen.getJSONObject(iAttr);
                final String key = attr.getString("key");
                if ("BEF".equals(key)) {
                    operator = attr.getString("value");
                } else if (wheelChairAttributes.contains(key)) {
                    lineAttrs.add(Line.Attr.WHEEL_CHAIR_ACCESS);
                } else if (bicycleAttributes.contains(key)) {
                    lineAttrs.add(Line.Attr.BICYCLE_CARRIAGE);
                }
            }
        }
        return new Line(
                null,
                operator,
                product,
                getSaneLineShortName(product, shortName),
                name,
                lineStyle(styles, operator, product, name),
                lineAttrs,
                null);
    }

    private boolean parseCancelled(final JSONObject stop) throws JSONException {
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
        final Location stopLocation = parseLocation(stop);
        final String ankunftSollzeit, ankunftEchtzeit;
        final JSONObject ankunft = stop.optJSONObject("ankunft");
        if (ankunft != null) {
            ankunftSollzeit = ankunft.optString("sollzeit", null);
            ankunftEchtzeit = ankunft.optString("echtzeit", null);
        } else {
            ankunftSollzeit = stop.optString("ankunftsZeitpunkt", null);
            ankunftEchtzeit = stop.optString("ezAnkunftsZeitpunkt", null);
        }
        final String abfahrtSollzeit, abfahrtEchtzeit;
        final JSONObject abfahrt = stop.optJSONObject("abfahrt");
        if (abfahrt != null) {
            abfahrtSollzeit = abfahrt.optString("sollzeit", null);
            abfahrtEchtzeit = abfahrt.optString("echtzeit", null);
        } else {
            abfahrtSollzeit = stop.optString("abfahrtsZeitpunkt", null);
            abfahrtEchtzeit = stop.optString("ezAbfahrtsZeitpunkt", null);
        }
        return new Stop(
                stopLocation != null && stopLocation.id != null ? stopLocation : fallbackLocation,
                parseIso8601NoOffset(ankunftSollzeit),
                parseIso8601NoOffset(ankunftEchtzeit),
                gleis, ezGleis, cancelled,
                parseIso8601NoOffset(abfahrtSollzeit),
                parseIso8601NoOffset(abfahrtEchtzeit),
                gleis, ezGleis, cancelled);
    }

    private List<Stop> parseStops(final JSONArray stops) throws JSONException {
        if (stops == null)
            return null;
        final List<Stop> out = new LinkedList<>();
        for (int iStop = 0; iStop < stops.length(); iStop++) {
            out.add(parseStop(stops.getJSONObject(iStop), null));
        }
        return out;
    }

    private int[] parseCapacity(final JSONObject verbindung) throws JSONException {
        final JSONArray auslastungen = verbindung.optJSONArray("auslastungsMeldungen");
        final int[] out = { 0, 0 };
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

    private Point parseCoordinate(final JSONObject coord) throws JSONException {
        return Point.fromDouble(coord.getDouble("lat"), coord.getDouble("lng"));
    }

    private List<Point> parsePolylineGroup(final JSONObject journey) throws JSONException {
        final JSONObject polylineGroup = journey.optJSONObject("polylineGroup");
        if (polylineGroup == null)
            return null;
        final JSONArray polylineDescriptions = polylineGroup.getJSONArray("polylineDescriptions");
        final int numDescriptions = polylineDescriptions.length();
        if (numDescriptions == 0)
            return null;
        // first, find the polylineDescription with the greatest distance
        // note that sometime there are 2 or 3 descriptions and those at the beginning or end
        // look like walks inside the stations.
        double maxDistance = -1d;
        JSONArray longestCoordinates = null;
        for (int nGroup = 0; nGroup < numDescriptions; ++nGroup) {
            final JSONObject description = polylineDescriptions.getJSONObject(nGroup);
            final JSONArray coordinates = description.getJSONArray("coordinates");
            final Point firstPoint = parseCoordinate(coordinates.getJSONObject(0));
            final Point lastPoint = parseCoordinate(coordinates.getJSONObject(coordinates.length() - 1));
            final double distance = GeoUtils.geoDistanceInMeters(firstPoint, lastPoint);
            if (distance > maxDistance) {
                maxDistance = distance;
                longestCoordinates = coordinates;
            }
        }
        if (longestCoordinates == null)
            return null;
        final List<Point> path = new ArrayList<>();
        for (int nCoord = 0; nCoord < longestCoordinates.length(); ++nCoord) {
            path.add(parseCoordinate(longestCoordinates.getJSONObject(nCoord)));
        }
        return path;
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
        final String defaultTeilstreckenHinweis = String.format("(%s - %s)",
                departureStop.location.name, arrivalStop.location.name);
        final String message = parseJourneyMessages(
                journey, journey.optJSONArray("zugattribute"), journeyRef.line.network,
                defaultTeilstreckenHinweis);
        final List<Point> path = parsePolylineGroup(journey);
        final Trip.Public leg = new Trip.Public(
                journeyRef.line,
                new Destination(arrivalStop.location),
                departureStop, arrivalStop, intermediateStops,
                message,
                new DbJourneyRef(journeyRef.journeyId, null,
                        journeyRef.adminCode, journeyRef.productName, journeyRef.serviceNumber,
                        journeyRef.line));
        leg.setPath(path);
        return leg;
    }

    private Trip.Leg parseLeg(
            final JSONObject abschnitt,
            final Supplier<String> journeyRequestIdSupplier,
            final @Nullable Location fallbackDeparture,
            final @Nullable Location fallbackArrival
    ) throws JSONException {
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
            final String produktGattung = verkehrsmittel.optString("produktGattung", null);
            final String productName = verkehrsmittel.optString("kurzText", null);
            String serviceNumber = verkehrsmittel.optString("nummer", null);
            if (serviceNumber != null && !serviceNumber.isEmpty() && !Character.isDigit(serviceNumber.charAt(0)))
                serviceNumber = null;
            final Line line = parseLine(verkehrsmittel, produktGattung);
            final Destination destination = parseDirection(verkehrsmittel, LocationType.DIRECTION);
            final String defaultTeilstreckenHinweis = String.format("(%s - %s)",
                    departureStop.location.name, arrivalStop.location.name);
            final String message = parseJourneyMessages(
                    abschnitt, verkehrsmittel.optJSONArray("zugattribute"), null,
                    defaultTeilstreckenHinweis);
            final String journeyId = abschnitt.optString("journeyId", null);
            String journeyRequestId = journeyRequestIdSupplier.get();
            while (journeyRequestId == null || !journeyRequestId.startsWith("T$"))
                journeyRequestId = journeyRequestIdSupplier.get();
            return new Trip.Public(line, destination, departureStop, arrivalStop, intermediateStops, message,
                    journeyId == null ? null : new DbJourneyRef(journeyId, journeyRequestId, null, productName, serviceNumber, line));
        } else {
            final int dist = abschnitt.optInt("distanz");
            return new Trip.Individual(
                    "TRANSFER".equals(typ) ? Trip.Individual.Type.TRANSFER : Trip.Individual.Type.WALK,
                    departureStop.location,
                    departureStop.getDepartureTime(),
                    arrivalStop.location,
                    arrivalStop.getArrivalTime(),
                    dist);
        }
    }

    private List<Fare> parseFares(final JSONObject verbindung) {
        final List<Fare> fares = new ArrayList<>();
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

    private String parseErrorCode(final AbstractHttpException e) {
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
            final CtxRecon ctxRecon = new CtxRecon(verbindung.optString("ctxRecon"));
            final JSONArray abschnitte = verbindung.getJSONArray("verbindungsAbschnitte");
            final List<Trip.Leg> legs = new ArrayList<>();
            Location tripFrom = null;
            Location tripTo = null;

            final Iterator<String> itJourneyRequestIds = ctxRecon.journeyRequestIds.iterator();
            Trip.Public prevPublicLegWithArrivalSamePlatform = null;
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
                final Trip.Leg leg = parseLeg(abschnitt, itJourneyRequestIds::next, fallbackDeparture, fallbackArrival);
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
                    if (abschnitt.optBoolean("samePlatform")) {
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
            final List<Fare> fares = parseFares(verbindung);
            final int transfers = verbindung.optInt("umstiegsAnzahl", -1);
            final int[] capacity = parseCapacity(verbindung);
            trips.add(new Trip(
                    new Date(),
                    ctxRecon.tripId,
                    new DbTripRef(network, ctxRecon.ctxRecon, from, via, to, limitToDticket, hasDticket),
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
            @Nullable final Set<Product> products,
            final boolean direct, final boolean bike,
            @Nullable final Integer minUmstiegszeit,
            final @Nullable String context,
            final boolean loadPath) throws IOException {
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
        final String productsStr = ",\"produktgattungen\":[" + formatProducts(useProducts)
                + "],\"deutschlandTicketVorhanden\":" + hasDticket
                + ",\"nurDeutschlandTicketVerbindungen\":" + limitToDticket;
        final String viaLocations = via == null ? ""
                : ",\"zwischenhalte\":[{\"id\": \"" + formatLid(via) + "\"}]";
        final String directStr = direct ? ",\"maxUmstiege\":0" : "";
        final String bikeStr = bike ? ",\"bikeCarriage\":true" : "";
        final String bikeTraveling = bike ? ",{\"typ\":\"FAHRRAD\",\"ermaessigungen\":[{\"art\":\"KEINE_ERMAESSIGUNG\",\"klasse\":\"KLASSENLOS\"}],\"alter\":[],\"anzahl\":1}" : "";
        final String minUmstiegszeitStr = minUmstiegszeit != null ? ",\"minUmstiegszeit\":" + minUmstiegszeit : "";
        final String ctxStr = context != null ? ",\"pagingReference\": \"" + context + "\"" : "";
        final String request = "{\"sitzplatzOnly\":false,\"klasse\":\"KLASSE_2\"" //
                + ",\"abfahrtsHalt\": \"" + formatLid(from) + "\"" //
                + productsStr //
                + viaLocations //
                + directStr //
                + bikeStr //
                + minUmstiegszeitStr //
                + ctxStr //
                + ",\"anfrageZeitpunkt\":\"" + formatIso8601NoOffset(time) + "\",\"ankunftSuche\":\"" + deparr + "\"" //
                + ",\"ankunftsHalt\": \"" + formatLid(to) + "\"" //
                + ",\"reisende\":[{\"ermaessigungen\":[{\"art\":\"KEINE_ERMAESSIGUNG\",\"klasse\":\"KLASSENLOS\"}],\"typ\":\"ERWACHSENER\",\"alter\":[],\"anzahl\":1}" + bikeTraveling + "]" //
                + ",\"reservierungsKontingenteVorhanden\":false,\"schnelleVerbindungen\":false}";

        final HttpUrl url = this.tripEndpoint;

        String page = null;
        try {
            page = doRequest(url, request, 30);
            final JSONObject res = new JSONObject(page);
            final Optional<JSONObject> verbindungReference = Optional.ofNullable(res.optJSONObject("verbindungReference"));
            final DbWebApiContext apiContext = new DbWebApiContext(from, via, to, time, dep, products, direct, bike, minUmstiegszeit,
                    verbindungReference.map(v -> v.optString("later", null)).orElse(null),
                    verbindungReference.map(v -> v.optString("earlier", null)).orElse(null));
            return parseTrips(res, apiContext, from, via, to, limitToDticket, hasDticket);
        } catch (final InternalErrorException | BlockedException e) {
            final String code = parseErrorCode(e);
            if ("MDA-AK-MSG-1001".equals(code)) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.INVALID_DATE);
            } else if (code != null) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        } catch (final IOException | RuntimeException e) {
            log.error("queryTrips", e);
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        }
    }

    private QueryTripsResult doQueryReloadTrip(final DbTripRef tripRef, final boolean loadPath) throws IOException {
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
        } catch (final InternalErrorException | BlockedException e) {
            final String code = parseErrorCode(e);
            if ("MDA-AK-MSG-1001".equals(code)) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.INVALID_DATE);
            } else if (code != null) {
                return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        } catch (final IOException | RuntimeException e) {
            log.error("queryReloadTrip", e);
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(
            final Set<LocationType> types,
            final Location location,
            final EquivalentStationsMode equivsMode,
            int maxDistance,
            int maxLocations,
            final Set<Product> products) throws IOException {
        // TODO POIs not supported (?)
        if (maxDistance == 0)
            maxDistance = DEFAULT_MAX_DISTANCE;
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;
        if (location.coord == null) {
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.INVALID_ID);
        }

        final HttpUrl.Builder builder = this.nearbyEndpoint.newBuilder()
                .addQueryParameter("lat", Double.toString(location.coord.getLatAsDouble()))
                .addQueryParameter("long", Double.toString(location.coord.getLonAsDouble()))
                .addQueryParameter("radius", Integer.toString(maxDistance))
                .addQueryParameter("maxNo", Integer.toString(maxLocations));
        PRODUCTS_MAP.forEach((key, product) -> {
            if (products == null || products.contains(product))
                builder.addQueryParameter("product[]", key);
        });
        final HttpUrl url = builder.build();
        String page = null;
        try {
            page = doRequest(url);
            final JSONArray locs = new JSONArray(page);
            final List<Location> locations = parseLocations(locs);
            return new NearbyLocationsResult(this.resultHeader, locations);
        } catch (final InternalErrorException | BlockedException e) {
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.INVALID_ID);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        } catch (final IOException | RuntimeException e) {
            log.error("queryNearbyLocations", e);
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public QueryDeparturesResult queryDepartures(
            final String stationId,
            @Nullable final Date time,
            int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products)
            throws IOException {
        // TODO only 1 hour of results returned, find secret parameter?
        if (maxDepartures == 0)
            maxDepartures = DEFAULT_MAX_DEPARTURES;
        final Calendar c = new GregorianCalendar(timeZone);
        c.setTime(time);

        final HttpUrl.Builder builder = this.departureEndpoint.newBuilder()
                .addQueryParameter("datum", formatDate(c).toString())
                .addQueryParameter("zeit", formatTime(c).toString())
                .addQueryParameter("ortExtId", stationId)
                .addQueryParameter("ortId", formatLid(stationId))
                .addQueryParameter("mitVias", "true")
                .addQueryParameter("maxVias", "2");
        PRODUCTS_MAP.forEach((key, product) -> {
            if (products == null || products.contains(product))
                builder.addQueryParameter("verkehrsmittel[]", key);
        });
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
                if (equivsMode == EquivalentStationsMode.USE_META && !stationId.equals(bahnhofsId)) {
                    continue;
                }
                final Location location = createLocation(LocationType.STATION, bahnhofsId, null, bahnhofsName, null, null);
                StationDepartures stationDepartures = result.findStationDepartures(bahnhofsId);
                if (stationDepartures == null) {
                    stationDepartures = new StationDepartures(location, new ArrayList<Departure>(8), null);
                    result.stationDepartures.add(stationDepartures);
                }

                final String journeyId = dep.optString("journeyId", null);
                final JSONObject verkehrmittel = dep.getJSONObject("verkehrmittel");
                final String produktGattung = verkehrmittel.optString("produktGattung", null);
                final Line line = parseLine(verkehrmittel, produktGattung);
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
                final Position plannedPosition = parsePosition(dep.optString("gleis", null));
                final Position predictedPosition = parsePosition(dep.optString("ezGleis", null));
                final Departure departure = new Departure(
                        parseIso8601NoOffset(dep.optString("zeit", null)),
                        parseIso8601NoOffset(dep.optString("ezZeit", null)),
                        line,
                        plannedPosition, predictedPosition,
                        new Destination(createLocation(LocationType.STATION, null, null, destinationName, null, null)),
                        cancelled,
                        null,
                        parseJourneyMessages(dep, null, null, null),
                        journeyId == null ? null : new DbJourneyRef(journeyId, null, null, produktGattung,null, line));

                stationDepartures.departures.add(departure);
                added += 1;
                if (added >= maxDepartures) {
                    break;
                }
            }

            for (final StationDepartures stationDepartures : result.stationDepartures)
                Collections.sort(stationDepartures.departures, Departure.TIME_COMPARATOR);
            return result;
        } catch (final InternalErrorException | BlockedException e) {
            return new QueryDeparturesResult(this.resultHeader, QueryDeparturesResult.Status.INVALID_STATION);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        } catch (final IOException | RuntimeException e) {
            log.error("queryDepartures", e);
            return new QueryDeparturesResult(this.resultHeader, QueryDeparturesResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public SuggestLocationsResult suggestLocations(
            final CharSequence constraint,
            @Nullable final Set<LocationType> types,
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
            for (final LocationType type : types) {
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
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new SuggestLocationsResult(this.resultHeader, SuggestLocationsResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public QueryTripsResult queryTrips(
            final Location from, @Nullable final Location via, final Location to,
            final Date date, final boolean dep,
            @Nullable final TripOptions options, final boolean loadPath) throws IOException {
        final Set<TripFlag> tripFlags = options == null ? null : options.flags;
        return doQueryTrips(from, via, to, date, dep,
                options != null ? options.products : null,
                tripFlags != null && tripFlags.contains(TripFlag.DIRECT),
                tripFlags != null && tripFlags.contains(TripFlag.BIKE),
                options == null || options.minTransferTimeMinutes == null ? null
                        : getApplicableMinTransferTime(options.minTransferTimeMinutes),
                null, loadPath);
    }

    @Override
    public QueryTripsResult queryMoreTrips(
            final QueryTripsContext context, final boolean later,
            final boolean loadPath) throws IOException {
        final DbWebApiContext ctx = (DbWebApiContext) context;
        final String ctxToken;
        if (later && ctx.canQueryLater()) {
            ctxToken = ctx.laterContext;
        } else if (!later && ctx.canQueryEarlier()) {
            ctxToken = ctx.earlierContext;
        } else {
            return new QueryTripsResult(this.resultHeader, QueryTripsResult.Status.NO_TRIPS);
        }
        return doQueryTrips(ctx.from, ctx.via, ctx.to, ctx.date, ctx.dep, ctx.products, ctx.direct, ctx.bike, ctx.minUmstiegszeit, ctxToken, loadPath);
    }

    @Override
    public QueryTripsResult queryReloadTrip(
            final TripRef tripRef,
            final boolean loadPath) throws IOException {
        return doQueryReloadTrip((DbTripRef) tripRef, loadPath);
    }

    @Override
    protected QueryJourneyResult doQueryJourney(final DbJourneyRef journeyRef, final boolean loadPath) throws IOException {
        final HttpUrl url = this.journeyEndpoint.newBuilder()
                .addQueryParameter("journeyId", journeyRef.journeyId)
                .addQueryParameter("poly", loadPath ? "true" : "false")
                .build();
        String page = null;
        try {
            page = doRequest(url);
            final JSONObject res = new JSONObject(page);
            final Trip.Public leg = parseJourney(res, journeyRef);
            return new QueryJourneyResult(this.resultHeader, url.toString(), journeyRef, leg);
        } catch (final InternalErrorException | BlockedException e) {
            final String code = parseErrorCode(e);
            if (code != null) {
                return new QueryJourneyResult(this.resultHeader, QueryJourneyResult.Status.NO_JOURNEY);
            }
            return new QueryJourneyResult(this.resultHeader, QueryJourneyResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        } catch (final IOException | RuntimeException e) {
            log.error("queryJourney", e);
            return new QueryJourneyResult(this.resultHeader, QueryJourneyResult.Status.SERVICE_DOWN);
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
        public final boolean direct;
        public final boolean bike;
        public final Integer minUmstiegszeit;
        public final String laterContext, earlierContext;

        public DbWebApiContext(
                final Location from, final @Nullable Location via, final Location to,
                final Date date, final boolean dep,
                final Set<Product> products,
                final boolean direct,
                final boolean bike,
                final Integer minUmstiegszeit,
                final String laterContext, final String earlierContext) {
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
            this.dep = dep;
            this.products = products;
            this.direct = direct;
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
        final DbTripRef tripRef = (DbTripRef) trip.tripRef;
        return linkSharing.getOpenLink(trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public String getShareLink(final Trip trip) throws IOException {
        final DbTripRef tripRef = (DbTripRef) trip.tripRef;
        return linkSharing.getShareLink(this, trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public TripShare shareTrip(final Trip trip) throws IOException {
        final DbTripRef tripRef = (DbTripRef) trip.tripRef;
        return linkSharing.shareTrip(this, trip, tripRef.getSimplified(), tripRef.ctxRecon);
    }

    @Override
    public QueryTripsResult loadSharedTrip(
            final TripShare tripShare,
            final boolean loadPath) throws IOException {
        final DbWebTripShare dbWebTripShare = (DbWebTripShare) tripShare;
        final String recon = linkSharing.loadSharedTrip(this, dbWebTripShare);
        final DbTripRef tripRef = new DbTripRef((DbTripRef) tripShare.simplifiedTripRef, recon);
        return queryReloadTrip(tripRef, loadPath);
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
                final DbProvider dbProvider,
                final Trip trip,
                final TripRef simplifiedTripRef,
                final String recon) throws IOException {
            final DbWebTripShare tripShare = shareTrip(dbProvider, trip, simplifiedTripRef, recon);
            if (tripShare == null)
                return null;
            final String vbid = tripShare.vbid;
            if (vbid == null)
                return null;
            return String.format("https://www.bahn.de/buchung/start?vbid=%1$s", vbid);
        }

        public DbWebTripShare shareTrip(
                final DbProvider dbProvider,
                final Trip trip,
                final TripRef simplifiedTripRef, final String recon) throws IOException {
            final String request = "{\"hinfahrtDatum\":\"" + ISO_DATE_TIME_UTC_FORMAT.format(trip.getFirstDepartureTime()) + "\"," //
                    + "\"hinfahrtRecon\": \"" + recon + "\"," //
                    + "\"startOrt\": \"" + simplifiedTripRef.from.uniqueShortName() + "\"," //
                    + "\"zielOrt\": \"" + simplifiedTripRef.to.uniqueShortName() + "\"}";

            final HttpUrl url = this.saveConnectionEndpoint;

            String page = null;
            try {
                page = DbWebProvider.doRequest(dbProvider, null, url, request, null);
                final JSONObject res = new JSONObject(page);
                final String vbid = res.optString("vbid");
                return new DbWebTripShare(simplifiedTripRef, vbid);
            } catch (final InternalErrorException | BlockedException e) {
                return null;
            } catch (final JSONException x) {
                throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
            } catch (final IOException | RuntimeException e) {
                dbProvider.getLog().error("error on shareTrip request", e);
                return null;
            }
        }

        public String loadSharedTrip(
                final DbProvider dbProvider,
                final DbWebTripShare tripShare) throws IOException {
            final HttpUrl url = this.loadConnectionEndpoint.newBuilder()
                    .addEncodedPathSegment(tripShare.vbid)
                    .build();

            String page = null;
            try {
                page = DbWebProvider.doRequest(dbProvider, null, url, null, null);
                final JSONObject res = new JSONObject(page);
                return res.optString("hinfahrtRecon");
            } catch (final InternalErrorException | BlockedException e) {
                return null;
            } catch (final JSONException x) {
                throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
            } catch (final IOException | RuntimeException e) {
                dbProvider.getLog().error("error on loadSharedTrip request", e);
                return null;
            }
        }
    }
}
