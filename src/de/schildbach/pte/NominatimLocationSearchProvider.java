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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.HttpClient;
import okhttp3.HttpUrl;

public class NominatimLocationSearchProvider extends AbstractLocationSearchProvider {
    private static final HttpUrl WEB_API_BASE = HttpUrl.parse("https://nominatim.openstreetmap.org/");

    private final HttpUrl searchEndpoint;

    private static final Map<LocationType, String> LOCATION_TYPE_MAP = new HashMap<LocationType, String>() {
        private static final long serialVersionUID = -2827675532432392114L;

        {
            put(LocationType.ANY, "address,poi,railway");
            put(LocationType.STATION, "railway");
            put(LocationType.POI, "poi");
            put(LocationType.ADDRESS, "address");
        }
    };

    public NominatimLocationSearchProvider() {
        this.searchEndpoint = WEB_API_BASE.newBuilder().addPathSegments("search").build();
    }

    @Override
    public SuggestLocationsResult suggestLocations(
            final CharSequence constraint,
            final @Nullable Set<LocationType> types,
            final int maxLocations) throws IOException {

        final String layer;
        if (types == null || types.isEmpty()) {
            layer = "address,poi,railway";
        } else {
            layer = types.stream()
                    .map(LOCATION_TYPE_MAP::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(","));
        }

        final HttpUrl.Builder builder = this.searchEndpoint.newBuilder()
                .addQueryParameter("q", constraint.toString())
                .addQueryParameter("limit", Integer.toString(maxLocations))
                .addQueryParameter("layer", layer)
                .addQueryParameter("format", "jsonv2");
        if (userInterfaceLanguage != null)
            builder.addQueryParameter("accept-language", userInterfaceLanguage);
        final HttpUrl url = builder.build();

        String page = null;
        try {
            page = doRequest(url);

            final JSONArray locs = new JSONArray(page);
            final List<SuggestedLocation> locations = new ArrayList<>();
            for (int iLoc = 0; iLoc < locs.length(); iLoc++) {
                final JSONObject jsonL = locs.getJSONObject(iLoc);
                final Location loc = parseLocation(jsonL);
                if (loc != null) {
                    locations.add(new SuggestedLocation(loc, jsonL.optInt("weight", -iLoc)));
                }
            }
            return new SuggestLocationsResult(null, locations);
        } catch (IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new SuggestLocationsResult(null, SuggestLocationsResult.Status.SERVICE_DOWN);
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + builder, x);
        }
    }

    private Location parseLocation(final JSONObject jsonL) throws JSONException {
        final String displayName = jsonL.getString("display_name");
        final String category = jsonL.getString("category");
        final String lat = jsonL.optString("lat");
        final String lon = jsonL.optString("lon");
        Point coords = null;
        try {
            coords = Point.fromDouble(Double.parseDouble(lat), Double.parseDouble(lon));
        } catch (NumberFormatException nfe) {
            // ignore, coords not available
        }
        final String place = null;
        final String name = displayName;

        return new Location(LocationType.ADDRESS, null, coords, place, name);
    }

    private static String doRequest(
            final HttpClient httpClient, final String userInterfaceLanguage,
            final HttpUrl url, final String body, final String contentType) throws IOException {
        String cType = contentType != null ? contentType : "application/json";
        httpClient.setHeader("Accept", cType);
        if (body != null) httpClient.setHeader("Content-Type", cType);
        httpClient.setHeader("Accept", cType);
        if (userInterfaceLanguage != null)
            httpClient.setHeader("Accept-Language", userInterfaceLanguage);
        return httpClient.get(url, body, null).toString();
    }

    private String doRequest(final HttpUrl url, final String body) throws IOException {
        return doRequest(httpClient, userInterfaceLanguage, url, body, null);
    }

    private String doRequest(final HttpUrl url) throws IOException {
        return doRequest(url, null);
    }
}
