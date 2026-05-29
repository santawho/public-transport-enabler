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

package de.schildbach.pte.provider.motis;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.*;
import de.schildbach.pte.exception.InvalidDataException;
import de.schildbach.pte.exception.NotFoundException;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import de.schildbach.pte.util.MessagePackUtils;
import de.schildbach.pte.util.PolylineFormat;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * @author Dan Cojocaru
 */
public class AbstractMotisProvider extends AbstractNetworkProvider {
    private static final Map<LocationType, String> SUPPORTED_NEARBY_LOCATIONS;
    private static final Map<String, Product> MOTIS_MODE_MAP;
    private static final Map<String, Trip.Individual.Type> MOTIS_INDIVIDUAL_MODE_MAP;
    private static final Map<Product, String[]> MODE_MOTIS_MAP;
    protected static final Set<Capability> CAPABILITIES = new HashSet<>();

    private static final Pattern STOP_CLEANUP_PATTERN = Pattern.compile("(^\\s*[\\-_,]?\\s*)|(\\s*[\\-_,]?\\s*$)");

    static {
        SUPPORTED_NEARBY_LOCATIONS = new HashMap<>();
        SUPPORTED_NEARBY_LOCATIONS.put(LocationType.STATION, "STOP");
        SUPPORTED_NEARBY_LOCATIONS.put(LocationType.ADDRESS, "ADDRESS");
        SUPPORTED_NEARBY_LOCATIONS.put(LocationType.POI, "PLACE");

        MOTIS_MODE_MAP = new HashMap<>();
        MOTIS_MODE_MAP.put("ODM", Product.ON_DEMAND);
        MOTIS_MODE_MAP.put("TRAM", Product.TRAM);
        MOTIS_MODE_MAP.put("SUBWAY", Product.SUBWAY);
        MOTIS_MODE_MAP.put("FERRY", Product.FERRY);
        MOTIS_MODE_MAP.put("BUS", Product.BUS);
        MOTIS_MODE_MAP.put("COACH", Product.COACH);
        MOTIS_MODE_MAP.put("RAIL", Product.REGIONAL_TRAIN);
        MOTIS_MODE_MAP.put("HIGHSPEED_RAIL", Product.HIGH_SPEED_TRAIN);
        MOTIS_MODE_MAP.put("LONG_DISTANCE", Product.HIGH_SPEED_TRAIN);
        MOTIS_MODE_MAP.put("NIGHT_RAIL", Product.HIGH_SPEED_TRAIN);
        MOTIS_MODE_MAP.put("REGIONAL_RAIL", Product.REGIONAL_TRAIN);
        MOTIS_MODE_MAP.put("SUBURBAN", Product.SUBURBAN_TRAIN);
        MOTIS_MODE_MAP.put("FUNICULAR", Product.CABLECAR);
        MOTIS_MODE_MAP.put("AERIAL_LIFT", Product.CABLECAR);
        MOTIS_MODE_MAP.put("AREAL_LIFT", Product.CABLECAR);
        MOTIS_MODE_MAP.put("METRO", Product.SUBURBAN_TRAIN);
        MOTIS_MODE_MAP.put("CABLE_CAR", Product.CABLECAR);

        MOTIS_INDIVIDUAL_MODE_MAP = new HashMap<>();
        MOTIS_INDIVIDUAL_MODE_MAP.put("WALK", Trip.Individual.Type.WALK);
        MOTIS_INDIVIDUAL_MODE_MAP.put("BIKE", Trip.Individual.Type.BIKE);
        MOTIS_INDIVIDUAL_MODE_MAP.put("CAR", Trip.Individual.Type.CAR);

        MODE_MOTIS_MAP = new HashMap<>();
        MODE_MOTIS_MAP.put(Product.BUS, new String[]{"BUS"});
        MODE_MOTIS_MAP.put(Product.COACH, new String[]{"COACH"});
        MODE_MOTIS_MAP.put(Product.CABLECAR, new String[]{"FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR"});
        MODE_MOTIS_MAP.put(Product.FERRY, new String[]{"FERRY"});
        MODE_MOTIS_MAP.put(Product.HIGH_SPEED_TRAIN, new String[]{"HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL"});
        MODE_MOTIS_MAP.put(Product.REGIONAL_TRAIN, new String[]{"REGIONAL_RAIL"});
        MODE_MOTIS_MAP.put(Product.SUBURBAN_TRAIN, new String[]{"SUBURBAN", "METRO"});
        MODE_MOTIS_MAP.put(Product.ON_DEMAND, new String[]{"ODM"});
        MODE_MOTIS_MAP.put(Product.SUBWAY, new String[]{"SUBWAY"});
        MODE_MOTIS_MAP.put(Product.TRAM, new String[]{"TRAM"});
        
        CAPABILITIES.add(Capability.SUGGEST_LOCATIONS);
        CAPABILITIES.add(Capability.NEARBY_LOCATIONS);
        CAPABILITIES.add(Capability.DEPARTURES);
        CAPABILITIES.add(Capability.TRIPS);
        CAPABILITIES.add(Capability.TRIPS_VIA);
        CAPABILITIES.add(Capability.BIKE_OPTION);
        CAPABILITIES.add(Capability.DIRECT_OPTION);
        CAPABILITIES.add(Capability.MIN_TRANSFER_TIMES);
        CAPABILITIES.add(Capability.JOURNEY);
        CAPABILITIES.add(Capability.TRIP_RELOAD);
    }
    
