package de.schildbach.pte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import org.json.JSONException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.LineDestination;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;

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
import okhttp3.HttpUrl;

public abstract class AbstractMOTISProvider extends AbstractNetworkProvider {
    HttpUrl api;

    private static class Context implements QueryTripsContext {
        public @Nullable String previousCursor;
        public @Nullable String nextCursor;
        public String url;
        public Location from;
        public Location via;
        public Location to;
        public Date date;

        Context(String url,
                String previousCursor, String nextCursor,
                Location from, Location via, Location to, Date date) {
            this.url = url;
            this.previousCursor = previousCursor;
            this.nextCursor = nextCursor;
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
        }

        Context(Context copyFrom) {
            this.url = copyFrom.url;
            this.previousCursor = copyFrom.previousCursor;
            this.nextCursor = copyFrom.nextCursor;
            this.from = copyFrom.from;
            this.via = copyFrom.via;
            this.to = copyFrom.to;
            this.date = copyFrom.date;
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

    private final List<Capability> CAPABILITIES = Arrays.asList(
            Capability.SUGGEST_LOCATIONS,
            Capability.TRIPS,
            Capability.DEPARTURES
    );

    protected static final String SERVER_PRODUCT = "MOTIS";

    public AbstractMOTISProvider(NetworkId networkId, String apiUrl) {
        super(networkId);
        api = HttpUrl.parse(apiUrl).newBuilder().addPathSegment("api").build();
    }

    @Override
    public boolean hasCapability(Capability cap) {
        return CAPABILITIES.contains(cap);
    }

    private @Nullable String getBoundary(JSONArray boundaries, int max) throws JSONException {
        for (int i = 0; i < boundaries.length(); i++) {
            JSONObject boundary = boundaries.getJSONObject(boundaries.length() - 1 - i);
            if (boundary.getInt("adminLevel") <= max) {
                return boundary.getString("name");
            }
        }

        return null;
    }

    private @Nullable String getCity(JSONArray boundaries) throws JSONException {
        return getBoundary(boundaries, 8);
    }

    private @Nullable String getCountry(JSONArray boundaries) throws JSONException {
        return getBoundary(boundaries, 2);
    }

    private LocationType parseLocationType(String type) {
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

    private @Nullable Style parseStyle(JSONObject obj) {
        if (obj.has("routeColor")) {
            int backgroundColor = Style.parseColor("#" + obj.getString("routeColor"));
            int foregroundColor;

            if (obj.has("routeTextColor")) {
                foregroundColor = Style.parseColor("#" + obj.getString("routeTextColor"));
            } else {
                foregroundColor = Style.BLACK;
            }
            return new Style(backgroundColor, foregroundColor);
        }

        return null;
    }

    @Override
    public SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types,
                                                   int maxLocations) throws IOException {
        HttpUrl url = api.newBuilder().addPathSegment("v1").addPathSegment("geocode").addQueryParameter("text", constraint.toString()).build();

        CharSequence response = httpClient.get(url);

        List<SuggestedLocation> suggestions = new ArrayList<>();

        try {

            JSONArray json = new JSONArray(response.toString());
            ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);

            for (int i = 0; i < json.length(); i++) {
                JSONObject guessObj = json.getJSONObject(i);
                JSONArray boundaries = guessObj.getJSONArray("areas");


                String suggestedName = null;
                String city = getCity(boundaries);
                String country = getCountry(boundaries);
                if (city != null && country != null) {
                    suggestedName = city + ", " + country;
                }

                LocationType type = parseLocationType(guessObj.getString("type"));
                SuggestedLocation loc = new SuggestedLocation(new Location(type,
                        type == LocationType.STATION ? guessObj.getString("id") : null,
                        Point.fromDouble(guessObj.getDouble("lat"), guessObj.getDouble("lon")),
                        suggestedName,
                        guessObj.getString("name")));
                suggestions.add(loc);
            }

            return new SuggestLocationsResult(header, suggestions);
        } catch (JSONException exc) {
            throw new IOException(exc.toString());
        }
    }

    private Product productFromString(String mode) {
        switch (mode) {
            case "BUS":
            case "COACH":
                return Product.BUS;
            case "SUBWAY":
                return Product.SUBWAY;
            case "METRO":
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
            default:
                return null;
        }
    }

    private void addToStringListFromProduct(ArrayList<String> list, Product product) {
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
                list.add("METRO");
                break;
            case HIGH_SPEED_TRAIN:
                list.add("RAIL");
                break;
        }
    }

    private String stringFromLocation(Location loc) {
        // TODO: is level=0 really the right value here?
        return loc.id != null ? loc.id : String.format(Locale.US, "%f,%f,0", loc.getLatAsDouble(), loc.getLonAsDouble());
    }

