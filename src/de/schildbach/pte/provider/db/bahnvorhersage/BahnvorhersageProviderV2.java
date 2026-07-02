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

package de.schildbach.pte.provider.db.bahnvorhersage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.PTDate;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.TransferDetails;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.db.DbProvider;
import okhttp3.HttpUrl;

public final class BahnvorhersageProviderV2 extends AbstractBahnvorhersageProvider {
    private final HttpUrl journeysEndpoint;

    public BahnvorhersageProviderV2(final DbProvider dbProvider) {
        super(dbProvider);
        this.journeysEndpoint = API_BASE.newBuilder().addPathSegments("mobile/v2/journeys").build();
    }

    @Override
    public List<TransferDetails> evaluateTransfersForTrip(final Trip trip) throws IOException {
        if (!checkPreconditions(trip))
            return null;

        return queryTransferDetailsForTrip(trip);
    }

    private List<TransferDetails> queryTransferDetailsForTrip(final Trip trip) throws IOException {
        final HttpUrl url = journeysEndpoint;

        String page = null;
        try {
            final JSONObject request = buildRequestObject(trip);
            if (request == null) {
                log.error("unable to build request for bahnvorhersage");
                return null;
            }
            page = doRequest(url, request.toString());
            final JSONArray res = new JSONArray(page);
            if (res.length() < 1)
                return null;
            return parseLegsFromJourneyResult(res.getJSONObject(0));
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    private JSONObject buildRequestObject(final Trip trip) throws JSONException, IOException {
        final JSONObject oRequest = new JSONObject();
        final JSONArray journeysArray = new JSONArray();
        journeysArray.put(buildJourneyObject(trip));
        oRequest.put("journeys", journeysArray);
        final JSONObject trips = buildTripsObject(trip, true);
        if (trips != null)
            oRequest.put("trips", trips);
        return oRequest;
    }

    private JSONObject buildTripsObject(final Trip trip, final boolean nullOnAnyError) throws IOException, JSONException {
        final JSONObject oTrips = new JSONObject();
        for (final Trip.Leg leg : trip.legs) {
            if (!(leg instanceof Trip.Public))
                continue;
            final DbProvider.DbJourneyRef journeyRef = (DbProvider.DbJourneyRef) ((Trip.Public) leg).journeyRef;
            final QueryJourneyResult result = dbProvider.queryJourneyWithCache(journeyRef);
            if (result == null || result.status != QueryJourneyResult.Status.OK) {
                if (nullOnAnyError)
                    return null;
                continue;
            }
            final Trip.Public journeyLeg = result.journeyLeg;
            final JourneyRef ref = journeyLeg.journeyRef;
            if (ref == null) {
                if (nullOnAnyError)
                    return null;
                continue;
            }
            final JSONObject oTrip = buildLegObject(journeyLeg, "id");
            oTrips.put(ref.getUniqueId(), oTrip);
        }
        return oTrips;
    }

    private JSONObject buildJourneyObject(final Trip trip) throws JSONException {
        final JSONObject oJourney = new JSONObject();
        oJourney.put("type", "journey");

        final JSONArray oLegs = new JSONArray();
        Trip.Public prevPublicLeg = null;
        JSONObject prevTransferObject = null;
        for (final Trip.Leg leg : trip.legs) {
            final JSONObject oLeg;
            if (leg instanceof Trip.Public) {
                final Trip.Public pubLeg = (Trip.Public) leg;
                if (prevTransferObject != null) {
                    oLegs.put(prevTransferObject);
                    prevTransferObject = null;
                } else if (prevPublicLeg != null) {
                    oLegs.put(buildTransferObject(prevPublicLeg, pubLeg));
                }
                oLeg = buildLegObject(pubLeg, "tripId");
                prevPublicLeg = pubLeg;
            } else if (leg instanceof Trip.Individual) {
                if (prevPublicLeg != null) {
                    prevTransferObject = buildTransferObject((Trip.Individual) leg);
                    prevPublicLeg = null;
                }
                continue;
            } else {
                continue;
            }
            oLegs.put(oLeg);
        }
        oJourney.put("legs", oLegs);

        oJourney.put("refreshToken", getRefreshTokenForTrip(trip));
        return oJourney;
    }

    private JSONObject buildLegObject(final Trip.Public leg, final String idName) throws JSONException {
        final Stop arrivalStop = leg.arrivalStop;
        final Stop departureStop = leg.departureStop;

        final JSONObject oLeg = new JSONObject();

        oLeg.put("origin", buildLocationObject(departureStop.location));
        oLeg.put("destination", buildLocationObject(arrivalStop.location));

        buildStopData(oLeg, departureStop, false);
        buildStopData(oLeg, arrivalStop, true);

        oLeg.put(idName, leg.journeyRef.getUniqueId());
        oLeg.put("line", buildLineObject(leg));
        oLeg.put("direction", leg.destination == null ? null : leg.destination.location.fullName(true));

        final JSONArray stopOvers = new JSONArray();
        JSONObject oStopOver;

        oStopOver = new JSONObject();
        oStopOver.put("stop", buildLocationObject(departureStop.location));
        setCancelled(oStopOver, departureStop.departureCancelled);
        buildStopData(oStopOver, departureStop, false);
        buildStopData(oStopOver, null, true);
        stopOvers.put(oStopOver);

        if (leg.intermediateStops != null) {
            for (final Stop stop : leg.intermediateStops) {
                oStopOver = new JSONObject();
                oStopOver.put("stop", buildLocationObject(stop.location));
                setCancelled(oStopOver, stop.arrivalCancelled || stop.departureCancelled);
                buildStopData(oStopOver, stop, true);
                buildStopData(oStopOver, stop, false);
                stopOvers.put(oStopOver);
            }
        }

        oStopOver = new JSONObject();
        oStopOver.put("stop", buildLocationObject(arrivalStop.location));
        setCancelled(oStopOver, arrivalStop.arrivalCancelled);
        buildStopData(oStopOver, arrivalStop, true);
        buildStopData(oStopOver, null, false);
        stopOvers.put(oStopOver);

        oLeg.put("stopovers", stopOvers);

        setCancelled(oLeg, departureStop.departureCancelled || arrivalStop.arrivalCancelled);

        return oLeg;
    }

    private static final Pattern P_REGIONAL_FAHRTNR = Pattern.compile("^[^(]*\\(([^)]*)\\).*$");
    private static final Pattern P_LONG_DISTANCE_FAHRTNR = Pattern.compile("^[^\\d]*(\\d+).*$");

    private JSONObject buildLineObject(final Trip.Public leg) throws JSONException {
        final JSONObject oLine = new JSONObject();

        final DbProvider.DbJourneyRef dbJourneyRef = (DbProvider.DbJourneyRef) leg.journeyRef;
        final Line line = leg.line;
        final Product product = line.product;

        oLine.put("type", "line");
        final String lineId = "?";
        oLine.put("id", lineId);

        String adminCode = null;
        String fahrtNr = null;
        String productName = null;
        if (dbJourneyRef != null) {
            productName = dbJourneyRef.productName;
            adminCode = dbJourneyRef.adminCode;
            fahrtNr = dbJourneyRef.serviceNumber;
        }
        if (fahrtNr == null) {
            final String name = line.name;
            if (name != null) {
                if (product == Product.HIGH_SPEED_TRAIN) {
                    final Matcher m = P_LONG_DISTANCE_FAHRTNR.matcher(name);
                    if (m.matches()) {
                        fahrtNr = m.group(1);
                    }
                } else {
                    final Matcher m = P_REGIONAL_FAHRTNR.matcher(name);
                    if (m.matches()) {
                        fahrtNr = m.group(1);
                    }
                }
            }
        }
        oLine.put("fahrtNr", fahrtNr == null ? "0" : fahrtNr);
        oLine.put("name", line.label);
        oLine.put("adminCode", adminCode == null ? "?" : adminCode);
        oLine.put("productName", productName == null ? "?" : productName);

        return oLine;
    }

    private JSONObject buildLocationObject(final Location location) throws JSONException {
        final JSONObject oLoc = new JSONObject();
        String type = null;
        String id = null;
        String name = null;
        switch (location.type) {
            case STATION:
                type = "station";
                id = location.id;
                name = location == null ? null : location.fullName(true);
                break;
        }
        if (type == null)
            return null;
        oLoc.put("type", type);
        oLoc.put("id", forceJsonNull(id));
        oLoc.put("name", forceJsonNull(name));
        final JSONObject oCoord = new JSONObject();
        oCoord.put("type", "location");
        oCoord.put("id", forceJsonNull(id));
        if (location.coord != null) {
            oCoord.put("latitude", location.coord.getLatAsDouble());
            oCoord.put("longitude", location.coord.getLonAsDouble());
        }
        oLoc.put("location", oCoord);
        return oLoc;
    }

    private void buildStopData(final JSONObject obj, final Stop stop, final boolean isArrival) throws JSONException {
        final PTDate time;
        final PTDate plannedTime;
        final Position position;
        if (stop == null) {
            time = null;
            plannedTime = null;
            position = null;
        } else {
            time = isArrival ? stop.getArrivalTime(false) : stop.getDepartureTime(false);
            plannedTime = isArrival ? stop.getArrivalTime(true) : stop.getDepartureTime(true);
            position = isArrival ? stop.getArrivalPosition() : stop.getDeparturePosition();
        }
        buildStopTimes(obj, time, plannedTime, isArrival);
        obj.put(isArrival ? "arrivalPlatform" : "departurePlatform", forceJsonNull(position == null ? null : position.name));
    }

    private void buildStopTimes(final JSONObject obj, final PTDate time, final PTDate plannedTime, final boolean isArrival) throws JSONException {
        obj.put(isArrival ? "arrival" : "departure", forceJsonNull(buildDateTime(time)));
        obj.put(isArrival ? "plannedArrival" : "plannedDeparture", buildDateTime(plannedTime));
        // obj.put(isArrival ? "arrivalDelay" : "departureDelay", (time.getTime() - plannedTime.getTime()) / 1000);
    }

    private JSONObject buildTransferObject(final Trip.Public legFrom, final Trip.Public legTo) throws JSONException {
        final JSONObject oTransfer = new JSONObject();

        oTransfer.put("origin", buildLocationObject(legFrom.arrival));
        oTransfer.put("destination", buildLocationObject(legTo.departure));

        final PTDate departureTime = legFrom.getArrivalTime();
        buildStopTimes(oTransfer, departureTime, departureTime, false);

        final PTDate arrivalTime = legTo.getDepartureTime();
        buildStopTimes(oTransfer, arrivalTime, arrivalTime, true);

        oTransfer.put("walking", true);

        return oTransfer;
    }

    private JSONObject buildTransferObject(final Trip.Individual leg) throws JSONException {
        final JSONObject oTransfer = new JSONObject();

        oTransfer.put("origin", buildLocationObject(leg.departure));
        oTransfer.put("destination", buildLocationObject(leg.arrival));

        final PTDate departureTime = leg.getDepartureTime();
        buildStopTimes(oTransfer, departureTime, departureTime, false);

        final PTDate arrivalTime = leg.getArrivalTime();
        buildStopTimes(oTransfer, arrivalTime, arrivalTime, true);

        oTransfer.put("walking", true);
        oTransfer.put("distance", leg.distance);

        return oTransfer;
    }

    private void setCancelled(final JSONObject obj, final boolean isCancelled) throws JSONException {
        if (isCancelled)
            obj.put("cancelled", isCancelled);
    }

    private Object forceJsonNull(final Object obj) {
        return obj == null ? JSONObject.NULL : obj;
    }

    private static final DateFormat ISO_DATE_TIME_UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private String buildDateTime(final PTDate date) {
        if (date == null)
            return null;
        return ISO_DATE_TIME_UTC_FORMAT.format(date);
    }
}
