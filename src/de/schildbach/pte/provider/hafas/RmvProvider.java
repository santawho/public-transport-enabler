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

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.Standard;
import okhttp3.HttpUrl;

/**
 * Provider implementation for the Rhein-Main-Verkehrsverbund (Germany).
 */
public class RmvProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://www.rmv.de/auskunft/bin/jp/");
    private static final Product[] PRODUCTS_MAP = {
            Product.HIGH_SPEED_TRAIN,
            Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN,
            Product.SUBURBAN_TRAIN,
            Product.SUBWAY,
            Product.TRAM,
            Product.BUS,
            Product.BUS,
            Product.FERRY,
            Product.ON_DEMAND,
            Product.SUBURBAN_TRAIN, // upstream PTE has Product.REGIONAL_TRAIN instead?
            // upstream PTE has additional Product.REGIONAL_TRAIN
    };
    private static final String DEFAULT_API_CLIENT = "{\"id\":\"RMV\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
    private static final String WEBAPP_CONFIG_URL = "https://www.rmv.de/auskunft/rmv/app/config/webapp.config.json";

    public RmvProvider() {
        this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public RmvProvider(final String apiAuthorization) {
        this(DEFAULT_API_CLIENT, apiAuthorization);
    }

    public RmvProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.RMV, API_BASE, PRODUCTS_MAP);
        setApiVersion("1.79");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
        setStyles(STYLES);
    }

    @Override
    protected String additionalJnyFltrL() {
        return ",{\"value\": \"GROUP_PT\",\"mode\":\"INC\",\"type\":\"GROUP\"}";
    }

    // town (place) and stop name.
    // separated by space, or a comma, or a minus optionally followd by spaces.
    // name is the second part and may contain spaces.
    // place is the first part and may
    // - be prefixed by "Bad " (like. "Bad Vilbel")
    // - be suffixed by " (...)" (like "Frankfurt (Main)")
    // other spaces are not permitted in a place.
    // all places containing spaces must be listed in the SPECIAL_PLACES
    private static final Pattern P_SPLIT_NAME_RMV = Pattern.compile("((?:Bad )?(?:St. )?[^ ]*(?: ?\\([^)]*\\))?)[ ,\\-]? *(.*)");

    // list all places, which contain at least one space
    // except: places with "Bad "-prefix and no further spaces
    private static final String[] SPECIAL_PLACES = new String[]{
// the following contain spaces and must be listed here
            "Groß Gerau",
            "Bad Soden-Salmünster-Bad Soden", // special, because "Bad Soden-Salmünster-Bad Soden Schweizerhaus", but "Bad Soden-Salmünster-Salmünster Am Palmusacker" and "Bad Soden-Salmünster-Kath.-Willenroth Waldschule"
            "Hofheim am Taunus",
            "Bad Homburg v.d.H."
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

        for (final String place: SPECIAL_PLACES) {
            if (!placeAndName.startsWith(place))
                continue;

            final int placeLength = place.length();
            final String trailer = placeAndName.substring(placeLength);
            if (trailer.startsWith("-"))
                return new String[] { place, trailer.substring(1) };

            if (trailer.startsWith(" - "))
                return new String[] { place, trailer.substring(3) };

            if (trailer.startsWith(" "))
                return new String[] { place, trailer.substring(1) };
        }

        final Matcher m = P_SPLIT_NAME_RMV.matcher(placeAndName);
        if (m.matches()) {
            final String place = m.group(1);
            final String name = m.group(2);
            if (name != null && !name.isEmpty())
                return new String[]{place, name};
        }

        return new String[] { null, placeAndName };
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

    private static final Style.Shape RMV_SHAPE = Style.Shape.ROUNDED;

    protected static final Map<String, Style> STYLES = new HashMap<>();

    static {
        STYLES.put(Product.HIGH_SPEED_TRAIN.toString(),
                new Style(RMV_SHAPE, Standard.COLOR_BACKGROUND_HIGH_SPEED_TRAIN, Style.RED, Style.RED));
        STYLES.put(Product.REGIONAL_TRAIN.toString(), new Style(RMV_SHAPE, Standard.COLOR_BACKGROUND_REGIONAL_TRAIN, Style.WHITE));
        STYLES.put(Product.SUBURBAN_TRAIN.toString(), new Style(RMV_SHAPE, Standard.COLOR_BACKGROUND_SUBURBAN_TRAIN, Style.WHITE));
        STYLES.put(Product.SUBWAY.toString(), new Style(RMV_SHAPE, Standard.COLOR_BACKGROUND_SUBWAY, Style.WHITE));
        STYLES.put(Product.TRAM.toString(), new Style(RMV_SHAPE, Standard.COLOR_BACKGROUND_TRAM, Style.WHITE));
        STYLES.put(Product.BUS.toString(), new Style(Standard.COLOR_BACKGROUND_BUS, Style.WHITE));
        STYLES.put(Product.ON_DEMAND.toString(), new Style(Standard.COLOR_BACKGROUND_ON_DEMAND, Style.WHITE));
        STYLES.put(Product.FERRY.toString(), new Style(RMV_SHAPE, Standard.COLOR_BACKGROUND_FERRY, Style.WHITE));
        STYLES.put(Product.REPLACEMENT_SERVICE.toString(), new Style(Standard.COLOR_BACKGROUND_REPLACEMENT_SERVICE, Style.WHITE));
        STYLES.put(null, new Style(Style.DKGRAY, Style.WHITE));

//        STYLES.put("UU1", new Style(RMV_SHAPE, Style.rgb(184, 41, 47), Style.WHITE));
//        STYLES.put("UU2", new Style(RMV_SHAPE, Style.rgb(0, 166, 81), Style.WHITE));
//        STYLES.put("UU3", new Style(RMV_SHAPE, Style.rgb(75, 93, 170), Style.WHITE));
//        STYLES.put("UU4", new Style(RMV_SHAPE, Style.rgb(240, 92, 161), Style.WHITE));
//        STYLES.put("UU5", new Style(RMV_SHAPE, Style.rgb(1, 122, 67), Style.WHITE));
//        STYLES.put("UU6", new Style(RMV_SHAPE, Style.rgb(1, 125, 198), Style.WHITE));
//        STYLES.put("UU7", new Style(RMV_SHAPE, Style.rgb(228, 161, 35), Style.WHITE));
//        STYLES.put("UU8", new Style(RMV_SHAPE, Style.rgb(199, 125, 181), Style.WHITE));
//        STYLES.put("UU9", new Style(RMV_SHAPE, Style.rgb(255, 222, 1), Style.BLACK));
//        STYLES.put("SS1", new Style(RMV_SHAPE, Style.rgb(0, 136, 195), Style.WHITE));
//        STYLES.put("SS2", new Style(RMV_SHAPE, Style.rgb(210, 33, 41), Style.WHITE));
//        STYLES.put("SS3", new Style(RMV_SHAPE, Style.rgb(0, 157, 135), Style.WHITE));
//        STYLES.put("SS4", new Style(RMV_SHAPE, Style.rgb(255, 222, 1), Style.BLACK, Style.BLACK));
//        STYLES.put("SS5", new Style(RMV_SHAPE, Style.rgb(133, 84, 55), Style.WHITE));
//        STYLES.put("SS6", new Style(RMV_SHAPE, Style.rgb(229, 113, 42), Style.WHITE));
//        STYLES.put("SS7", new Style(RMV_SHAPE, Style.rgb(37, 75, 58), Style.WHITE));
//        STYLES.put("SS8", new Style(RMV_SHAPE, Style.rgb(131, 191, 66), Style.WHITE));
//        STYLES.put("SS9", new Style(RMV_SHAPE, Style.rgb(129, 43, 124), Style.WHITE));
//        STYLES.put("T11", new Style(RMV_SHAPE, Style.rgb(136, 129, 189), Style.WHITE));
//        STYLES.put("T12", new Style(RMV_SHAPE, Style.rgb(231, 185, 9), Style.WHITE));
//        STYLES.put("T14", new Style(RMV_SHAPE, Style.rgb(0, 166, 222), Style.WHITE));
//        STYLES.put("T15", new Style(RMV_SHAPE, Style.rgb(245, 130, 32), Style.WHITE));
//        STYLES.put("T16", new Style(RMV_SHAPE, Style.rgb(81, 184, 72), Style.WHITE));
//        STYLES.put("T17", new Style(RMV_SHAPE, Style.rgb(237, 29, 37), Style.WHITE));
//        STYLES.put("T18", new Style(RMV_SHAPE, Style.rgb(22, 71, 158), Style.WHITE));
//        STYLES.put("T19", new Style(RMV_SHAPE, Style.WHITE, Style.rgb(120, 205, 208), Style.rgb(120, 205, 208)));
//        STYLES.put("T20", new Style(RMV_SHAPE, Style.WHITE, Style.rgb(148, 149, 152), Style.rgb(148, 149, 152)));
//        STYLES.put("T21", new Style(RMV_SHAPE, Style.rgb(242, 135, 183), Style.WHITE));

        STYLES.put("RRB10", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB12", new Style(RMV_SHAPE, Style.rgb(148, 91, 54), Style.WHITE));
        STYLES.put("RRB15", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB16", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB21", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB22", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRB23", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRB26", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB29", new Style(RMV_SHAPE, Style.rgb(199, 125, 180), Style.WHITE));
        STYLES.put("RRB31", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRB33", new Style(RMV_SHAPE, Style.rgb(148, 91, 54), Style.WHITE));
        STYLES.put("RRB34", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB35", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB37", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB38", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB39", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRB40", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB41", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB45", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB46", new Style(RMV_SHAPE, Style.rgb(148, 91, 54), Style.WHITE));
        STYLES.put("RRB47", new Style(RMV_SHAPE, Style.rgb(199, 125, 180), Style.WHITE));
        STYLES.put("RRB48", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB49", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB5", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB50", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRB51", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRB52", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB53", new Style(RMV_SHAPE, Style.rgb(199, 125, 180), Style.WHITE));
        STYLES.put("RRB58", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB6", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRB61", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB62", new Style(RMV_SHAPE, Style.rgb(199, 125, 180), Style.WHITE));
        STYLES.put("RRB63", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRB65", new Style(RMV_SHAPE, Style.rgb(199, 125, 180), Style.WHITE));
        STYLES.put("RRB66", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB67", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB68", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB69", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRB75", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB81", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRB82", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRB85", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB86", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRB90", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRB94", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB95", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRB96", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRB97", new Style(RMV_SHAPE, Style.rgb(148, 91, 54), Style.WHITE));
        STYLES.put("RRE10a", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRE13", new Style(RMV_SHAPE, Style.rgb(21, 192, 242), Style.WHITE));
        STYLES.put("RRE14", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRE15", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRE17", new Style(RMV_SHAPE, Style.rgb(199, 125, 180), Style.WHITE));
        STYLES.put("RRE2", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRE20", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRE25", new Style(RMV_SHAPE, Style.rgb(0, 126, 197), Style.WHITE));
        STYLES.put("RRE3", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRE30", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRE4", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRE5", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRE50", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRE54", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRE55", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRE59", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("RRE60", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRE70", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRE80", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("RRE85", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRE97", new Style(RMV_SHAPE, Style.rgb(148, 91, 54), Style.WHITE));
        STYLES.put("RRE98", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));
        STYLES.put("RRE99", new Style(RMV_SHAPE, Style.rgb(227, 160, 35), Style.WHITE));

        STYLES.put("BX11", new Style(RMV_SHAPE, Style.rgb(167, 22, 128), Style.WHITE));
        STYLES.put("BX14", new Style(RMV_SHAPE, Style.rgb(0, 103, 148), Style.WHITE));
        STYLES.put("BX15", new Style(RMV_SHAPE, Style.rgb(168, 138, 91), Style.WHITE));
        STYLES.put("BX17", new Style(RMV_SHAPE, Style.rgb(229, 167, 18), Style.BLACK));
        STYLES.put("BX18", new Style(RMV_SHAPE, Style.rgb(100, 133, 146), Style.WHITE));
        STYLES.put("BX19", new Style(RMV_SHAPE, Style.rgb(11, 45, 114), Style.WHITE));
        STYLES.put("BX26", new Style(RMV_SHAPE, Style.rgb(0, 111, 69), Style.WHITE));
        STYLES.put("BX27", new Style(RMV_SHAPE, Style.rgb(206, 123, 45), Style.WHITE));
        STYLES.put("BX33", new Style(RMV_SHAPE, Style.rgb(46, 48, 146), Style.WHITE));
        STYLES.put("BX35", new Style(RMV_SHAPE, Style.rgb(0, 70, 89), Style.WHITE));
        STYLES.put("BX37", new Style(RMV_SHAPE, Style.rgb(61, 123, 147), Style.WHITE));
        STYLES.put("BX38", new Style(RMV_SHAPE, Style.rgb(241, 91, 91), Style.WHITE));
        STYLES.put("BX39", new Style(RMV_SHAPE, Style.rgb(186, 119, 61), Style.WHITE));
        STYLES.put("BX40", new Style(RMV_SHAPE, Style.rgb(68, 185, 123), Style.WHITE));
        STYLES.put("BX41", new Style(RMV_SHAPE, Style.rgb(175, 170, 31), Style.WHITE));
        STYLES.put("BX53", new Style(RMV_SHAPE, Style.rgb(167, 22, 128), Style.WHITE));
        STYLES.put("BX57", new Style(RMV_SHAPE, Style.rgb(0, 153, 218), Style.WHITE));
        STYLES.put("BX58", new Style(RMV_SHAPE, Style.rgb(167, 22, 128), Style.WHITE));
        STYLES.put("BX61", new Style(RMV_SHAPE, Style.rgb(167, 22, 128), Style.WHITE));
        STYLES.put("BX64", new Style(RMV_SHAPE, Style.rgb(245, 130, 31), Style.WHITE));
        STYLES.put("BX69", new Style(RMV_SHAPE, Style.rgb(229, 168, 46), Style.WHITE));
        STYLES.put("BX71", new Style(RMV_SHAPE, Style.rgb(0, 165, 139), Style.WHITE));
        STYLES.put("BX72", new Style(RMV_SHAPE, Style.rgb(237, 20, 90), Style.WHITE));
        STYLES.put("BX74", new Style(RMV_SHAPE, Style.rgb(237, 28, 36), Style.WHITE));
        STYLES.put("BX76", new Style(RMV_SHAPE, Style.rgb(79, 75, 114), Style.WHITE));
        STYLES.put("BX77", new Style(RMV_SHAPE, Style.rgb(167, 22, 128), Style.WHITE));
        STYLES.put("BX78", new Style(RMV_SHAPE, Style.rgb(0, 185, 242), Style.WHITE));
        STYLES.put("BX79", new Style(RMV_SHAPE, Style.rgb(0, 112, 126), Style.WHITE));
        STYLES.put("BX83", new Style(RMV_SHAPE, Style.rgb(196, 8, 117), Style.WHITE));
        STYLES.put("BX89", new Style(RMV_SHAPE, Style.rgb(135, 129, 189), Style.WHITE));
        STYLES.put("BX93", new Style(RMV_SHAPE, Style.rgb(143, 88, 115), Style.WHITE));
        STYLES.put("BX94", new Style(RMV_SHAPE, Style.rgb(112, 153, 165), Style.WHITE));
        STYLES.put("BX95", new Style(RMV_SHAPE, Style.rgb(91, 196, 190), Style.WHITE));

        // S-Bahn Rhein-Main
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS1", new Style(RMV_SHAPE, Style.rgb(0, 149, 217), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS2", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS3", new Style(RMV_SHAPE, Style.rgb(0, 168, 149), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS4", new Style(RMV_SHAPE, Style.rgb(254, 202, 10), Style.BLACK));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS5", new Style(RMV_SHAPE, Style.rgb(148, 91, 54), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS6", new Style(RMV_SHAPE, Style.rgb(244, 121, 33), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS7", new Style(RMV_SHAPE, Style.rgb(32, 84, 63), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS8", new Style(RMV_SHAPE, Style.rgb(139, 198, 62), Style.WHITE));
        STYLES.put("DB Regio AG S-Bahn Rhein-Main|SS9", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));

        // S-Bahn Rhein-Neckar
        STYLES.put("DB Regio AG Mitte Region Südwest|SS1", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS2", new Style(RMV_SHAPE, Style.rgb(0, 120, 192), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS3", new Style(RMV_SHAPE, Style.rgb(255, 220, 1), Style.BLACK));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS4", new Style(RMV_SHAPE, Style.rgb(0, 165, 79), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS5", new Style(RMV_SHAPE, Style.rgb(247, 151, 53), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS6", new Style(RMV_SHAPE, Style.rgb(1, 189, 242), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS7", new Style(RMV_SHAPE, Style.rgb(237, 74, 155), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS8", new Style(RMV_SHAPE, Style.rgb(164, 110, 168), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS9", new Style(RMV_SHAPE, Style.rgb(127, 194, 65), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS33", new Style(RMV_SHAPE, Style.rgb(136, 76, 158), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS39", new Style(RMV_SHAPE, Style.rgb(192, 102, 22), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS44", new Style(RMV_SHAPE, Style.rgb(0, 165, 79), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|SS51", new Style(RMV_SHAPE, Style.rgb(247, 151, 53), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|RRE5", new Style(RMV_SHAPE, Style.rgb(247, 151, 53), Style.WHITE));
        STYLES.put("DB Regio AG Mitte Region Südwest|RRE9", new Style(RMV_SHAPE, Style.rgb(127, 194, 65), Style.WHITE));

        // U-Bahn Frankfurt
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU1", new Style(RMV_SHAPE, Style.rgb(181, 31, 31), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU2", new Style(RMV_SHAPE, Style.rgb(0, 152, 67), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU3", new Style(RMV_SHAPE, Style.rgb(78, 85, 161), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU4", new Style(RMV_SHAPE, Style.rgb(234, 82, 151), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU5", new Style(RMV_SHAPE, Style.rgb(0, 115, 57), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU6", new Style(RMV_SHAPE, Style.rgb(0, 105, 180), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU7", new Style(RMV_SHAPE, Style.rgb(229, 161, 0), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU8", new Style(RMV_SHAPE, Style.rgb(206, 128, 180), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|UU9", new Style(RMV_SHAPE, Style.rgb(249, 218, 27), Style.BLACK));

        // Tram Frankfurt
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T11", new Style(RMV_SHAPE, Style.rgb(253, 195, 0), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T12", new Style(RMV_SHAPE, Style.rgb(197, 68, 9), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T14", new Style(RMV_SHAPE, Style.rgb(233, 78, 15), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T15", new Style(RMV_SHAPE, Style.rgb(253, 195, 0), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T16", new Style(RMV_SHAPE, Style.rgb(239, 125, 0), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T17", new Style(RMV_SHAPE, Style.rgb(233, 78, 15), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T18", new Style(RMV_SHAPE, Style.rgb(247, 166, 0), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T19", new Style(RMV_SHAPE, Style.rgb(240, 90, 34), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T20", new Style(RMV_SHAPE, Style.rgb(247, 166, 0), Style.WHITE));
        STYLES.put("Stadtwerke Verkehrsgesellschaft Frankfurt|T21", new Style(RMV_SHAPE, Style.rgb(247, 166, 0), Style.WHITE));

        // busses Frankfurt
        STYLES.put("In-der-City-Bus|BM32", new Style(RMV_SHAPE, Style.rgb(186, 91, 158), Style.WHITE));
        STYLES.put("In-der-City-Bus|BM34", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("In-der-City-Bus|BM36", new Style(RMV_SHAPE, Style.rgb(130, 14, 100), Style.WHITE));
        STYLES.put("In-der-City-Bus|BM43", new Style(RMV_SHAPE, Style.rgb(206, 128, 180), Style.WHITE));
        STYLES.put("In-der-City-Bus|BM46", new Style(RMV_SHAPE, Style.rgb(130, 14, 100), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|BM55", new Style(RMV_SHAPE, Style.rgb(201, 65, 145), Style.WHITE));
        STYLES.put("Transdev Rhein-Main GmbH|BM60", new Style(RMV_SHAPE, Style.rgb(130, 14, 100), Style.WHITE));
        STYLES.put("Transdev Rhein-Main GmbH|BM72", new Style(RMV_SHAPE, Style.rgb(206, 128, 180), Style.WHITE));
        STYLES.put("Transdev Rhein-Main GmbH|BM73", new Style(RMV_SHAPE, Style.rgb(206, 128, 180), Style.WHITE));

        // busses Hanau
        STYLES.put("Hanauer Straßenbahn GmbH|B1", new Style(RMV_SHAPE, Style.rgb(139, 198, 62), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B2", new Style(RMV_SHAPE, Style.rgb(255, 220, 1), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B4", new Style(RMV_SHAPE, Style.rgb(135, 84, 0), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B5", new Style(RMV_SHAPE, Style.rgb(244, 130, 51), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B6", new Style(RMV_SHAPE, Style.rgb(135, 62, 151), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B6S", new Style(RMV_SHAPE, Style.rgb(135, 62, 151), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B7", new Style(RMV_SHAPE, Style.rgb(0, 140, 208), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B8", new Style(RMV_SHAPE, Style.rgb(215, 12, 140), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B9", new Style(RMV_SHAPE, Style.rgb(239, 64, 34), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B10", new Style(RMV_SHAPE, Style.rgb(1, 90, 170), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B11", new Style(RMV_SHAPE, Style.rgb(119, 182, 228), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B12", new Style(RMV_SHAPE, Style.rgb(243, 144, 179), Style.WHITE));
        STYLES.put("Hanauer Straßenbahn GmbH|B20", new Style(RMV_SHAPE, Style.rgb(176, 13, 29), Style.WHITE));

        // busses Bad Homburg
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B1", new Style(RMV_SHAPE, Style.rgb(234, 47, 50), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B2", new Style(RMV_SHAPE, Style.rgb(30, 160, 100), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B3", new Style(RMV_SHAPE, Style.rgb(24, 105, 178), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B4", new Style(RMV_SHAPE, Style.rgb(240, 130, 54), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B5", new Style(RMV_SHAPE, Style.rgb(240, 233, 69), Style.BLACK));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B6", new Style(RMV_SHAPE, Style.rgb(65, 181, 107), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B7", new Style(RMV_SHAPE, Style.rgb(180, 49, 145), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B8", new Style(RMV_SHAPE, Style.rgb(36, 170, 225), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B11", new Style(RMV_SHAPE, Style.rgb(234, 47, 50), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B12", new Style(RMV_SHAPE, Style.rgb(30, 160, 100), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B17", new Style(RMV_SHAPE, Style.rgb(180, 49, 145), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B21", new Style(RMV_SHAPE, Style.rgb(244, 124, 39), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B22", new Style(RMV_SHAPE, Style.rgb(30, 160, 100), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B23", new Style(RMV_SHAPE, Style.rgb(247, 235, 47), Style.BLACK));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B24", new Style(RMV_SHAPE, Style.rgb(0, 177, 176), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B26", new Style(RMV_SHAPE, Style.rgb(90, 87, 165), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B27", new Style(RMV_SHAPE, Style.rgb(180, 49, 145), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B31", new Style(RMV_SHAPE, Style.rgb(254, 202, 38), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B32", new Style(RMV_SHAPE, Style.rgb(144, 199, 61), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B33", new Style(RMV_SHAPE, Style.rgb(198, 150, 38), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B34", new Style(RMV_SHAPE, Style.rgb(72, 140, 202), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B35", new Style(RMV_SHAPE, Style.rgb(208, 31, 38), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B36", new Style(RMV_SHAPE, Style.rgb(213, 113, 173), Style.WHITE));
        STYLES.put("Magistrat der Stadt Bad Homburg v.d.H.|B39", new Style(RMV_SHAPE, Style.rgb(41, 196, 243), Style.WHITE));

        // busses Rüsselsheim
        STYLES.put("Stadtwerke Rüsselsheim|B1", new Style(RMV_SHAPE, Style.rgb(249, 168, 54), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B6", new Style(RMV_SHAPE, Style.rgb(99, 45, 143), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B11", new Style(RMV_SHAPE, Style.rgb(84, 180, 76), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B31", new Style(RMV_SHAPE, Style.rgb(255, 240, 3), Style.BLACK));
        STYLES.put("Stadtwerke Rüsselsheim|B32", new Style(RMV_SHAPE, Style.rgb(255, 247, 172), Style.BLACK));
        STYLES.put("Stadtwerke Rüsselsheim|B41", new Style(RMV_SHAPE, Style.rgb(33, 192, 235), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B42", new Style(RMV_SHAPE, Style.rgb(0, 139, 205), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B51", new Style(RMV_SHAPE, Style.rgb(215, 46, 108), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B52", new Style(RMV_SHAPE, Style.rgb(132, 11, 52), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B70", new Style(RMV_SHAPE, Style.rgb(0, 112, 112), Style.WHITE));
        STYLES.put("Stadtwerke Rüsselsheim|B71", new Style(RMV_SHAPE, Style.rgb(241, 89, 33), Style.WHITE));

        // busses Offenbach
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B101", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B102", new Style(RMV_SHAPE, Style.rgb(46, 48, 146), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B103", new Style(RMV_SHAPE, Style.rgb(0, 165, 79), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B104", new Style(RMV_SHAPE, Style.rgb(242, 194, 9), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B105", new Style(RMV_SHAPE, Style.rgb(161, 208, 118), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B106", new Style(RMV_SHAPE, Style.rgb(144, 38, 143), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B107", new Style(RMV_SHAPE, Style.rgb(0, 174, 239), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B108", new Style(RMV_SHAPE, Style.rgb(239, 89, 161), Style.WHITE));
        STYLES.put("Offenbacher Verkehrsbetriebe GmbH|B120", new Style(RMV_SHAPE, Style.rgb(193, 104, 85), Style.WHITE));

        // trams Darmstadt
        STYLES.put("HEAG Mobilo|T1", new Style(RMV_SHAPE, Style.rgb(236, 123, 143), Style.WHITE));
        STYLES.put("HEAG Mobilo|T2", new Style(RMV_SHAPE, Style.rgb(22, 159, 78), Style.WHITE));
        STYLES.put("HEAG Mobilo|T3", new Style(RMV_SHAPE, Style.rgb(246, 192, 39), Style.WHITE));
        STYLES.put("HEAG Mobilo|T4", new Style(RMV_SHAPE, Style.rgb(16, 116, 188), Style.WHITE));
        STYLES.put("HEAG Mobilo|T5", new Style(RMV_SHAPE, Style.rgb(28, 169, 225), Style.WHITE));
        STYLES.put("HEAG Mobilo|T6", new Style(RMV_SHAPE, Style.rgb(237, 126, 41), Style.WHITE));
        STYLES.put("HEAG Mobilo|T7", new Style(RMV_SHAPE, Style.rgb(237, 2, 140), Style.WHITE));
        STYLES.put("HEAG Mobilo|T8", new Style(RMV_SHAPE, Style.rgb(223, 42, 41), Style.WHITE));
        STYLES.put("HEAG Mobilo|T9", new Style(RMV_SHAPE, Style.rgb(127, 195, 70), Style.WHITE));
        STYLES.put("HEAG Mobilo|T10", new Style(RMV_SHAPE, Style.rgb(147, 149, 149), Style.WHITE));

        // busses Darmstadt
        STYLES.put("HEAG mobiBus GmbH + Co. KG|B3A", new Style(RMV_SHAPE, Style.rgb(241, 88, 33), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BA", new Style(RMV_SHAPE, Style.rgb(167, 122, 77), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BAH", new Style(RMV_SHAPE, Style.rgb(95, 56, 18), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BAIR", new Style(RMV_SHAPE, Style.rgb(63, 145, 154), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BB", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BBE1", new Style(RMV_SHAPE, Style.rgb(44, 53, 138), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BBE2", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BBE3", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BDG", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BEB", new Style(RMV_SHAPE, Style.rgb(139, 198, 63), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BF", new Style(RMV_SHAPE, Style.rgb(192, 31, 46), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BFM", new Style(RMV_SHAPE, Style.rgb(192, 31, 46), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BG", new Style(RMV_SHAPE, Style.rgb(251, 177, 63), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BH", new Style(RMV_SHAPE, Style.rgb(100, 46, 143), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BK", new Style(RMV_SHAPE, Style.rgb(18, 118, 189), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BL", new Style(RMV_SHAPE, Style.rgb(232, 132, 181), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BM1", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BM2", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BM3", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BMX", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BO", new Style(RMV_SHAPE, Style.rgb(159, 25, 96), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BP", new Style(RMV_SHAPE, Style.rgb(246, 147, 35), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BPE", new Style(RMV_SHAPE, Style.rgb(246, 147, 35), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BPG", new Style(RMV_SHAPE, Style.rgb(246, 147, 35), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BR", new Style(RMV_SHAPE, Style.rgb(59, 181, 74), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BWE1", new Style(RMV_SHAPE, Style.rgb(98, 112, 141), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BWE2", new Style(RMV_SHAPE, Style.rgb(98, 112, 141), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BWE3", new Style(RMV_SHAPE, Style.rgb(241, 90, 41), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BWE4", new Style(RMV_SHAPE, Style.rgb(241, 90, 41), Style.WHITE));
        STYLES.put("HEAG mobiBus GmbH + Co. KG|BWX-", new Style(RMV_SHAPE, Style.rgb(251, 177, 63), Style.WHITE));

        // busses Wiesbaden
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B1", new Style(RMV_SHAPE, Style.rgb(238, 114, 3), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B3", new Style(RMV_SHAPE, Style.rgb(139, 85, 147), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B4", new Style(RMV_SHAPE, Style.rgb(47, 172, 102), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B5", new Style(RMV_SHAPE, Style.rgb(228, 3, 46), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B6", new Style(RMV_SHAPE, Style.rgb(235, 94, 87), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B8", new Style(RMV_SHAPE, Style.rgb(238, 114, 3), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B9", new Style(RMV_SHAPE, Style.rgb(71, 126, 192), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B14", new Style(RMV_SHAPE, Style.rgb(47, 172, 102), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B15", new Style(RMV_SHAPE, Style.rgb(228, 3, 46), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B16", new Style(RMV_SHAPE, Style.rgb(243, 154, 139), Style.BLACK));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B17", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B18", new Style(RMV_SHAPE, Style.rgb(140, 157, 166), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B20", new Style(RMV_SHAPE, Style.rgb(228, 3, 46), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B21", new Style(RMV_SHAPE, Style.rgb(0, 150, 65), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B22", new Style(RMV_SHAPE, Style.rgb(0, 150, 65), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B23", new Style(RMV_SHAPE, Style.rgb(0, 93, 169), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B24", new Style(RMV_SHAPE, Style.rgb(0, 93, 169), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B27", new Style(RMV_SHAPE, Style.rgb(184, 14, 128), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B28", new Style(RMV_SHAPE, Style.rgb(0, 182, 237), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B33", new Style(RMV_SHAPE, Style.rgb(139, 85, 147), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B34", new Style(RMV_SHAPE, Style.rgb(145, 141, 190), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B37", new Style(RMV_SHAPE, Style.rgb(40, 53, 131), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B38", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B39", new Style(RMV_SHAPE, Style.rgb(244, 149, 74), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B44", new Style(RMV_SHAPE, Style.rgb(71, 126, 192), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B45", new Style(RMV_SHAPE, Style.rgb(203, 206, 0), Style.BLACK));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B46", new Style(RMV_SHAPE, Style.rgb(199, 92, 159), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B47", new Style(RMV_SHAPE, Style.rgb(107, 124, 133), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B48", new Style(RMV_SHAPE, Style.rgb(71, 126, 192), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B49", new Style(RMV_SHAPE, Style.rgb(142, 200, 154), Style.BLACK));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|B74", new Style(RMV_SHAPE, Style.rgb(230, 0, 125), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN2", new Style(RMV_SHAPE, Style.rgb(0, 93, 169), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN3", new Style(RMV_SHAPE, Style.rgb(184, 14, 128), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN4", new Style(RMV_SHAPE, Style.rgb(111, 188, 133), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN5", new Style(RMV_SHAPE, Style.rgb(0, 150, 65), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN7", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN9", new Style(RMV_SHAPE, Style.rgb(200, 104, 166), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN10", new Style(RMV_SHAPE, Style.rgb(251, 186, 0), Style.BLACK));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN11", new Style(RMV_SHAPE, Style.rgb(206, 126, 0), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN12", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BN13", new Style(RMV_SHAPE, Style.rgb(0, 0, 0), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BE46", new Style(RMV_SHAPE, Style.rgb(199, 92, 159), Style.WHITE));
        STYLES.put("ESWE Verkehrsgesellschaft mbH|BE47", new Style(RMV_SHAPE, Style.rgb(107, 124, 133), Style.WHITE));

        // busses Wetzlar
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B10", new Style(RMV_SHAPE, Style.rgb(247, 147, 29), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B11", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B12", new Style(RMV_SHAPE, Style.rgb(46, 48, 146), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B13", new Style(RMV_SHAPE, Style.rgb(46, 48, 146), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B14", new Style(RMV_SHAPE, Style.rgb(143, 62, 151), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B15", new Style(RMV_SHAPE, Style.rgb(0, 174, 239), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B16", new Style(RMV_SHAPE, Style.rgb(155, 125, 38), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B17", new Style(RMV_SHAPE, Style.rgb(237, 2, 140), Style.WHITE));
        STYLES.put("Wetzlarer Verkehrsbetriebe und Reisebüro GmbH|B18", new Style(RMV_SHAPE, Style.rgb(237, 2, 140), Style.WHITE));

        // busses Mainz
        STYLES.put("Mainzer Mobilität|B6", new Style(RMV_SHAPE, Style.rgb(239, 125, 0), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B9", new Style(RMV_SHAPE, Style.rgb(0, 110, 137), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B28", new Style(RMV_SHAPE, Style.rgb(219, 226, 131), Style.BLACK));
        STYLES.put("Mainzer Mobilität|B33", new Style(RMV_SHAPE, Style.rgb(0, 143, 207), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B52", new Style(RMV_SHAPE, Style.rgb(124, 123, 123), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B54", new Style(RMV_SHAPE, Style.rgb(64, 137, 39), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B55", new Style(RMV_SHAPE, Style.rgb(64, 137, 39), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B56", new Style(RMV_SHAPE, Style.rgb(149, 193, 31), Style.BLACK));
        STYLES.put("Mainzer Mobilität|B57", new Style(RMV_SHAPE, Style.rgb(0, 117, 235), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B58", new Style(RMV_SHAPE, Style.rgb(149, 193, 31), Style.BLACK));
        STYLES.put("Mainzer Mobilität|B60", new Style(RMV_SHAPE, Style.rgb(0, 175, 203), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B62", new Style(RMV_SHAPE, Style.rgb(194, 46, 12), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B63", new Style(RMV_SHAPE, Style.rgb(0, 175, 203), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B64", new Style(RMV_SHAPE, Style.rgb(245, 156, 0), Style.BLACK));
        STYLES.put("Mainzer Mobilität|B65", new Style(RMV_SHAPE, Style.rgb(245, 156, 0), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B66", new Style(RMV_SHAPE, Style.rgb(255, 213, 0), Style.BLACK));
        STYLES.put("Mainzer Mobilität|B67", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B68", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B69", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B70", new Style(RMV_SHAPE, Style.rgb(167, 27, 113), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B71", new Style(RMV_SHAPE, Style.rgb(167, 27, 113), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B74", new Style(RMV_SHAPE, Style.rgb(230, 0, 125), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B75", new Style(RMV_SHAPE, Style.rgb(195, 159, 0), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B76", new Style(RMV_SHAPE, Style.rgb(138, 16, 2), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B78", new Style(RMV_SHAPE, Style.rgb(18, 74, 115), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B79", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B80", new Style(RMV_SHAPE, Style.rgb(0, 97, 167), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B81", new Style(RMV_SHAPE, Style.rgb(0, 97, 167), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B90", new Style(RMV_SHAPE, Style.rgb(141, 0, 76), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B91", new Style(RMV_SHAPE, Style.rgb(145, 79, 0), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B92", new Style(RMV_SHAPE, Style.rgb(0, 109, 143), Style.WHITE));
        STYLES.put("Mainzer Mobilität|B93", new Style(RMV_SHAPE, Style.rgb(91, 120, 19), Style.WHITE));
        STYLES.put("Mainzer Mobilität|BN7", new Style(RMV_SHAPE, Style.rgb(0, 81, 37), Style.WHITE));

        // tram Mainz
        STYLES.put("Mainzer Mobilität|T50", new Style(RMV_SHAPE, Style.rgb(0, 0, 0), Style.WHITE));
        STYLES.put("Mainzer Mobilität|T51", new Style(RMV_SHAPE, Style.rgb(74, 74, 73), Style.WHITE));
        STYLES.put("Mainzer Mobilität|T52", new Style(RMV_SHAPE, Style.rgb(124, 123, 123), Style.WHITE));
        STYLES.put("Mainzer Mobilität|T53", new Style(RMV_SHAPE, Style.rgb(100, 99, 99), Style.WHITE));
        STYLES.put("Mainzer Mobilität|T59", new Style(RMV_SHAPE, Style.rgb(146, 146, 146), Style.WHITE));

        // busses Gießen (1)
        STYLES.put("Stadtwerke Gießen|B1", new Style(RMV_SHAPE, Style.rgb(214, 1, 127), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B2", new Style(RMV_SHAPE, Style.rgb(133, 189, 59), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B3", new Style(RMV_SHAPE, Style.rgb(0, 130, 195), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B5", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B6", new Style(RMV_SHAPE, Style.rgb(245, 230, 1), Style.BLACK));
        STYLES.put("Stadtwerke Gießen|B7", new Style(RMV_SHAPE, Style.rgb(0, 165, 79), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B9", new Style(RMV_SHAPE, Style.rgb(132, 31, 131), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B10", new Style(RMV_SHAPE, Style.rgb(150, 46, 51), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B12", new Style(RMV_SHAPE, Style.rgb(254, 194, 16), Style.BLACK));
        STYLES.put("Stadtwerke Gießen|B13", new Style(RMV_SHAPE, Style.rgb(147, 127, 58), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B15", new Style(RMV_SHAPE, Style.rgb(111, 182, 223), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B17", new Style(RMV_SHAPE, Style.rgb(35, 31, 32), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B18", new Style(RMV_SHAPE, Style.rgb(242, 126, 148), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B801", new Style(RMV_SHAPE, Style.rgb(1, 112, 126), Style.WHITE));
        STYLES.put("Stadtwerke Gießen|B802", new Style(RMV_SHAPE, Style.rgb(247, 147, 29), Style.WHITE));

        // busses Gießen (2)
        STYLES.put("MIT.BUS|B1", new Style(RMV_SHAPE, Style.rgb(214, 1, 127), Style.WHITE));
        STYLES.put("MIT.BUS|B2", new Style(RMV_SHAPE, Style.rgb(133, 189, 59), Style.WHITE));
        STYLES.put("MIT.BUS|B3", new Style(RMV_SHAPE, Style.rgb(0, 130, 195), Style.WHITE));
        STYLES.put("MIT.BUS|B5", new Style(RMV_SHAPE, Style.rgb(238, 29, 35), Style.WHITE));
        STYLES.put("MIT.BUS|B7", new Style(RMV_SHAPE, Style.rgb(0, 165, 79), Style.WHITE));
        STYLES.put("MIT.BUS|B9", new Style(RMV_SHAPE, Style.rgb(132, 31, 131), Style.WHITE));
        STYLES.put("MIT.BUS|B10", new Style(RMV_SHAPE, Style.rgb(150, 46, 51), Style.WHITE));
        STYLES.put("MIT.BUS|B12", new Style(RMV_SHAPE, Style.rgb(254, 194, 16), Style.BLACK));
        STYLES.put("MIT.BUS|B13", new Style(RMV_SHAPE, Style.rgb(147, 127, 58), Style.WHITE));
        STYLES.put("MIT.BUS|B14", new Style(RMV_SHAPE, Style.rgb(245, 230, 1), Style.BLACK));
        STYLES.put("MIT.BUS|B15", new Style(RMV_SHAPE, Style.rgb(111, 182, 223), Style.WHITE));
        STYLES.put("MIT.BUS|B17", new Style(RMV_SHAPE, Style.rgb(35, 31, 32), Style.WHITE));
        STYLES.put("MIT.BUS|B18", new Style(RMV_SHAPE, Style.rgb(242, 126, 148), Style.WHITE));
        STYLES.put("MIT.BUS|B801", new Style(RMV_SHAPE, Style.rgb(1, 112, 126), Style.WHITE));
        STYLES.put("MIT.BUS|B802", new Style(RMV_SHAPE, Style.rgb(247, 147, 29), Style.WHITE));

        // busses Rüsselsheim
        STYLES.put("Rüsselsheimer Mobilität|B1", new Style(RMV_SHAPE, Style.rgb(247, 147, 29), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B2", new Style(RMV_SHAPE, Style.rgb(0, 165, 79), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B3a", new Style(RMV_SHAPE, Style.rgb(235, 219, 1), Style.BLACK));
        STYLES.put("Rüsselsheimer Mobilität|B3b", new Style(RMV_SHAPE, Style.rgb(235, 219, 1), Style.BLACK));
        STYLES.put("Rüsselsheimer Mobilität|B4a", new Style(RMV_SHAPE, Style.rgb(0, 114, 188), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B4b", new Style(RMV_SHAPE, Style.rgb(0, 114, 188), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B5a", new Style(RMV_SHAPE, Style.rgb(190, 29, 44), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B5b", new Style(RMV_SHAPE, Style.rgb(190, 29, 44), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B6", new Style(RMV_SHAPE, Style.rgb(101, 45, 145), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B7a", new Style(RMV_SHAPE, Style.rgb(0, 174, 239), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B7b", new Style(RMV_SHAPE, Style.rgb(0, 174, 239), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B8a", new Style(RMV_SHAPE, Style.rgb(237, 2, 140), Style.WHITE));
        STYLES.put("Rüsselsheimer Mobilität|B8b", new Style(RMV_SHAPE, Style.rgb(237, 2, 140), Style.WHITE));

        // busses Offenbach
        STYLES.put("BOF-32", new Style(RMV_SHAPE, Style.rgb(208, 118, 39), Style.WHITE));
        STYLES.put("BOF-40", new Style(RMV_SHAPE, Style.rgb(30, 155, 215), Style.WHITE));
        STYLES.put("BOF-51", new Style(RMV_SHAPE, Style.rgb(64, 193, 239), Style.WHITE));
        STYLES.put("BOF-52", new Style(RMV_SHAPE, Style.rgb(139, 198, 62), Style.WHITE));
        STYLES.put("BOF-53", new Style(RMV_SHAPE, Style.rgb(16, 112, 185), Style.WHITE));
        STYLES.put("BOF-64", new Style(RMV_SHAPE, Style.rgb(25, 98, 174), Style.WHITE));
        STYLES.put("BOF-65", new Style(RMV_SHAPE, Style.rgb(242, 158, 197), Style.WHITE));
        STYLES.put("BOF-67", new Style(RMV_SHAPE, Style.rgb(21, 173, 186), Style.WHITE));
        STYLES.put("BOF-71", new Style(RMV_SHAPE, Style.rgb(110, 203, 243), Style.WHITE));
        STYLES.put("BOF-72", new Style(RMV_SHAPE, Style.rgb(32, 67, 144), Style.WHITE));
        STYLES.put("BOF-73", new Style(RMV_SHAPE, Style.rgb(84, 166, 214), Style.WHITE));
        STYLES.put("BOF-75", new Style(RMV_SHAPE, Style.rgb(17, 134, 162), Style.WHITE));
        STYLES.put("BOF-85", new Style(RMV_SHAPE, Style.rgb(158, 96, 165), Style.WHITE));
        STYLES.put("BOF-86", new Style(RMV_SHAPE, Style.rgb(142, 140, 196), Style.WHITE));
        STYLES.put("BOF-87", new Style(RMV_SHAPE, Style.rgb(231, 111, 161), Style.WHITE));
        STYLES.put("BOF-91", new Style(RMV_SHAPE, Style.rgb(235, 94, 159), Style.WHITE));
        STYLES.put("BOF-92", new Style(RMV_SHAPE, Style.rgb(67, 168, 66), Style.WHITE));
        STYLES.put("BOF-95", new Style(RMV_SHAPE, Style.rgb(100, 43, 131), Style.WHITE));
        STYLES.put("BOF-96", new Style(RMV_SHAPE, Style.rgb(230, 7, 124), Style.WHITE));
        STYLES.put("BOF-97", new Style(RMV_SHAPE, Style.rgb(160, 53, 125), Style.WHITE));
        STYLES.put("BOF-99", new Style(RMV_SHAPE, Style.rgb(231, 47, 36), Style.WHITE));

        // busses Rheingau-Taunus
        STYLES.put("HLB Bus GmbH|B81", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("Martin Becker GmbH|B170", new Style(RMV_SHAPE, Style.rgb(237, 109, 150), Style.WHITE));
        STYLES.put("ALV Oberhessen|B171", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("Martin Becker GmbH|B172", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("Martin Becker GmbH|B175", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("ALV Oberhessen|B181", new Style(RMV_SHAPE, Style.rgb(241, 135, 0), Style.WHITE));
        STYLES.put("ALV Oberhessen|B182", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("ALV Oberhessen|B183", new Style(RMV_SHAPE, Style.rgb(82, 78, 151), Style.WHITE));
        STYLES.put("ALV Oberhessen|B185", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("ALV Oberhessen|B187", new Style(RMV_SHAPE, Style.rgb(241, 135, 0), Style.WHITE));
        STYLES.put("ALV Oberhessen|B191", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("-ALV Oberhessen|B192", new Style(RMV_SHAPE, Style.rgb(241, 135, 0), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B201", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("FahrPlan Verkehrsgesellschaft mbH|B201", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("-Engelhardt Omnibusbetrieb GmbH|B202", new Style(RMV_SHAPE, Style.rgb(85, 169, 50), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B203", new Style(RMV_SHAPE, Style.rgb(82, 78, 151), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B204", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B205", new Style(RMV_SHAPE, Style.rgb(149, 96, 149), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B207", new Style(RMV_SHAPE, Style.rgb(234, 81, 109), Style.WHITE));
        STYLES.put("FahrPlan Verkehrsgesellschaft mbH|B207", new Style(RMV_SHAPE, Style.rgb(234, 81, 109), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B208", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B211", new Style(RMV_SHAPE, Style.rgb(237, 109, 150), Style.WHITE));
        STYLES.put("FahrPlan Verkehrsgesellschaft mbH|B211", new Style(RMV_SHAPE, Style.rgb(237, 109, 150), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B212", new Style(RMV_SHAPE, Style.rgb(234, 91, 11), Style.WHITE));
        STYLES.put("FahrPlan Verkehrsgesellschaft mbH|B212", new Style(RMV_SHAPE, Style.rgb(234, 91, 11), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B218", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B220", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B221", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B222", new Style(RMV_SHAPE, Style.rgb(237, 105, 75), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B223", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B224", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B225", new Style(RMV_SHAPE, Style.rgb(82, 78, 151), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B226", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("DB Regio Bus Südwest GmbH|B226", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B229", new Style(RMV_SHAPE, Style.rgb(149, 193, 31), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B230", new Style(RMV_SHAPE, Style.rgb(207, 149, 194), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B231", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B232", new Style(RMV_SHAPE, Style.rgb(237, 109, 150), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B233", new Style(RMV_SHAPE, Style.rgb(82, 78, 151), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B234", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B235", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B240", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B242", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("FahrPlan Verkehrsgesellschaft mbH|B242", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B243", new Style(RMV_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B245", new Style(RMV_SHAPE, Style.rgb(241, 135, 0), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B246", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("FahrPlan Verkehrsgesellschaft mbH|B246", new Style(RMV_SHAPE, Style.rgb(93, 179, 174), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B247", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B248", new Style(RMV_SHAPE, Style.rgb(204, 105, 166), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B250", new Style(RMV_SHAPE, Style.rgb(29, 113, 184), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B265", new Style(RMV_SHAPE, Style.rgb(251, 186, 0), Style.WHITE));
        STYLES.put("WEFRA-Bus GbR|B269", new Style(RMV_SHAPE, Style.rgb(241, 135, 0), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B270", new Style(RMV_SHAPE, Style.rgb(237, 109, 150), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B270", new Style(RMV_SHAPE, Style.rgb(237, 109, 150), Style.WHITE));
        STYLES.put("WEFRA-Bus GbR|B271", new Style(RMV_SHAPE, Style.rgb(0, 159, 227), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B272", new Style(RMV_SHAPE, Style.rgb(206, 128, 180), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B273", new Style(RMV_SHAPE, Style.rgb(29, 113, 184), Style.WHITE));
        STYLES.put("DB Regio Bus Mitte|B274", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("Engelhardt Omnibusbetrieb GmbH|B275", new Style(RMV_SHAPE, Style.rgb(167, 22, 129), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B277", new Style(RMV_SHAPE, Style.rgb(0, 128, 61), Style.WHITE));
        STYLES.put("ESE Verkehrsgesellschaft mbH|B278", new Style(RMV_SHAPE, Style.rgb(131, 208, 245), Style.WHITE));
    }
}