    private Location locationFromJSON(JSONObject loc, String name) throws JSONException {
        Point coords = Point.fromDouble(loc.getDouble("lat"), loc.getDouble("lon"));
        if (loc.has("stopId")) {
            return new Location(LocationType.STATION, loc.getString("stopId"), coords, "", name);
        } else {
            return new Location(LocationType.ANY, null, coords, "", name);
        }
    }

    private Date dateFromString(String isoDate) {
        return Date.from(DateTimeFormatter.ISO_INSTANT.parse(isoDate, Instant::from));
    }

    private Trip.Leg parseTripLegIndividual(JSONObject leg, Location from, Date departure, Location to, Date arrival) throws JSONException {
        int distance = leg.has("distance") ? leg.getInt("distance") : 0;

        // todo: parse legGeometry
        return new Trip.Individual(Trip.Individual.Type.WALK, from, departure, to, arrival, null, distance);
    }

    private Trip.Leg parseTripLegPublic(JSONObject leg, Location from, Date departure, Location to, Date arrival) throws JSONException {
        Date plannedDepartureTime = dateFromString(leg.getJSONObject("from").getString("scheduledDeparture"));
        Date plannedArrivalTime = dateFromString(leg.getJSONObject("to").getString("scheduledArrival"));

        Style style = parseStyle(leg);

        JSONArray stopsJson = leg.getJSONArray("intermediateStops");
        ArrayList<Stop> stops = new ArrayList<>();
        for (int k = 0; k < stopsJson.length(); k++) {
            JSONObject stopJson = stopsJson.getJSONObject(k);

            Date stopPlannedDepartureTime = dateFromString(stopJson.getString("scheduledDeparture"));
            Date stopDepartureTime = leg.getBoolean("realTime") ? dateFromString(stopJson.getString("departure")) : null;

            Stop stop = new Stop(locationFromJSON(stopJson, stopJson.getString("name")),
                    true, stopPlannedDepartureTime, stopDepartureTime, null, null);

            stops.add(stop);
        }

        Line line = new Line(leg.has("tripId") ? leg.getString("tripId") : "",  null, productFromString(leg.getString("mode")),
                leg.has("routeShortName") ? leg.getString("routeShortName") : "", style);

        Location destination = new Location(LocationType.STATION, null, null, leg.getString("headsign"));

        return new Trip.Public(
                line,
                destination,
                new Stop(from, true, plannedDepartureTime, departure, null, null),
                new Stop(to, false, plannedArrivalTime, arrival, null, null),
                stops,
                null, // todo: parse legGeometry
                null
        );
    }

    private Trip.Leg parseTripLeg(JSONObject leg, Context ctx) throws JSONException {
        JSONObject legFrom = leg.getJSONObject("from");
        JSONObject legTo = leg.getJSONObject("to");

        String startName = legFrom.getString("name");
        if (startName.equals("START")) startName = ctx.from.name;
        String destName = legTo.getString("name");
        if (destName.equals("END")) destName = ctx.to.name;

        Location fromLocation = locationFromJSON(legFrom, startName);
        Location toLocation = locationFromJSON(legTo, destName);

        Date departureTime = dateFromString(legFrom.getString("departure"));
        Date arrivalTime = dateFromString(legTo.getString("arrival"));
        if (leg.getString("mode").equals("WALK")) {
            return parseTripLegIndividual(leg, fromLocation, departureTime, toLocation, arrivalTime);
        } else {
            return parseTripLegPublic(leg, fromLocation, departureTime, toLocation, arrivalTime);
        }
    }

    private QueryTripsResult parseQueryTripsResult(CharSequence response, Context ctx) throws JSONException {
        JSONObject obj = new JSONObject(response.toString());

        JSONArray itineraries = obj.getJSONArray("itineraries");

        ArrayList<Trip> trips = new ArrayList<>();

        for (int i = 0; i < itineraries.length(); i++) {
            JSONObject itinerary = itineraries.getJSONObject(i);
            JSONArray legsJson = itinerary.getJSONArray("legs");

            ArrayList<Trip.Leg> legs = new ArrayList<>();
            for (int j = 0; j < legsJson.length(); j++) {
                JSONObject leg = legsJson.getJSONObject(j);
                legs.add(parseTripLeg(leg, ctx));
            }

            Trip trip = new Trip(String.format("%s", legs.toString().hashCode()), ctx.from, ctx.to, legs, null, null, null);
            trips.add(trip);
        }

        ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
        Context ctxNew = new Context(ctx);
        if (obj.has("previousPageCursor"))
            ctxNew.previousCursor = obj.getString("previousPageCursor");
        if (obj.has("nextPageCursor"))
            ctxNew.nextCursor = obj.getString("nextPageCursor");
        return new QueryTripsResult(header, ctx.url, ctx.from, ctx.via, ctx.to, ctxNew, trips);
    }

