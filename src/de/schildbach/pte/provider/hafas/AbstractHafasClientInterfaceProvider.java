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

package de.schildbach.pte.provider.hafas;

import static de.schildbach.pte.util.Preconditions.checkArgument;
import static de.schildbach.pte.util.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Destination;
import de.schildbach.pte.dto.Fare;
import de.schildbach.pte.dto.JourneyRef;
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
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.PTDate;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.GeoUtils;
import de.schildbach.pte.util.ParserUtils;
import de.schildbach.pte.util.PolylineFormat;

import okhttp3.HttpUrl;

/**
 * This is an implementation of the HCI (HAFAS Client Interface).
 * 
 * @author Andreas Schildbach
 */
public abstract class AbstractHafasClientInterfaceProvider extends AbstractHafasProvider {

    private static final int[] VALID_MIN_TRANSFER_TIMES = { 0, 5, 10, 20, 30, 60 };

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

    private HttpUrl apiBase;
    private String apiEndpoint = "mgate.exe";
    @Nullable
    private String apiVersion;
    private int apiLevel;
    private boolean apiUseLocationLidOnly;
    @Nullable
    private String apiExt;
    @Nullable
    private HttpUrl webappConfigUrl;
    @Nullable
    private String apiAuthorization;
    @Nullable
    private String apiClient;
    private boolean useAddName = false;
    private boolean findNearbyStationsUsingRectangle = false;

    private static final String SERVER_PRODUCT = "hci";
    private static final String SECTION_TYPE_JOURNEY = "JNY";
    private static final String SECTION_TYPE_WALK = "WALK";
    private static final String SECTION_TYPE_TRANSFER = "TRSF";
    private static final String SECTION_TYPE_TELE_TAXI = "TETA";
    private static final String SECTION_TYPE_DEVI = "DEVI";
    private static final String SECTION_TYPE_CHECK_IN = "CHKI";
    private static final String SECTION_TYPE_CHECK_OUT = "CHKO";

    protected static class Remark {
        String code;
        String type;
        @Nullable
        String title;
        String text;
        @Nullable
        String url;
    }

    protected AbstractHafasClientInterfaceProvider(
            final NetworkId network,
            final HttpUrl apiBase,
            final Product[] productsMap) {
        super(network, productsMap);
        this.apiBase = requireNonNull(apiBase);
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new HafasTripRef(network, unpacker);
    }

    @Override
    protected String[] getValidUserInterfaceLanguages() {
        return new String[] { "en", "de" };
    }

    public HttpUrl getApiBase() {
        return apiBase;
    }

    protected AbstractHafasClientInterfaceProvider setApiEndpoint(final String apiEndpoint) {
        this.apiEndpoint = requireNonNull(apiEndpoint);
        return this;
    }

    public void setUseAddName(final boolean useAddName) {
        this.useAddName = useAddName;
    }

