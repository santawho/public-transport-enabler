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

import java.util.Set;
import java.util.regex.Matcher;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Product;
import okhttp3.HttpUrl;

/**
 * Provider implementation for Transports publics genevois (TPG, Geneva, Switzerland).
 */
public class TpgProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://tpg.hafas.cloud/");
    private static final Product[] PRODUCTS_MAP = {
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN,
            Product.REGIONAL_TRAIN,
            Product.FERRY,
            Product.SUBURBAN_TRAIN,
            Product.BUS,
            Product.CABLECAR,
            Product.BUS,
            Product.TRAM,
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
    };
    private static final String DEFAULT_API_CLIENT = "{\"id\":\"HAFAS\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
    private static final String WEBAPP_CONFIG_URL = "https://tpg.hafas.cloud/config/webapp.config.json";

    public TpgProvider() {
        this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public TpgProvider(final String apiAuthorization) {
        this(DEFAULT_API_CLIENT, apiAuthorization);
    }

    public TpgProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.TPG, API_BASE, PRODUCTS_MAP);
        setApiVersion("1.68");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
    }

    private static final String[] OPERATORS = { "SBB", "SZU" };
    private static final String[] PLACES = { "Zürich", "Winterthur" };

    @Override
    protected boolean isStationBoardDestinationCommonlyDirection() {
        return false;
    }

    @Override
    protected String[] splitStationName(String name) {
        for (final String operator : OPERATORS) {
            if (name.endsWith(" " + operator)) {
                name = name.substring(0, name.length() - operator.length() - 1);
                break;
            }

            if (name.endsWith(" (" + operator + ")")) {
                name = name.substring(0, name.length() - operator.length() - 3);
                break;
            }
        }

        for (final String place : PLACES) {
            if (name.startsWith(place + " "))
                return new String[] { place, name.substring(place.length() + 1) };
            else if (name.startsWith(place + ", "))
                return new String[] { place, name.substring(place.length() + 2) };
        }

        final Matcher m = P_SPLIT_NAME_FIRST_COMMA.matcher(name);
        if (m.matches())
            return new String[] { m.group(1), m.group(2) };
        return super.splitStationName(name);
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

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL_INCLUDING_HIGHSPEED;
    }
}
