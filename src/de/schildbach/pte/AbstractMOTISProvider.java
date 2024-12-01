package de.schildbach.pte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.Array;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

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


class MotisQueryTripsContext implements QueryTripsContext {
    public String previousCursor;
    public String nextCursor;
    public String url;
    public Location from;
    public Location via;
    public Location to;
    public Date date;

    MotisQueryTripsContext(String url,
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

    @Override
    public boolean canQueryLater() {
        return nextCursor != null;
    }

    @Override
    public boolean canQueryEarlier() {
        return previousCursor != null;
    }
}

public class AbstractMOTISProvider extends AbstractNetworkProvider {
    HttpUrl api;


    private final List<Capability> CAPABILITIES = Arrays.asList(
            Capability.SUGGEST_LOCATIONS,
            Capability.TRIPS,
            Capability.DEPARTURES
    );

    public AbstractMOTISProvider(String apiUrl) {
        super(NetworkId.TRANSITOUS);
        api = HttpUrl.parse(apiUrl).newBuilder().addPathSegment("api").addPathSegment("v1").build();
    }

    @Override
    public boolean hasCapability(Capability cap) {
        return CAPABILITIES.contains(cap);
    }

    private Optional<String> getBoundary(JSONArray boundaries, int max) {
        return IntStream.range(0, boundaries.length()).mapToObj(i -> boundaries.getJSONObject(boundaries.length() - 1 - i))
                .filter((b) -> b.getInt("adminLevel") <= max).findFirst().map((b) -> b.getString("name"));
    }

    private Optional<String> getCity(JSONArray boundaries) {
        return getBoundary(boundaries, 8);
    }

    private Optional<String> getCountry(JSONArray boundaries) {
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

    @Override
    public SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types,
                                                   int maxLocations) throws IOException {
        HttpUrl url = api.newBuilder().addPathSegment("geocode").addQueryParameter("text", constraint.toString()).build();

        CharSequence response = httpClient.get(url);

        List<SuggestedLocation> suggestions = new ArrayList<>();
        JSONArray json = new JSONArray(response.toString());
        ResultHeader header = new ResultHeader(NetworkId.TRANSITOUS, "MOTIS");
        for (int i = 0; i < json.length(); i++) {
            JSONObject guessObj = json.getJSONObject(i);
            JSONArray boundaries = guessObj.getJSONArray("areas");
            LocationType type = parseLocationType(guessObj.getString("type"));
            SuggestedLocation loc = new SuggestedLocation(new Location(type,
                    type == LocationType.STATION ? guessObj.getString("id") : null,
                    Point.fromDouble(guessObj.getDouble("lat"), guessObj.getDouble("lon")),
                    getCity(boundaries).flatMap(city -> getCountry(boundaries).map(country -> city + ", " + country)).orElse(null),
                    guessObj.getString("name")));
            suggestions.add(loc);
        }

        return new SuggestLocationsResult(header, suggestions);
    }

