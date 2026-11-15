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
import java.util.Set;
import java.util.regex.Matcher;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.Standard;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Style.Shape;

import okhttp3.HttpUrl;

/**
 * Provider implementation for the Verkehrsverbund Warnow Rostock.
 * see VBN using the same server URL
 */
public class VvwProvider extends AbstractHafasClientInterfaceProvider {
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
    private static final String WEB_API_CLIENT = "{\"id\":\"VBN\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_vvw\"}";
    private static final String WEBAPP_CONFIG_URL = "https://fahrplaner.vbn.de/fahrplan/config/webapp.config.json";

    @Override
    protected String getWebAppLocalizationId() {
        return "vs_vvw";
    }

    public VvwProvider() {
        this(WEB_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public VvwProvider(final String apiAuthorization) {
        this(AND_API_CLIENT, apiAuthorization);
    }

    public VvwProvider(final String apiClient, final String apiAuthorization) {
        this(NetworkId.VVW, apiClient, apiAuthorization);
    }

    protected VvwProvider(final NetworkId networkId, final String apiClient, final String apiAuthorization) {
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
    public Set<Product> defaultProducts() {
        return Product.ALL_INCLUDING_HIGHSPEED;
    }

    private static final Shape VVW_SHAPE = Shape.ROUNDED;

    private static final Map<String, Style> STYLES = new HashMap<>();

    static {
        STYLES.put(Character.toString(Product.HIGH_SPEED_TRAIN.code), new Style(VVW_SHAPE, Style.rgb(231, 50, 76), Style.WHITE));
        STYLES.put(Character.toString(Product.REGIONAL_TRAIN.code), new Style(VVW_SHAPE, Style.rgb(131, 29, 129), Style.WHITE));
        STYLES.put(Character.toString(Product.SUBURBAN_TRAIN.code), new Style(VVW_SHAPE, Style.rgb(19, 148, 69), Style.WHITE));
        STYLES.put(Character.toString(Product.SUBWAY.code), new Style(VVW_SHAPE, Style.rgb(32, 53, 99), Style.WHITE));
        STYLES.put(Character.toString(Product.TRAM.code), new Style(VVW_SHAPE, Style.rgb(227, 0, 15), Style.WHITE));
        STYLES.put(Character.toString(Product.BUS.code), new Style(Style.rgb(0, 103, 121), Style.WHITE));
        STYLES.put(Character.toString(Product.ON_DEMAND.code), new Style(Style.rgb(255, 204, 20), Style.WHITE));
        STYLES.put(Character.toString(Product.FERRY.code), new Style(VVW_SHAPE, Style.rgb(54, 169, 225), Style.WHITE));
        STYLES.put(Character.toString(Product.REPLACEMENT_SERVICE.code), new Style(Standard.COLOR_BACKGROUND_REPLACEMENT_SERVICE, Style.WHITE));

        STYLES.put(null, new Style(Style.DKGRAY, Style.WHITE));

        // Rostock
        STYLES.put("DB Regio AG|SS1", new Style(Style.parseColor("#009037"), Style.WHITE));
        STYLES.put("DB Regio AG|SS2", new Style(Style.parseColor("#009037"), Style.WHITE));
        STYLES.put("DB Regio AG|SS3", new Style(Style.parseColor("#009037"), Style.WHITE));

        STYLES.put("Rostocker Straßenbahn AG|T1", new Style(Shape.RECT, Style.parseColor("#712090"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|T2", new Style(Shape.RECT, Style.parseColor("#f47216"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|T3", new Style(Shape.RECT, Style.parseColor("#870e12"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|T4", new Style(Shape.RECT, Style.parseColor("#d136a3"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|T5", new Style(Shape.RECT, Style.parseColor("#ed1c24"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|T6", new Style(Shape.RECT, Style.parseColor("#fab20b"), Style.WHITE));

        STYLES.put("Rostocker Straßenbahn AG|B16", new Style(Style.parseColor("#3ca0e1"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B17", new Style(Style.parseColor("#6e87cd"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B18", new Style(Style.parseColor("#1e64b9"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B19", new Style(Style.parseColor("#0f82cd"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B22", new Style(Style.parseColor("#3ca0e1"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B23", new Style(Style.parseColor("#232369"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B25", new Style(Style.parseColor("#19417d"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B26", new Style(Style.parseColor("#50c8f5"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B28", new Style(Style.parseColor("#0f82cd"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|PALT 28A", new Style(Style.WHITE, Style.parseColor("#50c8f5")));
        STYLES.put("Rostocker Straßenbahn AG|B29", new Style(Style.parseColor("#3ca0e1"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|PALT 30A", new Style(Style.WHITE, Style.parseColor("#19417d")));
        STYLES.put("Rostocker Straßenbahn AG|B31", new Style(Style.parseColor("#232369"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B34", new Style(Style.parseColor("#1e64b9"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|PALT 34A", new Style(Style.WHITE, Style.parseColor("#1e64b9")));
        STYLES.put("Rostocker Straßenbahn AG|B35", new Style(Style.parseColor("#3ca0e1"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|PALT 35A", new Style(Style.WHITE, Style.parseColor("#3ca0e1")));
        STYLES.put("Rostocker Straßenbahn AG|B36", new Style(Style.parseColor("#1e64b9"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B37", new Style(Style.parseColor("#3ca0e1"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B38", new Style(Style.parseColor("#0f82cd"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B39", new Style(Style.parseColor("#6e87cd"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|BX41", new Style(Style.parseColor("#0a9b96"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|B45", new Style(Style.parseColor("#50c8f5"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|PALT 45A", new Style(Style.WHITE, Style.parseColor("#50c8f5")));
        STYLES.put("Rostocker Straßenbahn AG|B49", new Style(Style.parseColor("#19417d"), Style.WHITE));

        STYLES.put("Rostocker Straßenbahn AG|BF1", new Style(Style.parseColor("#787878"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|BF2", new Style(Style.parseColor("#787878"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|BF3", new Style(Style.parseColor("#787878"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|BF4", new Style(Style.parseColor("#787878"), Style.WHITE));
        STYLES.put("Rostocker Straßenbahn AG|PALT F4A", new Style(Style.WHITE, Style.parseColor("#787878")));

        STYLES.put("rebus Regionalbus Rostock GmbH|B101", new Style(Style.parseColor("#e30613"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B102", new Style(Style.parseColor("#2699d6"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B103", new Style(Style.parseColor("#d18f00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B104", new Style(Style.parseColor("#006f9e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B105", new Style(Style.parseColor("#c2a712"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B106", new Style(Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B107", new Style(Style.parseColor("#a62341"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B108", new Style(Style.parseColor("#009fe3"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B109", new Style(Style.parseColor("#aa7fa6"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B110", new Style(Style.parseColor("#95c11f"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B111", new Style(Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B112", new Style(Style.parseColor("#e50069"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B113", new Style(Style.parseColor("#935b00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B114", new Style(Style.parseColor("#935b00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B115", new Style(Style.parseColor("#74b959"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B116", new Style(Style.parseColor("#0085ac"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B118", new Style(Style.parseColor("#f9b000"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B119", new Style(Style.parseColor("#055da9"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B120", new Style(Style.parseColor("#74b959"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B121", new Style(Style.parseColor("#e63323"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B122", new Style(Style.parseColor("#009870"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B123", new Style(Style.parseColor("#f39200"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B124", new Style(Style.parseColor("#9dc41a"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B125", new Style(Style.parseColor("#935b00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B128", new Style(Style.parseColor("#7263a9"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B129", new Style(Style.parseColor("#e6007e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B130", new Style(Style.parseColor("#fcba63"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B131", new Style(Style.parseColor("#0075bf"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B132", new Style(Style.parseColor("#ef7d00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B134", new Style(Style.parseColor("#008e5c"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B135", new Style(Style.parseColor("#e30613"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B136", new Style(Style.parseColor("#aa7fa6"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B137", new Style(Style.parseColor("#ef7c00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B139", new Style(Style.parseColor("#f8ac00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B140", new Style(Style.parseColor("#c2a712"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B200", new Style(Style.parseColor("#e6007e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B201", new Style(Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B202", new Style(Style.parseColor("#d9181b"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B203", new Style(Style.parseColor("#f59c00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B204", new Style(Style.parseColor("#b3cf3b"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B205", new Style(Style.parseColor("#dd6ca7"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B206", new Style(Style.parseColor("#3bade8"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B207", new Style(Style.parseColor("#123274"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B208", new Style(Style.parseColor("#9dc41a"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B210", new Style(Style.parseColor("#e30613"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B211", new Style(Style.parseColor("#95c11f"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B213", new Style(Style.parseColor("#a877b2"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B215", new Style(Style.parseColor("#009fe3"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B216", new Style(Style.parseColor("#935b00"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B220", new Style(Style.parseColor("#0090d7"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B221", new Style(Style.parseColor("#009640"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B222", new Style(Style.parseColor("#f088b6"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B223", new Style(Style.parseColor("#f9b000"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B224", new Style(Style.parseColor("#004f9f"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B225", new Style(Style.parseColor("#7263a9"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B230", new Style(Style.parseColor("#005ca9"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B231", new Style(Style.parseColor("#00853e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B232", new Style(Style.parseColor("#e30613"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B233", new Style(Style.parseColor("#123274"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B235", new Style(Style.parseColor("#ba0066"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B240", new Style(Style.parseColor("#7263a9"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B241", new Style(Style.parseColor("#ea5297"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B244", new Style(Style.parseColor("#f7ab59"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B245", new Style(Style.parseColor("#76b82a"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B246", new Style(Style.parseColor("#f39a8b"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B247", new Style(Style.parseColor("#009fe3"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B250", new Style(Style.parseColor("#009741"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B251", new Style(Style.parseColor("#033572"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B252", new Style(Style.parseColor("#e30613"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B260", new Style(Style.parseColor("#e6007e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B270", new Style(Style.parseColor("#fbbe5e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B271", new Style(Style.parseColor("#e30613"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B272", new Style(Style.parseColor("#009fe3"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B273", new Style(Style.parseColor("#004899"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B280", new Style(Style.parseColor("#e41b18"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B281", new Style(Style.parseColor("#f9b000"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B282", new Style(Style.parseColor("#005ca9"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B283", new Style(Style.parseColor("#ec619f"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B284", new Style(Style.parseColor("#951b81"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B285", new Style(Style.parseColor("#a42522"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B286", new Style(Style.parseColor("#e6007e"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B290", new Style(Style.parseColor("#312783"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B291", new Style(Style.parseColor("#a71680"), Style.WHITE));
        STYLES.put("rebus Regionalbus Rostock GmbH|B292", new Style(Style.parseColor("#cabe46"), Style.WHITE));

        STYLES.put("Rostocker Fähren|F", new Style(Shape.CIRCLE, Style.parseColor("#17a4da"), Style.WHITE));
    }
}
