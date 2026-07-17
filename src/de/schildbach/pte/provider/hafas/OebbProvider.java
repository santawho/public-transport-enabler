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

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Product;

import okhttp3.HttpUrl;

/**
 * Provider implementation for the Österreichische Bundesbahnen (Austria).
 * 
 * @author Andreas Schildbach
 */
public class OebbProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://fahrplan.oebb.at/");
    private static final Product[] PRODUCTS_MAP = { Product.HIGH_SPEED_TRAIN, Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN, Product.REGIONAL_TRAIN, Product.REGIONAL_TRAIN, Product.SUBURBAN_TRAIN,
            Product.BUS, Product.FERRY, Product.SUBWAY, Product.TRAM, Product.HIGH_SPEED_TRAIN, Product.ON_DEMAND,
            Product.HIGH_SPEED_TRAIN };
    private static final String AND_API_CLIENT = "{\"id\":\"OEBB\",\"type\":\"AND\"}";
    private static final String WEB_API_CLIENT = "{\"id\":\"OEBB\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
    private static final String WEBAPP_CONFIG_URL = "https://fahrplan.oebb.at/webapp/config/webapp.config.json";

    public OebbProvider() {
        this(WEB_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public OebbProvider(final String apiAuthorization) {
        this(AND_API_CLIENT, apiAuthorization);
        setApiExt("OEBB.14");
    }

    public OebbProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.OEBB, API_BASE, PRODUCTS_MAP);
        setApiEndpoint("gate");
        setApiVersion("1.88");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
    }

//    private static final String[] PLACES = { "Wien", "Graz", "Linz/Donau", "Salzburg", "Innsbruck" };
//
//    @Override
//    protected String[] splitStationName(final String name) {
//        for (final String place : PLACES)
//            if (name.startsWith(place + " "))
//                return new String[] { place, name.substring(place.length() + 1) };
//        return super.splitStationName(name);
//    }


    // list all places, which contain at least one space
    // except: places with "Bad "-prefix and no further spaces
    private static final String[] SPECIAL_PLACES = new String[]{
// the following contain spaces and must be listed here
    };

    @Override
    protected String[] splitDirectionName(final String placeAndName, @Nullable final Line line) {
        return parseSpaceDelimitedPlaceAndStation(placeAndName, SPECIAL_PLACES);
    }

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

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL_INCLUDING_HIGHSPEED;
    }
}