    private Product parseMode(String mode) {
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

    @SuppressWarnings("NewApi")
    private QueryTripsResult queryTripsInternal(final Location from, final @Nullable Location via, final Location to,
                                                final Date date, final boolean dep, @Nullable TripOptions options,
                                                @Nullable String contextUrl, final String cursor) throws IOException {
        String transitModes = "TRANSIT";
        if (options != null && options.products != null) {
            ArrayList<String> transitModesBuilder = new ArrayList<>();
            for (Product product : options.products) {
                switch (product) {
                    case TRAM:
                        transitModesBuilder.add("TRAM");
                        break;
                    case SUBWAY:
                        transitModesBuilder.add("SUBWAY");
                        break;
                    case FERRY:
                        transitModesBuilder.add("FERRY");
                        break;
                    case BUS:
                        transitModesBuilder.add("BUS");
                        transitModesBuilder.add("COACH");
                        break;
                    case REGIONAL_TRAIN:
                        transitModesBuilder.add("REGIONAL_RAIL");
                        transitModesBuilder.add("REGIONAL_FAST_RAIL");
                        break;
                    case SUBURBAN_TRAIN:
                        transitModesBuilder.add("METRO");
                        break;
                    case HIGH_SPEED_TRAIN:
                        transitModesBuilder.add("RAIL");
                        break;
                }
            }
            transitModes = String.join(",", transitModesBuilder);
        }

        HttpUrl.Builder builder;

        if (contextUrl == null) {
            builder = api.newBuilder().addPathSegment("plan")
                    .addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(date.toInstant()))
                    .addQueryParameter("fromPlace", from.id != null ? from.id : String.format(Locale.US, "%f,%f,0", from.getLatAsDouble(), from.getLonAsDouble()))
                    .addQueryParameter("toPlace", to.id != null ? to.id : String.format(Locale.US, "%f,%f,0", to.getLatAsDouble(), to.getLonAsDouble()))
                    .addQueryParameter("transitModes", transitModes);
        } else {
            builder = HttpUrl.parse(contextUrl).newBuilder();
        }

        if (cursor != null) {
            builder.addQueryParameter("pageCursor", cursor);
        }

        HttpUrl url = builder.build();

        CharSequence response = httpClient.get(url);

        ResultHeader header = new ResultHeader(NetworkId.TRANSITOUS, "MOTIS");

        JSONObject obj = new JSONObject(response.toString());

        JSONArray itineraries = obj.getJSONArray("itineraries");

        ArrayList<Trip> trips = new ArrayList<>();

        for (int i = 0; i < itineraries.length(); i++) {
            JSONObject itinerary = itineraries.getJSONObject(i);
            JSONArray legsJson = itinerary.getJSONArray("legs");

            ArrayList<Trip.Leg> legs = new ArrayList<>();
            for (int j = 0; j < legsJson.length(); j++) {
                JSONObject legJson = legsJson.getJSONObject(j);
                JSONObject legFrom = legJson.getJSONObject("from");
                JSONObject legTo = legJson.getJSONObject("to");

                String startName = legFrom.getString("name");
                String destName = legTo.getString("name");

                Location fromLocation = new Location(legFrom.has("stopId") ? LocationType.STATION : LocationType.ANY, legFrom.has("stopId") ? legFrom.getString("stopId") : null,
                        Point.fromDouble(legFrom.getDouble("lat"),
                                legFrom.getDouble("lon")), "", startName.equals("START") ? from.name : startName);

                Location toLocation = new Location(legTo.has("stopId") ? LocationType.STATION : LocationType.ANY, legTo.has("stopId") ? legTo.getString("stopId") : null,
                        Point.fromDouble(legTo.getDouble("lat"),
                                legTo.getDouble("lon")), "", destName.equals("END") ? to.name : destName);

                Trip.Leg leg;
                if (legJson.getString("mode").equals("WALK")) {
                    Date departureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(legFrom.getString("departure"), Instant::from));
                    Date arrivalTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(legTo.getString("arrival"), Instant::from));

                    int distance = legJson.has("distance") ? legJson.getInt("distance") : 0;

                    leg = new Trip.Individual(Trip.Individual.Type.WALK, fromLocation, departureTime, toLocation, arrivalTime, null, distance);
                } else {

                    Date plannedDepartureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(legFrom.getString("scheduledDeparture"), Instant::from));
                    Date departureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(legFrom.getString("departure"), Instant::from));
                    Date plannedArrivalTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(legTo.getString("scheduledArrival"), Instant::from));
                    Date arrivalTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(legTo.getString("arrival"), Instant::from));

                    Style style = null;
                    if (legJson.has("routeColor")) {
                        int backgroundColor = Style.parseColor("#" + legJson.getString("routeColor"));
                        int foregroundColor;

                        if (legJson.has("routeTextColor")) {
                            foregroundColor = Style.parseColor("#" + legJson.getString("routeTextColor"));
                        } else {
                            foregroundColor = Style.BLACK;
                        }
                        style = new Style(backgroundColor, foregroundColor);
                    }

                    JSONArray stopsJson = legJson.getJSONArray("intermediateStops");
                    ArrayList<Stop> stops = new ArrayList<>();
                    for (int k = 0; k < stopsJson.length(); k++) {
                        JSONObject stopJson = stopsJson.getJSONObject(k);

                        Date stopPlannedDepartureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(stopJson.getString("scheduledDeparture"), Instant::from));
                        Date stopDepartureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(stopJson.getString("departure"), Instant::from));

                        Stop stop = new Stop(new Location(stopJson.has("stopId") ? LocationType.STATION : LocationType.ANY, stopJson.has("stopId") ? stopJson.getString("stopId") : null,
                                Point.fromDouble(stopJson.getDouble("lat"),
                                        stopJson.getDouble("lon")), null, stopJson.getString("name")),
                                true, stopPlannedDepartureTime, stopDepartureTime, null, null);

                        stops.add(stop);
                    }

                    leg = new Trip.Public(
                            new Line(legJson.has("tripId") ? legJson.getString("tripId") : "",
                                    null, parseMode(legJson.getString("mode")),
                                    legJson.has("routeShortName") ? legJson.getString("routeShortName") : "", style),
                            null,
                            new Stop(fromLocation,
                                    true,
                                    plannedDepartureTime,
                                    departureTime,
                                    null, null
                            ),
                            new Stop(toLocation,
                                    false,
                                    plannedArrivalTime,
                                    arrivalTime,
                                    null, null),
                            stops,
                            new ArrayList<>(),
                            null
                    );
                }

                legs.add(leg);
            }

            Trip trip = new Trip(String.format("%s", legs.toString().hashCode()), from, to, legs, null, null, null);
            trips.add(trip);
        }

        return new QueryTripsResult(header, url.toString(), from, via, to,
                new MotisQueryTripsContext(
                        contextUrl != null ? contextUrl : url.toString(),
                        obj.has("previousPageCursor") ? obj.getString("previousPageCursor") : null,
                        obj.has("nextPageCursor") ? obj.getString("nextPageCursor") : null,
                        from,
                        via,
                        to, date),
                trips);
    }

    @Override
    public QueryTripsResult queryTrips(final Location from, final @Nullable Location via, final Location to,
                                       final Date date, final boolean dep, @Nullable TripOptions options) throws IOException {
        return queryTripsInternal(from, via, to, date, dep, options, null, null);
    }

    @Override
    public QueryTripsResult queryMoreTrips(final QueryTripsContext contextObj, final boolean later) throws IOException {
        MotisQueryTripsContext ctx = (MotisQueryTripsContext) contextObj;
        return queryTripsInternal(ctx.from, ctx.via, ctx.to, ctx.date, false, null, ctx.url, later ? ctx.nextCursor : ctx.previousCursor);
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

    @SuppressWarnings("NewApi")
    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures, boolean equivs)
            throws IOException {
        HttpUrl url = api.newBuilder()
                .addPathSegment("stoptimes")
                .addQueryParameter("stopId", stationId)
                .addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(time.toInstant()))
                .addQueryParameter("n", String.format(Locale.US, "%d", maxDepartures))
                .addQueryParameter("radius", "100")
                .build();
        CharSequence response = httpClient.get(url);
        JSONObject json = new JSONObject(response.toString());

        ResultHeader header = new ResultHeader(NetworkId.TRANSITOUS, "MOTIS");
        QueryDeparturesResult result = new QueryDeparturesResult(header);

        // departures by stop id
        HashMap<String, ArrayList<Departure>> departures = new HashMap<>();

        // stop location by stop id
        HashMap<String, Location> stops = new HashMap<>();

        // lines
        Set<LineDestination> lines = new HashSet<LineDestination>();

        JSONArray departuresJson = json.getJSONArray("stopTimes");
        for (int i = 0; i < departuresJson.length(); i++) {
            JSONObject stopTime = departuresJson.getJSONObject(i);

            JSONObject place = stopTime.getJSONObject("place");

            // skip arrivals
            if (!place.has("scheduledDeparture") || !place.has("departure")) {
                continue;
            }

            // line
            Line line = new Line(null, null, parseMode(stopTime.getString("mode")), stopTime.getString("routeShortName"));
            Location destination = new Location(LocationType.STATION, null, null, stopTime.getString("headsign"));
            lines.add(new LineDestination(line, destination));

            // location
            Location stop = new Location(LocationType.STATION, place.getString("stopId"), Point.fromDouble(place.getDouble("lat"), place.getDouble("lon")), null, place.getString("name"));
            stops.put(stopTime.getJSONObject("place").getString("stopId"), stop);

            // departure
            Date plannedDepartureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(place.getString("scheduledDeparture"), Instant::from));
            Date departureTime = Date.from(DateTimeFormatter.ISO_INSTANT.parse(place.getString("departure"), Instant::from));

            Departure departure = new Departure(plannedDepartureTime, departureTime, line, null, destination, null, null);
            if (departures.containsKey(place.getString("stopId"))) {
                departures.get(place.getString("stopId")).add(departure);
            } else {
                departures.put(place.getString("stopId"), new ArrayList<>(Arrays.asList(new Departure[] {departure})));
            }
        }

        for (String stopId : departures.keySet()) {
            StationDepartures station = new StationDepartures(stops.get(stopId), departures.get(stopId), new ArrayList<>(lines));
            result.stationDepartures.add(station);
        }

        return result;
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(Set<LocationType> ls, Location l, int i, int j) throws IOException {
        throw new IOException("Unimplemented");
    }

}