    public static class MotisTripRef extends TripRef implements QueryTripsContext, Serializable, MessagePackUtils.Packable {
        @Serial
        private static final long serialVersionUID = 7250525175653739883L;

        @Nullable
        protected String nextPageCursor;
        @Nullable
        protected String previousPageCursor;
        protected String endpointUrl;

        public MotisTripRef(final NetworkId network, final HttpUrl endpoint, final Location from, @Nullable final Location via, final Location to, @Nullable final String nextPageCursor, @Nullable final String previousPageCursor) {
            super(network, from, via, to);
            this.endpointUrl = endpoint.toString();
            this.nextPageCursor = nextPageCursor;
            this.previousPageCursor = previousPageCursor;
        }

        @Override
        public void packToMessage(final MessagePacker packer) throws IOException {
            super.packToMessage(packer);
            MessagePackUtils.packNullableString(packer, previousPageCursor);
            MessagePackUtils.packNullableString(packer, nextPageCursor);
            MessagePackUtils.packNullableString(packer, endpointUrl);
        }

        public MotisTripRef(final NetworkId network, final MessageUnpacker unpacker) throws IOException {
            super(network, unpacker);
            this.previousPageCursor = MessagePackUtils.unpackNullableString(unpacker);
            this.nextPageCursor = MessagePackUtils.unpackNullableString(unpacker);
            this.endpointUrl = requireNonNull(MessagePackUtils.unpackNullableString(unpacker));
        }

        @Override
        public boolean canQueryLater() {
            return nextPageCursor != null;
        }

        @Override
        public boolean canQueryEarlier() {
            return previousPageCursor != null;
        }

