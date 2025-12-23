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

package de.schildbach.pte.live;

import de.schildbach.pte.TransitousProvider;
import de.schildbach.pte.dto.*;
import org.junit.Test;

import java.util.Date;
import java.util.EnumSet;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransitousProviderLiveTest extends AbstractProviderLiveTest {
    public TransitousProviderLiveTest() {
        super(new TransitousProvider());
    }

    @Test
    public void nearbyStations() throws Exception {
        final NearbyLocationsResult result =
                queryNearbyStations(new Location(LocationType.STATION, "de-DELFI_de:09162:93:2:2"));
        print(result);
    }

    @Test
    public void nearbyStationsByCoordinateMarienplatz() throws Exception {
        final NearbyLocationsResult result = queryNearbyStations(
                Location.coord(Point.fromDouble(48.1364360, 11.5776610)));
        print(result);
        assertTrue(result.locations.size() > 0);
    }

    @Test
    public void nearbyLocationsByCoordinate() throws Exception {
        final NearbyLocationsResult result = queryNearbyLocations(EnumSet.of(LocationType.STATION, LocationType.POI),
                Location.coord(48135232, 11560650));
        print(result);
        assertTrue(result.locations.size() > 0);
    }

    @Test
    public void queryDeparturesMarienplatz() throws Exception {
        final QueryDeparturesResult result1 = queryDepartures("de-DELFI_de:09162:93:2:2", false);
        assertEquals(QueryDeparturesResult.Status.OK, result1.status);
        print(result1);
    }

    @Test
    public void queryDeparturesInvalidStation() throws Exception {
        final QueryDeparturesResult result = queryDepartures("999999", false);
        assertEquals(QueryDeparturesResult.Status.INVALID_STATION, result.status);
    }

    @Test
    public void suggestLocationsIdentified() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Starnberg, Agentur für Arbeit");
        print(result);
    }

    @Test
    public void suggestLocationsIncomplete() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Marien");
        print(result);
    }

    @Test
    public void suggestLocationsWithUmlaut() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Grüntal");
        print(result);
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "de-DELFI_de:09162:619:1:1")));
    }

    @Test
    public void tripBetweenCoordinates() throws Exception {
        final Location from = Location.coord(48165238, 11577473);
        final Location to = Location.coord(47987199, 11326532);
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void suggestLocationsFraunhofer() throws Exception {
        final SuggestLocationsResult result = suggestLocations("fraunhofer");
        print(result);
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "de-DELFI_de:09162:150")));
    }

    @Test
    public void suggestLocationsHirschgarten() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Hirschgarten");
        print(result);
        assertEquals("München, Deutschland", result.getLocations().get(0).place);
    }

    @Test
    public void suggestLocationsOstbahnhof() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Ostbahnhof");
        print(result);
        assertEquals("München, Deutschland", result.getLocations().get(0).place);
    }

    @Test
    public void suggestLocationsMarienplatz() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Marienplatz");
        print(result);
        assertEquals("München, Deutschland", result.getLocations().get(0).place);
    }

    @Test
    public void suggestAddress() throws Exception {
        final SuggestLocationsResult result = suggestLocations("München, Maximilianstr. 17");
        print(result);
        assertThat(result.getLocations(), hasItem(hasName("Maximilianstraße 17")));
    }

    @Test
    public void suggestStreet() throws Exception {
        final SuggestLocationsResult result = suggestLocations("München, Maximilianstr.");
        print(result);
        assertThat(result.getLocations(), hasItem(hasName("Maximilianstraße")));
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "de-DELFI_de:09161:512:0:1")));
    }

    @Test
    public void shortTrip() throws Exception {
        final Location from = new Location(LocationType.STATION, "de-DELFI_de:09162:2");
        final Location to = new Location(LocationType.STATION, "de-DELFI_de:09162:10");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
        final QueryTripsResult earlierResult = queryMoreTrips(laterResult.context, false);
        print(earlierResult);
    }

    @Test
    public void longTrip() throws Exception {
        // münchen marienplatz
        final Location from = new Location(LocationType.STATION, "de-DELFI_de:09188:5530:0:1");
        // london paddington
        final Location to = new Location(LocationType.STATION, "gb-great-britain_910GPADTON");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
    }

    @Test
    public void tripBetweenAddressAndStation() throws Exception {
        final Location from = new Location(LocationType.ADDRESS, null, Point.from1E6(48238341, 11478230));
        final Location to = new Location(LocationType.STATION, "at-Railway-Current-Reference-Data-2025_Pde:09162:5"); // münchen ostbahnhof
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void tripBetweenAddresses() throws Exception {
        final Location from = new Location(LocationType.ADDRESS, null, Point.fromDouble(50.8505159,5.6877679)); // maastricht
        final Location to = new Location(LocationType.ADDRESS, null, Point.fromDouble(45.778324825003864,16.000943970501027)); //zagreb
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void tripBetweenStationAndAddress() throws Exception {
        final Location from = new Location(LocationType.STATION, "at-Railway-Current-Reference-Data-2025_Pde:09162:5");
        final Location to = new Location(LocationType.ADDRESS, null, Point.from1E6(48188018, 11574239));
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void tripInvalidStation() throws Exception {
        final Location valid = new Location(LocationType.STATION, "at-Railway-Current-Reference-Data-2025_Pde:09162:5", "München", "Ostbahnhof");
        final Location invalid = new Location(LocationType.STATION, "99999", null, null);
        final QueryTripsResult result1 = queryTrips(valid, null, invalid, new Date(), true, null);
        assertEquals(QueryTripsResult.Status.UNKNOWN_LOCATION, result1.status);
        final QueryTripsResult result2 = queryTrips(invalid, null, valid, new Date(), true, null);
        assertEquals(QueryTripsResult.Status.UNKNOWN_LOCATION, result2.status);
    }
}
