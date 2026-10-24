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

import java.util.regex.Matcher;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Product;
import okhttp3.HttpUrl;

/**
 * Provider implementation for the GVH / Üstra Hannover (Germany).
 */
public class UestraProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://gvh.hafas.de/");
    private static final Product[] PRODUCTS_MAP = {
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN,
            Product.SUBURBAN_TRAIN,
            Product.BUS,
            Product.TRAM,
            Product.BUS,
            Product.SUBWAY,
            Product.ON_DEMAND,
            Product.TRAM,
    };
    private static final String DEFAULT_API_CLIENT = "{\"id\":\"HAFAS\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
    private static final String WEBAPP_CONFIG_URL = "https://gvh.hafas.de/config/webapp.config.json";

    public UestraProvider() {
        this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public UestraProvider(final String apiAuthorization) {
        this(DEFAULT_API_CLIENT, apiAuthorization);
    }

    public UestraProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.UESTRA, API_BASE, PRODUCTS_MAP);
        setApiVersion("1.62");
        setApiEndpoint("hamm");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
    }

    protected boolean supportsStationBoardEquivs() {
        return false;
    }

    // list all places, which contain at least one space
    // except: places with "Bad "-prefix and no further spaces
    private static final String[] SPECIAL_PLACES = new String[]{
// the following contain spaces and must be listed here
    };

    @Override
    protected String[] splitStationName(final String placeAndName) {
        return parseSpaceDelimitedPlaceAndStation(placeAndName, SPECIAL_PLACES);
    }

    @Override
    protected String[] splitPOI(final String poi) {
        final Matcher m = P_SPLIT_NAME_FIRST_COMMA.matcher(poi);
        if (m.matches())
            return new String[] { m.group(1), m.group(2) };

        return super.splitStationName(poi);
    }

    @Override
    protected String[] splitAddress(final String address) {
        final Matcher m = P_SPLIT_NAME_FIRST_COMMA.matcher(address);
        if (m.matches())
            return new String[] { m.group(1), m.group(2) };

        return super.splitStationName(address);
    }
}