        public HttpUrl getEndpoint() {
            return HttpUrl.parse(endpointUrl);
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof MotisTripRef)) return false;
            if (!super.equals(o)) return false;
            final MotisTripRef that = (MotisTripRef) o;
            return Objects.equals(from, that.from) && Objects.equals(via, that.via) && Objects.equals(to, that.to) && Objects.equals(nextPageCursor, that.nextPageCursor) && Objects.equals(previousPageCursor, that.previousPageCursor) && Objects.equals(endpointUrl, that.endpointUrl);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), from, via, to, nextPageCursor, previousPageCursor, endpointUrl);
        }
    }

    public static class MotisJourneyRef extends JourneyRef {
        @Serial
        private static final long serialVersionUID = 6082455169397886151L;

        private final String tripId;

        public MotisJourneyRef(final String tripId) {
            this.tripId = tripId;
        }

        @Override
        public String getUniqueId() {
            return tripId;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof MotisJourneyRef)) return false;
            final MotisJourneyRef that = (MotisJourneyRef) o;
            return Objects.equals(tripId, that.tripId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(tripId);
        }
    }

    private final HttpUrl apiBase;

    protected AbstractMotisProvider(final NetworkId network, final HttpUrl apiBase) {
        super(network);
        httpClient.setHeader("Accept", "application/json");
        this.apiBase = requireNonNull(apiBase);
    }

    @Override
    protected Set<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public UserAgentType getUserAgentType() {
        return UserAgentType.APP;
    }

    protected static TimeZone getMotisTimeZone(final JSONObject jsonObject) {
        if (jsonObject == null)
            return null;
        final String tzString = jsonObject.optString("tz", null);
        if (tzString == null)
            return null;
        return TimeZone.getTimeZone(tzString);
    }

    protected static PTDate parseMotisDateTime(final String dateTime, final TimeZone timeZone) {
        final long millis = OffsetDateTime.parse(dateTime).toInstant().toEpochMilli();
        if (timeZone == null) {
            return PTDate.withSystemOffset(millis);
        } else {
            return new PTDate(millis, timeZone);
        }
    }
    
    protected static Line parseMotisLine(final JSONObject data) throws JSONException {
        return new Line(
                data.getString("routeId"),
                data.getString("agencyName"),
                MOTIS_MODE_MAP.get(data.getString("mode")),
                data.getString("routeShortName"),
                data.getString("displayName"),
                data.has("routeColor") && data.has("routeTextColor") ?
                        new Style(
                                Style.parseColor("#" + data.getString("routeColor")),
                                Style.parseColor("#" + data.getString("routeTextColor"))
                        ) : null);
    }

    protected static Stop parseMotisStop(final JSONObject data, final boolean realtime) throws JSONException {
        final Location location = parseMotisPlace(data);
        final TimeZone timeZone = getMotisTimeZone(data);
        final Function<String, PTDate> getDate = s -> parseMotisDateTime(s, timeZone);

        return new Stop(
                location,
                data.has("scheduledArrival") ? getDate.apply(data.getString("scheduledArrival")) : null,
                realtime && data.has("arrival") ? getDate.apply(data.getString("arrival")) : null,
                data.has("scheduledTrack") ? new Position(data.getString("scheduledTrack")) : null,
                realtime && data.has("track") ? new Position(data.getString("track")) : null,
                data.has("cancelled") && data.getBoolean("cancelled"),
                data.has("scheduledDeparture") ? getDate.apply(data.getString("scheduledDeparture")) : null,
                realtime && data.has("departure") ? getDate.apply(data.getString("departure")) : null,
                data.has("scheduledTrack") ? new Position(data.getString("scheduledTrack")) : null,
                realtime && data.has("track") ? new Position(data.getString("track")) : null,
                data.has("cancelled") && data.getBoolean("cancelled")
        );
    }

    protected static Location parseMotisPlace(final JSONObject place) throws JSONException {
        final String motisStopId = place.optString("stopId", null);
        final String stopId = motisStopId == null || motisStopId.isEmpty() ? null : motisStopId;
        return new Location(
                LocationType.STATION,
                stopId,
                Point.fromDouble(place.getDouble("lat"), place.getDouble("lon")),
                null,
                place.getString("name")
        );
    }

    protected static Location parseMotisLocation(final JSONObject location) throws JSONException, InvalidDataException {
        String place = null;
        final JSONArray areas = location.optJSONArray("areas");
        for (int ai = 0; ai < (areas != null ? areas.length() : 0); ai++) {
            final JSONObject area = areas.getJSONObject(ai);
            if (!area.getBoolean("default")) continue;
            place = area.getString("name");
            if (place.isEmpty()) {
                place = null;
            }
        }

        String name = location.getString("name");

        final String motisType = location.getString("type");
        final String motisLocationId = location.optString("id", null);
        final String locationId = motisLocationId == null || motisLocationId.isEmpty() ? null : motisLocationId;
        switch (motisType) {
            case "ADDRESS":
                return new Location(
                        LocationType.ADDRESS,
                        locationId,
                        Point.fromDouble(location.getDouble("lat"), location.getDouble("lon")),
                        place,
                        name);
            case "PLACE":
                return new Location(
                        LocationType.POI,
                        locationId,
                        Point.fromDouble(location.getDouble("lat"), location.getDouble("lon")),
                        place,
                        name);
            case "STOP":
                final JSONArray modes = location.optJSONArray("modes");
                final Set<Product> products = new HashSet<>();
                for (int mi = 0; mi < (modes != null ? modes.length() : 0); mi++) {
                    final String mode = modes.getString(mi);
                    if (MOTIS_MODE_MAP.containsKey(mode)) {
                        products.add(MOTIS_MODE_MAP.get(mode));
                    }
                }

                // Clean name
                if (place != null) {
                    if (name.toUpperCase().startsWith(place.toUpperCase())) {
                        name = name.substring(place.length());
                    } else if (name.toUpperCase().endsWith(place.toUpperCase())) {
                        name = name.substring(0, name.length() - place.length());
                    }

                    final Matcher m = STOP_CLEANUP_PATTERN.matcher(name);
                    name = m.replaceAll("");
                }

                return new Location(
                        LocationType.STATION,
                        locationId,
                        Point.fromDouble(location.getDouble("lat"), location.getDouble("lon")),
                        place,
                        name,
                        products.isEmpty() ? null : products);
            default:
                throw new InvalidDataException("Unexpected MOTIS location type: " + motisType);
        }
    }

    protected static Stream<Location> parseMotisLocations(final String json) {
        try {
            return parseMotisLocations(new JSONArray(json));
        } catch (final JSONException e) {
            throw new RuntimeException(e);
        }
    }

    protected static Stream<Location> parseMotisLocations(final JSONArray data) {
        return IntStream.range(0, data.length()).mapToObj(i -> {
            try {
                return parseMotisLocation(data.getJSONObject(i));
            } catch (final JSONException | InvalidDataException e) {
                throw new RuntimeException(e);
            }
        });
    }

    protected Trip parseMotisItinerary(final JSONObject data, final TripRef ref) throws JSONException, InvalidDataException {
        // TODO: Add support for fares

        final JSONArray motisLegs = data.getJSONArray("legs");
        final List<Trip.Leg> legs = new ArrayList<>(motisLegs.length());

        for (int i = 0; i < motisLegs.length(); i++) {
            final JSONObject motisLeg = motisLegs.getJSONObject(i);

            final Stop depStop = parseMotisStop(motisLeg.getJSONObject("from"), motisLeg.getBoolean("realTime"));
            final Stop arrStop = parseMotisStop(motisLeg.getJSONObject("to"), motisLeg.getBoolean("realTime"));
            final List<Point> polyline = PolylineFormat.decode(motisLeg.getJSONObject("legGeometry").getString("points"));

            final String mode = motisLeg.getString("mode");
            if (MOTIS_INDIVIDUAL_MODE_MAP.containsKey(mode)) {
                // Individual leg
                final int distance = (int) motisLeg.optDouble("distance", 0);
                legs.add(new Trip.Individual(
                        MOTIS_INDIVIDUAL_MODE_MAP.get(mode), 
                        depStop.location, 
                        depStop.plannedDepartureTime, 
                        arrStop.location, 
                        arrStop.plannedArrivalTime, 
                        distance));
            } else if (MOTIS_MODE_MAP.containsKey(mode)) {
                // Public leg
                final String tripId = motisLeg.getString("tripId");
                final boolean realtime = motisLeg.getBoolean("realTime");
                final Line line = parseMotisLine(motisLeg);
                final JSONArray motisIntermediateStopsJson = motisLeg.getJSONArray("intermediateStops");
                final List<Stop> intermediateStops = new ArrayList<>(motisIntermediateStopsJson.length());
                for (int isi = 0; isi < motisIntermediateStopsJson.length(); isi++) {
                    intermediateStops.add(parseMotisStop(motisIntermediateStopsJson.getJSONObject(isi), realtime));
                }

                legs.add(new Trip.Public(
                        line,
                        parseMotisPlace(motisLeg.getJSONObject("tripTo")),
                        parseMotisStop(motisLeg.getJSONObject("from"), realtime),
                        parseMotisStop(motisLeg.getJSONObject("to"), realtime),
                        intermediateStops,
                        null,
                        new MotisJourneyRef(tripId)));
            } else {
                log.warn("Unknown MOTIS leg mode: {}", mode);
                continue;
            }
            legs.get(legs.size() - 1).setPath(polyline);
        }

        return new Trip(
                new Date(),
                null,
                ref,
                legs.get(0).departure,
                legs.get(legs.size() - 1).arrival,
                legs,
                null,
                null,
                data.getInt("transfers"));
    }

    protected Stream<Trip> parseMotisItineraries(final JSONArray data, final TripRef ref) {
        return IntStream.range(0, data.length()).mapToObj(i -> {
            try {
                return parseMotisItinerary(data.getJSONObject(i), ref);
            } catch (final JSONException | InvalidDataException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(final Set<LocationType> types, final Location location, final EquivalentStationsMode equivsMode, final int maxDistance, final int maxLocations, final Set<Product> products) throws IOException {
        if (location.coord == null) {
            throw new IllegalArgumentException("cannot handle: " + location);
        }

        final HttpUrl.Builder endpointBuilder = apiBase.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v1")
                .addPathSegment("reverse-geocode")
                .addQueryParameter("place", String.format(Locale.US, "%f,%f", location.coord.getLatAsDouble(), location.coord.getLonAsDouble()));
        if (maxLocations > 0) {
            endpointBuilder.addQueryParameter("numResults", String.valueOf(maxLocations));
        }
        final HttpUrl endpointWithoutType = endpointBuilder.build();

        // MOTIS API only allows one location type per request
        final List<HttpUrl> endpoints = types.stream()
                .filter(SUPPORTED_NEARBY_LOCATIONS::containsKey)
                .map(t -> endpointWithoutType.newBuilder().addQueryParameter("type", SUPPORTED_NEARBY_LOCATIONS.get(t)).build())
                .collect(Collectors.toList());

        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("No supported location types");
        }

        final List<Location> locations = new ArrayList<>();
        final ResultHeader header = new ResultHeader(network, "MOTIS");
        for (final HttpUrl endpoint : endpoints) {
            final CharSequence apiResult = httpClient.get(endpoint);
            try {
                parseMotisLocations(apiResult.toString())
                        .filter(l -> l.products == null || l.products.stream().anyMatch(products::contains))
                        .forEach(locations::add);
            } catch (final RuntimeException e) {
                if (e.getCause() instanceof JSONException) {
                    final JSONException x = (JSONException) e.getCause();
                    throw new ParserException("cannot parse json: '" + apiResult + "' on " + endpoint, x);
                }
                throw e;
            }
        }
        return new NearbyLocationsResult(header, locations);
    }

    @Override
    public QueryDeparturesResult queryDepartures(final String stationId, @Nullable final Date time, final int maxDepartures, final EquivalentStationsMode equivsMode, @Nullable final Set<Product> products) throws IOException {
        final HttpUrl.Builder endpointBuilder = apiBase.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v5")
                .addPathSegment("stoptimes")
                .addQueryParameter("stopId", stationId)
                .addQueryParameter("exactRadius", "false")
                .addQueryParameter("radius", "200");
        if (maxDepartures != 0) {
            endpointBuilder.setQueryParameter("n", String.valueOf(maxDepartures));
        } else {
            // If no maxDepartures is supplied, set a window of 1 hour
            endpointBuilder.setQueryParameter("window", String.valueOf(3600));
        }
        if (time != null) {
            endpointBuilder.addQueryParameter("time", DateTimeFormatter.ISO_DATE_TIME.format(time.toInstant().atZone(ZoneId.of("UTC"))));
        }
        
        if (products != null && !products.isEmpty()) {
            final List<String> motisModes = new ArrayList<>();
            if (products.contains(Product.HIGH_SPEED_TRAIN) && products.contains(Product.REGIONAL_TRAIN)) {
                // All train types included, so include the catch-all category as well
                motisModes.add("RAIL");
            }
            for (final Product p : products) {
                Collections.addAll(motisModes, MODE_MOTIS_MAP.get(p));
            }

            endpointBuilder.addQueryParameter("mode", String.join(",", motisModes));
        }

        final HttpUrl endpoint = endpointBuilder.build();

        final QueryDeparturesResult result = new QueryDeparturesResult(new ResultHeader(network, "MOTIS"));
        final Map<String, StationDepartures> stationMap = new HashMap<>();
        final Set<String> encounteredLines = new HashSet<>();

        try {
            final CharSequence apiResult = httpClient.get(endpoint);
            try {
                final JSONObject data = new JSONObject(apiResult.toString());

                if (data.has("error")) {
                    return new QueryDeparturesResult(new ResultHeader(network, "MOTIS"), QueryDeparturesResult.Status.INVALID_STATION);
                }

                final JSONArray stopTimes = data.getJSONArray("stopTimes");
                for (int i = 0; i < stopTimes.length(); i++) {
                    final JSONObject stopTime = stopTimes.getJSONObject(i);
                    final JSONObject place = stopTime.getJSONObject("place");
                    final String tripId = stopTime.getString("tripId");
                    final String departureStopId = place.getString("stopId");

                    if (equivsMode == EquivalentStationsMode.KEEP_DISTINCT && !stationId.equals(departureStopId)) {
                        continue;
                    }

                    StationDepartures sd = stationMap.get(departureStopId);
                    if (sd == null) {
                        sd = new StationDepartures(
                                parseMotisPlace(place),
                                new ArrayList<>(),
                                new ArrayList<>());
                        result.stationDepartures.add(sd);
                        stationMap.put(departureStopId, sd);
                    }

                    final Line line = parseMotisLine(stopTime);

                    final Location destination = parseMotisPlace(stopTime.getJSONObject("tripTo"));

                    final TimeZone timeZone = getMotisTimeZone(place);
                    sd.departures.add(new Departure(
                            parseMotisDateTime(place.getString("scheduledDeparture"), timeZone),
                            parseMotisDateTime(place.getString("departure"), timeZone),
                            line,
                            place.has("scheduledTrack") ? new Position(place.getString("scheduledTrack")) : null,
                            place.has("track") ? new Position(place.getString("track")) : null,
                            destination,
                            stopTime.has("cancelled") && stopTime.getBoolean("cancelled"),
                            null,
                            null,
                            new MotisJourneyRef(tripId)));
                    if (!encounteredLines.contains(line.id)) {
                        assert sd.lines != null;
                        sd.lines.add(new LineDestination(line, destination));
                        encounteredLines.add(line.id);
                    }
                }
            } catch (final JSONException x) {
                throw new ParserException("cannot parse json: '" + apiResult + "' on " + endpoint, x);
            }
        } catch (final NotFoundException x) {
            return new QueryDeparturesResult(new ResultHeader(network, "MOTIS"), QueryDeparturesResult.Status.INVALID_STATION);
        }

        return result;
    }

    protected int getSuggestedLocationsServerLimit() {
        return 10;
    }

    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint, @Nullable final Set<LocationType> types, final int maxLocations) throws IOException {
        final HttpUrl.Builder endpointBuilder = apiBase.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v1")
                .addPathSegment("geocode")
                .addQueryParameter("text", constraint.toString());
        if (maxLocations > 0) {
            endpointBuilder.addQueryParameter("numResults", String.valueOf(
                    Math.min(maxLocations, getSuggestedLocationsServerLimit())));
        }
        final HttpUrl endpoint = endpointBuilder.build();

        // One could use the query parameter type and make one request per type,
        // but this would needlessly spam the server,
        // so instead client side filtering is employed

        final CharSequence apiResult = httpClient.get(endpoint);
        try {
            final Stream<Location> locations = parseMotisLocations(apiResult.toString());
            return new SuggestLocationsResult(
                    new ResultHeader(network, "MOTIS"),
                    locations
                            .filter(l -> types == null || types.contains(l.type))
                            .map(SuggestedLocation::new)
                            .collect(Collectors.toList()));
        } catch (final RuntimeException e) {
            if (e.getCause() instanceof JSONException) {
                final JSONException x = (JSONException) e.getCause();
                throw new ParserException("cannot parse json: '" + apiResult + "' on " + endpoint, x);
            }
            throw e;
        }
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new MotisTripRef(network, unpacker);
    }

    @Override
    public QueryTripsResult queryTrips(final Location from, @Nullable final Location via, final Location to, final Date date, final boolean dep, @Nullable final TripOptions options, final boolean loadPath) throws IOException {
        final HttpUrl.Builder endpointBuilder = apiBase.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v5")
                .addPathSegment("plan")
                .addQueryParameter("detailedLegs", "true");

        // TODO: Uncomment when support for fares is added in parseMotisItinerary
        // endpointBuilder.addQueryParameter("withFares", "true");

        if (from.type == LocationType.STATION) {
            endpointBuilder.addQueryParameter("fromPlace", from.id);
        } else if (from.coord != null) {
            endpointBuilder.addQueryParameter("fromPlace", String.format(Locale.US, "%f,%f", from.coord.getLatAsDouble(), from.coord.getLonAsDouble()));
        } else {
            throw new IllegalArgumentException("from needs to be stop or have coordinates: " + to);
        }

        if (via != null) {
            if (via.type != LocationType.STATION) {
                throw new IllegalArgumentException("via only a stop: " + via);
            }
            endpointBuilder.addQueryParameter("via", via.id);
        }

        if (to.type == LocationType.STATION) {
            endpointBuilder.addQueryParameter("toPlace", to.id);
        } else if (to.coord != null) {
            endpointBuilder.addQueryParameter("toPlace", String.format(Locale.US, "%f,%f", to.coord.getLatAsDouble(), to.coord.getLonAsDouble()));
        } else {
            throw new IllegalArgumentException("to needs to be stop or have coordinates: " + to);
        }

        endpointBuilder.addQueryParameter("time", DateTimeFormatter.ISO_DATE_TIME.format(date.toInstant().atZone(ZoneId.of("UTC"))));

        endpointBuilder.addQueryParameter("arriveBy", String.valueOf(!dep));

        if (options != null) {
            if (options.accessibility == Accessibility.BARRIER_FREE) {
                endpointBuilder.addQueryParameter("pedestrianProfile", "WHEELCHAIR");
            }
            if (options.flags != null) {
                if (options.flags.contains(TripFlag.BIKE)) {
                    endpointBuilder.addQueryParameter("requireBikeTransport", "true");
                }
                if (options.flags.contains(TripFlag.DIRECT)) {
                    endpointBuilder.addQueryParameter("maxTransfers", "0");
                }
            }
            // TODO: Figure out how to map walking speed enum to API walking speeds
            if (options.products != null) {
                final List<String> motisModes = new ArrayList<>();
                if (options.products.contains(Product.HIGH_SPEED_TRAIN) && options.products.contains(Product.REGIONAL_TRAIN)) {
                    // All train types included, so include the catch-all category as well
                    motisModes.add("RAIL");
                }
                for (final Product p : options.products) {
                    Collections.addAll(motisModes, MODE_MOTIS_MAP.get(p));
                }

                endpointBuilder.addQueryParameter("transitModes", String.join(",", motisModes));
            }
            if (via != null && options.minTransferTimeMinutes != null) {
                endpointBuilder.addQueryParameter("viaMinimumStay", String.valueOf(options.minTransferTimeMinutes));
            }
        }

        final HttpUrl endpoint = endpointBuilder.build();

        return actualQueryTrips(endpoint, from, via, to, loadPath);

    }

    @Override
    public QueryTripsResult queryMoreTrips(final QueryTripsContext context, final boolean later, final boolean loadPath) throws IOException {
        if (!(context instanceof MotisTripRef)) {
            throw new IllegalArgumentException("Wrong context");
        }
        final String pageCursor = later ? ((MotisTripRef) context).nextPageCursor : ((MotisTripRef) context).previousPageCursor;
        if (pageCursor == null) {
            return new QueryTripsResult(new ResultHeader(network, "MOTIS"), QueryTripsResult.Status.NO_TRIPS);
        }

        final HttpUrl.Builder b = ((MotisTripRef) context).getEndpoint().newBuilder();
        b.addQueryParameter("pageCursor", pageCursor);
        final HttpUrl endpointWithCursor = b.build();

        return actualQueryTrips(endpointWithCursor, ((MotisTripRef) context).from, ((MotisTripRef) context).via, ((MotisTripRef) context).to, loadPath);
    }

    protected QueryTripsResult actualQueryTrips(@Nonnull final HttpUrl endpoint, @Nonnull final Location from, @Nullable final Location via, @Nonnull final Location to, final boolean loadPath) throws IOException {
        final HttpUrl.Builder b = endpoint.newBuilder();
        b.removeAllQueryParameters("detailedTransfers");
        
        // TODO: Uncomment when loadPath is not always false
        // detailedTransfers are needed in order to obtain the distance when walking from one stop to another
        
        // if (!loadPath) {
        //     b.setQueryParameter("detailedTransfers", "false");
        // }
        final CharSequence apiResult = httpClient.get(b.build(), 30);

        try {
            final JSONObject data = new JSONObject(apiResult.toString());
            final JSONArray itineraries = data.getJSONArray("itineraries");
            final JSONArray direct = data.getJSONArray("direct");
            final List<Trip> trips = new ArrayList<>(itineraries.length() + direct.length());

            try {
                parseMotisItineraries(itineraries, new MotisTripRef(network, endpoint, from, via, to, null, null)).forEach(trips::add);
                parseMotisItineraries(direct, new MotisTripRef(network, endpoint, from, via, to, null, null)).forEach(trips::add);
            } catch (final RuntimeException e) {
                if (e.getCause() instanceof JSONException) {
                    throw (JSONException) e.getCause();
                }
                throw e;
            }

            return new QueryTripsResult(
                    new ResultHeader(network, "MOTIS"),
                    endpoint.toString(),
                    from,
                    via,
                    to,
                    new MotisTripRef(network, endpoint, from, via, to, data.optString("nextPageCursor", null), data.optString("previousPageCursor", null)),
                    trips
            );
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + apiResult + "' on " + endpoint, x);
        }
    }

    @Override
    public Trip queryTripDetails(final Trip trip, final List<TripDetails> whichDetails) throws IOException {
        return super.queryTripDetails(trip, whichDetails);
    }

    @Override
    public QueryJourneyResult queryJourney(final JourneyRef journeyRef, final boolean loadPath) throws IOException {
        final HttpUrl.Builder endpointBuilder = apiBase.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v5")
                .addPathSegment("trip")
                .addQueryParameter("tripId", journeyRef.getUniqueId())
                .addQueryParameter("detailedLegs", String.valueOf(loadPath));
        
        final HttpUrl endpoint = endpointBuilder.build();

        final CharSequence apiResult = httpClient.get(endpoint);

        try {
            final JSONObject data = new JSONObject(apiResult.toString());
            
            final Trip trip = parseMotisItinerary(data, null);

            return new QueryJourneyResult(
                    new ResultHeader(network, "MOTIS"),
                    endpoint.toString(),
                    journeyRef,
                    (Trip.Public) trip.legs.get(0)
            );
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + apiResult + "' on " + endpoint, x);
        }
    }

    @Override
    public QueryTripsResult queryReloadTrip(final TripRef tripRef, final boolean loadPath) throws IOException {
        if (tripRef.network != network || !(tripRef instanceof MotisTripRef)) {
            throw new IllegalArgumentException("cannot handle: " + tripRef);
        }
        final MotisTripRef ctx = (MotisTripRef) tripRef;
        return this.actualQueryTrips(ctx.getEndpoint(), tripRef.from, tripRef.via, tripRef.to, loadPath);
    }
}
