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

package de.schildbach.pte.provider.other;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.*;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import okhttp3.HttpUrl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Serial;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static de.schildbach.pte.dto.Style.parseColor;

// must be upgraded from time to time from
//   https://gitlab.com/opentransitmap/public-transport-enabler/-/blob/master/src/de/schildbach/pte/CHSearchProvider.java

/**
 * Implementation of timetables.search.ch provider.
 * Provides data for Switzerland
 *
 *
 * <p><b>Notes:</b></p>
 * <ul>
 *  <li>
 *      Track changes are indicated with a "!" as prefix of the "track" attribute value
 *  </li>
 *  <li>
 *      Canceled connections are either indicated by a "dep_delay" of "X" and/or the a particular leg has the attribute "cancelled" set to true
 *  </li>
 *  <li>
 *      This code intentionally does not take advantage of newer Java features like stream api etc to keep it as compatible as possible
 *  </li>
 * </ul>
 *
 *
 * <p>
 * Quota: 1000 route queries and 5000 departure/arrival tables per Day
 * </p>
 * <p>
 * TOS: https://timetable.search.ch/api/terms
 * </p>
 *
 * @author Tobias Bossert
 * @apiNote https://timetable.search.ch/api/help
 */
public class CHSearchProvider extends AbstractNetworkProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://timetable.search.ch/api/");
    private static final int N_TRIPS = 8;
    private static final String COMPLETION_ENDPOINT = "completion.json";
    private static final String TRIP_ENDPOINT = "route.json";
    private static final String STATIONBOARD_ENDPOINT = "stationboard.json";
    protected static final String SERVER_PRODUCT = "timetables.search.ch";
    private final ResultHeader resultHeader = new ResultHeader(network, SERVER_PRODUCT);
    private static final DateFormat DATE_FORMATTER = new SimpleDateFormat("MM/dd/yyyy");
    private static final DateFormat TIME_FORMATTER = new SimpleDateFormat("HH:mm");
    protected static final SimpleDateFormat DATE_TIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    protected static final TimeZone TIME_ZONE = TimeZone.getTimeZone("Europe/Zurich");
    // As of 19. Nov 2023, the API seems to provide disruptions again, us this toggle to quickly disable if it causes problems.
    private static final boolean DISABLE_DISRUPTIONS = false;

    protected static PTDate parseDateTime(final String s) throws ParseException {
        final Date date = DATE_TIME_FORMATTER.parse(s);
        if (date == null)
            return null;
        return new PTDate(date, TIME_ZONE);
    }

    private final Set<Capability> CAPABILITIES = Set.of(
            Capability.SUGGEST_LOCATIONS,
            Capability.NEARBY_LOCATIONS,
            Capability.DEPARTURES,
            Capability.TRIPS,
            Capability.TRIPS_VIA
    );

    public CHSearchProvider() {
        super(NetworkId.SEARCHCH);
    }

    @Override
    protected Set<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    protected boolean hasCapability(final Capability capability) {
        return CAPABILITIES.contains(capability);
    }

    /**
     * Finds nearby locations. Please note that locations without coordinates result in an additional query
     *
     * @param types        Location types (not supported!)
     * @param location     A Location object, must have either a name or valid id.
     * @param maxDistance  Distance (radius) from location in meters
     * @param maxLocations Number of locations (not supported, is always 10!)
     * @return A possibly empty list of <L>{@link Location}s</L>
     */
    @Override
    public NearbyLocationsResult queryNearbyLocations(final Set<LocationType> types, final Location location, final EquivalentStationsMode equivsMode, final int maxDistance, final int maxLocations, final Set<Product> products) throws IOException {
        // Since the endpoint only supports lat/long we have to get the coordinates first (if not already supplied in the Location attribute)
        Location fixedLocation = location;
        if (location.coord == null && location.id != null) {
            final SuggestLocationsResult suggestionResult = this.suggestLocations(location.id, null, 2);
            if (suggestionResult.suggestedLocations == null || suggestionResult.suggestedLocations.size() != 1) {
                return new NearbyLocationsResult(resultHeader, NearbyLocationsResult.Status.INVALID_ID);
            } else {
                fixedLocation = suggestionResult.suggestedLocations.get(0).location;
            }
        }
        if (fixedLocation.coord != null) {
            final String latlon = String.format(Locale.ROOT, "%f,%f", fixedLocation.coord.getLatAsDouble(), fixedLocation.coord.getLonAsDouble());
            final HttpUrl queryUrl = API_BASE.newBuilder()
                    .addPathSegment(COMPLETION_ENDPOINT)
                    .addQueryParameter("latlon", latlon)
                    .addQueryParameter("accuracy", Integer.toString(maxDistance))
                    .addQueryParameter("show_ids", "1")
                    .addQueryParameter("show_coordinates", "1")
                    .build();
            final CharSequence res = httpClient.get(queryUrl);
            try {
                final String jsonResult = res.toString();
                if (jsonResult.equals("")) {
                    return new NearbyLocationsResult(resultHeader, NearbyLocationsResult.Status.INVALID_ID);
                }
                final JSONArray rawResult = new JSONArray(jsonResult);
                final List<Location> suggestions = new ArrayList<>();
                for (int i = 0; i < rawResult.length(); i++) {
                    final JSONObject entry = rawResult.getJSONObject(i);
                    suggestions.add(extractLocation(entry));
                }
                return new NearbyLocationsResult(resultHeader, suggestions);
            } catch (final JSONException x) {
                throw new ParserException("queryNearbyLocations: cannot parse json:" + x);
            }
        } else {
            return new NearbyLocationsResult(resultHeader, NearbyLocationsResult.Status.INVALID_ID);
        }
    }

    /**
     * Returns all departing connections from a station id
     *
     * @param stationId     id (or name) of the station
     * @param aTime          desired time for departing, or {@code null} for the provider default
     * @param maxDepartures maximum number of departures to get or {@code 0}
     * @param equivsMode    (Not supported!)
     * @return List of Departure objects
     */
    @Override
    public QueryDeparturesResult queryDepartures(final String stationId, @androidx.annotation.Nullable final Date aTime, final int maxDepartures, final EquivalentStationsMode equivsMode, final Set<Product> products) throws IOException {
        // Set time to now if not set
        final Date time = aTime == null ? new Date() : aTime;

        final HttpUrl queryUrl = API_BASE.newBuilder()
                .addPathSegment(STATIONBOARD_ENDPOINT)
                .addQueryParameter("stop", stationId)
                .addQueryParameter("date", DATE_FORMATTER.format(time))
                .addQueryParameter("time", TIME_FORMATTER.format(time))
                .addQueryParameter("limit", String.valueOf(maxDepartures))
                .addQueryParameter("show_tracks", "1")
                .addQueryParameter("show_delays", "1")
                .build();

        final CharSequence res = httpClient.get(queryUrl);
        try {
            final JSONObject rawResult = new JSONObject(res.toString());
            if (rawResult.has("messages")) {
                // This could be bit more refined "messages" is also set when there are simply no departures
                return new QueryDeparturesResult(resultHeader, QueryDeparturesResult.Status.INVALID_STATION);
            }
            final StationBoardResult sb = new StationBoardResult(rawResult, this);
            final Location boardLocation = new Location(LocationType.STATION, sb.stationID, Point.fromDouble(sb.lat, sb.lon), null, sb.name);
            final List<Departure> departures = new ArrayList<>();
            for (final StationBoardResult.StationBoardEntry sbEntry : sb.entries) {

                final PTDate predictedTime = addMinutesToDate(sbEntry.time, sbEntry.dep_delay);
                final Line line = new Line(sbEntry.Z, sbEntry.operator, type2Product(sbEntry.G), getTrainName(sbEntry.G, sbEntry.Z, sbEntry.L), new Style(Style.Shape.RECT, sbEntry.bgColor, sbEntry.fgColor));
                final Location destinationLocation = new Location(LocationType.STATION, sbEntry.terminal.stationID, Point.fromDouble(sbEntry.terminal.lat, sbEntry.terminal.lon), null, sbEntry.terminal.name);
                final Position departurePos = sbEntry.track != null ? new Position(sbEntry.track) : null;
                departures.add(new Departure(sbEntry.time, predictedTime, line, null, departurePos, destinationLocation, false, null, null, null));
            }
            final StationDepartures sd = new StationDepartures(boardLocation, departures, null);
            final QueryDeparturesResult QDres = new QueryDeparturesResult(resultHeader);
            QDres.stationDepartures.add(sd);
            return QDres;
        } catch (final JSONException | ParseException x) {
            throw new ParserException("queryNearbyLocations: cannot parse json:" + x);
        }
    }

    /**
     * Suggests stations, POIs or addresses based on user input
     *
     * @param constraint   Input by user so far
     * @param types        Types of locations to suggest (not supported!)
     * @param maxLocations Number of locations (not supported, is always 10!)
     * @return A possibly empty list of <L>{@link Location}s</L>
     */
    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint, @Nullable final Set<LocationType> types, final int maxLocations) throws IOException {
        final HttpUrl queryUrl = API_BASE.newBuilder()
                .addPathSegment(COMPLETION_ENDPOINT)
                .addQueryParameter("term", constraint.toString())
                .addQueryParameter("show_ids", "1")
                .addQueryParameter("show_coordinates", "1")
                .build();
        final CharSequence res = httpClient.get(queryUrl);
        try {
            final JSONArray rawResult = new JSONArray(res.toString());
            final List<SuggestedLocation> suggestions = new ArrayList<>();
            for (int i = 0; i < rawResult.length(); i++) {
                final JSONObject entry = rawResult.getJSONObject(i);
                suggestions.add(new SuggestedLocation(extractLocation(entry)));
            }
            final ResultHeader header = new ResultHeader(network, SERVER_PRODUCT);
            return new SuggestLocationsResult(header, suggestions);
        } catch (final JSONException x) {
            throw new ParserException("suggestLocations: cannot parse json:" + x);
        }
    }


    @Override
    public QueryTripsResult queryTrips(final Location from, @androidx.annotation.Nullable final Location via, final Location to, final Date date, final boolean dep, @androidx.annotation.Nullable final TripOptions options, final boolean loadPath) throws IOException {
        // We define all request parameters here since we can't submit "null"
        final HashMap<String, String> rawParameters = new HashMap<>();
        rawParameters.put("from", from.id == null ? from.name : from.id);
        rawParameters.put("to", to.id == null ? to.name : to.id);
        rawParameters.put("via", via != null ? via.id : null);
        rawParameters.put("date", DATE_FORMATTER.format(date));
        rawParameters.put("time", TIME_FORMATTER.format(date));
        rawParameters.put("time_type", dep ? "depart" : "arrival");
        rawParameters.put("show_delays", "1");
        rawParameters.put("show_trackchanges", "1");
        final String productListForUrl = options == null ? "" : product2apiType(options.products);
        if (!productListForUrl.isEmpty()) rawParameters.put("transportation_types", productListForUrl);
        final HttpUrl.Builder builder = API_BASE.newBuilder();
        builder.addPathSegment(TRIP_ENDPOINT);
        // And then build the request-url with all non-null keys
        for (final Map.Entry<String, String> item :
                rawParameters.entrySet()) {
            if (null != item.getValue()) {
                builder.addQueryParameter(item.getKey(), item.getValue());
            }
        }
        final HttpUrl requestURL = builder.build();
        final CharSequence res = httpClient.get(requestURL);
        try {
            final Date now = new Date();
            final RouteResult routeResult = new RouteResult(new JSONObject(res.toString()), this);
            if (routeResult.connections.size() == 0) {
                // More granularity would be very tedious to implement since reasons are free-text and possibly in 4 different languages...
                return new QueryTripsResult(resultHeader, QueryTripsResult.Status.NO_TRIPS);
            }
            final List<Trip> tripsList = new ArrayList<>(N_TRIPS);
            for (final RouteResult.Connection connection : routeResult.connections) {
                final AtomicInteger numChanges = new AtomicInteger(-1);
                final List<Trip.Leg> legsList = new ArrayList<>(10);
                for (final RouteResult.Connection.Leg leg : connection.legs) {
                    final List<Stop> intermediateStops = new ArrayList<>(20);

                    // Collect disruptions
                    final StringBuilder disruptions = new StringBuilder();
                    for (final RouteResult.Connection.Disruption disruption : leg.disruptions) {
                        disruptions.append(disruptions);
                    }


                    if (leg.exit != null) {
                        // Some legs do not have location data...
                        final Point legExitLocation = leg.exit.lon != null ? Point.fromDouble(leg.exit.lat, leg.exit.lon) : null;
                        final Point legLocation = leg.lon != null ? Point.fromDouble(leg.lat, leg.lon) : null;

                        // Some bus-stops in rural areas do not have a track name..
                        final Position planedDeparturePos = leg.track != null ? new Position(leg.track) : null;
                        final Position planedArrivalPos = leg.exit.track != null ? new Position(leg.exit.track) : null;

                        // Departure
                        final PTDate plannedDeparture = leg.departure;
                        final PTDate expectedDeparture = addMinutesToDate(leg.departure, leg.dep_delay);
                        final Location departureLocation = new Location(leg.isAddress ? LocationType.ADDRESS : LocationType.STATION, leg.stopID, legLocation, null, leg.name);
                        final Stop departureStop = new Stop(departureLocation, true, plannedDeparture, expectedDeparture, planedDeparturePos, null, leg.cancelled);

                        // Arrival
                        final PTDate plannedArrival = leg.exit.arrival;
                        final PTDate expectedArrival = addMinutesToDate(leg.exit.arrival, leg.exit.arr_delay);
                        final Location arrivalLocation = new Location(leg.exit.isAddress ? LocationType.ADDRESS : LocationType.STATION, leg.exit.stopID, legExitLocation, null, leg.exit.name);
                        final Stop arrivalStop = new Stop(arrivalLocation, false, plannedArrival, expectedArrival, planedArrivalPos, null, leg.cancelled);

                        // Collect possible info texts (e.g number for on-demand services)
                        final String infoText = String.join(",", leg.infotexts);
                        if (leg.is_walk) {
                            legsList.add(new Trip.Individual(Trip.Individual.Type.WALK, departureLocation, plannedDeparture, arrivalLocation, plannedArrival, 0));
                        } else {
                            numChanges.getAndIncrement();
                            final Location terminalLocation = new Location(LocationType.STATION, null, null, leg.terminal);
                            final Line line = new Line(leg.Z, leg.operator, type2Product(leg.G), getTrainName(leg.G, leg.Z, leg.L), new Style(Style.Shape.RECT, leg.bgColor, leg.fgColor));
                            legsList.add(new Trip.Public(line, terminalLocation, departureStop, arrivalStop, intermediateStops, infoText + disruptions));
                        }

                        for (final RouteResult.Connection.Leg.Stop stop : leg.stops) {
                            if (!stop.isSpecial) {
                                final Location stopLocation = new Location(LocationType.STATION, stop.stopID, Point.fromDouble(stop.lat, stop.lon), null, stop.name);
                                final PTDate plannedArrivalTime = stop.arrival;
                                final PTDate expectedArrivalTime = addMinutesToDate(stop.arrival, stop.arr_delay);
                                final PTDate plannedDepartureTime = stop.departure;
                                final PTDate expectedDepartureTime = addMinutesToDate(stop.departure, stop.dep_delay);
                                intermediateStops.add(new Stop(stopLocation,
                                        plannedArrivalTime,
                                        expectedArrivalTime,
                                        null,
                                        null,
                                        plannedDepartureTime,
                                        expectedDepartureTime,
                                        null,
                                        null));
                            }
                        }

                    } else {
                        // We reached the final leg which is our destination (and identical with the last "Exit")
                        break;
                    }
                }
                //if the connection is just walking
                if (numChanges.get() == -1) numChanges.set(0);
                final String tripID = generateTripID(from, to, legsList, numChanges.get());
                tripsList.add(new Trip(now, tripID, null, from, to, legsList, null, null, numChanges.get()));
            }
            // We must consider that the first/last leg may be not a "Public" one and therefore, we can not use getLastPublicLeg()
            final PTDate lastDeparture = tripsList.get(tripsList.size() - 1).legs.get(0).getDepartureTime();
            final Trip firstConnection = tripsList.get(0);
            final PTDate firstArrival = firstConnection.legs.get(firstConnection.legs.size() - 1).getArrivalTime();
            final CHSearchContext context = new CHSearchContext(from, to, via, firstArrival, lastDeparture, options);
            return new QueryTripsResult(resultHeader, requestURL.toString(), from, via, to, context, tripsList);

        } catch (final JSONException x) {
            throw new ParserException("JSON Error:" + x);
        } catch (final ParseException e) {
            log.error("query trips parse exception", e);
        }
        return null;
    }

    private static String generateTripID(final Location from, final Location to, final List<Trip.Leg> legs, final int numChanges) {
        try {
            final Trip.Leg firstLeg = legs.get(0);
            final Trip.Leg lastLeg = legs.get(legs.size() - 1);
            return String.format("%s_%ts_%s_%ts_%d", from.name, firstLeg.getDepartureTime(), to.name, lastLeg.getArrivalTime(), numChanges);
        } catch (final NullPointerException e) {
            return "fallback_generated_" + UUID.randomUUID();
        }
    }

    /**
     * Generate train name
     *
     * @param G Product name
     * @param Z Train number
     * @param L Line number
     * @return Extracts the product name / train number / line number string. For domestic trains
     * this usually results in {Product} {Line number} and for international trains {Product} {Train number}
     */
    private static String getTrainName(final String G, final String Z, final String L) {
        // Worst case, not seen in the wild yet...
        if ("".equals(G)) return "UKN";
        // train number nor line number
        if ("".equals(Z) && "".equals(L)) return G;
        // Train numbers usually have leading zeros
        final String cleanedTrainNumber = Z.replaceAll("^0*", "");
        if ("".equals(L)) {
            return String.format("%s %s", G, cleanedTrainNumber);
        } else {
            return String.format("%s %s", G, L);
        }
    }


    @Override
    public QueryTripsResult queryMoreTrips(final QueryTripsContext context, final boolean later, final boolean loadPath) throws IOException {
        // We have no context if the previous result returned no results
        if (context != null) {
            final CHSearchContext chCont = (CHSearchContext) context;
            if (later && context.canQueryLater()) {
                // later
                return queryTrips(chCont.from, chCont.via, chCont.to, addMinutesToDate(chCont.lastDeparture, 1), true, chCont.options, false);
            } else if (context.canQueryEarlier()) {
                // before
                return queryTrips(chCont.from, chCont.via, chCont.to, addMinutesToDate(chCont.firstArrival, -1), false, chCont.options, false);
            }
        }
        return new QueryTripsResult(resultHeader, QueryTripsResult.Status.NO_TRIPS);
    }

    private static Product type2Product(final String chSearchType) {
        final HashMap<String, Product> mapping = new HashMap<>();
        mapping.put("IC", Product.HIGH_SPEED_TRAIN);
        mapping.put("ICE", Product.HIGH_SPEED_TRAIN);
        mapping.put("ICN", Product.HIGH_SPEED_TRAIN); // Intercity tilting train
        mapping.put("IRE", Product.REGIONAL_TRAIN);
        mapping.put("TGV", Product.HIGH_SPEED_TRAIN);
        mapping.put("RJX", Product.HIGH_SPEED_TRAIN); // RailJetExpress
        mapping.put("NJ", Product.HIGH_SPEED_TRAIN); // ÖBB NightJet
        mapping.put("IR", Product.HIGH_SPEED_TRAIN);
        mapping.put("EC", Product.HIGH_SPEED_TRAIN);
        mapping.put("RE", Product.REGIONAL_TRAIN);
        mapping.put("PE", Product.REGIONAL_TRAIN); // Panorama Express
        mapping.put("BEX", Product.REGIONAL_TRAIN); // Bernina Express
        mapping.put("GEX", Product.REGIONAL_TRAIN); // Galcier Express
        mapping.put("CEX", Product.REGIONAL_TRAIN); // Centovalli Express (operated by SSIF italy)
        mapping.put("CER", Product.REGIONAL_TRAIN); // Centovalli Regional (operated by SSIF italy)
        mapping.put("R", Product.REGIONAL_TRAIN);
        mapping.put("SL", Product.REGIONAL_TRAIN); // Regional trains from france, around geneva
        mapping.put("M", Product.SUBWAY);
        mapping.put("FUN", Product.TRAM); // Funicular railways
        mapping.put("CC", Product.TRAM);  // Also used for funicular railways
        mapping.put("B", Product.BUS);
        mapping.put("S", Product.SUBURBAN_TRAIN);
        mapping.put("T", Product.TRAM);
        mapping.put("PB", Product.CABLECAR);
        mapping.put("GB", Product.CABLECAR); // Gondola Lift
        mapping.put("BAT", Product.FERRY);
        // There is unfortunately no 'UNKNOWN' product, and since this static list seems quite brittle to me, we return
        // Product.REGIONAL_TRAIN if there is no match
        return mapping.getOrDefault(chSearchType, Product.REGIONAL_TRAIN);
    }

    /**
     * Reduce products selection to the limited set of products offered by the API
     *
     * @param products Set of products
     * @return A possibly empty, comma seperated string ready to use ase parameter value in the GET request
     */
    private static String product2apiType(final Set<Product> products) {
        if (products == null) return "";
        final HashMap<Product, String> mapping = new HashMap<>();
        final HashSet<String> productList = new HashSet<>(5);
        mapping.put(Product.ON_DEMAND, "");
        mapping.put(Product.HIGH_SPEED_TRAIN, "train");
        mapping.put(Product.REGIONAL_TRAIN, "train");
        mapping.put(Product.SUBURBAN_TRAIN, "train");
        mapping.put(Product.SUBWAY, "tram"); // The only subway in switzerland is considered as tram by the API :(
        mapping.put(Product.TRAM, "tram");
        mapping.put(Product.BUS, "bus");
        mapping.put(Product.FERRY, "ship");
        mapping.put(Product.CABLECAR, "cableway");

        for (final Product product : products) {
            final String productApiString = mapping.getOrDefault(product, "");
            if (!"".equals(productApiString)) {
                productList.add(productApiString);
            }
        }
        return String.join(",", productList);
    }

    private Location extractLocation(final JSONObject locationEntry) throws JSONException {
        // Sometimes there is no station-id and location
        final String stationID = locationEntry.has("id") ? locationEntry.getString("id") : null;
        final Point stationLocation = locationEntry.has("lat") ? Point.fromDouble(locationEntry.getDouble("lat"), locationEntry.getDouble("lon")) : null;
        return new Location(
                LocationType.STATION,
                stationID,
                stationLocation,
                null,
                locationEntry.getString("label"));
    }

    private static PTDate addMinutesToDate(final PTDate orig, final long minutes) {
        if (orig == null) return null;
        final long newTime = orig.getTime() + (1000 * 60 * minutes);
        return new PTDate(newTime, orig.getOffset());
    }

    private static int delayParser(final String delay, final CHSearchProvider provider) {
        try {
            // "X" translates to canceled
            if ("X".equals(delay)) return 0;
            return Integer.parseInt(delay);
        } catch (final NumberFormatException x) {
            provider.log.error("delayParser NumberFormatException", x);
        }
        return 0;
    }

    /**
     * Expands shorthand hex "f0a" to "ff00aa" and adds "#" as prefix
     *
     * @param hexValue 3 or 6 character hex value
     * @return Expanded and prefixed hex string
     */
    private static String expandHex(final String hexValue) {
        //Unfortunately they mix between short and long from...
        if (hexValue.length() == 3) {
            final char[] seq = {'#',
                    hexValue.charAt(0), hexValue.charAt(0),
                    hexValue.charAt(1), hexValue.charAt(1),
                    hexValue.charAt(2), hexValue.charAt(2)
            };
            return new String(seq);
        } else if (hexValue.length() == 6) {
            return "#" + hexValue;
        } else {
            throw new NumberFormatException("hex value has more than six bytes: " + hexValue);
        }
    }

    private static class RouteResult {
        public final int nConnections;
        public final String error;
        public final List<String> messages = new ArrayList<>();
        public final List<Connection> connections = new ArrayList<>(N_TRIPS);

        /**
         * Mapping of route.json endpoint
         *
         * @param rawResult raw json result
         */
        RouteResult(final JSONObject rawResult, final CHSearchProvider provider) throws JSONException, ParseException {
            if (rawResult.has("error")) {
                this.error = rawResult.getString("error");
                this.nConnections = 0;
            } else if (rawResult.has("count")) {
                nConnections = rawResult.getInt("count");
                this.error = "";
                final JSONArray rawCons = rawResult.getJSONArray("connections");
                for (int i = 0; i < rawCons.length(); i++) {
                    connections.add(new Connection(rawCons.getJSONObject(i), provider));
                }
            } else {
                if (rawResult.has("messages")) {
                    final JSONArray rawMessages = rawResult.getJSONArray("messages");
                    for (int j = 0; j < rawMessages.length(); j++) {
                        messages.add(rawMessages.getString(j));
                    }
                }
                this.nConnections = 0;
                this.error = "No connections found";
            }


        }

        private static class Connection {
            public final List<Leg> legs = new ArrayList<>();
            public final String from;
            public final String to;
            public final Date arrival;
            public final Date departure;
            public final double duration;
            public final List<Disruption> disruptions;

            Connection(final JSONObject rawConnection, final CHSearchProvider provider) throws JSONException, ParseException {
                try {
                    this.from = rawConnection.getString("from");
                    this.to = rawConnection.getString("to");
                    this.duration = rawConnection.getDouble("duration");
                    this.arrival = parseDateTime(rawConnection.getString("arrival"));
                    this.departure = parseDateTime(rawConnection.getString("departure"));
                    this.disruptions = DISABLE_DISRUPTIONS ? new ArrayList<>() : Disruption.extractDisruptionsToList(rawConnection, provider);

                    final JSONArray rawLegs = rawConnection.getJSONArray("legs");
                    for (int i = 0; i < rawLegs.length(); i++) {
                        legs.add(new Leg(rawLegs.getJSONObject(i), provider));
                    }
                } catch (final JSONException x) {
                    throw new JSONException("Connection::" + x);
                }
            }


            private static class Disruption {
                public final String ID;
                public final String summary;
                public final String reason;
                public final String consequence;
                public final String recommendation;
                public final @Nullable
                Date timeStart;
                public final @Nullable
                Date timeEnd;

                private Disruption(final JSONObject rawDisruption) throws JSONException {
                    String tempSummary = "No information provided by API";
                    String tempReason = "";
                    String tempConsequence = "";
                    String tempRecommendation = "";
                    this.ID = rawDisruption.getString("id");
                    this.timeStart = new Date();
                    this.timeEnd = new Date();

                    if (rawDisruption.has("periods")) {
                        // If the disruption parser breaks, start looking in this mess here
                        // ToDo: Make timezone aware
                        final JSONObject disruptionPeriods = rawDisruption.getJSONObject("periods");
                        final JSONArray disruptionValidity = disruptionPeriods.getJSONArray("validity");
                        final JSONArray firstDisruptionValidity = disruptionValidity.getJSONArray(0);
                        this.timeStart.setTime(firstDisruptionValidity.getLong(0) * 1000);
                        this.timeEnd.setTime(firstDisruptionValidity.getLong(1) * 1000);
                    }

                    if (rawDisruption.has("texts")) {
                        final JSONObject disruptionTexts = rawDisruption.getJSONObject("texts");
                        if (disruptionTexts.has("S")) {
                            final JSONObject shortDisruptionTexts = disruptionTexts.getJSONObject("S");
                            tempSummary = shortDisruptionTexts.has("summary") ? shortDisruptionTexts.getString("summary") : "No information provided by API";
                            tempReason = shortDisruptionTexts.has("reason") ? shortDisruptionTexts.getString("reason") : "";
                            tempConsequence = shortDisruptionTexts.has("consequence") ? shortDisruptionTexts.getString("consequence") : "";
                            tempRecommendation = shortDisruptionTexts.has("recommendation") ? shortDisruptionTexts.getString("recommendation") : "";
                        }
                    }
                    this.summary = tempSummary;
                    this.reason = tempReason;
                    this.consequence = tempConsequence;
                    this.recommendation = tempRecommendation;
                }

                @Override
                @Nonnull
                public String toString() {
                    return this.summary + "," + this.reason;
                }

                /**
                 * Returns a (possibly empty) List of Disruptions from a JsonObject
                 *
                 * @param rawObject JSONObject, usually a "leg" or "connection"
                 * @return list of Disruptions (empty, if key "disruptions" not present)
                 */
                public static List<Disruption> extractDisruptionsToList(final JSONObject rawObject, final CHSearchProvider provider) throws JSONException {
                    final List<Disruption> disruptions = new ArrayList<>();
                    if (rawObject.has("disruptions")) {
                        final Object rawDisruptions = rawObject.get("disruptions");
                        if (rawDisruptions instanceof JSONObject) {
                            try {
                                //Since the individual disruptions have their url as key(!) we have to do a bit of ugliness here...
                                final JSONArray dis = ((JSONObject) rawDisruptions).names();
                                if (dis != null) {
                                    for (int k = 0; k < ((JSONObject) rawDisruptions).length(); k++) {
                                        final String disruptionKey = dis.getString(k);
                                        disruptions.add(new Disruption(((JSONObject) rawDisruptions).getJSONObject(disruptionKey)));
                                    }
                                }
                            } catch (final Exception e) {
                                // Apparently, the format of disruptions changes quite often, so let's catch any errors here...
                                provider.log.error("extractDisruptionsToList", e);
                                return disruptions;
                            }
                        }
                    }
                    return disruptions;
                }
            }

            private static class Leg {
                public final @Nullable
                PTDate departure;
                public final @Nullable
                PTDate arrival;
                public final String tripID;
                public final @Nullable
                String stopID;
                public final String name;
                /**
                 * Train number
                 */
                public final String Z;
                /**
                 * Train Product
                 */
                public final String G;
                /**
                 * Line number
                 */
                public final String L;
                public final @Nullable
                String terminal;
                public final @Nullable
                String line;
                public final String type;
                public final @Nullable
                String operator;
                public final int fgColor;
                public final int bgColor;
                public final double runningTime;
                public final int dep_delay;
                public final int arr_delay;
                public final @Nullable
                String track;
                public final @Nullable
                Double lat;
                public final boolean cancelled;
                public final @Nullable
                Double lon;
                public final boolean isAddress;
                public final @Nullable
                Exit exit;
                public final List<Stop> stops = new ArrayList<>();
                public final boolean is_walk;
                public final List<String> infotexts = new ArrayList<>();
                public final List<Disruption> disruptions;


                public Leg(final JSONObject rawLeg, final CHSearchProvider provider) throws JSONException, ParseException {
                    try {
                        this.departure = rawLeg.has("departure") ? parseDateTime(rawLeg.getString("departure")) : null;
                        this.arrival = rawLeg.has("arrival") ? parseDateTime(rawLeg.getString("arrival")) : null;
                        this.type = rawLeg.has("type") ? rawLeg.getString("type") : "unknown";
                        this.is_walk = "walk".equals(this.type);
                        this.Z = rawLeg.has("*Z") ? rawLeg.getString("*Z") : "";
                        this.G = rawLeg.has("*G") ? rawLeg.getString("*G") : "UNKN";
                        this.L = rawLeg.has("*L") ? rawLeg.getString("*L") : "";
                        this.name = rawLeg.getString("name");
                        this.terminal = rawLeg.has("terminal") ? rawLeg.getString("terminal") : null;
                        this.tripID = rawLeg.has("tripid") ? rawLeg.getString("tripid") : "generated_" + UUID.randomUUID();
                        final String rawLine = rawLeg.has("line") ? rawLeg.getString("line") : null;
                        // Otherwise we could get a line named "null"
                        this.line = "null".equals(rawLine) ? "" : rawLine;
                        this.stopID = rawLeg.has("stopid") ? rawLeg.getString("stopid") : null;
                        this.operator = rawLeg.has("operator") ? rawLeg.getString("operator") : null;
                        if (rawLeg.has("bgcolor")) {
                            this.fgColor = parseColor(expandHex(rawLeg.getString("fgcolor")));
                            this.bgColor = parseColor(expandHex(rawLeg.getString("bgcolor")));
                        } else {
                            this.bgColor = Style.WHITE;
                            this.fgColor = Style.BLACK;
                        }
                        this.runningTime = rawLeg.has("runningtime") ? rawLeg.getDouble("runningtime") : 0;
                        this.dep_delay = rawLeg.has("dep_delay") ? delayParser(rawLeg.getString("dep_delay"), provider) : 0;
                        this.arr_delay = rawLeg.has("arr_delay") ? delayParser(rawLeg.getString("arr_delay"), provider) : 0;
                        this.track = rawLeg.has("track") ? rawLeg.getString("track") : null;
                        this.lat = rawLeg.has("lat") ? rawLeg.getDouble("lat") : null;
                        this.lon = rawLeg.has("lon") ? rawLeg.getDouble("lon") : null;
                        this.isAddress = rawLeg.has("isaddress") && rawLeg.getBoolean("isaddress");
                        this.exit = rawLeg.has("exit") ? new Exit(rawLeg.getJSONObject("exit"), provider) : null;
                        this.cancelled = rawLeg.has("cancelled") && rawLeg.getBoolean("cancelled");
                        this.disruptions = DISABLE_DISRUPTIONS ? new ArrayList<>() : Disruption.extractDisruptionsToList(rawLeg, provider);

                        if (rawLeg.has("stops") && !rawLeg.isNull("stops")) {
                            final JSONArray rawStops = rawLeg.getJSONArray("stops");
                            for (int i = 0; i < rawStops.length(); i++) {
                                stops.add(new Stop(rawStops.getJSONObject(i), provider));
                            }
                        }
                        // Sometimes we have an "infotext" attribute which e.g holds the phone number of on-demand services
                        if (rawLeg.has("infotext")) {
                            final JSONArray rawInfoTexts = rawLeg.getJSONArray("infotext");
                            for (int i = 0; i < rawInfoTexts.length(); i++) {
                                infotexts.add(rawInfoTexts.getString(i));
                            }
                        }

                    } catch (final JSONException x) {
                        throw new JSONException("Leg::" + x);
                    }


                }

                private static class Exit {
                    public final PTDate arrival;
                    public final @Nullable
                    String stopID;
                    public final String name;
                    public final double waitTime;
                    public final @Nullable
                    String track;
                    public final int arr_delay;
                    public final @Nullable
                    Double lat;
                    public final @Nullable
                    Double lon;
                    public final boolean isAddress;

                    Exit(final JSONObject rawExit, final CHSearchProvider provider) throws JSONException, ParseException {
                        try {
                            this.arrival = parseDateTime(rawExit.getString("arrival"));
                            this.stopID = rawExit.has("stopid") ? rawExit.getString("stopid") : null;
                            this.name = rawExit.getString("name");
                            this.waitTime = rawExit.has("waittime") ? rawExit.getDouble("waittime") : 0;
                            this.track = rawExit.has("track") ? rawExit.getString("track") : null;
                            this.arr_delay = rawExit.has("arr_delay") ? delayParser(rawExit.getString("arr_delay"), provider) : 0;
                            this.lat = rawExit.has("lat") ? rawExit.getDouble("lat") : null;
                            this.lon = rawExit.has("lon") ? rawExit.getDouble("lon") : null;
                            this.isAddress = rawExit.has("isaddress") && rawExit.getBoolean("isaddress");
                        } catch (final JSONException x) {
                            throw new JSONException("Exit::" + x);
                        }

                    }
                }

                private static class Stop {
                    public final @Nullable
                    PTDate arrival;
                    public final @Nullable
                    PTDate departure;
                    public final int dep_delay;
                    public final int arr_delay;
                    public final String stopID;
                    public final String name;
                    public final Double lat;
                    public final Double lon;
                    // Sometimes the we have no real "Stop" e.g (Löschbergbasis Tunnel) which means we have no arrival/departure times
                    public final boolean isSpecial;

                    Stop(final JSONObject rawStop, final CHSearchProvider provider) throws JSONException, ParseException {
                        try {
                            if (rawStop.has("arrival") || rawStop.has("departure")) {
                                // The first stop does not have an arrival attribute an similarly the last no departure
                                this.departure = rawStop.has("departure") ? parseDateTime(rawStop.getString("departure")) : parseDateTime(rawStop.getString("arrival"));
                                this.arrival = rawStop.has("arrival") ? parseDateTime(rawStop.getString("arrival")) : parseDateTime(rawStop.getString("departure"));
                                this.isSpecial = false;
                            } else {
                                this.isSpecial = true;
                                this.departure = null;
                                this.arrival = null;
                            }
                            this.dep_delay = rawStop.has("dep_delay") ? delayParser(rawStop.getString("dep_delay"), provider) : 0;
                            this.arr_delay = rawStop.has("arr_delay") ? delayParser(rawStop.getString("arr_delay"), provider) : 0;
                            this.stopID = rawStop.getString("stopid");
                            this.name = rawStop.getString("name");
                            this.lat = rawStop.getDouble("lat");
                            this.lon = rawStop.getDouble("lon");
                        } catch (final JSONException x) {
                            throw new JSONException("Stop::" + x);
                        }

                    }
                }
            }

        }
    }

    private static class StationBoardResult {
        public final String stationID;
        public final String name;
        public final double lat;
        public final double lon;
        public final List<StationBoardEntry> entries = new ArrayList<>();

        /**
         * Mapping of stationboard.json endpoint
         *
         * @param rawStationBoard raw Json object
         */
        public StationBoardResult(final JSONObject rawStationBoard, final CHSearchProvider provider) throws JSONException, ParseException {
            final JSONObject rawStop = rawStationBoard.getJSONObject("stop");
            this.stationID = rawStop.getString("id");
            this.name = rawStop.getString("name");
            this.lat = rawStop.getDouble("lat");
            this.lon = rawStop.getDouble("lon");
            final JSONArray rawEntries = rawStationBoard.getJSONArray("connections");
            for (int i = 0; i < rawEntries.length(); i++) {
                entries.add(new StationBoardEntry(rawEntries.getJSONObject(i), provider));
            }
        }

        private static class StationBoardEntry {
            public final PTDate time;
            public final String G; // Product
            public final String L; // Line number
            public final String Z; // Full train number
            public final String line;
            public final @Nullable
            String track;
            public final String operator;
            public final int fgColor;
            public final int bgColor;
            public final int arr_delay;
            public final int dep_delay;
            public Terminal terminal;


            public StationBoardEntry(final JSONObject rawEntry, final CHSearchProvider provider) throws JSONException, ParseException {
                this.time = parseDateTime(rawEntry.getString("time"));
                G = rawEntry.has("*G") ? rawEntry.getString("*G") : "UNKN";
                L = rawEntry.has("*L") ? rawEntry.getString("*L") : "";
                Z = rawEntry.has("*Z") ? rawEntry.getString("*Z") : "";
                final String rawLine = rawEntry.has("line") ? rawEntry.getString("line") : null;
                // Otherwise we would get a line named "null"
                this.line = "null".equals(rawLine) ? "" : rawLine;
                this.operator = rawEntry.getString("operator");
                this.track = rawEntry.has("track") ? rawEntry.getString("track") : null;
                final String[] colors = rawEntry.getString("color").split("~", 3);
                this.dep_delay = rawEntry.has("dep_delay") ? delayParser(rawEntry.getString("dep_delay"), provider) : 0;
                this.arr_delay = rawEntry.has("arr_delay") ? delayParser(rawEntry.getString("arr_delay"), provider) : 0;
                this.bgColor = "".equals(colors[0]) ? Style.WHITE : parseColor(expandHex(colors[0]));
                this.fgColor = "".equals(colors[1]) ? Style.BLACK : parseColor(expandHex(colors[1]));
                this.terminal = new Terminal(rawEntry.getJSONObject("terminal"));
            }

            private static class Terminal {
                public final String stationID;
                public final String name;
                public final double lat;
                public final double lon;

                public Terminal(final JSONObject rawTerminal) throws JSONException {
                    this.stationID = rawTerminal.has("id") ? rawTerminal.getString("id") : null;
                    this.name = rawTerminal.getString("name");
                    this.lat = rawTerminal.getDouble("lat");
                    this.lon = rawTerminal.getDouble("lon");
                }
            }
        }

    }

    public static class CHSearchContext implements QueryTripsContext {
        @Serial
        private static final long serialVersionUID = 1170137277212192970L;
        private final Location from;
        private final Location to;
        private final @Nullable
        Location via;
        private final @Nullable
        PTDate lastDeparture;
        private final @Nullable
        PTDate firstArrival;
        private final @Nullable
        TripOptions options;

        /**
         * Stores a route query context to create before/after queries
         *
         * @param from          From location
         * @param to            To location
         * @param via           Via Location
         * @param fristArrival  Arrival time of first connection
         * @param lastDeparture Departure time of last connection
         * @param options       (currently not supported)
         */
        public CHSearchContext(final Location from, final Location to, @Nullable final Location via, @Nullable final PTDate fristArrival, @Nullable final PTDate lastDeparture, @Nullable final TripOptions options) {
            this.from = from;
            this.to = to;
            this.via = via;
            this.lastDeparture = lastDeparture;
            this.firstArrival = fristArrival;
            this.options = options;
        }

        @Override
        public boolean canQueryLater() {
            return lastDeparture != null;
        }

        @Override
        public boolean canQueryEarlier() {
            return firstArrival != null;
        }
    }
}
