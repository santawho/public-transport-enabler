package de.schildbach.pte.provider.motis;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.Standard;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import de.schildbach.pte.provider.NetworkProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.LineDestination;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.PTDate;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.exception.InternalErrorException;
import okhttp3.HttpUrl;

public abstract class AbstractMotisProvider extends AbstractNetworkProvider {
    HttpUrl api;

    private static class Context implements QueryTripsContext {
        private static final long serialVersionUID = -1372763740044812765L;

        public @Nullable String previousCursor;
        public @Nullable String nextCursor;
        public String url;
        public Location from;
        public Location via;
        public Location to;
        public Date date;

        Context(final String url, @Nullable final String previousCursor, @Nullable final String nextCursor, final Location from, final Location via, final Location to, final Date date) {
            this.url = url;
            this.previousCursor = previousCursor;
            this.nextCursor = nextCursor;
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
        }

        @Override
        public boolean canQueryLater() {
            return nextCursor != null;
        }

        @Override
        public boolean canQueryEarlier() {
            return previousCursor != null;
        }
    }

    private final Set<NetworkProvider.Capability> CAPABILITIES = Set.of(Capability.SUGGEST_LOCATIONS, Capability.TRIPS, NetworkProvider.Capability.DEPARTURES);

    protected static final String SERVER_PRODUCT = "MOTIS";


    public AbstractMotisProvider(final NetworkId networkId, final String apiUrl) {
        super(networkId);
        api = HttpUrl.parse(apiUrl).newBuilder().addPathSegment("api").build();
        httpClient.setUserAgent("oeffi-ng test implementation https://github.com/jendrikw/public-transport-enabler");
    }


    @Override
    protected Set<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    private @Nullable String getBoundary(final JSONArray boundaries, final int max) throws JSONException {
        for (int i = 0; i < boundaries.length(); i++) {
            final JSONObject boundary = boundaries.getJSONObject(boundaries.length() - 1 - i);
            if (boundary.getInt("adminLevel") <= max) {
                return boundary.getString("name");
            }
        }

        return null;
    }

    private @Nullable String getCity(final JSONArray boundaries) throws JSONException {
        return getBoundary(boundaries, 8);
    }

    private @Nullable String getCountry(final JSONArray boundaries) throws JSONException {
        return getBoundary(boundaries, 2);
    }

    private LocationType parseLocationType(final String type) {
        switch (type) {
            case "STOP":
                return LocationType.STATION;
            case "PLACE":
                return LocationType.POI;
            case "ADDRESS":
                return LocationType.ADDRESS;
            default:
                return LocationType.ANY;
        }
    }

    private String locationTypesToString(final Set<LocationType> types) {
        if (types.size() == 1) {
            final LocationType type = types.iterator().next();
            switch (type) {
                case ADDRESS:
                    return "ADDRESS";
                case STATION:
                    return "STOP";
                case POI:
                    return "PLACE";
                case ANY:
                case COORD:
                default:
                    return null;
            }
        } else {
            //string (LocationType)
            //Enum: "ADDRESS" "PLACE" "STOP"
            //Optional. Default is all types.
            return null;
        }
    }

