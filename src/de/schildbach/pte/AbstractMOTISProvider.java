package de.schildbach.pte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;

import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import okhttp3.HttpUrl;

public class AbstractMOTISProvider extends AbstractNetworkProvider {
    // FIXME: Set in child class
    static String API = "https://routing.spline.de/api/";

    private final List<Capability> CAPABILITIES = Arrays.asList(
            Capability.SUGGEST_LOCATIONS,
            Capability.TRIPS,
            Capability.TRIPS_VIA,
            Capability.DEPARTURES
    );

    public AbstractMOTISProvider() {
        super(NetworkId.TRANSITUOUS);
    }

    @Override
    public boolean hasCapability(Capability cap) {
        return CAPABILITIES.contains(cap);
    }

    @Override
    public SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types,
                                                   int maxLocations) throws IOException {
        JSONObject queryStationsJSON = new JSONObject();
        queryStationsJSON.put("content_type", "StationGuesserRequest");
        JSONObject content = new JSONObject();
        content.put("guess_count", 6);
        content.put("input", constraint);
        queryStationsJSON.put("content", content);
        JSONObject destination = new JSONObject();
        destination.put("type", "Module");
        destination.put("target", "/guesser");
        // FIXME: Don't hardcode server
        CharSequence response = httpClient.get(HttpUrl.parse(API), queryStationsJSON.toString(), "application/json");


        List<SuggestedLocation> suggestions = new ArrayList<>();
        JSONArray json = new JSONObject(response).getJSONObject("content").getJSONArray("guesses");
        ResultHeader header = new ResultHeader(NetworkId.TRANSITUOUS, "MOTIS");
        for (Object guess : json) {
            System.out.println("Adding suggestion");
            JSONObject guessObj = ((JSONObject) guess);
            JSONObject pointJson = guessObj.getJSONObject("pos");
            SuggestedLocation loc = new SuggestedLocation(new Location(LocationType.STATION, guessObj.getString("id"), Point.fromDouble(pointJson.getDouble("lat"), pointJson.getDouble("lng")), "", guessObj.getString("name")));
            suggestions.add(loc);
        }

        return new SuggestLocationsResult(header, suggestions);

        // Query address suggestions ({"destination":{"type":"Module","target":"/address"},"content_type":"AddressRequest","content":{"input":"Ber"}}
    }

    @Override
    public QueryTripsResult queryTrips(final Location from, final @Nullable Location via, final Location to,
                                       final Date date, final boolean dep, @Nullable TripOptions options) throws IOException {
        JSONObject obj = new JSONObject("{\"destination\":{\"type\":\"Module\",\"target\":\"/intermodal\"},\"content_type\":\"IntermodalRoutingRequest\",\"content\":{\"start_type\":\"IntermodalPretripStart\",\"start\":{\"position\":{\"lat\":42.773939,\"lng\":18.94881},\"interval\":{\"begin\":1725636300,\"end\":1725643500},\"min_connection_count\":5,\"extend_interval_earlier\":true,\"extend_interval_later\":true},\"start_modes\":[{\"mode_type\":\"FootPPR\",\"mode\":{\"search_options\":{\"profile\":\"default\",\"duration_limit\":900}}}],\"destination_type\":\"InputPosition\",\"destination\":{\"lat\":42.823839,\"lng\":19.521801},\"destination_modes\":[{\"mode_type\":\"FootPPR\",\"mode\":{\"search_options\":{\"profile\":\"default\",\"duration_limit\":1800}}}],\"search_type\":\"Accessibility\",\"search_dir\":\"Forward\",\"router\":\"\"}}");
        JSONObject startPosition = obj.getJSONObject("content").getJSONObject("start").getJSONObject("position");
        startPosition.put("lat", from.getLatAsDouble());
        startPosition.put("lng", to.getLonAsDouble());
        JSONObject destPosition = obj.getJSONObject("content").getJSONObject("destination");
        destPosition.put("lat", to.getLatAsDouble());
        destPosition.put("lng", to.getLonAsDouble());


        ResultHeader header = new ResultHeader(NetworkId.TRANSITUOUS, "MOTIS");

        QueryTripsResult result = new QueryTripsResult(header, QueryTripsResult.Status.OK);

        CharSequence response = httpClient.get(HttpUrl.parse(API), obj.toString(), "application/json");
        JSONArray connections = new JSONObject(response).getJSONObject("content").getJSONArray("connections");
        for (Object connection: connections){
            ArrayList<Trip.Leg> legs = new ArrayList<>();
            JSONArray transports = new JSONObject(connection).getJSONArray("transports");
            JSONArray stops = new JSONObject(connection).getJSONArray("stops");
            for (Object transport: transports){
                String name = ((JSONObject)transport).getString("name");
                int from_index = ((JSONObject)transport).getJSONObject("range").getInt("from");
                int to_index = ((JSONObject)transport).getJSONObject("range").getInt("to");
                String direction = ((JSONObject) transport).getString("direction");
                Location train_destination = new Location(LocationType.STATION, null, null, direction);
                List<Point> path = new ArrayList<>();
                ArrayList<Stop> intermediateStops = new ArrayList<>();

                for (int i = from_index; i <= to_index; i++){
                    JSONObject stop_json = stops.getJSONObject(i);
                    JSONObject station = stop_json.getJSONObject("station");
                    String id = station.getString("id");
                    JSONObject coordinates = station.getJSONObject("pos");
                    double lon = coordinates.getDouble("lng");
                    double lat = coordinates.getDouble("lat");
                    Point point = Point.fromDouble(lat, lon);
                    Location location = new Location(LocationType.STATION, id, point, "", station.getString("name"));
                    Stop stop = new Stop(location,null,null,null,null);
                    intermediateStops.add(stop);
                }
                Trip.Leg leg = new Trip.Public(null, train_destination, intermediateStops.get(0), intermediateStops.get(intermediateStops.size()-1), intermediateStops, path, name);
                legs.add(leg);
            }
            Trip trip = new Trip(null,null,null,legs,null,null, legs.size() - 1);
            assert result.trips != null;
            result.trips.add(trip);
        }
        throw new IOException();
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