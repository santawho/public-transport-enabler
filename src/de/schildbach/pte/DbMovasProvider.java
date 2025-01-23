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
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.exception.AbstractHttpException;
import de.schildbach.pte.exception.BlockedException;
import de.schildbach.pte.exception.InternalErrorException;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.ParserUtils;
import okhttp3.HttpUrl;

/**
 * Provider implementation for movas API of Deutsche Bahn (Germany).
 * 
 * @author Andreas Schildbach
 */
public class DbMovasProvider extends AbstractNetworkProvider {
    private final List<Capability> CAPABILITIES = Arrays.asList(
            Capability.SUGGEST_LOCATIONS,
            Capability.NEARBY_LOCATIONS,
            Capability.DEPARTURES,
            Capability.TRIPS,
            Capability.TRIPS_VIA);

    private static final HttpUrl API_BASE = HttpUrl.parse("https://app.vendo.noncd.db.de/mob/");
    private final ResultHeader resultHeader;

    private static final Map<String, Product> PRODUCTS_MAP = new LinkedHashMap<String, Product>() {
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
        }
    };

    private static final Map<String, Product> SHORT_PRODUCTS_MAP = new LinkedHashMap<String, Product>() {
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
        }
    };

    private static final Map<String, LocationType> ID_LOCATION_TYPE_MAP = new HashMap<String, LocationType>() {
        {
            put("1", LocationType.STATION);
            put("4", LocationType.POI);
            put("2", LocationType.ADDRESS);
        }
    };

    private static final Map<LocationType, String> LOCATION_TYPE_MAP = new HashMap<LocationType, String>() {
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
    private final HttpUrl locationsEndpoint;
    private final HttpUrl nearbyEndpoint;

    private static final TimeZone timeZone = TimeZone.getTimeZone("Europe/Berlin");

    private static final Pattern P_SPLIT_NAME_FIRST_COMMA = Pattern.compile("([^,]*), (.*)");
    private static final Pattern P_SPLIT_NAME_ONE_COMMA = Pattern.compile("([^,]*), ([^,]*)");

    public DbMovasProvider() {
        this(NetworkId.DB);
    }

    protected DbMovasProvider(final NetworkId networkId) {
        super(networkId);
        this.departureEndpoint = API_BASE.newBuilder().addPathSegments("bahnhofstafel/abfahrt").build();
        this.tripEndpoint = API_BASE.newBuilder().addPathSegments("angebote/fahrplan").build();
        this.locationsEndpoint = API_BASE.newBuilder().addPathSegments("location/search").build();
        this.nearbyEndpoint = API_BASE.newBuilder().addPathSegments("location/nearby").build();
        this.resultHeader = new ResultHeader(network, "movas");
    }

    @Override
    protected String[] getValidUserInterfaceLanguages() {
        return new String[] { "en", "de", "fr", "es", "dk", "cz", "it", "nl", "pl" };
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

    private static final DateFormat ISO_DATE_TIME_WOFFSET_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    static {
        ISO_DATE_TIME_WOFFSET_FORMAT.setTimeZone(timeZone);
    }

    private String formatIso8601WOffset(final Date time) {
        if (time == null)
            return null;
        return ISO_DATE_TIME_WOFFSET_FORMAT.format(time);
    }


    private Date parseIso8601WOffset(final String time) {
        if (time == null)
            return null;
        try {
            return ISO_DATE_TIME_WOFFSET_FORMAT.parse(time);
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

    private String parseJourneyMessages(final JSONObject jny) throws JSONException {
        final List<String> messages = new ArrayList<>();
        parseMessages(jny.optJSONArray("echtzeitNotizen"), messages, null, null);
        parseMessages(jny.optJSONArray("himNotizen"), messages, null, null);
        // show very important static messages (e.g. on demand tel)
        parseMessages(jny.optJSONArray("attributNotizen"), messages, this.messagesAsSimpleHtml ? "&#8226; " : null, 100);
        return messages.isEmpty() ? null : join(this.messagesAsSimpleHtml ? "<br>" : " - ", messages);
    }

    // replace with String.join() at some point
    private static String join(final CharSequence delimiter, final Iterable<? extends CharSequence> elements) {
        final StringJoiner joiner = new StringJoiner(delimiter);
        elements.forEach(joiner::add);
        return joiner.toString();
    }

    private Line parseLine(final JSONObject jny) throws JSONException {
        // TODO attrs, messages
        final Product product = SHORT_PRODUCTS_MAP.get(jny.optString("produktGattung", null));
        final String name = Optional.ofNullable(jny.optString("langtext", null)).orElse(jny.optString("mitteltext", null));
        String shortName = jny.optString("mitteltext", null);
        if (shortName != null && (product == Product.BUS || product == Product.TRAM)) {
            shortName = shortName.replaceAll("^[A-Za-z]+ ", "");
        }
        String operator = null;
        final JSONArray attributNotizen = jny.optJSONArray("attributNotizen");
        if (attributNotizen != null) {
            for (int iAttr = 0; iAttr < attributNotizen.length(); ++iAttr) {
                JSONObject attr = attributNotizen.getJSONObject(iAttr);
                if ("OP".equals(attr.get("key"))) {
                    operator = attr.getString("text");
                    break;
                }
            }
        }
        return new Line(
                jny.optString("zuglaufId", null),
                operator,
                product,
                shortName,
                name,
                lineStyle(operator, product, name));
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

    private Trip.Leg parseLeg(final JSONObject abschnitt) throws JSONException {
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
            final String message = parseJourneyMessages(abschnitt);
            return new Trip.Public(line, destination, departureStop, arrivalStop, intermediateStops, null, message);
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

    private QueryTripsResult doQueryTrips(Location from, @Nullable Location via, Location to, Date time, boolean dep,
            @Nullable Set<Product> products, final boolean bike, final @Nullable String context) throws IOException {
        // TODO minUmstiegsdauer instead of walkSpeed ?
        // accessibility, optimize not supported

        final String deparr = dep ? "ABFAHRT" : "ANKUNFT";
        final String productsStr = "\"verkehrsmittel\":[" + formatProducts(products) + "]";
        final String viaLocations = via != null
                ? "\"viaLocations\":[{\"locationId\": \"" + formatLid(via) + "\"," + productsStr + "}],"
                : "";
        final String bikeStr = bike ? "\"fahrradmitnahme\":true," : "";
        final String ctxStr = context != null ? "\"context\": \"" + context + "\"," : "";
        final String request = "{\"autonomeReservierung\":false,\"einstiegsTypList\":[\"STANDARD\"],\"klasse\":\"KLASSE_2\"," //
                + "\"reiseHin\":{\"wunsch\":{\"abgangsLocationId\": \"" + formatLid(from) + "\"," //
                + productsStr + "," //
                + viaLocations //
                + bikeStr //
                + ctxStr //
                + "\"zeitWunsch\":{\"reiseDatum\":\"" + formatIso8601WOffset(time) + "\",\"zeitPunktArt\":\"" + deparr //
                + "\"}," //
                + "\"zielLocationId\": \"" + formatLid(to) + "\"}}," //
                + "\"reisendenProfil\":{\"reisende\":[{\"ermaessigungen\":[\"KEINE_ERMAESSIGUNG KLASSENLOS\"],\"reisendenTyp\":\"ERWACHSENER\"}]}," //
                + "\"reservierungsKontingenteVorhanden\":false}";

        final HttpUrl url = this.tripEndpoint;
        final String contentType = "application/x.db.vendo.mob.verbindungssuche.v8+json";

        String page = null;
        try {
            page = doRequest(url, request, contentType);
            final JSONObject res = new JSONObject(page);
            final JSONArray verbindungen = res.getJSONArray("verbindungen");
            final List<Trip> trips = new ArrayList<>();

            for (int iTrip = 0; iTrip < verbindungen.length(); iTrip++) {
                final JSONObject verbindungParent = verbindungen.getJSONObject(iTrip);
                final JSONObject verbindung = verbindungParent.getJSONObject("verbindung");
                final JSONArray abschnitte = verbindung.getJSONArray("verbindungsAbschnitte");
                final List<Trip.Leg> legs = new ArrayList<>();
                Location tripFrom = null;
                Location tripTo = null;

                for (int iLeg = 0; iLeg < abschnitte.length(); iLeg++) {
                    final Trip.Leg leg = parseLeg(abschnitte.getJSONObject(iLeg));
                    if (leg == null) continue;
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
                trips.add(new Trip(
                        verbindung.optString("kontext").split("#")[0],
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
            final DbMovasContext ctx = new DbMovasContext(from, via, to, time, dep, products, bike,
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
                if (parseCancelled(dep)) {
                    continue;
                }
                final Location location = parseLocation(dep.optJSONObject("abfrageOrt"));
                if (!equivs && !stationId.equals(location.id)) {
                    continue;
                }
                StationDepartures stationDepartures = result.findStationDepartures(location.id);
                if (stationDepartures == null) {
                    stationDepartures = new StationDepartures(location, new ArrayList<Departure>(8), null);
                    result.stationDepartures.add(stationDepartures);
                }

                final Stop stop = parseStop(dep, location);
                final Departure departure = new Departure(
                        stop.plannedDepartureTime,
                        stop.predictedDepartureTime,
                        parseLine(dep),
                        Optional.ofNullable(stop.predictedDeparturePosition).orElse(stop.plannedDeparturePosition),
                        parseDirection(dep),
                        null,
                        parseJourneyMessages(dep));

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
        return doQueryTrips(ctx.from, ctx.via, ctx.to, ctx.date, ctx.dep, ctx.products, ctx.bike, ctxToken);
    }

    @Override
    protected boolean hasCapability(Capability capability) {
        return CAPABILITIES.contains(capability);
    }

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL;
    }

    private static class DbMovasContext implements QueryTripsContext {
        public final Location from, via, to;
        public final Date date;
        public final boolean dep;
        public final Set<Product> products;
        public final boolean bike;
        public final String laterContext, earlierContext;

        public DbMovasContext(final Location from, final @Nullable Location via, final Location to, final Date date,
                final boolean dep, final Set<Product> products, final boolean bike, final String laterContext,
                final String earlierContext) {
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
            this.dep = dep;
            this.products = products;
            this.bike = bike;
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
}