    private @Nullable Style parseStyle(final JSONObject obj, final Product product) throws JSONException {
        if (obj.has("routeColor")) {
            final int backgroundColor = Style.parseColor("#" + obj.getString("routeColor"));

            final int foregroundColor;
            if (obj.has("routeTextColor")) {
                foregroundColor = Style.parseColor("#" + obj.getString("routeTextColor"));
            } else {
                foregroundColor = Style.deriveForegroundColor(backgroundColor);
            }

            final Style productDefaultStyle = Standard.STYLES.get(product);

            return new Style(
                    productDefaultStyle != null ? productDefaultStyle.shape : Style.Shape.RECT,
                    backgroundColor,
                    foregroundColor
            );
        }

        return null;
    }

    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint, @Nullable final Set<LocationType> types, final int maxLocations) throws IOException {
        final HttpUrl url = api.newBuilder().addPathSegment("v1").addPathSegment("geocode").addQueryParameter("text", constraint.toString()).build();

        final CharSequence response = httpClient.get(url);

        final List<SuggestedLocation> suggestions = new ArrayList<>();

        try {

            final JSONArray json = new JSONArray(response.toString());
            final ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);

            for (int i = 0; i < json.length(); i++) {
                final JSONObject guessObj = json.getJSONObject(i);
                final JSONArray boundaries = guessObj.getJSONArray("areas");


                String suggestedName = null;
                final String city = getCity(boundaries);
                final String country = getCountry(boundaries);
                if (city != null && country != null) {
                    suggestedName = city + ", " + country;
                }

                final LocationType type = parseLocationType(guessObj.getString("type"));
                final SuggestedLocation loc = new SuggestedLocation(new Location(type, type == LocationType.STATION ? guessObj.getString("id") : null, Point.fromDouble(guessObj.getDouble("lat"), guessObj.getDouble("lon")), suggestedName, guessObj.getString("name")));
                suggestions.add(loc);
            }

            return new SuggestLocationsResult(header, suggestions);
        } catch (final JSONException exc) {
            throw new IOException(exc.toString());
        }
    }

    private Product productFromString(final String mode) {
        switch (mode) {
            case "BUS":
            case "COACH":
                return Product.BUS;
            case "SUBWAY":
                return Product.SUBWAY;
            case "METRO":
            case "SUBURBAN":
                return Product.SUBURBAN_TRAIN;
            case "REGIONAL_RAIL":
            case "REGIONAL_FAST_RAIL":
                return Product.REGIONAL_TRAIN;
            case "TRAM":
                return Product.TRAM;
            case "FERRY":
                return Product.FERRY;
            case "NIGHT_RAIL":
            case "LONG_DISTANCE":
            case "HIGHSPEED_RAIL":
                return Product.HIGH_SPEED_TRAIN;
            case "ODM":
            case "FLEX":
                return Product.ON_DEMAND;
            case "CABLE_CAR":
            case "FUNICULAR":
            case "AERIAL_LIFT":
            case "AREAL_LIFT":
                return Product.CABLECAR;
            case "WALK":
            case "BIKE":
            case "RENTAL":
            case "CAR":
            case "CAR_PARKING":
            case "CAR_DROPOFF":
            case "RIDE_SHARING":
                return null;
            default:
                return Product.UNKNOWN;
        }
    }

    private void addToStringListFromProduct(final ArrayList<String> list, final Product product) {
        switch (product) {
            case TRAM:
                list.add("TRAM");
                break;
            case SUBWAY:
                list.add("SUBWAY");
                break;
            case FERRY:
                list.add("FERRY");
                break;
            case BUS:
                list.add("BUS");
                list.add("COACH");
                break;
            case REGIONAL_TRAIN:
                list.add("REGIONAL_RAIL");
                list.add("REGIONAL_FAST_RAIL");
                break;
            case SUBURBAN_TRAIN:
                list.add("SUBURBAN");
                break;
            case HIGH_SPEED_TRAIN:
                list.add("HIGHSPEED_RAIL");
                list.add("LONG_DISTANCE");
                list.add("NIGHT_RAIL");
                break;
        }
    }

    private String stringFromLocation(final Location loc) {
        if (loc.hasCoord()) {
            return String.format(Locale.US, "%f,%f", loc.getLatAsDouble(), loc.getLonAsDouble());
        } else if (loc.name != null && loc.name.contains(",")) {
            final String[] result = loc.name.split(",");

            try {
                Float.parseFloat(result[0]);
                Float.parseFloat(result[1]);
                return loc.name;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException();
            }
        } else {
            return loc.id;
        }
    }

    private Location parseLocation(final JSONObject loc, final String name) throws JSONException {
        final Point coords = Point.fromDouble(loc.getDouble("lat"), loc.getDouble("lon"));
        if (loc.has("stopId")) {
            return new Location(LocationType.STATION, loc.getString("stopId"), coords, "", name);
        } else {
            return new Location(LocationType.ANY, null, coords, "", name);
        }
    }

    private PTDate dateFromString(@Nonnull final String isoDate, @Nullable final String timezone) {
        if (timezone == null) {
            final Date date = Date.from(DateTimeFormatter.ISO_INSTANT.parse(isoDate, Instant::from));
            return PTDate.withUnknownLocationSpecificOffset(date.getTime());
        }
        final ZoneId zoneid = ZoneId.of(timezone);
        final TimeZone tz = TimeZone.getTimeZone(zoneid);
        final Date date = Date.from(DateTimeFormatter.ISO_INSTANT.parse(isoDate, Instant::from));
        return new PTDate(date, tz);
    }

    private Trip.Leg parseTripLegIndividual(final JSONObject leg, final Trip.Individual.Type tripType, final Context ctx) throws JSONException {
        final int distance = leg.has("distance") ? leg.getInt("distance") : 0;

        final JSONObject legFrom = leg.getJSONObject("from");
        final JSONObject legTo = leg.getJSONObject("to");

        String startName = legFrom.getString("name");
        if (startName.equals("START")) startName = ctx.from.name;
        if (startName == null && ctx.from.coord != null) startName = ctx.from.coord.toString();
        String destName = legTo.getString("name");
        if (destName.equals("END")) destName = ctx.to.name;
        if (destName == null && ctx.to.coord != null) destName = ctx.to.coord.toString();

        final Location fromLocation = parseLocation(legFrom, startName);
        final Location toLocation = parseLocation(legTo, destName);

        final PTDate departureTime = dateFromString(legFrom.getString("departure"), null);
        final PTDate arrivalTime = dateFromString(legTo.getString("arrival"), null);

        // todo: parse legGeometry
        return new Trip.Individual(tripType, fromLocation, departureTime, toLocation, arrivalTime, null, distance);
    }

    private Stop parseStop(final JSONObject stop, final boolean realTime) throws JSONException {
        final Location location = parseLocation(stop, stop.getString("name"));
        final PTDate departureTime = realTime && stop.has("departure") ? dateFromString(stop.getString("departure"), stop.getString("tz")) : null;
        final PTDate arrivalTime = realTime && stop.has("arrival") ? dateFromString(stop.getString("arrival"), stop.getString("tz")) : null;
        final PTDate plannedDepartureTime = stop.has("scheduledDeparture") ? dateFromString(stop.getString("scheduledDeparture"), stop.getString("tz")) : null;
        final PTDate plannedArrivalTime = stop.has("scheduledArrival") ? dateFromString(stop.getString("scheduledArrival"), stop.getString("tz")) : null;
        final boolean cancelled = stop.getBoolean("cancelled");

        final Position plannedTrack = stop.has("scheduledTrack") ? new Position(stop.getString("scheduledTrack")) : null;
        final Position track = realTime && stop.has("track") ? new Position(stop.getString("track")) : null;

        return new Stop(location, plannedArrivalTime, arrivalTime, plannedTrack, track, cancelled, plannedDepartureTime, departureTime, plannedTrack, track, cancelled);
    }

    private Trip.Leg parseTripLegPublic(final JSONObject leg) throws JSONException {
        final boolean realTime = leg.getBoolean("realTime");

        final JSONArray stopsJson = leg.getJSONArray("intermediateStops");
        final ArrayList<Stop> stops = new ArrayList<>();
        for (int k = 0; k < stopsJson.length(); k++) {
            final JSONObject stopJson = stopsJson.getJSONObject(k);

            stops.add(parseStop(stopJson, realTime));
        }

        final Line line = parseLine(leg);

        final Location destination = new Location(LocationType.STATION, null, null, leg.getString("headsign"));

        return new Trip.Public(line, destination, parseStop(leg.getJSONObject("from"), realTime), parseStop(leg.getJSONObject("to"), realTime), stops, null, // todo: parse legGeometry
                "tripId: " + leg.optString("tripId"));
    }

    @Nonnull
    private Line parseLine(JSONObject leg) throws JSONException {
        final String label = leg.optString("displayName");
        final String name = leg.optString("routeShortName");
        final Product product = productFromString(leg.getString("mode"));
        final Style style = lineStyle(null, product, null, parseStyle(leg, product));
        return new Line(null, null, product, label.isEmpty() ? null : label, name.isEmpty() ? null : name, style);
    }

    private Trip.Leg parseTripLeg(final JSONObject leg, final Context ctx) throws JSONException {
        final Trip.Individual.Type tripType;
        switch (leg.getString("mode")) {
            case "WALK":
                tripType = Trip.Individual.Type.WALK;
                break;
            case "BIKE":
                tripType = Trip.Individual.Type.BIKE;
                break;
            case "CAR":
            case "CAR_PARKING":
            case "CAR_DROPOFF":
                tripType = Trip.Individual.Type.CAR;
                break;
            default:
                tripType = null;
        }
        if (tripType != null) {
            return parseTripLegIndividual(leg, tripType, ctx);
        } else {
            return parseTripLegPublic(leg);
        }
    }

    private QueryTripsResult parseQueryTripsResult(final CharSequence response, final Context ctx, final boolean later) throws JSONException {
        final JSONObject obj = new JSONObject(response.toString());
        ArrayList<Trip> trips = new ArrayList<>();

        final JSONArray itineraries = obj.getJSONArray("itineraries");
        for (int i = 0; i < itineraries.length(); i++) {
            parseItinerary(ctx, itineraries.getJSONObject(i), trips);
        }

        final JSONArray direct = obj.getJSONArray("direct");
        for (int i = 0; i < direct.length(); i++) {
            parseItinerary(ctx, direct.getJSONObject(i), trips);
        }

        final ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
        if (trips.isEmpty()) {
            return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
        }

        // HACK: Since we truncate the number of results on the client side to avoid hitting binder limitations (TransactionTooLargeException),
        // we can't use the string provided by MOTIS.
//        final Context ctxNew = new Context(ctx);
        if (!obj.optString("previousPageCursor").isEmpty()) ctx.previousCursor = obj.getString("previousPageCursor");
        if (!obj.optString("nextPageCursor").isEmpty()) ctx.nextCursor = obj.getString("nextPageCursor");
        return new QueryTripsResult(header, ctx.url, ctx.from, ctx.via, ctx.to, ctx, trips);
    }

    private void parseItinerary(Context ctx, JSONObject itinerary, ArrayList<Trip> trips) throws JSONException {
        final JSONArray legsJson = itinerary.getJSONArray("legs");

        final ArrayList<Trip.Leg> legs = new ArrayList<>();
        for (int j = 0; j < legsJson.length(); j++) {
            final JSONObject leg = legsJson.getJSONObject(j);
            legs.add(parseTripLeg(leg, ctx));
        }

        final Trip trip = new Trip(new Date(), String.format("%s", legs.toString().hashCode()), null, ctx.from, ctx.to, legs, null, null, itinerary.getInt("transfers"));
        trips.add(trip);
    }

    @Override
    public QueryTripsResult queryTrips(final Location from, final @Nullable Location via, final Location to, final Date date, final boolean dep, @Nullable final TripOptions options) throws IOException {
        String transitModes = "TRANSIT";
        if (options != null && options.products != null) {
            final ArrayList<String> transitModesBuilder = new ArrayList<>();
            for (final Product product : options.products) {
                addToStringListFromProduct(transitModesBuilder, product);
            }
            transitModes = String.join(",", transitModesBuilder);
        }

        final HttpUrl.Builder builder = api.newBuilder().addPathSegment("v4").addPathSegment("plan").addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(date.toInstant())).addQueryParameter("fromPlace", stringFromLocation(from)).addQueryParameter("toPlace", stringFromLocation(to)).addQueryParameter("transitModes", transitModes);

        final HttpUrl url = builder.build();
        try {
            final CharSequence response = httpClient.get(url);
            try {
                final Context contextObj = new Context(url.toString(), null, null, from, via, to, date);
                return parseQueryTripsResult(response, contextObj, true);
            } catch (final JSONException e) {
                throw new RuntimeException(e);
            }
        } catch (final InternalErrorException e) {
            return new QueryTripsResult(new ResultHeader(network, SERVER_PRODUCT), QueryTripsResult.Status.UNKNOWN_LOCATION);
        }

    }

    @Override
    public QueryTripsResult queryMoreTrips(final QueryTripsContext contextObj, final boolean later) throws IOException {
        final Context ctx = (Context) contextObj;
        final HttpUrl.Builder builder = HttpUrl.parse(ctx.url).newBuilder().addQueryParameter("pageCursor", later ? ctx.nextCursor : ctx.previousCursor);

        final HttpUrl url = builder.build();
        final CharSequence response = httpClient.get(url);

        try {
            return parseQueryTripsResult(response, ctx, later);
        } catch (final JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public QueryDeparturesResult queryDepartures(final String stationId, @Nullable final Date time, final int maxDepartures, final boolean equivs) throws IOException {
        return internQueryDepartures(stationId, time, maxDepartures, equivs).result;
    }

    public static class MotisQueryDeparturesResult {
        final QueryDeparturesResult result;
        final Location from;

        MotisQueryDeparturesResult(final QueryDeparturesResult result, final Location from) {
            this.result = result;
            this.from = from;
        }
    }

    public MotisQueryDeparturesResult internQueryDepartures(final String stationId, @Nullable final Date time, final int maxDepartures, final boolean equivs) throws IOException {
        final ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);

        try {
            final HttpUrl url = api.newBuilder().addPathSegment("v5").addPathSegment("stoptimes").addQueryParameter("stopId", stationId).addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(time.toInstant())).addQueryParameter("n", String.format(Locale.US, "%d", maxDepartures)).addQueryParameter("radius", "100").build();
            final CharSequence response = httpClient.get(url);
            final JSONObject json = new JSONObject(response.toString());
            final JSONObject from = json.getJSONObject("place");

            final MotisQueryDeparturesResult result = new MotisQueryDeparturesResult(new QueryDeparturesResult(header), new Location(LocationType.STATION, from.getString("stopId"), Point.fromDouble(from.getDouble("lat"), from.getDouble("lon"))));

            // departures by stop id
            final HashMap<String, ArrayList<Departure>> departures = new HashMap<>();

            // stop location by stop id
            final HashMap<String, Location> stops = new HashMap<>();

            // lines
            final HashMap<String, ArrayList<LineDestination>> lines = new HashMap<>();

            final JSONArray departuresJson = json.getJSONArray("stopTimes");
            for (int i = 0; i < departuresJson.length(); i++) {
                final JSONObject stopTime = departuresJson.getJSONObject(i);

                final JSONObject place = stopTime.getJSONObject("place");


                // skip arrivals
                if (!place.has("scheduledDeparture") || !place.has("departure")) {
                    continue;
                }

                final String stopId = place.getString("stopId");

                // line
                final Line line = parseLine(stopTime);
                final Location destination = new Location(LocationType.STATION, null, null, stopTime.getString("headsign"));
                final LineDestination lineDestination = new LineDestination(line, destination);

                if (lines.containsKey(stopId)) {
                    Objects.requireNonNull(lines.get(stopId)).add(lineDestination);
                } else {
                    lines.put(stopId, new ArrayList<LineDestination>(Collections.singletonList(lineDestination)));
                }

                // location
                final Location stop = new Location(LocationType.STATION, place.getString("stopId"), Point.fromDouble(place.getDouble("lat"), place.getDouble("lon")), null, place.getString("name"));
                stops.put(place.getString("stopId"), stop);

                // departure
                final PTDate plannedDepartureTime = dateFromString(place.getString("scheduledDeparture"), place.getString("tz"));
                final PTDate departureTime = dateFromString(place.getString("departure"), place.getString("tz"));

                final Departure departure = new Departure(plannedDepartureTime, departureTime, line, null, null, destination, false, null, null, null);
                if (departures.containsKey(stopId)) {
                    departures.get(stopId).add(departure);
                } else {
                    departures.put(stopId, new ArrayList<>(Collections.singletonList(departure)));
                }
            }

            for (final String stopId : departures.keySet()) {
                final StationDepartures station = new StationDepartures(stops.get(stopId), departures.get(stopId), new ArrayList<>(lines.get(stopId)));
                result.result.stationDepartures.add(station);
            }

            return result;
        } catch (final InternalErrorException e) {
            return new MotisQueryDeparturesResult(new QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION), null);
        } catch (final JSONException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(final Set<LocationType> ls, final Location queryLocation, final int maxDistance, final int maxLocations) throws IOException {
        Point coord = queryLocation.coord;
        if (coord == null) {
            final MotisQueryDeparturesResult departures = internQueryDepartures(queryLocation.id, new Date(), 0, false);
            coord = departures.from.coord;
        }
        final HttpUrl.Builder builder = api.newBuilder().addPathSegment("v1").addPathSegment("reverse-geocode").addEncodedQueryParameter("place", coord.getLatAsDouble() + "," + coord.getLonAsDouble());
        final String locationType = locationTypesToString(ls);
        if (locationType != null) {
            builder.addQueryParameter("type", locationType);
        }
        final HttpUrl url = builder.build();
        final String response = httpClient.get(url).toString();
        final ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
        if (!response.startsWith("[")) {
            return new NearbyLocationsResult(header, NearbyLocationsResult.Status.SERVICE_DOWN);
        }
        try {
            final JSONArray json = new JSONArray(response);
            final int length = maxLocations > 0 ? Math.min(maxLocations, json.length()) : json.length();
            final List<Location> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                final JSONObject loc = json.getJSONObject(i);
                final String type = loc.getString("type");
                String id = loc.getString("id");
                if (id.isEmpty()) {
                    id = null;
                }
                final double lat = loc.getDouble("lat");
                final double lon = loc.getDouble("lon");
                final String name = loc.getString("name");
                Location l = new Location(parseLocationType(type), id, Point.fromDouble(lat, lon), null, name);
                result.add(l);
            }
            return new NearbyLocationsResult(header, result);
        } catch (final JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
