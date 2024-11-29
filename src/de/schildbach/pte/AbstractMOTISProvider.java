package de.schildbach.pte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;

import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import okhttp3.HttpUrl;


class MotisQueryTripsContext implements QueryTripsContext {
    @Override
    public boolean canQueryLater() {
        return false;
    }

    @Override
    public boolean canQueryEarlier() {
        return false;
    }
}

public class AbstractMOTISProvider extends AbstractNetworkProvider {
    HttpUrl api;


    private final List<Capability> CAPABILITIES = Arrays.asList(
            Capability.SUGGEST_LOCATIONS,
            Capability.TRIPS,
            Capability.TRIPS_VIA,
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
            SuggestedLocation loc = new SuggestedLocation(new Location(LocationType.STATION,
                    guessObj.getString("type").equals("STOP") ? guessObj.getString("id") : null,
                    Point.fromDouble(guessObj.getDouble("lat"), guessObj.getDouble("lon")),
                    getCity(boundaries).flatMap(city -> getCountry(boundaries).map(country -> city + ", " + country)).orElse(null),
                    guessObj.getString("name")));
            suggestions.add(loc);
        }

        return new SuggestLocationsResult(header, suggestions);
    }

    private Product parseMode(String mode) throws IOException {
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
                return Product.HIGH_SPEED_TRAIN;
            default:
                throw new IOException("Unknown transport mode");
        }
    }

    @SuppressWarnings("NewApi")
    @Override
    public QueryTripsResult queryTrips(final Location from, final @Nullable Location via, final Location to,
                                       final Date date, final boolean dep, @Nullable TripOptions options) throws IOException {
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

        @SuppressWarnings("NewApi") HttpUrl url = api.newBuilder().addPathSegment("plan")
                .addQueryParameter("time", DateTimeFormatter.ISO_INSTANT.format(date.toInstant()))
                .addQueryParameter("fromPlace", from.id != null ? from.id : String.format(Locale.US, "%f,%f,0", from.getLatAsDouble(), from.getLonAsDouble()))
                .addQueryParameter("toPlace", to.id != null ? to.id : String.format(Locale.US, "%f,%f,0", to.getLatAsDouble(), to.getLonAsDouble()))
                .addQueryParameter("transitModes", transitModes)
                .build();

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
                            new ArrayList<Point>(),
                            null
                    );
                }

                legs.add(leg);
            }

            Trip trip = new Trip(String.format("%s", legs.toString().hashCode()), from, to, legs, null, null, null);
            trips.add(trip);
        }

        return new QueryTripsResult(header, url.toString(), from, via, to, new MotisQueryTripsContext(), trips);
    }

    @Override
    public QueryTripsResult queryMoreTrips(final QueryTripsContext contextObj, final boolean later) throws IOException {
        throw new IOException();
    }


    @Override
    public Point[] getArea() throws IOException {
        return new Point[]{Point.fromDouble(0, 0), Point.fromDouble(0, 0), Point.fromDouble(0, 0), Point.fromDouble(0, 0)};
    }

    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures, boolean equivs)
            throws IOException {
        // FIXME: Must be implemented for oeffi to be usable
        throw new IOException("Unimplemented");
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(Set<LocationType> ls, Location l, int i, int j) throws IOException {
        // FIXME: Must be implemented for oeffi to be usable
        throw new IOException("Unimplemented");
    }

}