    @Override
    public QueryTripsResult queryTrips(final Location from, final @Nullable Location via, final Location to,
                                       final Date date, final boolean dep, @Nullable TripOptions options) throws IOException {
        String transitModes = "TRANSIT";
        if (options != null && options.products != null) {
            ArrayList<String> transitModesBuilder = new ArrayList<>();
            for (Product product : options.products) {
                addToStringListFromProduct(transitModesBuilder, product);
            }
            transitModes = String.join(",", transitModesBuilder);
        }

        HttpUrl.Builder builder = api.newBuilder()
                    .addPathSegment("v3")
                    .addPathSegment("plan")
                    .addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(date.toInstant()))
                    .addQueryParameter("fromPlace", stringFromLocation(from))
                    .addQueryParameter("toPlace", stringFromLocation(to))
                    .addQueryParameter("transitModes", transitModes);

        HttpUrl url = builder.build();
        CharSequence response = httpClient.get(url);

        try {
            Context contextObj = new Context(url.toString(), null, null, from, via, to, date);
            return parseQueryTripsResult(response, contextObj);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public QueryTripsResult queryMoreTrips(final QueryTripsContext contextObj, final boolean later) throws IOException {
        Context ctx = (Context) contextObj;
        HttpUrl.Builder builder = HttpUrl.parse(ctx.url).newBuilder()
                .addQueryParameter("pageCursor", later ? ctx.nextCursor : ctx.previousCursor);

        HttpUrl url = builder.build();
        CharSequence response = httpClient.get(url);

        try {
            return parseQueryTripsResult(response, ctx);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public Point[] getArea() throws IOException {
        return new Point[]{
                Point.fromDouble(90, 0),
                Point.fromDouble(90, 180),
                Point.fromDouble(-90, 180),
                Point.fromDouble(-90, 0)
        };
    }

    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures, boolean equivs)
            throws IOException {

        try {
            HttpUrl url = api.newBuilder()
                    .addPathSegment("v1")
                    .addPathSegment("stoptimes")
                    .addQueryParameter("stopId", stationId)
                    .addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(time.toInstant()))
                    .addQueryParameter("n", String.format(Locale.US, "%d", maxDepartures))
                    .addQueryParameter("radius", "100")
                    .build();
            CharSequence response = httpClient.get(url);
            JSONObject json = new JSONObject(response.toString());

            ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
            QueryDeparturesResult result = new QueryDeparturesResult(header);

            // departures by stop id
            HashMap<String, ArrayList<Departure>> departures = new HashMap<>();

            // stop location by stop id
            HashMap<String, Location> stops = new HashMap<>();

            // lines
            Set<LineDestination> lines = new HashSet<>();

            JSONArray departuresJson = json.getJSONArray("stopTimes");
            for (int i = 0; i < departuresJson.length(); i++) {
                JSONObject stopTime = departuresJson.getJSONObject(i);

                JSONObject place = stopTime.getJSONObject("place");

                // skip arrivals
                if (!place.has("scheduledDeparture") || !place.has("departure")) {
                    continue;
                }

                // line
                Line line = new Line(null, null, productFromString(stopTime.getString("mode")), stopTime.getString("routeShortName"));
                Location destination = new Location(LocationType.STATION, null, null, stopTime.getString("headsign"));
                lines.add(new LineDestination(line, destination));

                // location
                Location stop = new Location(LocationType.STATION, place.getString("stopId"), Point.fromDouble(place.getDouble("lat"), place.getDouble("lon")), null, place.getString("name"));
                stops.put(place.getString("stopId"), stop);

                // departure
                Date plannedDepartureTime = dateFromString(place.getString("scheduledDeparture"));
                Date departureTime = dateFromString(place.getString("departure"));

                String stopId = place.getString("stopId");
                Departure departure = new Departure(plannedDepartureTime, departureTime, line, null, destination, null, null);
                if (departures.containsKey(stopId)) {
                    departures.get(stopId).add(departure);
                } else {
                    departures.put(stopId, new ArrayList<Departure>(Collections.singletonList(departure)));
                }
            }

            for (String stopId : departures.keySet()) {
                StationDepartures station = new StationDepartures(stops.get(stopId), departures.get(stopId), new ArrayList<>(lines));
                result.stationDepartures.add(station);
            }

            return result;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(Set<LocationType> ls, Location l, int i, int j) throws IOException {
        // TODO: implement via reverse-geocode API
        throw new IOException("Unimplemented");
    }

}
