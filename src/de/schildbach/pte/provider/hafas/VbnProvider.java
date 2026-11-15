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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.Standard;
import de.schildbach.pte.dto.Fare;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Line.Attr;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Style.Shape;

import okhttp3.HttpUrl;

/**
 * Provider implementation for the Verkehrsverbund Bremen/Niedersachsen (Lower Saxony, Germany).
 * 
 * @author Andreas Schildbach
 */
public class VbnProvider extends AbstractHafasClientInterfaceProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://fahrplaner.vbn.de/");
    private static final Product[] PRODUCTS_MAP = {
            Product.HIGH_SPEED_TRAIN, // ICE
            Product.HIGH_SPEED_TRAIN, // IC / EC / CNL
            Product.HIGH_SPEED_TRAIN, // IR, sonstiger Schnellzug
            Product.REGIONAL_TRAIN, // Nahverkehr

            Product.SUBURBAN_TRAIN, // S-Bahn
            Product.BUS, // Bus
            Product.FERRY, // Schiff
            Product.SUBWAY, // U-Bahn

            Product.TRAM, // Straßen-/ Stadtbahn
            Product.ON_DEMAND // Anrufverkehr
    };
    private static final String AND_API_CLIENT = "{\"id\":\"VBN\",\"type\":\"AND\"}";
    private static final String WEB_API_CLIENT = "{\"id\":\"VBN\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
    private static final String WEBAPP_CONFIG_URL = "https://fahrplaner.vbn.de/fahrplan/config/webapp.config.json";

    public VbnProvider() {
        this(WEB_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public VbnProvider(final String apiAuthorization) {
        this(AND_API_CLIENT, apiAuthorization);
    }

    public VbnProvider(final String apiClient, final String apiAuthorization) {
        this(NetworkId.VBN, apiClient, apiAuthorization);
    }

    protected VbnProvider(final NetworkId networkId, final String apiClient, final String apiAuthorization) {
        super(networkId, API_BASE, PRODUCTS_MAP);
        setApiEndpoint("gate");
        setApiVersion("1.66");
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
        setStyles(STYLES);
    }

    @Override
    protected boolean isStationBoardDestinationCommonlyDirection() {
        return false;
    }

//    private static final String[] PLACES = { "Bremen", "Bremerhaven", "Hamburg", "Oldenburg(Oldb)", "Osnabrück",
//            "Göttingen", "Rostock", "Warnemünde" };
//
//    @Override
//    protected String[] splitStationName(final String name) {
//        for (final String place : PLACES) {
//            if (name.startsWith(place + " ") || name.startsWith(place + "-"))
//                return new String[] { place, name.substring(place.length() + 1) };
//        }
//
//        return super.splitStationName(name);
//    }

    // list all places, which contain at least one space
    // except: places with "Bad "-prefix and no further spaces
    private static final String[] SPECIAL_PLACES = new String[]{
            "Groß Schwaß"
    };

    @Override
    protected String[] splitDirectionName(final String name, @Nullable final Line line) {
        return noPlaceStationName(name);
    }

    @Override
    protected String[] splitStationName(final String placeAndName) {
        return parseSpaceDelimitedPlaceAndStation(placeAndName, SPECIAL_PLACES);
    }

    @Override
    protected String[] splitAddress(final String address) {
        final Matcher m = P_SPLIT_NAME_FIRST_COMMA.matcher(address);
        if (m.matches())
            return new String[] { m.group(1), m.group(2) };

        return super.splitAddress(address);
    }

    @Override
    protected boolean hideFare(Fare fare) {
        final String fareNameLc = fare.name.toLowerCase(Locale.US);
        if (fareNameLc.contains("2 adults"))
            return true;
        if (fareNameLc.contains("3 adults"))
            return true;
        if (fareNameLc.contains("4 adults"))
            return true;
        if (fareNameLc.contains("5 adults"))
            return true;
        return super.hideFare(fare);
    }

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL_INCLUDING_HIGHSPEED;
    }

    private static final Set<Attr> ATTRS_SERVICE_REPLACEMENT_CIRCLE_CLOCKWISE =
            Stream.of(Attr.SERVICE_REPLACEMENT, Attr.CIRCLE_CLOCKWISE).collect(Collectors.toSet());
    private static final Set<Attr> ATTRS_SERVICE_REPLACEMENT_CIRCLE_ANTICLOCKWISE =
            Stream.of(Attr.SERVICE_REPLACEMENT, Attr.CIRCLE_ANTICLOCKWISE).collect(Collectors.toSet());

    @Override
    protected Line newLine(final String id, final String operator, final Product product, final @Nullable String name,
            final @Nullable String shortName, final @Nullable String number, final @Nullable String addName, final Style style) {
        final Line line = super.newLine(id, operator, product, name, shortName, number, addName, style);

        if (line.product == Product.BUS) {
            if ("57".equals(line.label))
                return new Line(network, id, line.operator, line.product, line.label, line.name, line.style,
                        ATTRS_SERVICE_REPLACEMENT_CIRCLE_CLOCKWISE, line.message);
            if ("58".equals(line.label))
                return new Line(network, id, line.operator, line.product, line.label, line.name, line.style,
                        ATTRS_SERVICE_REPLACEMENT_CIRCLE_ANTICLOCKWISE, line.message);
        }

        return line;
    }

    private static final Style.Shape VBN_SHAPE = Style.Shape.ROUNDED;

    private static final Map<String, Style> STYLES = new HashMap<>();

    static {
        STYLES.put(Character.toString(Product.HIGH_SPEED_TRAIN.code), new Style(VBN_SHAPE, Style.rgb(150, 25, 29), Style.WHITE));
        STYLES.put(Character.toString(Product.REGIONAL_TRAIN.code), new Style(VBN_SHAPE, Style.rgb(82, 38, 125), Style.WHITE));
        STYLES.put(Character.toString(Product.SUBURBAN_TRAIN.code), new Style(VBN_SHAPE, Style.rgb(19, 148, 69), Style.WHITE));
        STYLES.put(Character.toString(Product.SUBWAY.code), new Style(VBN_SHAPE, Style.rgb(32, 53, 99), Style.WHITE));
        STYLES.put(Character.toString(Product.TRAM.code), new Style(VBN_SHAPE, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put(Character.toString(Product.BUS.code), new Style(Style.rgb(0, 92, 169), Style.WHITE));
        STYLES.put(Character.toString(Product.ON_DEMAND.code), new Style(Style.rgb(255, 204, 20), Style.WHITE));
        STYLES.put(Character.toString(Product.FERRY.code), new Style(VBN_SHAPE, Style.rgb(54, 169, 225), Style.WHITE));
        STYLES.put(Character.toString(Product.REPLACEMENT_SERVICE.code), new Style(Standard.COLOR_BACKGROUND_REPLACEMENT_SERVICE, Style.WHITE));

        STYLES.put(null, new Style(Style.DKGRAY, Style.WHITE));

        // Bremen
        STYLES.put("Bremer Straßenbahn AG|T1", new Style(Shape.RECT, Style.parseColor("#129640"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|T1S", new Style(Shape.RECT, Style.WHITE, Style.parseColor("#129640")));
        STYLES.put("Bremer Straßenbahn AG|T2", new Style(Shape.RECT, Style.parseColor("#115CA8"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|T3", new Style(Shape.RECT, Style.parseColor("#2A9AD6"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|T4", new Style(Shape.RECT, Style.parseColor("#E30C15"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|T4S", new Style(Shape.RECT, Style.WHITE, Style.parseColor("#E30C15")));
        STYLES.put("Bremer Straßenbahn AG|T6", new Style(Shape.RECT, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|T8", new Style(Shape.RECT, Style.parseColor("#98C21E"), Style.WHITE));

        STYLES.put("Bremer Straßenbahn AG|B20", new Style(Shape.CIRCLE, Style.parseColor("#95C11F"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B21", new Style(Shape.CIRCLE, Style.parseColor("#009FE3"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B22", new Style(Shape.CIRCLE, Style.parseColor("#A69DCD"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B24", new Style(Shape.CIRCLE, Style.parseColor("#951B81"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B25", new Style(Shape.CIRCLE, Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B26", new Style(Shape.CIRCLE, Style.parseColor("#E30613"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B27", new Style(Shape.CIRCLE, Style.parseColor("#E30613"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B28", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B29", new Style(Shape.CIRCLE, Style.parseColor("#95C11F"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B31", new Style(Shape.CIRCLE, Style.parseColor("#95C11F"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B33", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B34", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B37", new Style(Shape.CIRCLE, Style.parseColor("#951B81"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B38", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B39", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B40", new Style(Shape.CIRCLE, Style.parseColor("#E30613"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B41", new Style(Shape.CIRCLE, Style.parseColor("#E30613"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B41S", new Style(Shape.CIRCLE, Style.WHITE, Style.parseColor("#E30613")));
        STYLES.put("Bremer Straßenbahn AG|B42", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B44", new Style(Shape.CIRCLE, Style.parseColor("#EF7D00"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B51", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B52", new Style(Shape.CIRCLE, Style.parseColor("#95C11F"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B53", new Style(Shape.CIRCLE, Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B55", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B57", new Style(Shape.CIRCLE, Style.parseColor("#EF7D00"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B58", new Style(Shape.CIRCLE, Style.parseColor("#EF7D00"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B61", new Style(Shape.CIRCLE, Style.parseColor("#95C11F"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B62", new Style(Shape.CIRCLE, Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B63", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B65", new Style(Shape.CIRCLE, Style.parseColor("#A8D3AF"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B66", new Style(Shape.CIRCLE, Style.parseColor("#A8D3AF"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B77", new Style(Shape.CIRCLE, Style.parseColor("#808080"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B80", new Style(Shape.CIRCLE, Style.parseColor("#A8D3AF"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B81", new Style(Shape.CIRCLE, Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B82", new Style(Shape.CIRCLE, Style.parseColor("#EF7D00"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B83", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B87", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B90", new Style(Shape.CIRCLE, Style.parseColor("#312783"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B91", new Style(Shape.CIRCLE, Style.parseColor("#009FE3"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B92", new Style(Shape.CIRCLE, Style.parseColor("#009FE3"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B93", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|B94", new Style(Shape.CIRCLE, Style.parseColor("#E30613"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B95", new Style(Shape.CIRCLE, Style.parseColor("#EF7D00"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B96", new Style(Shape.CIRCLE, Style.parseColor("#951B81"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B97", new Style(Shape.CIRCLE, Style.parseColor("#A6DCDD"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|B98", new Style(Shape.CIRCLE, Style.parseColor("#009640"), Style.WHITE));

        // Bremen Nachtverkehr
        STYLES.put("Bremer Straßenbahn AG|TN1", new Style(Shape.RECT, Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|TN3", new Style(Shape.RECT, Style.parseColor("#FB3099"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|TN4", new Style(Shape.RECT, Style.parseColor("#E30613"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|BN5", new Style(Shape.CIRCLE, Style.parseColor("#EF7D00"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|BN6", new Style(Shape.CIRCLE, Style.parseColor("#009FE3"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|BN7", new Style(Shape.CIRCLE, Style.parseColor("#95C11F"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|BN9", new Style(Shape.CIRCLE, Style.parseColor("#FFCC00"), Style.BLACK));
        STYLES.put("Bremer Straßenbahn AG|TN10", new Style(Shape.RECT, Style.parseColor("#0075BF"), Style.WHITE));
        STYLES.put("Bremer Straßenbahn AG|BN94", new Style(Shape.CIRCLE, Style.parseColor("#A69DCD"), Style.WHITE));

        // NordWestBahn - Regio-S-Bahn
        STYLES.put("NordWestBahn|SRS1", new Style(Style.parseColor("#214889"), Style.WHITE));
        STYLES.put("NordWestBahn|SRS2", new Style(Style.parseColor("#DB8F2D"), Style.WHITE));
        STYLES.put("NordWestBahn|SRS3", new Style(Style.parseColor("#A5C242"), Style.WHITE));
        STYLES.put("NordWestBahn|SRS4", new Style(Style.parseColor("#C4031E"), Style.WHITE));
    }
}