    public void setFindNearbyStationsUsingRectangle(final boolean findNearbyStationsUsingRectangle) {
        this.findNearbyStationsUsingRectangle = findNearbyStationsUsingRectangle;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    protected AbstractHafasClientInterfaceProvider setApiVersion(final String apiVersion) {
        final String[] versionParts = apiVersion.split("\\.");
        checkArgument(versionParts.length == 2, () -> "bad apiVersion");
        checkArgument("1".equals(versionParts[0]), () -> "apiVersion major must be 1");
        final int minorVersion;
        try {
            minorVersion = Integer.parseInt(versionParts[1]);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("invalid apiVersion");
        }
        checkArgument(minorVersion >= 14, () -> "apiVersion must be 1.14 or higher");
        this.apiVersion = apiVersion;
        this.apiLevel = minorVersion;
        this.apiUseLocationLidOnly = minorVersion >= 40;
        return this;
    }

    public @Nullable String getApiVersion() {
        return apiVersion;
    }

    protected AbstractHafasClientInterfaceProvider setApiExt(final String apiExt) {
        this.apiExt = requireNonNull(apiExt);
        return this;
    }

    public @Nullable String getApiExt() {
        return apiExt;
    }

    protected AbstractHafasClientInterfaceProvider setApiAuthorization(final String apiAuthorization) {
        if (apiAuthorization == null || apiAuthorization.startsWith("{")) {
            this.apiAuthorization = apiAuthorization;
            this.webappConfigUrl = null;
        } else {
            this.apiAuthorization = "";
            this.webappConfigUrl = HttpUrl.parse(apiAuthorization);
        }
        return this;
    }

    public String getApiAuthorization() throws IOException {
        if (apiAuthorization == null || !apiAuthorization.isEmpty() || webappConfigUrl == null)
            return apiAuthorization;
        loadWebappConfig();
        return apiAuthorization;
    }

    protected AbstractHafasClientInterfaceProvider setApiClient(final String apiClient) {
        this.apiClient = apiClient;
        return this;
    }

    public @Nullable String getApiClient() {
        return apiClient;
    }

    protected @Nullable String getWebAppLocalizationId() {
        return null;
    }

    private void loadWebappConfig() throws IOException {
        if (webappConfigUrl == null)
            return;

        final CharSequence page = httpClient.get(webappConfigUrl, null, "application/json");
        try {
            final JSONObject config = new JSONObject(page.toString());
            final String localizationId = getWebAppLocalizationId();
            final JSONObject localConfig = localizationId == null ? null : config.optJSONObject(localizationId);
            JSONObject hciAuth = localConfig == null ? null : localConfig.optJSONObject("hciAuth");
            if (hciAuth == null)
                hciAuth = config.getJSONObject("hciAuth");
            final String aid = hciAuth.getString("aid");
            apiAuthorization = String.format("{\"type\":\"AID\",\"aid\":\"%s\"}", aid);
            String apiUrl = localConfig == null ? null : localConfig.optString("urlMgate", null);
            if (apiUrl == null)
                apiUrl = config.optString("urlMgate", null);
            if (apiUrl != null) {
                final HttpUrl url = HttpUrl.parse(apiUrl);
                if (url != null) {
                    apiBase = url;
                    setApiEndpoint("");
                }
            }
        } catch (final JSONException je) {
            throw new ParserException("cannot parse json: '" + page + "' on " + webappConfigUrl, je);
        }
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(
            final Set<LocationType> types,
            final Location location,
            final EquivalentStationsMode equivsMode,
            final int maxDistance,
            final int maxLocations,
            final Set<Product> products) throws IOException {
        if (location.hasCoord())
            return jsonLocGeoPos(types, location.coord, equivsMode, maxDistance, maxLocations, products);
        else
            throw new IllegalArgumentException("cannot handle: " + location);
    }

    @Override
    public QueryDeparturesResult queryDepartures(
            final String stationId,
            final @Nullable Date time,
            final int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        return jsonStationBoard(stationId, time, maxDepartures, equivsMode, products);
    }

    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint,
            final @Nullable Set<LocationType> types, final int maxLocations) throws IOException {
        return jsonLocMatch(constraint, types, maxLocations);
    }

    @Override
    public QueryTripsResult queryTrips(
            final Location from, final @Nullable Location via, final Location to,
            final Date date, final boolean dep, final @Nullable TripOptions options,
            final boolean loadPath) throws IOException {
        final Set<TripFlag> tripFlags = options == null ? null : options.flags;
        return jsonTripSearch(from, via, to, date, dep,
                tripFlags != null && tripFlags.contains(TripFlag.DIRECT),
                tripFlags != null && tripFlags.contains(TripFlag.BIKE),
                options != null ? options.products : null,
                options != null ? options.walkSpeed : null,
                options != null ? options.maxWalkDistanceMeters : null,
                options == null || options.minTransferTimeMinutes == null ? null
                        : getApplicableMinTransferTime(options.minTransferTimeMinutes),
                null,
                loadPath);
    }

    @Override
    public QueryTripsResult queryMoreTrips(
            final QueryTripsContext context, final boolean later,
            final boolean loadPath) throws IOException {
        final JsonContext jsonContext = (JsonContext) context;
        return jsonTripSearch(jsonContext.from, jsonContext.via, jsonContext.to, jsonContext.date, jsonContext.dep,
                jsonContext.direct, jsonContext.bike,
                jsonContext.products, jsonContext.walkSpeed,
                jsonContext.maxWalkDistanceMeters, jsonContext.minTransferTimeMinutes,
                later ? jsonContext.laterContext : jsonContext.earlierContext,
                loadPath);
    }

    @Override
    public QueryTripsResult queryReloadTrip(final TripRef tripRef, final boolean loadPath) throws IOException {
        return jsonTripReload((HafasTripRef) tripRef, loadPath);
    }

    public static class HafasTripRef extends TripRef {
        private static final long serialVersionUID = -3651797810123427825L;

        public final String ctxRecon;

        public HafasTripRef(
                final NetworkId network,
                final Location from, final Location via, final Location to,
                final String ctxRecon) {
            super(network, from, via, to);
            this.ctxRecon = ctxRecon;
        }

        public HafasTripRef(final NetworkId network, final MessageUnpacker unpacker) throws IOException {
            super(network, unpacker);
            this.ctxRecon = unpacker.unpackString();
        }

        @Override
        public void packToMessage(final MessagePacker packer) throws IOException {
            super.packToMessage(packer);
            packer.packString(ctxRecon);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof HafasTripRef)) return false;
            final HafasTripRef that = (HafasTripRef) o;
            return super.equals(that)
                    && Objects.equals(ctxRecon, that.ctxRecon);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), ctxRecon);
        }
    }

    public static class HafasJourneyRef extends JourneyRef {
        private static final long serialVersionUID = -3103436830992954576L;

        public final String jid;

        public HafasJourneyRef(final String jid) {
            this.jid = jid;
        }

        @Override
        public String getUniqueId() {
            return jid;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof HafasJourneyRef)) return false;
            final HafasJourneyRef that = (HafasJourneyRef) o;
            return Objects.equals(jid, that.jid);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(jid);
        }
    }

    @Override
    public QueryJourneyResult queryJourney(
            final JourneyRef journeyRef,
            final boolean splitSubJourneys,
            final boolean loadPath) throws IOException {
        return jsonJourney((HafasJourneyRef) journeyRef, splitSubJourneys, loadPath);
    }

    protected final NearbyLocationsResult jsonLocGeoPos(
            final Set<LocationType> types,
            final Point coord,
            final EquivalentStationsMode equivsMode,
            int maxDistance,
            int maxLocations,
            final Set<Product> products) throws IOException {
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;
        final boolean getStations = types.contains(LocationType.STATION);
        final boolean getPOIs = types.contains(LocationType.POI);
        final String geoMatch;
        if (findNearbyStationsUsingRectangle) {
            if (maxDistance == 0)
                maxDistance = DEFAULT_MAX_DISTANCE_RECTANGLE;
            final Point lowerLeft = GeoUtils.getRemotePoint(coord, 225, maxDistance);
            final Point upperRight = GeoUtils.getRemotePoint(coord, 45, maxDistance);
            geoMatch = "\"rect\": {\"llCrd\": {\"x\":" + lowerLeft.getLonAs1E6() + ",\"y\":" + lowerLeft.getLatAs1E6() + "},\"urCrd\": {\"x\":" + upperRight.getLonAs1E6() + ",\"y\":" + upperRight.getLatAs1E6() + "}}";
        } else {
            if (maxDistance == 0)
                maxDistance = DEFAULT_MAX_DISTANCE_CIRCLE;
            geoMatch = "\"ring\":{\"cCrd\":{\"x\":" + coord.getLonAs1E6() + ",\"y\":" + coord.getLatAs1E6() + "},\"maxDist\":" + maxDistance + "}";
        }
        final String request = wrapJsonApiRequest("LocGeoPos", "{" //
                        + geoMatch //
                        + ",\"getStops\":" + getStations //
                        + ",\"getPOIs\":" + getPOIs //
                        + (products == null ? "" : (",\"locFltrL\":[{\"value\":" + productsInt(products) + ",\"mode\":\"INC\",\"type\":\"PROD\"}]")) //
                        + (maxLocations > 0 ? (",\"maxLoc\":" + maxLocations) : "") //
                        + "}", //
                false);

        final HttpUrl url = requestUrl(request);
        final CharSequence page = httpClient.get(url, request, "application/json");

        try {
            final JSONObject head = new JSONObject(page.toString());
            final String headErr = head.optString("err", null);
            if (headErr != null && !"OK".equals(headErr)) {
                if ("HAMM".equals(headErr) // ??? (sporadically found on OEBB)
                        || "HAMM_LOAD".equals(headErr) // ??? (sporadically found on DSB)
                ) {
                    return new NearbyLocationsResult(new ResultHeader(network, SERVER_PRODUCT), NearbyLocationsResult.Status.SERVICE_DOWN);
                }
                final String headErrTxt = head.optString("errTxt");
                throw new RuntimeException(headErr + " " + headErrTxt);
            }

            final JSONArray svcResList = head.getJSONArray("svcResL");
            checkState(svcResList.length() == 2);
            final ResultHeader header = parseServerInfo(svcResList.getJSONObject(0), head.getString("ver"));

            final JSONObject svcRes = svcResList.getJSONObject(1);
            checkState("LocGeoPos".equals(svcRes.getString("meth")));
            final String err = svcRes.getString("err");
            if (!"OK".equals(err)) {
                final String errTxt = svcRes.optString("errTxt");
                final String msg = "err=" + err + ", errTxt=\"" + errTxt + "\"";
                log.debug("Hafas error: {}", msg);
                final String plainErr = getPlainErr(err);
                if ("FAIL".equals(plainErr))
                    return new NearbyLocationsResult(header, NearbyLocationsResult.Status.SERVICE_DOWN);
                if ("CGI_READ_FAILED".equals(plainErr))
                    return new NearbyLocationsResult(header, NearbyLocationsResult.Status.SERVICE_DOWN);
                if ("CGI_NO_SERVER".equals(plainErr))
                    return new NearbyLocationsResult(header, NearbyLocationsResult.Status.SERVICE_DOWN);
                if ("H_UNKNOWN".equals(plainErr))
                    return new NearbyLocationsResult(header, NearbyLocationsResult.Status.SERVICE_DOWN);
                throw new RuntimeException(msg);
            }
            final JSONObject res = svcRes.getJSONObject("res");

            final JSONObject common = res.getJSONObject("common");
            /* final List<String[]> remarks = */ parseRemList(common.optJSONArray("remL"));
            final JSONArray commonLocL = common.optJSONArray("locL");
            final JSONArray crdSysList = common.optJSONArray("crdSysL");

            final JSONArray locL = res.optJSONArray("locL");
            final List<Location> locations;
            if (locL != null) {
                locations = parseLocList(locL, crdSysList, commonLocL, equivsMode);

                // filter unwanted location types
                locations.removeIf(location -> !types.contains(location.type));
            } else {
                locations = Collections.emptyList();
            }

            return new NearbyLocationsResult(header, locations);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    protected final QueryDeparturesResult jsonStationBoard(
            final String stationId,
            final @Nullable Date time,
            int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        final boolean canStbFltrEquiv = apiLevel <= 18;
        if (maxDepartures == 0)
            maxDepartures = DEFAULT_MAX_DEPARTURES;
        final boolean stbFltrEquiv;
        if (equivsMode != EquivalentStationsMode.USE_META) {
            stbFltrEquiv = false;
        } else if (canStbFltrEquiv) {
            stbFltrEquiv = true;
        } else {
            stbFltrEquiv = false;
            final int raisedMaxDepartures = maxDepartures * 4;
            log.info("stbFltrEquiv workaround in effect: querying for {} departures rather than {}",
                    raisedMaxDepartures, maxDepartures);
            maxDepartures = raisedMaxDepartures;
        }

        final Calendar c = new GregorianCalendar(timeZone);
        c.setTime(time);
        final String jsonDate = jsonDate(c);
        final String jsonTime = jsonTime(c);
        final String normalizedStationId = normalizeStationId(stationId);
        final String maxJny = Integer.toString(maxDepartures);
        final String request = wrapJsonApiRequest("StationBoard", "{\"type\":\"DEP\"," //
                + "\"date\":\"" + jsonDate + "\"," //
                + "\"time\":\"" + jsonTime + "\"," //
                + "\"stbLoc\":{\"type\":\"S\"," + "\"state\":\"F\"," // F/M
                + "\"" + (isLid(normalizedStationId) ? "lid" : "extId") + "\":" + JSONObject.quote(normalizedStationId.toString()) + "}," //
                + (canStbFltrEquiv ? "\"stbFltrEquiv\":" + stbFltrEquiv + "," : "") //
                + "\"maxJny\":" + maxJny + "}", false);

        final HttpUrl url = requestUrl(request);
        final CharSequence page = httpClient.get(url, request, "application/json");

        try {
            final JSONObject head = new JSONObject(page.toString());
            final String headErr = head.optString("err", null);
            if (headErr != null && !"OK".equals(headErr)) {
                if ("HAMM".equals(headErr) // ??? (sporadically found on OEBB)
                        || "HAMM_LOAD".equals(headErr) // ??? (sporadically found on DSB)
                ) {
                    return new QueryDeparturesResult(new ResultHeader(network, SERVER_PRODUCT), QueryDeparturesResult.Status.SERVICE_DOWN);
                }
                final String headErrTxt = head.optString("errTxt");
                throw new RuntimeException(headErr + " " + headErrTxt);
            }

            final JSONArray svcResList = head.getJSONArray("svcResL");
            checkState(svcResList.length() == 2);
            final ResultHeader header = parseServerInfo(svcResList.getJSONObject(0), head.getString("ver"));
            final QueryDeparturesResult result = new QueryDeparturesResult(header);

            final JSONObject svcRes = svcResList.optJSONObject(1);
            checkState("StationBoard".equals(svcRes.getString("meth")));
            final String err = svcRes.getString("err");
            if (!"OK".equals(err)) {
                final String errTxt = svcRes.optString("errTxt");
                final String msg = "err=" + err + ", errTxt=\"" + errTxt + "\"";
                log.debug("Hafas error: {}", msg);
                final String plainErr = getPlainErr(err);
                if ("LOCATION".equals(plainErr) && "HCI Service: location missing or invalid".equals(errTxt))
                    return new QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION);
                if ("FAIL".equals(plainErr))
                    return new QueryDeparturesResult(header, QueryDeparturesResult.Status.SERVICE_DOWN);
                if ("PROBLEMS".equals(plainErr) && "HCI Service: problems during service execution".equals(errTxt))
                    return new QueryDeparturesResult(header, QueryDeparturesResult.Status.SERVICE_DOWN);
                if ("CGI_READ_FAILED".equals(plainErr))
                    return new QueryDeparturesResult(header, QueryDeparturesResult.Status.SERVICE_DOWN);
                if ("CGI_NO_SERVER".equals(plainErr))
                    return new QueryDeparturesResult(header, QueryDeparturesResult.Status.SERVICE_DOWN);
                if ("H_UNKNOWN".equals(plainErr))
                    return new QueryDeparturesResult(header, QueryDeparturesResult.Status.SERVICE_DOWN);
                throw new RuntimeException(msg);
            }
            final JSONObject res = svcRes.getJSONObject("res");

            final JSONObject common = res.getJSONObject("common");
            final List<Remark> remarks = parseRemList(common.optJSONArray("remL"));
            final List<Remark> hims = parseHimList(common.optJSONArray("himL"));
            final List<Style> styles = parseIcoList(common.getJSONArray("icoL"));
            final List<String> operators = parseOpList(common.optJSONArray("opL"));
            final List<Line> lines = parseProdList(common.optJSONArray("prodL"), operators, styles);
            final JSONArray crdSysList = common.optJSONArray("crdSysL");
            final JSONArray locList = common.getJSONArray("locL");

            final JSONArray jnyList = res.optJSONArray("jnyL");
            if (jnyList != null) {
                for (int iJny = 0; iJny < jnyList.length(); iJny++) {
                    final JSONObject jny = jnyList.getJSONObject(iJny);
                    final JSONObject stbStop = jny.getJSONObject("stbStop");

                    final boolean cancelled = stbStop.optBoolean("dCncl", false);
//                    if (cancelled)
//                        continue;

                    final Position plannedPosition = parseJsonPosition(stbStop, "dPlatfS", "dPltfS");
                    final Position predictedPosition = parseJsonPosition(stbStop, "dPlatfR", "dPltfR");

                    c.clear();
                    ParserUtils.parseIsoDate(c, jny.getString("date"));
                    final Date baseDate = c.getTime();

                    final PTDate plannedTime = parseJsonTime(c, baseDate, stbStop.getString("dTimeS"));

                    final PTDate predictedTime = parseJsonTime(c, baseDate, stbStop.optString("dTimeR", null));

                    final int dProdX = stbStop.optInt("dProdX", -1);
                    final Line line = dProdX != -1 ? lines.get(dProdX) : null;

                    final HashMap<Integer, MetaLocation> metaLocsByIndex = equivsMode == EquivalentStationsMode.KEEP_DISTINCT ? null : new HashMap<>();
                    final boolean alwaysUseMeta = equivsMode == EquivalentStationsMode.USE_META;
                    final LocationAndName locationAndName = parseLoc(locList, stbStop.getInt("locX"), metaLocsByIndex, alwaysUseMeta, crdSysList, locList);
                    final Location location = loc(locationAndName);
                    checkState(location != null && location.type == LocationType.STATION);
                    if (equivsMode == EquivalentStationsMode.KEEP_DISTINCT && !location.id.equals(stationId))
                        continue;

                    final String jnyDirTxt = jny.optString("dirTxt", null);
                    final Location directionLocation;
                    if (jnyDirTxt != null) {
                        final String[] splitJnyDirTxt = splitDirectionName(jnyDirTxt, line);
                        directionLocation = new Location(LocationType.DIRECTION, null, splitJnyDirTxt[0], splitJnyDirTxt[1]);
                    } else {
                        directionLocation = null;
                    }
                    Destination destination = null;
                    final JSONArray prodL = jny.optJSONArray("prodL");
                    if (prodL != null && prodL.length() > 0) {
                        // use terminal of first product
                        final int tLocX = prodL.getJSONObject(0).getInt("tLocX");
                        final LocationAndName lineTerminalAndName = parseLoc(locList, tLocX, null, false, crdSysList, locList);
                        final Location lineTerminal = loc(lineTerminalAndName);
                        if (lineTerminal != null && lineTerminal.hasName()) {
                            if (directionLocation == null
                                    || lineTerminalAndName.originalName.equals(jnyDirTxt)
                                    || lineTerminal.name.equals(directionLocation.name))
                                destination = new Destination(lineTerminal, isStationBoardDestinationCommonlyDirection());
                        }
                    }
                    if (destination == null) {
                        // if last entry in stopL happens to be our destination, use it
                        final JSONArray stopList = jny.optJSONArray("stopL");
                        if (stopList != null) {
                            final int lastStopIdx = stopList.getJSONObject(stopList.length() - 1).getInt("locX");
                            final LocationAndName lastStopAndName = parseLoc(locList, lastStopIdx, null, false, crdSysList, locList);
                            final Location lastStop = loc(lastStopAndName);
                            if (lastStop != null && lastStop.hasName()) {
                                if (directionLocation == null
                                        || lastStopAndName.originalName.equals(jnyDirTxt)
                                        || lastStop.name.equals(directionLocation.name))
                                    destination = new Destination(lastStop, isStationBoardDestinationCommonlyDirection());
                            }
                        }
                    }
                    if (destination == null) {
                        // otherwise use given direction
                        destination = new Destination(directionLocation, !isStationBoardDestinationCommonlyDirection());
                    }

                    final String message = buildMessageFromRemarks(jny, remarks, hims);

                    if (line != null) {
                        final String journeyId = jny.optString("jid", null);
                        final Departure departure = new Departure(
                                plannedTime,
                                predictedTime,
                                line,
                                plannedPosition, predictedPosition,
                                destination,
                                cancelled,
                                null,
                                message,
                                journeyId == null ? null : new HafasJourneyRef(journeyId));

                        StationDepartures stationDepartures = findStationDepartures(result.stationDepartures, location);
                        if (stationDepartures == null) {
                            stationDepartures = new StationDepartures(location, new ArrayList<Departure>(8), null);
                            result.stationDepartures.add(stationDepartures);
                        }

                        stationDepartures.departures.add(departure);
                    }
                }
            }

            // sort departures
            for (final StationDepartures stationDepartures : result.stationDepartures)
                Collections.sort(stationDepartures.departures, Departure.TIME_COMPARATOR);

            return result;
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    protected final SuggestLocationsResult jsonLocMatch(final CharSequence constraint,
            final @Nullable Set<LocationType> types, int maxLocations) throws IOException {
        requireNonNull(constraint);
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;
        final String type;
        if (types == null || types.contains(LocationType.ANY)
                || types.containsAll(EnumSet.of(LocationType.STATION, LocationType.ADDRESS, LocationType.POI)))
            type = "ALL";
        else
            type = Stream.of(types.contains(LocationType.STATION) ? "S" : "",
                            types.contains(LocationType.ADDRESS) ? "A" : "",
                            types.contains(LocationType.POI) ? "P" : "")
                    .collect(Collectors.joining());
        final String loc = "{\"name\":" + JSONObject.quote(constraint + "?") + ",\"type\":\"" + type + "\"}";
        final String request = wrapJsonApiRequest("LocMatch",
                "{\"input\":{\"field\":\"S\",\"loc\":" + loc + ",\"maxLoc\":" + maxLocations + "}}", false);

        final HttpUrl url = requestUrl(request);
        final CharSequence page = httpClient.get(url, request, "application/json");

        try {
            final JSONObject head = new JSONObject(page.toString());
            final String headErr = head.optString("err", null);
            if (headErr != null && !"OK".equals(headErr)) {
                if ("HAMM".equals(headErr) // ??? (sporadically found on OEBB)
                        || "HAMM_LOAD".equals(headErr) // ??? (sporadically found on DSB)
                ) {
                    return new SuggestLocationsResult(new ResultHeader(network, SERVER_PRODUCT), SuggestLocationsResult.Status.SERVICE_DOWN);
                }
                final String headErrTxt = head.optString("errTxt");
                throw new RuntimeException(headErr + " " + headErrTxt);
            }

            final JSONArray svcResList = head.getJSONArray("svcResL");
            checkState(svcResList.length() == 2);
            final ResultHeader header = parseServerInfo(svcResList.getJSONObject(0), head.getString("ver"));

            final JSONObject svcRes = svcResList.optJSONObject(1);
            checkState("LocMatch".equals(svcRes.getString("meth")));
            final String err = svcRes.getString("err");
            if (!"OK".equals(err)) {
                final String errTxt = svcRes.optString("errTxt");
                final String msg = "err=" + err + ", errTxt=\"" + errTxt + "\"";
                log.debug("Hafas error: {}", msg);
                final String plainErr = getPlainErr(err);
                if ("FAIL".equals(plainErr))
                    return new SuggestLocationsResult(header, SuggestLocationsResult.Status.SERVICE_DOWN);
                if ("CGI_READ_FAILED".equals(plainErr))
                    return new SuggestLocationsResult(header, SuggestLocationsResult.Status.SERVICE_DOWN);
                if ("CGI_NO_SERVER".equals(plainErr))
                    return new SuggestLocationsResult(header, SuggestLocationsResult.Status.SERVICE_DOWN);
                if ("H_UNKNOWN".equals(plainErr))
                    return new SuggestLocationsResult(header, SuggestLocationsResult.Status.SERVICE_DOWN);
                throw new RuntimeException(msg);
            }
            final JSONObject res = svcRes.getJSONObject("res");

            final JSONObject common = res.getJSONObject("common");
            /* final List<String[]> remarks = */ parseRemList(common.optJSONArray("remL"));
            final JSONArray commonLocL = common.optJSONArray("locL");

            final JSONObject match = res.getJSONObject("match");
            final JSONArray crdSysList = common.optJSONArray("crdSysL");
            final List<Location> locations = parseLocList(match.optJSONArray("locL"), crdSysList, commonLocL, EquivalentStationsMode.META_IF_SAME_NAME);
            final List<SuggestedLocation> suggestedLocations = new ArrayList<>(locations.size());
            for (final Location location : locations)
                suggestedLocations.add(new SuggestedLocation(location));
            // TODO weight

            return new SuggestLocationsResult(header, suggestedLocations);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    private Location jsonTripSearchIdentify(final Location location) throws IOException {
        if (location.hasId())
            return location;
        if (location.hasName()) {
            final SuggestLocationsResult result = jsonLocMatch(
                    Stream.of(location.place, location.name)
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining(" ")),
                    null, 1);
            if (result.status == SuggestLocationsResult.Status.OK) {
                final List<Location> locations = result.getLocations();
                if (!locations.isEmpty())
                    return locations.get(0);
            }
        }
        if (location.hasCoord()) {
            final NearbyLocationsResult result = jsonLocGeoPos(
                    EnumSet.allOf(LocationType.class), location.coord,
                    EquivalentStationsMode.USE_META, 0, 1, null);
            if (result.status == NearbyLocationsResult.Status.OK) {
                final List<Location> locations = result.locations;
                if (!locations.isEmpty())
                    return locations.get(0);
            }
        }
        return null;
    }

    protected final List<Trip.Public> jsonPublicLeg(
            final JSONObject jny,
            final boolean splitSubJourneys,
            final Stop aDepartureStop, final Stop aArrivalStop,
            final JSONObject common, final List<Line> lines, final JSONArray locList, final JSONArray crdSysList,
            final List<String> encodedPolylines,
            final List<Remark> remarks, final List<Remark> hims,
            final Calendar cal, final Date baseDate) throws JSONException {
        final List<Point> path;
        final JSONObject polyG = jny.optJSONObject("polyG");
        if (polyG != null && encodedPolylines != null) {
            final int crdSysX = polyG.optInt("crdSysX", -1);
            if (crdSysX != -1) {
                final String crdSysType = crdSysList.getJSONObject(crdSysX).getString("type");
                if (!"WGS84".equals(crdSysType))
                    throw new RuntimeException("unknown type: " + crdSysType);
            }
            final JSONArray polyXList = polyG.getJSONArray("polyXL");
            path = new LinkedList<>();
            final int polyXListLen = polyXList.length();
            for (int i = 0; i < polyXListLen; i++) {
                final String encodedPolyline = encodedPolylines.get(polyXList.getInt(i));
                path.addAll(PolylineFormat.decode(encodedPolyline, 5));
            }
        } else {
            path = null;
        }

        final String message = buildMessageFromRemarks(jny, remarks, hims);
        final String jid = jny.optString("jid", null);
        final JourneyRef journeyRef = jid == null ? null : new HafasJourneyRef(jid);
        final JSONArray stopList = jny.optJSONArray("stopL");
        // there often is field "idx" which tells the reference index of this listed object
        // here we build a lookup map
        final Map<Integer, Integer> stopLIndexMap = new HashMap<>();
        if (stopList != null) {
            for (int index = 0; index < stopList.length(); index += 1) {
                final JSONObject stopObject = stopList.getJSONObject(index);
                final int idx = stopObject.optInt("idx", index);
                stopLIndexMap.put(idx, index);
            }
        }
        final JSONArray dirL = splitSubJourneys ? jny.optJSONArray("dirL") : null;
        final List<Trip.Public> legs = new ArrayList<>();

        if (dirL != null) {
            // split leg into multiple according to direction list
            final JSONArray prodL = jny.getJSONArray("prodL");
            final JSONArray commonDirL = common.getJSONArray("dirL");
            for (int index = 0; index < dirL.length(); index += 1) {
                final JSONObject prodEntry = prodL.getJSONObject(index);
                final JSONObject dirEntry = dirL.getJSONObject(index);
                final String dirTxt = commonDirL.getJSONObject(dirEntry.getInt("dirX")).getString("txt");
                final Line line = lines.get(prodEntry.getInt("prodX"));

                final String[] splitDirTxt = splitDirectionName(dirTxt, line);
                final Destination destination = new Destination(new Location(LocationType.DIRECTION, null, splitDirTxt[0], splitDirTxt[1]));

                final int fIdx = prodEntry.getInt("fIdx");
                final int departureStopIndex = stopLIndexMap.get(fIdx);
                final int tIdx = prodEntry.getInt("tIdx");
                final int arrivalStopIndex = stopLIndexMap.get(tIdx);

                final Stop departureStop = parseJsonStop(stopList.getJSONObject(departureStopIndex), locList, crdSysList, cal, baseDate);
                final Stop arrivalStop = parseJsonStop(stopList.getJSONObject(arrivalStopIndex), locList, crdSysList, cal, baseDate);

                final List<Stop> intermediateStops;
                final int numIntermediates = arrivalStopIndex - departureStopIndex - 1;
                if (numIntermediates > 0) {
                    intermediateStops = new ArrayList<>(numIntermediates);
                    for (int iStop = departureStopIndex + 1; iStop < arrivalStopIndex; iStop++) {
                        final JSONObject stop = stopList.getJSONObject(iStop);
                        final Stop intermediateStop = parseJsonStop(stop, locList, crdSysList, cal, baseDate);
                        intermediateStops.add(intermediateStop);
                    }
                } else {
                    intermediateStops = null;
                }

                final Trip.Public newLeg = new Trip.Public(
                        line, destination,
                        departureStop, arrivalStop, intermediateStops,
                        message, journeyRef);
                newLeg.setPath(path);
                legs.add(newLeg);
            }
        } else {
            // treat whole leg as one
            Stop departureStop = aDepartureStop;
            Stop arrivalStop = aArrivalStop;

            final Line line = lines.get(jny.getInt("prodX"));
            final String dirTxt = jny.optString("dirTxt", null);

            final Destination destination;
            if (dirTxt != null) {
                final String[] splitDirTxt = splitDirectionName(dirTxt, line);
                destination = new Destination(new Location(LocationType.DIRECTION, null, splitDirTxt[0], splitDirTxt[1]));
            } else {
                destination = null;
            }

            final List<Stop> intermediateStops;
            if (stopList != null && stopList.length() >= 2) {
                // just treat stop-list of size 0 or 1 as not existing
                // Hafas sometimes happens to produce this bullshit
                // at least we don't understand the meaning yet
                // checkState(stopList.length() >= 2);
                if (departureStop == null)
                    departureStop = parseJsonStop(stopList.getJSONObject(0), locList, crdSysList, cal, baseDate);
                if (arrivalStop == null)
                    arrivalStop = parseJsonStop(stopList.getJSONObject(stopList.length() - 1), locList, crdSysList, cal, baseDate);
                intermediateStops = new ArrayList<>(stopList.length());
                for (int iStop = 1; iStop < stopList.length() - 1; iStop++) {
                    final JSONObject stop = stopList.getJSONObject(iStop);
                    final Stop intermediateStop = parseJsonStop(stop, locList, crdSysList, cal, baseDate);
                    intermediateStops.add(intermediateStop);
                }
            } else {
                intermediateStops = null;
            }

            final Trip.Public newLeg = new Trip.Public(
                    line, destination,
                    departureStop, arrivalStop, intermediateStops,
                    message, journeyRef);
            newLeg.setPath(path);
            legs.add(newLeg);
        }

        return legs;
    }

    protected final QueryTripsResult jsonTripRequest(
            final String method, final String request,
            final Location from, @Nullable final Location via, final Location to,
            final Date time, final boolean dep,
            final boolean direct, final boolean bike,
            final Set<Product> products, final WalkSpeed walkSpeed,
            final Integer maxWalkDistanceMeters,
            final Integer minTransferTimeMinutes) throws IOException {
        final Calendar c = new GregorianCalendar(timeZone);
        c.setTime(time);

        final HttpUrl url = requestUrl(request);
        final CharSequence page = httpClient.get(url, request, "application/json", 30);

        try {
            final JSONObject head = new JSONObject(page.toString());
            final String headErr = head.optString("err", null);
            if (headErr != null && !"OK".equals(headErr)) {
                log.warn("Hafas head error: {}", head.toString());
                if ("HAMM".equals(headErr) // ??? (sporadically found on OEBB)
                    || "HAMM_LOAD".equals(headErr) // ??? (sporadically found on DSB)
                ) {
                    return new QueryTripsResult(new ResultHeader(network, SERVER_PRODUCT), QueryTripsResult.Status.SERVICE_DOWN);
                }
                final String headErrTxt = head.optString("errTxt");
                throw new RuntimeException(headErr + " " + headErrTxt);
            }

            final JSONArray svcResList = head.getJSONArray("svcResL");
            checkState(svcResList.length() == 2);
            final ResultHeader header = parseServerInfo(svcResList.getJSONObject(0), head.getString("ver"));

            final JSONObject svcRes = svcResList.optJSONObject(1);
            checkState(method.equals(svcRes.getString("meth")));
            final String err = svcRes.getString("err");
            if (!"OK".equals(err)) {
                final String errTxt = svcRes.optString("errTxt");
                final String msg = "err=" + err + ", errTxt=\"" + errTxt + "\"";
                log.debug("Hafas error: {}", msg);
                final String plainErr = getPlainErr(err);
                if ("H890".equals(plainErr)) // No connections found.
                    return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
                if ("H883".equals(plainErr)) // HAFAS Kernel: No connection found after post filtering
                    return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
                if ("H891".equals(plainErr)) // No route found (try entering an intermediate station).
                    return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
                if ("H892".equals(plainErr)) // HAFAS Kernel: Request too complex (try entering less intermediate
                    // stations).
                    return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
                if ("H895".equals(plainErr)) // Departure/Arrival are too near.
                    return new QueryTripsResult(header, QueryTripsResult.Status.TOO_CLOSE);
                if ("H9220".equals(plainErr)) // Nearby to the given address stations could not be found.
                    return new QueryTripsResult(header, QueryTripsResult.Status.UNRESOLVABLE_ADDRESS);
                if ("H886".equals(plainErr)) // HAFAS Kernel: No connections found within the requested time
                    // interval.
                    return new QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS);
                if ("H887".equals(plainErr)) // HAFAS Kernel: Kernel computation time limit reached.
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                if ("H9240".equals(plainErr)) // HAFAS Kernel: Internal error.
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                if ("H900".equals(plainErr)) // HAFAS Kernel: No connection found, the connection period requested may be
                                        // outside the timetable period
                    return new QueryTripsResult(header, QueryTripsResult.Status.INVALID_DATE);
                if ("H9360".equals(plainErr)) // Date outside of the timetable period.
                    return new QueryTripsResult(header, QueryTripsResult.Status.INVALID_DATE);
                if ("H9380".equals(plainErr) || "H9381".equals(plainErr)) // Departure/Arrival/Intermediate or equivalent stations def'd more
                    // than once.
                    return new QueryTripsResult(header, QueryTripsResult.Status.TOO_CLOSE);
                if ("FAIL".equals(plainErr))
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                if ("PROBLEMS".equals(plainErr) && "HCI Service: problems during service execution".equals(errTxt))
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                if ("LOCATION".equals(plainErr) && "HCI Service: location missing or invalid".equals(errTxt))
                    return new QueryTripsResult(header, QueryTripsResult.Status.UNKNOWN_LOCATION);
                if ("CGI_READ_FAILED".equals(plainErr))
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                if ("CGI_NO_SERVER".equals(plainErr))
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                if ("H_UNKNOWN".equals(plainErr))
                    return new QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN);
                throw new RuntimeException(msg);
            }
            final JSONObject res = svcRes.getJSONObject("res");

            final JSONObject common = res.getJSONObject("common");
            final List<Remark> remarks = parseRemList(common.optJSONArray("remL"));
            final List<Remark> hims = parseHimList(common.optJSONArray("himL"));
            final List<Style> styles = parseIcoList(common.getJSONArray("icoL"));
            final JSONArray crdSysList = common.optJSONArray("crdSysL");
            final JSONArray locList = common.getJSONArray("locL");
            final List<String> operators = parseOpList(common.optJSONArray("opL"));
            final List<Line> lines = parseProdList(common.optJSONArray("prodL"), operators, styles);
            final List<String> encodedPolylines = parsePolyList(common.optJSONArray("polyL"));

            final JSONArray outConList = res.optJSONArray("outConL");
            final List<Trip> trips = new ArrayList<>(outConList.length());
            for (int iOutCon = 0; iOutCon < outConList.length(); iOutCon++) {
                final JSONObject outCon = outConList.getJSONObject(iOutCon);
                final Location tripFrom = loc(parseLoc(locList, outCon.getJSONObject("dep").getInt("locX"),
                        new HashMap<>(), true, crdSysList, locList));
                final Location tripTo = loc(parseLoc(locList, outCon.getJSONObject("arr").getInt("locX"),
                        new HashMap<>(), true, crdSysList, locList));

                c.clear();
                ParserUtils.parseIsoDate(c, outCon.getString("date"));
                final Date baseDate = c.getTime();

                final JSONArray secList = outCon.optJSONArray("secL");
                final List<Trip.Leg> legs = new ArrayList<>(secList.length());
                for (int iSec = 0; iSec < secList.length(); iSec++) {
                    final JSONObject sec = secList.getJSONObject(iSec);
                    final String secType = sec.getString("type");

                    final JSONObject secDep = sec.getJSONObject("dep");
                    final Stop departureStop = parseJsonStop(secDep, locList, crdSysList, c, baseDate);

                    final JSONObject secArr = sec.getJSONObject("arr");
                    final Stop arrivalStop = parseJsonStop(secArr, locList, crdSysList, c, baseDate);

                    final Trip.Leg leg;
                    if (SECTION_TYPE_JOURNEY.equals(secType) || SECTION_TYPE_TELE_TAXI.equals(secType)) {
                        final JSONObject jny = sec.getJSONObject("jny");
                        final List<Trip.Public> onlyOnePublic = jsonPublicLeg(
                                jny, false, departureStop, arrivalStop,
                                common, lines, locList, crdSysList,
                                encodedPolylines, remarks, hims, c, baseDate);
                        leg = onlyOnePublic.get(0);
                    } else if (SECTION_TYPE_WALK.equals(secType)) {
                        final JSONObject gis = sec.getJSONObject("gis");
                        final int distance = gis.optInt("dist", -1);
                        if (distance < 0) {
                            leg = null;
                        } else {
                            leg = new Trip.Individual(Trip.Individual.Type.WALK, departureStop.location,
                                    departureStop.getDepartureTime(), arrivalStop.location, arrivalStop.getArrivalTime(),
                                    distance);
                        }
                    } else if (SECTION_TYPE_TRANSFER.equals(secType) || SECTION_TYPE_DEVI.equals(secType)) {
                        final JSONObject gis = sec.optJSONObject("gis");
                        final int distance = gis != null ? gis.optInt("dist", 0) : 0;
                        leg = new Trip.Individual(Trip.Individual.Type.TRANSFER, departureStop.location,
                                departureStop.getDepartureTime(), arrivalStop.location, arrivalStop.getArrivalTime(),
                                distance);
                    } else if (SECTION_TYPE_CHECK_IN.equals(secType)) {
                        final JSONObject gis = sec.optJSONObject("gis");
                        final int distance = gis != null ? gis.optInt("dist", 0) : 0;
                        leg = new Trip.Individual(Trip.Individual.Type.CHECK_IN, departureStop.location,
                                departureStop.getDepartureTime(), arrivalStop.location, arrivalStop.getArrivalTime(),
                                distance);
                    } else if (SECTION_TYPE_CHECK_OUT.equals(secType)) {
                        final JSONObject gis = sec.optJSONObject("gis");
                        final int distance = gis != null ? gis.optInt("dist", 0) : 0;
                        leg = new Trip.Individual(Trip.Individual.Type.CHECK_OUT, departureStop.location,
                                departureStop.getDepartureTime(), arrivalStop.location, arrivalStop.getArrivalTime(),
                                distance);
                    } else {
                        throw new IllegalStateException("cannot handle type: " + secType);
                    }

                    if (leg != null)
                        legs.add(leg);
                }

                final List<Fare> fares;
                final JSONObject trfRes = outCon.optJSONObject("trfRes");
                if (trfRes != null) {
                    fares = new LinkedList<>();
                    final JSONArray fareSetList = trfRes.optJSONArray("fareSetL");
                    final JSONArray ovwTrfRefList = outCon.optJSONArray("ovwTrfRefL");
                    if (fareSetList != null && ovwTrfRefList != null) {
                        for (int i = 0; i < ovwTrfRefList.length(); i++) {
                            final JSONObject ovwTrfRef = ovwTrfRefList.getJSONObject(i);
                            final String type = ovwTrfRef.getString("type");
                            final int fareSetIndex = ovwTrfRef.optInt("fareSetX", -1);
                            final int fareX = ovwTrfRef.optInt("fareX", -1);
                            final JSONObject jsonFareSet = fareSetIndex < 0 ? null : fareSetList.getJSONObject(fareSetIndex);
                            if (type.equals("T") && jsonFareSet != null && fareX >= 0) { // ticket
                                final JSONObject jsonFare = jsonFareSet.getJSONArray("fareL").getJSONObject(fareX);
                                final String fareName = jsonFare.getString("name");
                                final int ticketX = ovwTrfRef.getInt("ticketX");
                                final JSONObject jsonTicket = jsonFare.getJSONArray("ticketL").getJSONObject(ticketX);
                                final String ticketName = jsonTicket.getString("name");
                                final String ticketDesc = jsonTicket.optString("desc");
                                final Price price = parsePriceFromObject(jsonTicket);
                                if (price != null) {
                                    final Fare fare = new Fare(
                                            normalizeFareName(fareName) + '\n' + ticketName,
                                            normalizeFareType(ticketName, ticketDesc),
                                            price.currency, price.amount,
                                            null, null);
                                    if (!hideFare(fare))
                                        fares.add(fare);
                                }
                            } else if (type.equals("F") && jsonFareSet != null && fareX >= 0) { // fare
                                final JSONObject jsonFare =
                                        jsonFareSet.getJSONArray("fareL").getJSONObject(fareX);
                                final String fareName = jsonFare.getString("name");
                                final Price price = parsePriceFromObject(jsonFare);
                                if (price != null) {
                                    final Fare fare = new Fare(
                                            normalizeFareName(fareName),
                                            normalizeFareType(fareName),
                                            price.currency, price.amount,
                                            null, null);
                                    if (!hideFare(fare))
                                        fares.add(fare);
                                }
                            } else if (type.equals("FS")) { // fare set
                                final String fareSetName = jsonFareSet.getString("name");
                                final JSONArray fareList = jsonFareSet.getJSONArray("fareL");
                                for (int iFare = 0; iFare < fareList.length(); iFare++) {
                                    final JSONObject jsonFare = fareList.getJSONObject(iFare);
                                    final String fareName = jsonFare.getString("name");
                                    final Price price = parsePriceFromObject(jsonFare);
                                    final Fare fare = new Fare(
                                            normalizeFareName(fareSetName),
                                            normalizeFareType(fareName),
                                            price.currency, price.amount,
                                            null, null);
                                    if (!hideFare(fare))
                                        fares.add(fare);
                                }
                            } else if (type.equals("TIBG")) { // ???
                                // RMV at API version 1.79 returns this.
                                // not yet implemented --> fall back to "totalPrice" search ...
                            } else {
                                throw new IllegalArgumentException("cannot handle type: " + type);
                            }
                        }
                    }
                    if (fares.isEmpty()) {
                        // find the first fare with same price as suggested by total price
                        final Price totalPrice = parsePriceObject(trfRes.optJSONObject("totalPrice"));
                        if (totalPrice != null) {
                            FareSetLoop:
                            for (int iFareSet = 0; iFareSet < fareSetList.length(); iFareSet++) {
                                final JSONObject jsonFareSet = fareSetList.getJSONObject(iFareSet);
                                final JSONArray fareList = jsonFareSet.getJSONArray("fareL");
                                for (int iFare = 0; iFare < fareList.length(); iFare++) {
                                    final JSONObject jsonFare = fareList.getJSONObject(iFare);
                                    final String fareName = jsonFare.getString("name");
                                    final Price price = parsePriceObject(jsonFare.optJSONObject("price"));
                                    if (price != null
                                            && price.amount == totalPrice.amount
                                            && price.currency.equals(totalPrice.currency)) {
                                        final Fare.Type fareType = normalizeFareType(fareName);
                                        if (fareType.equals(Fare.Type.ADULT)) {
                                            final Fare fare = new Fare(
                                                    normalizeFareName(fareName),
                                                    fareType,
                                                    price.currency, price.amount,
                                                    null, null);
                                            if (!hideFare(fare)) {
                                                fares.add(fare);
                                                break FareSetLoop;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    fares = null;
                }

                String ctxRecon = outCon.optString("ctxRecon");
                if (ctxRecon.isEmpty()) {
                    final JSONObject recon = outCon.getJSONObject("recon");
                    ctxRecon = recon.getString("ctx");
                }
                final Trip trip = new Trip(
                        new Date(),
                        ctxRecon.split("#")[0],
                        new HafasTripRef(network, from, via, to, ctxRecon),
                        tripFrom, tripTo, legs,
                        fares, null, null);
                trips.add(trip);
            }

            final JsonContext context = new JsonContext(
                    from, via, to, time, dep, direct, bike, products, walkSpeed,
                    maxWalkDistanceMeters, minTransferTimeMinutes,
                    res.optString("outCtxScrF"), res.optString("outCtxScrB"));
            return new QueryTripsResult(header, null, from, null, to, context, trips);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    protected String additionalJnyFltrL() {
        return "";
    }

    protected final QueryTripsResult jsonTripSearch(
            Location from, @Nullable Location via, Location to,
            final Date time, final boolean dep,
            final boolean direct,
            final boolean bike,
            final @Nullable Set<Product> products,
            final @Nullable WalkSpeed walkSpeed,
            final @Nullable Integer maxWalkDistanceMeters,
            final @Nullable Integer minTransferTimeMinutes,
            final @Nullable String moreContext,
            final boolean loadPath) throws IOException {
        from = jsonTripSearchIdentify(from);
        if (from == null)
            return new QueryTripsResult(new ResultHeader(network, SERVER_PRODUCT),
                    QueryTripsResult.Status.UNKNOWN_FROM);
        if (via != null) {
            via = jsonTripSearchIdentify(via);
            if (via == null)
                return new QueryTripsResult(new ResultHeader(network, SERVER_PRODUCT),
                        QueryTripsResult.Status.UNKNOWN_VIA);
        }
        to = jsonTripSearchIdentify(to);
        if (to == null)
            return new QueryTripsResult(new ResultHeader(network, SERVER_PRODUCT), QueryTripsResult.Status.UNKNOWN_TO);

        final Calendar c = new GregorianCalendar(timeZone);
        c.setTime(time);
        final String outDate = jsonDate(c);
        final String outTime = jsonTime(c);
        final String outFrwd = Boolean.toString(dep);
        final String jnyFltr;
        String additionalJnyFltr = additionalJnyFltrL();
        if (bike)
            additionalJnyFltr += ",{\"mode\":\"INC\",\"type\":\"BC\"}";
        if (products == null) {
            jnyFltr = additionalJnyFltr.startsWith(",") ? additionalJnyFltr.substring(1) : additionalJnyFltr;
        } else {
            if (apiLevel >= 40) {
                jnyFltr = "\"jnyFltrL\":[{\"value\": " + productsInt(products) + ",\"mode\":\"INC\",\"type\":\"PROD\"}"
                    + additionalJnyFltr + "],";
            } else {
                jnyFltr = "\"jnyFltrL\":[{\"value\":\"" + productsString(products) + "\",\"mode\":\"BIT\",\"type\":\"PROD\"}"
                    + additionalJnyFltr + "],";
            }
        }
        final String jsonContext = moreContext != null ? "\"ctxScr\":" + JSONObject.quote(moreContext) + "," : "";
        final String request = wrapJsonApiRequest("TripSearch", "{" //
                + jsonContext //
                + "\"depLocL\":[" + jsonLocation(from) + "]," //
                + "\"arrLocL\":[" + jsonLocation(to) + "]," //
                + (via != null ? "\"viaLocL\":[{\"loc\":" + jsonLocation(via) + "}]," : "") //
                + "\"outDate\":\"" + outDate + "\"," //
                + "\"outTime\":\"" + outTime + "\"," //
                + "\"outFrwd\":" + outFrwd + "," //
                + jnyFltr //
                + "\"gisFltrL\":[{" //
                + "\"type\":\"M\",\"mode\":\"FBT\",\"meta\":\"foot_speed_" + (walkSpeed != null ? walkSpeed : WalkSpeed.NORMAL).name().toLowerCase() + "\"" //
                + "},{\"type\":\"P\",\"mode\":\"FB\",\"profile\":{\"type\":\"F\",\"maxdist\":\"" + (maxWalkDistanceMeters != null ? maxWalkDistanceMeters : 2000) + "\"}" //
                + "}]," //
                + "\"getPolyline\":" + (loadPath ? "true" : "false") + ",\"getPasslist\":true," //
                + (apiLevel <= 24 ? "\"getConGroups\":false," : "") //
                + (direct ? "\"maxChg\":0," : "") //
                + "\"minChgTime\":" + (minTransferTimeMinutes != null ? minTransferTimeMinutes : -1) + "," //
                + "\"getIST\":false,\"getEco\":false,\"extChgTime\":-1}", //
                false);

        return jsonTripRequest("TripSearch", request,
                from, via, to,
                time, dep,
                direct, bike, products, walkSpeed, maxWalkDistanceMeters, minTransferTimeMinutes);
    }

    private QueryTripsResult jsonTripReload(final HafasTripRef tripRef, final boolean loadPath) throws IOException {
        final String request = wrapJsonApiRequest("Reconstruction", "{" //
                        + ((apiLevel >= 40) //
                            ? "\"outReconL\":[{\"ctx\":\"" + tripRef.ctxRecon + "\"}]," //
                            : "\"ctxRecon\":\"" + tripRef.ctxRecon + "\",") //
                        + "\"getPolyline\":" + (loadPath ? "true" : "false") + ",\"getPasslist\":true,\"getIST\":false}", //
                false);

        return jsonTripRequest("Reconstruction", request, tripRef.from, tripRef.via, tripRef.to,
                new Date(), true, false, false, null, null, null, null);
    }

    private QueryJourneyResult jsonJourney(
            final HafasJourneyRef journeyRef,
            final boolean splitSubJourneys,
            final boolean loadPath) throws IOException {
        final String request = wrapJsonApiRequest("JourneyDetails", "{" //
                        + "\"jid\":\"" + journeyRef.jid + "\"," //
                        + "\"getPasslist\":true,\"getPolyline\":" + (loadPath ? "true" : "false") + "}", //
                false);

        final HttpUrl url = requestUrl(request);
        final CharSequence page = httpClient.get(url, request, "application/json");

        try {
            final JSONObject head = new JSONObject(page.toString());
            final String headErr = head.optString("err", null);
            if (headErr != null && !"OK".equals(headErr)) {
                if ("HAMM".equals(headErr) // ??? (sporadically found on OEBB)
                        || "HAMM_LOAD".equals(headErr) // ??? (sporadically found on DSB)
                ) {
                    return new QueryJourneyResult(new ResultHeader(network, SERVER_PRODUCT), QueryJourneyResult.Status.SERVICE_DOWN);
                }
                final String headErrTxt = head.optString("errTxt");
                throw new RuntimeException(headErr + " " + headErrTxt);
            }

            final JSONArray svcResList = head.getJSONArray("svcResL");
            checkState(svcResList.length() == 2);
            final ResultHeader header = parseServerInfo(svcResList.getJSONObject(0), head.getString("ver"));

            final JSONObject svcRes = svcResList.optJSONObject(1);
            checkState("JourneyDetails".equals(svcRes.getString("meth")));
            final String err = svcRes.getString("err");
            if (!"OK".equals(err)) {
                final String errTxt = svcRes.optString("errTxt");
                final String msg = "err=" + err + ", errTxt=\"" + errTxt + "\"";
                log.debug("Hafas error: {}", msg);
                final String plainErr = getPlainErr(err);
                if ("H890".equals(plainErr)) // No connections found.
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.NO_JOURNEY);
                if ("H887".equals(plainErr)) // HAFAS Kernel: Kernel computation time limit reached.
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                if ("H9240".equals(plainErr)) // HAFAS Kernel: Internal error.
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                if ("FAIL".equals(plainErr))
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                if ("PROBLEMS".equals(plainErr) && "HCI Service: problems during service execution".equals(errTxt))
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                if ("CGI_READ_FAILED".equals(plainErr))
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                if ("CGI_NO_SERVER".equals(plainErr))
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                if ("H_UNKNOWN".equals(plainErr))
                    return new QueryJourneyResult(header, QueryJourneyResult.Status.SERVICE_DOWN);
                throw new RuntimeException(msg);
            }
            final JSONObject res = svcRes.getJSONObject("res");

            final JSONObject common = res.getJSONObject("common");
            final List<Remark> remarks = parseRemList(common.optJSONArray("remL"));
            final List<Remark> hims = parseHimList(common.optJSONArray("himL"));
            final List<Style> styles = parseIcoList(common.getJSONArray("icoL"));
            final JSONArray crdSysList = common.optJSONArray("crdSysL");
            final JSONArray locList = common.getJSONArray("locL");
            final List<String> operators = parseOpList(common.optJSONArray("opL"));
            final List<Line> lines = parseProdList(common.optJSONArray("prodL"), operators, styles);
            final List<String> encodedPolylines = parsePolyList(common.optJSONArray("polyL"));

            final JSONObject journey = res.optJSONObject("journey");

            final Calendar c = new GregorianCalendar(timeZone);
            c.clear();
            ParserUtils.parseIsoDate(c, journey.getString("date"));
            final Date baseDate = c.getTime();

            final List<Trip.Public> journeyLegs = jsonPublicLeg(
                    journey, splitSubJourneys, null, null,
                    common, lines, locList, crdSysList,
                    encodedPolylines, remarks, hims, c, baseDate);
            return new QueryJourneyResult(header, null, journeyRef, journeyLegs);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }
    }

    protected Fare.Type normalizeFareType(final String... fareNames) {
        for (final String fareName : fareNames) {
            if (fareName == null)
                continue;
            final String fareNameLc = fareName.toLowerCase(Locale.US);
            if (fareNameLc.contains("erwachsene") || fareNameLc.contains("adult"))
                return Fare.Type.ADULT;
            if (fareNameLc.contains("kind") || fareNameLc.contains("child") || fareNameLc.contains("kids"))
                return Fare.Type.CHILD;
            if (fareNameLc.contains("ermäßigung"))
                return Fare.Type.CHILD;
            if (fareNameLc.contains("schüler") || fareNameLc.contains("azubi"))
                return Fare.Type.STUDENT;
            if (fareNameLc.contains("fahrrad"))
                return Fare.Type.BIKE;
            if (fareNameLc.contains("senior"))
                return Fare.Type.SENIOR;
        }
        return Fare.Type.ADULT;
    }

    protected String normalizeFareName(final String fareName) {
        return fareName;
    }

    protected boolean hideFare(final Fare fare) {
        return false;
    }

    protected String getHafasLanguage(final String lang) {
        return "de".equals(lang) ? "deu" : "eng";
    }

    private String wrapJsonApiRequest(final String meth, final String req, final boolean formatted) throws IOException {
        final String apiAuthorization = getApiAuthorization();
        return "{" //
                + (apiAuthorization != null ? "\"auth\":" + apiAuthorization + "," : "") //
                + "\"client\":" + requireNonNull(apiClient) + "," //
                + (apiExt != null ? "\"ext\":\"" + apiExt + "\"," : "") //
                + "\"ver\":\"" + requireNonNull(apiVersion) + "\",\"lang\":\"" + getHafasLanguage(userInterfaceLanguage) + "\"," //
                + "\"svcReqL\":[" //
                + "{\"meth\":\"ServerInfo\",\"req\":{\"getServerDateTime\":true" //
                + (apiLevel <= 75 ? ",\"getTimeTablePeriod\":false" : "") //
                + "}}," //
                + "{\"meth\":\"" + meth + "\",\"cfg\":{\"polyEnc\":\"GPA\"},\"req\":" + req + "}" //
                + "]," //
                + "\"formatted\":" + formatted + "}";
    }

    private MessageDigest md5instance() {
        try {
            // instance not thread safe!
            return MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException x) {
            // should not happen
            throw new RuntimeException(x);
        }
    }

    private HttpUrl requestUrl(final String body) {
        final HttpUrl.Builder url = apiBase.newBuilder().addPathSegments(apiEndpoint);
        addSaltToUrl(url, body);
        return url.build();
    }

    private boolean isLid(final String id) {
        return apiUseLocationLidOnly && id != null && id.length() > 10;
    }

    private String jsonLocation(final Location location) {
        final String id = location.id;
        if (isLid(id))
            return "{\"lid\":" + JSONObject.quote(id) + "}";
        else if (location.type == LocationType.STATION && location.hasId())
            return "{\"type\":\"S\",\"extId\":" + JSONObject.quote(id) + "}";
        else if (location.type == LocationType.ADDRESS && location.hasId())
            return "{\"type\":\"A\",\"lid\":" + JSONObject.quote(id) + "}";
        else if (location.type == LocationType.POI && location.hasId())
            return "{\"type\":\"P\",\"lid\":" + JSONObject.quote(id) + "}";
        else
            throw new IllegalArgumentException("cannot handle: " + location);
    }

    private String jsonDate(final Calendar time) {
        final int year = time.get(Calendar.YEAR);
        final int month = time.get(Calendar.MONTH) + 1;
        final int day = time.get(Calendar.DAY_OF_MONTH);
        return String.format(Locale.ENGLISH, "%04d%02d%02d", year, month, day);
    }

    private String jsonTime(final Calendar time) {
        final int hour = time.get(Calendar.HOUR_OF_DAY);
        final int minute = time.get(Calendar.MINUTE);
        return String.format(Locale.ENGLISH, "%02d%02d00", hour, minute);
    }

    private ResultHeader parseServerInfo(final JSONObject serverInfo, final String serverVersion) throws JSONException {
        checkState("ServerInfo".equals(serverInfo.getString("meth")));
        final String err = serverInfo.optString("err", null);
        if (err != null && !"OK".equals(err)) {
            final String errTxt = serverInfo.optString("errTxt");
            final String msg = "err=" + err + ", errTxt=\"" + errTxt + "\"";
            log.info("ServerInfo error: {}, ignoring", msg);
            return new ResultHeader(network, SERVER_PRODUCT, serverVersion, null, 0, null);
        }
        final JSONObject res = serverInfo.getJSONObject("res");
        final Calendar c = new GregorianCalendar(timeZone);
        ParserUtils.parseIsoDate(c, res.getString("sD"));
        final PTDate timestamp = parseJsonTime(c, c.getTime(), res.getString("sT"));
        return new ResultHeader(network, SERVER_PRODUCT, serverVersion, null, timestamp.getTime(), null);
    }

    private static final Pattern P_JSON_TIME = Pattern.compile("(\\d{2,8})?(\\d{2})(\\d{2})(\\d{2})");

    private PTDate parseJsonTime(final Calendar calendar, final Date baseDate, final CharSequence str) {
        if (str == null)
            return null;

        final Matcher m = P_JSON_TIME.matcher(str);
        if (m.matches()) {
            calendar.setTime(baseDate);

            if (m.group(1) != null)
                calendar.add(Calendar.DAY_OF_YEAR, Integer.parseInt(m.group(1)));
            calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(2)));
            calendar.set(Calendar.MINUTE, Integer.parseInt(m.group(3)));
            calendar.set(Calendar.SECOND, Integer.parseInt(m.group(4)));

            return PTDate.fromCalendar(calendar);
        }

        throw new RuntimeException("cannot parse: '" + str + "'");
    }

    private Position parseJsonPosition(final JSONObject json, final String platfName, final String pltfName)
            throws JSONException {
        final JSONObject pltf = json.optJSONObject(pltfName);
        if (pltf != null)
            return parsePosition(pltf.getString("txt")); // TODO type
        final String platf = json.optString(platfName, null);
        if (platf != null)
            return normalizePosition(platf);
        return null;
    }

    private Stop parseJsonStop(
            final JSONObject json, final JSONArray locList, final JSONArray crdSysList,
            final Calendar c, final Date baseDate) throws JSONException {
        final Location location = loc(parseLoc(locList, json.getInt("locX"), new HashMap<>(), true, crdSysList, locList));

        final boolean arrivalCancelled = json.optBoolean("aCncl", false);
        final PTDate plannedArrivalTime = parseJsonTime(c, baseDate, json.optString("aTimeS", null));
        final PTDate predictedArrivalTime = parseJsonTime(c, baseDate, json.optString("aTimeR", null));
        final Position plannedArrivalPosition = parseJsonPosition(json, "aPlatfS", "aPltfS");
        final Position predictedArrivalPosition = parseJsonPosition(json, "aPlatfR", "aPltfR");

        final boolean departureCancelled = json.optBoolean("dCncl", false);
        final PTDate plannedDepartureTime = parseJsonTime(c, baseDate, json.optString("dTimeS", null));
        final PTDate predictedDepartureTime = parseJsonTime(c, baseDate, json.optString("dTimeR", null));
        final Position plannedDeparturePosition = parseJsonPosition(json, "dPlatfS", "dPltfS");
        final Position predictedDeparturePosition = parseJsonPosition(json, "dPlatfR", "dPltfR");

        return new Stop(location, plannedArrivalTime, predictedArrivalTime, plannedArrivalPosition,
                predictedArrivalPosition, arrivalCancelled, plannedDepartureTime, predictedDepartureTime,
                plannedDeparturePosition, predictedDeparturePosition, departureCancelled);
    }

    private List<Remark> parseRemList(final JSONArray remList) throws JSONException {
        if (remList == null)
            return null;

        final List<Remark> remarks = new ArrayList<>(remList.length());

        for (int i = 0; i < remList.length(); i++) {
            final JSONObject rem = remList.getJSONObject(i);
            final Remark remark = new Remark();
            remark.type = rem.optString("type", null);
            remark.code = rem.optString("code", null);
            remark.title = null; // rem.optString("txtS", null);
            final String txtL = rem.optString("txtL", null);
            remark.text = txtL != null ? txtL : rem.optString("txtN", null);
            remark.url = rem.optString("url", null);
            remarks.add(remark);
        }
        return remarks;
    }

    private List<Remark> parseHimList(final JSONArray himList) throws JSONException {
        if (himList == null) return null;
        final List<Remark> remarks = new ArrayList<>(himList.length());

        for (int i = 0; i < himList.length(); i++) {
            final JSONObject rem = himList.getJSONObject(i);
            final Remark remark = new Remark();
            remark.type = "HIM";
            remark.code = "-";
            remark.title = rem.optString("head", null);
            final String lead = rem.optString("lead", null);
            if (lead != null)
                remark.title = remark.title != null ? remark.title + " / " + lead : lead;
            remark.text = rem.optString("text", null);
            remark.url = rem.optString("url", null);
            remarks.add(remark);
        }

        return remarks;
    }

    private String buildMessageFromRemarks(
            final JSONObject jny,
            final List<Remark> remarks,
            final List<Remark> hims) throws JSONException {
        final JSONArray remList = jny.optJSONArray("remL");
        if (remList != null) {
            String message = null;
            for (int iRem = 0; iRem < remList.length(); iRem++) {
                final JSONObject rem = remList.getJSONObject(iRem);
                final int remX = rem.optInt("remX", -1);
                if (remX >= 0 && remarks != null && remX < remarks.size()) {
                    final Remark remark = remarks.get(remX);
                    if ("l?".equals(remark.code))
                        message = remark.text;
                }
            }
            return message;
        }
        final JSONArray msgList = jny.optJSONArray("msgL");
        if (msgList == null || msgList.length() == 0) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (int iRem = 0; iRem < msgList.length(); iRem++) {
            final JSONObject rem = msgList.getJSONObject(iRem);
            Remark remark = null;
            final int remX = rem.optInt("remX", -1);
            if (remX >= 0 && remarks != null && remX < remarks.size())
                remark = remarks.get(remX);
            final int himX = rem.optInt("himX", -1);
            if (himX >= 0 && hims != null && himX < hims.size())
                remark = hims.get(himX);
            if (remark != null) {
                if (iRem > 0) {
                    sb.append(messagesAsSimpleHtml ? "<br>" : " - ");
                }
                if (remark.title != null) {
                    if (messagesAsSimpleHtml) {
                        sb.append("<b>");
                        sb.append(remark.title);
                        sb.append("</b><br>");
                    }
                } else if ("A".equals(remark.type)) {
                    if (messagesAsSimpleHtml) {
                        sb.append("&#8226; ");
                    }
                }
                sb.append(remark.text);
                if (remark.url != null) {
                    if (messagesAsSimpleHtml) {
                        sb.append("<br><a href=\"");
                        sb.append(remark.url);
                        sb.append("\">Info&#128279;</a>");
                    }
                }
            }
        }
        return sb.toString();
    }

    protected Style.Shape getDefaultShape() {
        return Style.Shape.ROUNDED;
    }

    protected Style.Shape getRectangleShape() {
        return Style.Shape.RECT;
    }

    protected Style.Shape getCircleShape() {
        return Style.Shape.CIRCLE;
    }

    private List<Style> parseIcoList(final JSONArray icoList) throws JSONException {
        final List<Style> styles = new ArrayList<>(icoList.length());
        for (int i = 0; i < icoList.length(); i++) {
            final JSONObject ico = icoList.getJSONObject(i);
            if (ico.has("bg")) {
                final int background = parseIcoColor(ico.getJSONObject("bg"));
                final JSONObject fg = ico.optJSONObject("fg");
                final int foreground = fg != null ? parseIcoColor(fg) : Style.deriveForegroundColor(background);
                final String shp = ico.optString("shp", null);
                final Style.Shape shape;
                if (shp == null) {
                    shape = getDefaultShape();
                } else {
                    if ("C".equals(shp))
                        shape = getCircleShape();
                    else if ("R".equals(shp))
                        shape = getRectangleShape();
                    else
                        throw new IllegalStateException("cannot handle shp: " + shp);
                }
                styles.add(new Style(shape, background, foreground));
            } else {
                styles.add(null);
            }
        }
        return styles;
    }

    private int parseIcoColor(final JSONObject color) throws JSONException {
        final int a = color.optInt("a", 255);
        final int r = color.getInt("r");
        final int g = color.getInt("g");
        final int b = color.getInt("b");
        if (r != -1 || g != -1 || b != -1)
            return Style.argb(a, r, g, b);
        else
            return 0;
    }

    private List<Location> parseLocList(
            final JSONArray locList,
            final JSONArray crdSysList,
            final JSONArray commonLocL,
            final EquivalentStationsMode equivsMode) throws JSONException {
        final List<Location> locations = new ArrayList<>(locList.length());
        final HashMap<Integer, MetaLocation> metaLocsByIndex = equivsMode == EquivalentStationsMode.KEEP_DISTINCT ? null : new HashMap<>();
        final boolean alwaysUseMeta = equivsMode == EquivalentStationsMode.USE_META;
        for (int iLoc = 0; iLoc < locList.length(); iLoc++) {
            final Location location = loc(parseLoc(
                    locList,
                    iLoc,
                    metaLocsByIndex,
                    alwaysUseMeta,
                    crdSysList,
                    commonLocL));
            if (location != null)
                locations.add(location);
        }
        return locations;
    }

    private static class LocationAndName {
        Location loc;
        String originalName;
    }

    private static Location loc(final LocationAndName locationAndName) {
        if (locationAndName == null)
            return null;
        return locationAndName.loc;
    }

    private static class MetaLocation {
        final LocationAndName loc;
        boolean used;
        MetaLocation(final LocationAndName loc) {
            this.loc = loc;
        }
    }

    private LocationAndName parseLoc(
            final JSONArray locList,
            final int locListIndex,
            @Nullable final Map<Integer, MetaLocation> previousMetaLocsByIndex,
            final boolean alwaysUseMeta,
            final JSONArray crdSysList,
            final JSONArray commonLocL) throws JSONException {
        final JSONObject loc = locList.getJSONObject(locListIndex);
        final String type = loc.optString("type", null);
        if (type == null)
            return null;

        final LocationType locationType;
        final String id;
        String identityId = null;
        String displayId = null;
        final String[] placeAndName;
        final Set<Product> products;
        final String name = loc.getString("name");
        if ("S".equals(type)) {
            placeAndName = splitStationName(name);
            final int mMastLocX = loc.optInt("mMastLocX", -1);
            if (previousMetaLocsByIndex != null && mMastLocX != -1) {
                // check if meta parent should be used instead
                MetaLocation metaLocation = previousMetaLocsByIndex.get(mMastLocX);
                if (metaLocation == null) {
                    metaLocation = new MetaLocation(parseLoc(commonLocL, mMastLocX, null, false, crdSysList, null));
                    previousMetaLocsByIndex.put(mMastLocX, metaLocation);
                }
                if (alwaysUseMeta
                        || (Objects.equals(placeAndName[0], metaLocation.loc.loc.place)
                        && Objects.equals(placeAndName[1], metaLocation.loc.loc.name))) {
                    if (metaLocation.used)
                        return null;
                    metaLocation.used = true;
                    return metaLocation.loc;
                }
            }
            locationType = LocationType.STATION;
            final String extId = normalizeStationId(loc.getString("extId"));
            if (apiUseLocationLidOnly) {
                id = unifyLid(loc.getString("lid"));
                identityId = extId;
                displayId = extId;
            } else {
                id = extId;
            }
            final int pCls = loc.optInt("pCls", -1);
            products = pCls != -1 ? intToProducts(pCls) : null;
        } else if ("P".equals(type)) {
            locationType = LocationType.POI;
            id = loc.getString("lid");
            placeAndName = splitPOI(name);
            products = null;
        } else if ("A".equals(type)) {
            locationType = LocationType.ADDRESS;
            id = loc.getString("lid");
            final String place = loc.optString("descr");
            if (!place.isEmpty()) {
                placeAndName = new String[] { place, name };
            } else {
                placeAndName = splitAddress(name);
            }
            products = null;
        } else {
            throw new RuntimeException("Unknown type " + type + ": " + loc);
        }

        final Point coord;
        final JSONObject crd = loc.optJSONObject("crd");
        if (crd != null) {
            final int crdSysX = loc.optInt("crdSysX", -1);
            if (crdSysX != -1) {
                final String crdSysType = crdSysList.getJSONObject(crdSysX).getString("type");
                if (!"WGS84".equals(crdSysType))
                    throw new RuntimeException("unknown type: " + crdSysType);
            }
            coord = Point.from1E6(crd.getInt("y"), crd.getInt("x"));
        } else {
            coord = null;
        }

        final LocationAndName ret = new LocationAndName();
        ret.loc = new Location(locationType, id, identityId, displayId, coord, placeAndName[0], placeAndName[1], products);
        ret.originalName = name;
        return ret;
    }

    private static final Set<String> validLidNames = new HashSet<>(Arrays.asList("A", "O", "X", "Y", "U", "L"));

    private static String unifyLid(final String lid) {
        if (lid == null)
            return null;
        final String[] nameValueStrings = lid.split("@");
        if (nameValueStrings.length < 2)
            return lid;
        final StringBuilder validLid = new StringBuilder();
        for (final String nameValueString : nameValueStrings) {
            final String[] nameAndValue = nameValueString.split("=");
            if (nameAndValue.length != 2 || validLidNames.contains(nameAndValue[0])) {
                validLid.append(nameValueString);
                validLid.append("@");
            }
        }
        return validLid.toString();
    }

    private List<String> parseOpList(final JSONArray opList) throws JSONException {
        if (opList == null)
            return null;

        final List<String> operators = new ArrayList<>(opList.length());
        for (int i = 0; i < opList.length(); i++) {
            final JSONObject op = opList.getJSONObject(i);
            final String operator = op.getString("name");
            operators.add(operator);
        }
        return operators;
    }

    private List<Line> parseProdList(final JSONArray prodList, final List<String> operators, final List<Style> styles)
            throws JSONException {
        if (prodList == null)
            return null;

        final int prodListLen = prodList.length();
        final List<Line> lines = new ArrayList<>(prodListLen);

        for (int iProd = 0; iProd < prodListLen; iProd++) {
            final JSONObject prod = prodList.getJSONObject(iProd);
            final String name = prod.getString("name");
            final String nameS = prod.optString("nameS", null);
            final String number = prod.optString("number", null);
            final String addName = useAddName ? prod.optString("addName", null) : null;
            final int icoIndex = prod.optInt("icoX", -1);
            final Style style = icoIndex >= 0 ? styles.get(icoIndex) : null;
            final int oprIndex = prod.optInt("oprX", -1);
            final String operator = (oprIndex != -1 && operators != null) ? operators.get(oprIndex) : null;
            final int cls = prod.optInt("cls", -1);
            final String id;
            final String ctxNum;
            final JSONObject prodCtx = prod.optJSONObject("prodCtx");
            if (prodCtx != null) {
                id = prodCtx.optString("lineId", null);
                final String num = prodCtx.optString("num", null);
                final String matchId = prodCtx.optString("matchId", null);
                ctxNum = matchId == null || matchId.equals(number) || (num != null && num.equals(number)) ? num : matchId;
            } else {
                id = null;
                ctxNum = null;
            }
            final Product product = cls != -1 ? intToProduct(cls) : null;
            lines.add(newLine(
                    id, operator, product,
                    !name.isEmpty() ? name : null,
                    nameS,
                    ctxNum != null ? ctxNum : number,
                    addName,
                    style));
        }

        return lines;
    }

    private static class Price {
        public final Currency currency;
        public final float amount;

        public Price(final Currency currency, final float amount) {
            this.currency = currency;
            this.amount = amount;
        }
    }

    private Price parsePriceObject(final JSONObject jsonPrice) throws JSONException {
        if (jsonPrice == null)
            return null;
        final String currencyText = jsonPrice.optString("currency", null);
        if (currencyText == null)
            return null;
        final int amount = jsonPrice.optInt("amount", -1);
        if (amount < 0)
            return null;
        return new Price(ParserUtils.getCurrency(currencyText), amount / 100f);
    }

    private Price parsePriceFromObject(final JSONObject jsonObject) throws JSONException {
        final boolean hasPriceObject = apiLevel >= 27;
        if (hasPriceObject)
            return parsePriceObject(jsonObject.optJSONObject("price"));
        final Currency currency = ParserUtils.getCurrency(jsonObject.optString("cur"));
        final float amount = jsonObject.getInt("prc") / 100f;
        return new Price(currency, amount);
    }

    private List<String> parsePolyList(final JSONArray polyList) throws JSONException {
        if (polyList == null)
            return null;
        final int len = polyList.length();
        final List<String> polylines = new ArrayList<>(len);

        for (int i = 0; i < len; i++) {
            final JSONObject poly = polyList.getJSONObject(i);
            // checkState(poly.getBoolean("delta"));
            polylines.add(poly.getString("crdEncYX"));
        }
        return polylines;
    }

    protected Line newLine(
            final String id, final String operator, final Product product,
            final @Nullable String name, final @Nullable String shortName,
            final @Nullable String number, final @Nullable String addName,
            final Style style) {
        final String longName;
        if (addName != null)
            longName = addName + (number != null && !addName.endsWith(number) ? " (" + number + ")" : "");
        else if (shortName != null)
            longName = shortName + (number != null && !shortName.endsWith(number) ? " (" + number + ")" : "");
        else if (name != null)
            longName = name + (number != null && !name.endsWith(number) ? " (" + number + ")" : "");
        else
            longName = number;

        final String label;
        if (product == Product.BUS || product == Product.TRAM) {
            // For bus and tram, prefer a slightly shorter label without the product prefix
            if (shortName != null)
                label = shortName;
            else if (number != null && name != null && name.endsWith(number))
                label = number;
            else
                label = name;
        } else {
            // Otherwise the longer label is fine
            label = addName != null ? addName : shortName != null ? shortName : name;
        }
        return new Line(id, operator, product, label, longName, lineStyle(operator, product, label, style));
    }

    private static String getPlainErr(final String err) {
        return err.endsWith("_R") ? err.substring(0, err.length() - 2) : err;
    }

    public static class JsonContext implements QueryTripsContext {
        private static final long serialVersionUID = -4657767855558556495L;

        public final Location from, via, to;
        public final Date date;
        public final boolean dep;
        public final boolean direct;
        public final boolean bike;
        public final Set<Product> products;
        public final WalkSpeed walkSpeed;
        public final Integer maxWalkDistanceMeters;
        public final Integer minTransferTimeMinutes;
        public final String laterContext, earlierContext;

        public JsonContext(
                final Location from, final @Nullable Location via, final Location to,
                final Date date, final boolean dep,
                final boolean direct, final boolean bike,
                final Set<Product> products,
                final WalkSpeed walkSpeed,
                final Integer maxWalkDistanceMeters,
                final Integer minTransferTimeMinutes,
                final String laterContext, final String earlierContext) {
            this.from = from;
            this.via = via;
            this.to = to;
            this.date = date;
            this.dep = dep;
            this.direct = direct;
            this.bike = bike;
            this.products = products;
            this.walkSpeed = walkSpeed;
            this.maxWalkDistanceMeters = maxWalkDistanceMeters;
            this.minTransferTimeMinutes = minTransferTimeMinutes;
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

    @Nullable
    private byte[] requestChecksumSalt;
    @Nullable
    private byte[] requestMicMacSalt;

    private void addSaltToUrl(final HttpUrl.Builder url, final String body) {
        final MessageDigest md5 = md5instance();
        if (requestChecksumSalt != null) {
            md5.reset();
            md5.update(body.getBytes(StandardCharsets.UTF_8));
            md5.update(requestChecksumSalt);
            url.addQueryParameter("checksum", byteArrayToHexString(md5.digest()));
        }
        if (requestMicMacSalt != null) {
            md5.reset();
            md5.update(body.getBytes(StandardCharsets.UTF_8));
            final byte[] mic = md5.digest();
            url.addQueryParameter("mic", byteArrayToHexString(mic));
            md5.reset();
            md5.update(byteArrayToHexString(mic).getBytes(StandardCharsets.UTF_8));
            md5.update(requestMicMacSalt);
            final byte[] mac = md5.digest();
            url.addQueryParameter("mac", byteArrayToHexString(mac));
        }
    }

    protected void setRequestChecksumSalt(final byte[] requestChecksumSalt) {
        this.requestChecksumSalt = requestChecksumSalt;
    }

    public byte[] getRequestChecksumSalt() {
        return requestChecksumSalt;
    }

    protected void setRequestMicMacSalt(final byte[] requestMicMacSalt) {
        this.requestMicMacSalt = requestMicMacSalt;
    }

    public byte[] getRequestMicMacSalt() {
        return requestMicMacSalt;
    }

    public static byte[] decryptSalt(final String encryptedSalt, final String saltEncryptionKey) {
        try {
            final byte[] key = hexStringToByteArray(saltEncryptionKey);
            checkState(key.length * 8 == 128, () -> "encryption key must be 128 bits");
            final SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
            final IvParameterSpec ivParameterSpec = new IvParameterSpec(new byte[16]);
            final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);
            return cipher.doFinal(Base64.getDecoder().decode(encryptedSalt));
        } catch (final GeneralSecurityException x) {
            // should not happen
            throw new RuntimeException(x);
        }
    }

    public static String byteArrayToHexString(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for(final byte b: bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static byte[] hexStringToByteArray(final String string) {
        final int len = string.length();
        final byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(string.charAt(i), 16) << 4)
                    + Character.digit(string.charAt(i+1), 16));
        }
        return data;
    }

}
