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
 * Provider implementation for the Informationssystem Nahverkehr Sachsen-Anhalt (Saxony-Anhalt, Germany).
 * 
 * @author Andreas Schildbach
 */
public abstract class InsaProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://reiseauskunft.insa.de/bin/");
    private static final String WEBAPP_CONFIG_URL = "https://reiseauskunft.insa.de/auskunft-iframe/config/webapp.config.json";

    public static class Nasa extends InsaProvider {
        private static final Product[] PRODUCTS_MAP = {
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN,

            Product.SUBURBAN_TRAIN,
            Product.TRAM,
            Product.BUS,
            Product.ON_DEMAND,

            Product.REGIONAL_TRAIN,
            Product.FERRY,
        };
        private static final String AND_API_CLIENT = "{\"id\":\"NASA\",\"type\":\"AND\"}";
        private static final String WEB_API_CLIENT = "{\"id\":\"NASA\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp_nasa\"}";

        public Nasa() {
            this(WEB_API_CLIENT, WEBAPP_CONFIG_URL);
        }

        public Nasa(final String apiAuthorization) {
            this(AND_API_CLIENT, apiAuthorization);
        }

        public Nasa(final String apiClient, final String apiAuthorization) {
            super(NetworkId.NASA, PRODUCTS_MAP, apiClient, apiAuthorization);
        }
    }

    public static class Mdv extends InsaProvider {
        private static final Product[] PRODUCTS_MAP = {
                Product.HIGH_SPEED_TRAIN,
                Product.HIGH_SPEED_TRAIN,
                Product.HIGH_SPEED_TRAIN,
                Product.REGIONAL_TRAIN,
                Product.SUBURBAN_TRAIN,
                Product.TRAM,
                Product.BUS,
                Product.ON_DEMAND,
                Product.REGIONAL_TRAIN,
                Product.BUS,
                Product.ON_DEMAND,
                Product.COACH,
                Product.SUBURBAN_TRAIN,
        };
        private static final String WEB_API_CLIENT = "{\"id\":\"NASA\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_mdv\"}";

        @Override
        protected String getWebAppLocalizationId() {
            return "vs_mdv";
        }

        public Mdv() {
            super(NetworkId.MDV, PRODUCTS_MAP, WEB_API_CLIENT, WEBAPP_CONFIG_URL);
        }
    }

    protected InsaProvider(
            final NetworkId networkId,
            final Product[] productsMap,
            final String apiClient,
            final String apiAuthorization) {
        super(networkId, API_BASE, productsMap);
        setApiVersion("1.48");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
    }

    @Override
    protected String[] splitStationName(final String name) {
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
}
