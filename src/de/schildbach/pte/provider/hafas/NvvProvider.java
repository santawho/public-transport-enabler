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

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import okhttp3.HttpUrl;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Provider implementation for the Nordhessischer Verkehrsverbund (North Hesse, Germany).
 * 
 * @author Andreas Schildbach
 */
public class NvvProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl AND_API_BASE = HttpUrl.parse("https://auskunft.nvv.de/bin/");
    private static final HttpUrl WEB_API_BASE = HttpUrl.parse("https://auskunft.nvv.de/");
    private static final Product[] PRODUCTS_MAP = { Product.HIGH_SPEED_TRAIN, Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN, Product.SUBURBAN_TRAIN, Product.SUBWAY, Product.TRAM, Product.BUS, Product.BUS,
            Product.FERRY, Product.ON_DEMAND, Product.REGIONAL_TRAIN, Product.REGIONAL_TRAIN };
    private static final String AND_API_CLIENT = "{\"id\":\"NVV\",\"type\":\"AND\"}";
    private static final String WEB_API_CLIENT = "{\"id\":\"NVV\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
    private static final String WEBAPP_CONFIG_URL = "https://auskunft.nvv.de/config/webapp.config.json";

    public NvvProvider() {
        this(WEB_API_BASE, WEB_API_CLIENT, WEBAPP_CONFIG_URL);
        setApiEndpoint("gate");
    }

    public NvvProvider(final String apiAuthorization) {
        this(AND_API_BASE, AND_API_CLIENT, apiAuthorization);
        setApiExt("NVV.6.0");
    }

    private NvvProvider(final HttpUrl apiBase, final String apiClient, final String apiAuthorization) {
        super(NetworkId.NVV, apiBase, PRODUCTS_MAP);
        setApiVersion("1.68");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
        setStyles(STYLES);
    }

    private static final String[] SPECIAL_PLACES = new String[]{
// the following contain spaces and must be listed here
            "Groß Gerau",
            "Bad Soden-Salmünster-Bad Soden", // special, because "Bad Soden-Salmünster-Bad Soden Schweizerhaus", but "Bad Soden-Salmünster-Salmünster Am Palmusacker" and "Bad Soden-Salmünster-Kath.-Willenroth Waldschule"
//            "Hofheim am Taunus",
//            "Bad Homburg v.d.H."
// we now split using a complex Regex, so the following do not need an exception
//            "Frankfurt (Main)",
//            "Offenbach (Main)",
// we now split at first space, so the following do not need an exception
//            "Mainz",
//            "Wiesbaden",
//            "Marburg",
//            "Kassel",
//            "Hanau",
//            "Göttingen",
//            "Darmstadt",
//            "Aschaffenburg",
//            "Berlin",
//            "Fulda"
    };

    @Override
    protected String[] splitStationName(final String placeAndName) {
//        if (placeAndName.startsWith("F "))
//            return new String[] {"Frankfurt", placeAndName.substring(2)};
//
//        if (placeAndName.startsWith("OF "))
//            return new String[] {"Offenbach", placeAndName.substring(3)};
//
//        if (placeAndName.startsWith("MZ "))
//            return new String[] {"Mainz", placeAndName.substring(3)};

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
            return new String[] { m.group(2), m.group(1) };
        return super.splitStationName(address);
    }

    private static final Map<String, Style> STYLES = new HashMap<>();

    static {
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS1", new Style(Style.parseColor("#009edd"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS2", new Style(Style.parseColor("#ff2e17"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS3", new Style(Style.parseColor("#00b098"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS4", new Style(Style.parseColor("#ffc734"), Style.parseColor("#2c2e35"), Style.parseColor("#2c2e35")));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS5", new Style(Style.parseColor("#95542a"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS6", new Style(Style.parseColor("#ff7322"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS7", new Style(Style.parseColor("#214d36"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS8", new Style(Style.parseColor("#88c946"), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS9", new Style(Style.parseColor("#872996"), Style.WHITE));

        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU1", new Style(Style.parseColor("#c52b1e"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU2", new Style(Style.parseColor("#00ab4f"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU3", new Style(Style.parseColor("#345aaf"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU4", new Style(Style.parseColor("#fc5cac"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU5", new Style(Style.parseColor("#0c7d3e"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU6", new Style(Style.parseColor("#0082ca"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU7", new Style(Style.parseColor("#f19e2d"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU8", new Style(Style.parseColor("#ca7fbe"), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU9", new Style(Style.parseColor("#f4d039"), Style.parseColor("#2c2e35"), Style.parseColor("#2c2e35")));
    }
}
