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

package de.schildbach.pte.provider.openjourneyplanner;

import static java.util.Objects.requireNonNull;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Date;
import java.util.Set;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import okhttp3.HttpUrl;

public abstract class AbstractOpenJourneyPlannerProvider extends AbstractNetworkProvider {
    protected static final Set<Capability> CAPABILITIES = Set.of(
        Capability.SUGGEST_LOCATIONS,
        Capability.NEARBY_LOCATIONS,
        Capability.DEPARTURES,
        Capability.TRIPS,
        Capability.TRIPS_VIA,
        Capability.BIKE_OPTION,
        Capability.DIRECT_OPTION,
        Capability.MIN_TRANSFER_TIMES,
        Capability.JOURNEY,
        Capability.TRIP_RELOAD
    );

    private HttpUrl apiBase;
    private String apiToken;

    protected AbstractOpenJourneyPlannerProvider(
            final NetworkId network,
            final HttpUrl apiBase) {
        super(network);
        this.apiBase = requireNonNull(apiBase);
    }

    public HttpUrl getApiBase() {
        return apiBase;
    }

    @Override
    public void setCredentials(final String credentials) {
        apiToken = credentials;
    }

    @Override
    protected Set<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(
            final Set<LocationType> types,
            final Location location,
            final EquivalentStationsMode equivsMode,
            final int maxDistance,
            final int maxLocations,
            final Set<Product> products) throws IOException {
        return null;
    }

    @Override
    public QueryDeparturesResult queryDepartures(
            final String stationId,
            @Nullable final Date time,
            final int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        return null;
    }

    @Override
    public QueryTripsResult queryTrips(
            final Location from,
            @Nullable final Location via,
            final Location to,
            final Date date,
            final boolean dep,
            @Nullable final TripOptions options,
            final boolean loadPath) throws IOException {
        return null;
    }

    @Override
    public QueryTripsResult queryMoreTrips(
            final QueryTripsContext context,
            final boolean later,
            final boolean loadPath) throws IOException {
        return null;
    }

    @Override
    public SuggestLocationsResult suggestLocations(
            final CharSequence constraint,
            @Nullable final Set<LocationType> types,
            final int maxLocations) throws IOException {
        return null;
    }
}
