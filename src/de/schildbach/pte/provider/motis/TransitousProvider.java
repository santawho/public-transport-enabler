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

package de.schildbach.pte.provider.motis;

import java.util.Set;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Product;
import okhttp3.HttpUrl;

/**
 * @author Dan Cojocaru
 */
public class TransitousProvider extends AbstractMotisProvider {
    public TransitousProvider() {
        super(NetworkId.TRANSITOUS, HttpUrl.parse(
                "https://api.transitous.org"));
    }

    @Override
    public Description getDescription() {
        return new Description.Base() {
            @Override
            public String getName() {
                return "Transitous";
            }

            @Override
            public String getDescriptionText() {
                return "community-run provider-neutral international public transport routing service";
            }

            @Override
            public String getUrl() {
                return "https://transitous.org/sources/";
            }
        };
    }

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL_INCLUDING_HIGHSPEED;
    }

    // list all places, which contain at least one space
    // except: places with "Bad "-prefix and no further spaces
    private static final String[] SPECIAL_PLACES = new String[]{
// the following contain spaces and must be listed here
    };

    @Override
    protected String[] splitStationName(final String motisPlaceName) {
        return parseSpaceDelimitedPlaceAndStation(motisPlaceName, SPECIAL_PLACES);
    }
}
