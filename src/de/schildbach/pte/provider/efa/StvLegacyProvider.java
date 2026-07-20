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

package de.schildbach.pte.provider.efa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.TripOptions;

import okhttp3.HttpUrl;

import static java.util.Objects.requireNonNull;

/**
 * @author Andreas Schildbach
 */
public class StvLegacyProvider extends AbstractEfaProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("http://appefa10.verbundlinie.at/android/");

    public StvLegacyProvider() {
        super(NetworkId.STV, API_BASE);
        setRequestUrlEncoding(StandardCharsets.UTF_8);
        setIncludeRegionId(false);
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
            return mobileCoordRequest(types, location.coord, maxDistance, maxLocations);

        if (location.type != LocationType.STATION)
            throw new IllegalArgumentException("cannot handle: " + location.type);

        throw new IllegalArgumentException("station"); // TODO
    }

    @Override
    public QueryDeparturesResult queryDepartures(
            final String stationId,
            final @Nullable Date time,
            final int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        requireNonNull(stationId);

        return queryDeparturesMobile(stationId, time, maxDepartures, equivsMode);
    }

    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint,
            final @Nullable Set<LocationType> types, final int maxLocations) throws IOException {
        return mobileStopfinderRequest(constraint, types, maxLocations);
    }

    @Override
    public QueryJourneyResult queryJourney(
            final JourneyRef aJourneyRef,
            final boolean splitSubJourneys,
            final boolean loadPath) throws IOException {
        return queryJourneyMobile((EfaJourneyRef) aJourneyRef, loadPath);
    }

    @Override
    public QueryTripsResult queryTrips(
            final Location from, final @Nullable Location via, final Location to,
            final Date date, final boolean dep, final @Nullable TripOptions options,
            final boolean loadPath) throws IOException {
        return queryTripsMobile(from, via, to, date, dep, options, loadPath);
    }

    @Override
    public QueryTripsResult queryMoreTrips(
            final QueryTripsContext contextObj, final boolean later,
            final boolean loadPath) throws IOException {
        return queryMoreTripsMobile(contextObj, later, loadPath);
    }
}
