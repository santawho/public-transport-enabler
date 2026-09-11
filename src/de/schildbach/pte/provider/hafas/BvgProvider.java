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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Line.Attr;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Style.Shape;

import okhttp3.HttpUrl;

/**
 * Provider implementation for the Berliner Verkehrsbetriebe (Berlin, Germany).
 * 
 * @author Andreas Schildbach
 */
public abstract class BvgProvider extends AbstractHafasClientInterfaceProvider {
    public static class Legacy extends BvgProvider {
        private static final HttpUrl API_BASE = HttpUrl.parse(
                // "https://bvg-apps-ext.hafas.de/" // from original PTE legacy
                // "https://bvg.hafas.cloud/apps/" // from original PTE since June 2026, is the one used by the BVG web server
                "https://bvg-apps.hafas.de/"
        );
        private static final String DEFAULT_API_CLIENT = "{\"id\":\"BVG\",\"type\":\"AND\"}";

        public Legacy(final String apiAuthorization) {
            this(DEFAULT_API_CLIENT, apiAuthorization);
        }

        public Legacy(final String apiClient, final String apiAuthorization) {
            super(NetworkId.BVGLEGACY, API_BASE, apiClient, apiAuthorization);
            setApiEndpoint("gate");
            setApiVersion("1.72");
            setApiExt("BVG.1");
        }
    }

    public static class NextGen extends BvgProvider {
        private static final HttpUrl API_BASE = HttpUrl.parse("https://bvg.hafas.cloud/apps/");
        private static final String DEFAULT_API_CLIENT = "{\"id\":\"VBB\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_webapp\"}";
        private static final String WEBAPP_CONFIG_URL = "https://bvg-apps.hafas.de/webapp/config/webapp.config.json";

        public NextGen() {
            this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
        }

        public NextGen(final String apiAuthorization) {
            this(DEFAULT_API_CLIENT, apiAuthorization);
        }

        public NextGen(final String apiClient, final String apiAuthorization) {
            super(NetworkId.BVG, API_BASE, apiClient, apiAuthorization);
            setApiEndpoint("gate");
            setApiVersion("1.94");
        }
    }

    private static final Set<Capability> BVG_CAPABILITIES;

    protected BvgProvider(
            final NetworkId networkId,
            final HttpUrl apiBase,
            final String apiClient,
            final String apiAuthorization
    ) {
        super(networkId, apiBase, PRODUCTS_MAP);
        setApiClient(apiClient);
        setApiAuthorization(apiAuthorization);
        setStyles(STYLES);
    }

    private static final Product[] PRODUCTS_MAP = {
            Product.SUBURBAN_TRAIN,
            Product.SUBWAY,
            Product.TRAM,
            Product.BUS,
            Product.FERRY,
            Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN,
            Product.ON_DEMAND,
            null,
            null
    };

    static {
        final Set<Capability> capabilities = new HashSet<>(CAPABILITIES);
        capabilities.remove(Capability.BIKE_OPTION);
        BVG_CAPABILITIES = capabilities;
    }


    @Override
    protected Set<Capability> getCapabilities() {
        return BVG_CAPABILITIES;
    }

    private static final Pattern P_SPLIT_NAME_SU = Pattern.compile("(.*?)(?:\\s+\\((S|U|S\\+U)\\))?");
    private static final Pattern P_SPLIT_NAME_BUS = Pattern.compile("(.*?)(\\s+\\[([^\\]]+)\\])?");

    @Override
    protected String[] splitStationName(String name) {
        final Matcher mSu = P_SPLIT_NAME_SU.matcher(name);
        if (!mSu.matches())
            throw new IllegalStateException(name);
        name = mSu.group(1);
        final String su = mSu.group(2);

        final Matcher mBus = P_SPLIT_NAME_BUS.matcher(name);
        if (!mBus.matches())
            throw new IllegalStateException(name);
        name = mBus.group(1);
        final String ext = mBus.group(3);

        final Matcher mParen = P_SPLIT_NAME_PAREN.matcher(name);
        if (mParen.matches()) {
            final String stop = mParen.group(1);
            final String city = mParen.group(2);
            return new String[]{
                    normalizePlace(city),
                    (su != null ? su + " " : "") + stop + (ext == null || ext.equals(stop) ? "" : "/" + ext)
            };
        }

        final Matcher mComma = P_SPLIT_NAME_FIRST_COMMA.matcher(name);
        if (mComma.matches())
            return new String[] { normalizePlace(mComma.group(1)), mComma.group(2) };

        return super.splitStationName(name);
    }

    private String normalizePlace(final String place) {
        if ("Bln".equals(place))
            return "Berlin";
        else
            return place;
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

    @Override
    protected String normalizeFareName(final String fareName) {
        return fareName.replaceAll("Tarifgebiet ", "");
    }

    private static final Set<Attr> ATTRS_CIRCLE_CLOCKWISE =
            Stream.of(Attr.CIRCLE_CLOCKWISE).collect(Collectors.toSet());
    private static final Set<Attr> ATTRS_CIRCLE_ANTICLOCKWISE =
            Stream.of(Attr.CIRCLE_ANTICLOCKWISE).collect(Collectors.toSet());
    private static final Set<Attr> ATTRS_SERVICE_REPLACEMENT_CIRCLE_CLOCKWISE =
            Stream.of(Attr.SERVICE_REPLACEMENT, Attr.CIRCLE_CLOCKWISE).collect(Collectors.toSet());
    private static final Set<Attr> ATTRS_SERVICE_REPLACEMENT_CIRCLE_ANTICLOCKWISE =
            Stream.of(Attr.SERVICE_REPLACEMENT, Attr.CIRCLE_ANTICLOCKWISE).collect(Collectors.toSet());
    private static final Set<Attr> ATTRS_LINE_AIRPORT =
            Stream.of(Attr.LINE_AIRPORT).collect(Collectors.toSet());

    @Override
    protected Line newLine(final String id, final String operator, final Product product, final @Nullable String name,
            final @Nullable String shortName, final @Nullable String number, final @Nullable String addName,
            final Style style) {
        final Line line = super.newLine(id, operator, product, name, shortName, number, addName, style);

        if (line.product == Product.SUBURBAN_TRAIN) {
            if ("S41".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_CIRCLE_CLOCKWISE, line.message);
            if ("S42".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_CIRCLE_ANTICLOCKWISE, line.message);
            if ("S9".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_LINE_AIRPORT, line.message);
            if ("S45".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_LINE_AIRPORT, line.message);
        } else if (line.product == Product.BUS) {
            if ("S41".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_SERVICE_REPLACEMENT_CIRCLE_CLOCKWISE, line.message);
            if ("S42".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_SERVICE_REPLACEMENT_CIRCLE_ANTICLOCKWISE, line.message);
            if ("TXL".equals(line.label))
                return new Line(id, line.network, line.product, line.label, line.name, line.style,
                        ATTRS_LINE_AIRPORT, line.message);
        }

        return line;
    }

    private static final Map<String, Style> STYLES = new HashMap<>();

    static {
        STYLES.put("SS1", new Style(Style.rgb(221, 77, 174), Style.WHITE));
        STYLES.put("SS2", new Style(Style.rgb(16, 132, 73), Style.WHITE));
        STYLES.put("SS25", new Style(Style.rgb(16, 132, 73), Style.WHITE));
        STYLES.put("SS3", new Style(Style.rgb(22, 106, 184), Style.WHITE));
        STYLES.put("SS41", new Style(Style.rgb(162, 63, 48), Style.WHITE));
        STYLES.put("SS42", new Style(Style.rgb(191, 90, 42), Style.WHITE));
        STYLES.put("SS45", new Style(Style.WHITE, Style.rgb(191, 128, 55), Style.rgb(191, 128, 55)));
        STYLES.put("SS46", new Style(Style.rgb(191, 128, 55), Style.WHITE));
        STYLES.put("SS47", new Style(Style.rgb(191, 128, 55), Style.WHITE));
        STYLES.put("SS5", new Style(Style.rgb(243, 103, 23), Style.WHITE));
        STYLES.put("SS7", new Style(Style.rgb(119, 96, 176), Style.WHITE));
        STYLES.put("SS75", new Style(Style.rgb(119, 96, 176), Style.WHITE));
        STYLES.put("SS8", new Style(Style.rgb(85, 184, 49), Style.WHITE));
        STYLES.put("SS85", new Style(Style.WHITE, Style.rgb(85, 184, 49), Style.rgb(85, 184, 49)));
        STYLES.put("SS9", new Style(Style.rgb(148, 36, 64), Style.WHITE));

        STYLES.put("UU1", new Style(Shape.RECT, Style.rgb(98, 173, 45), Style.WHITE));
        STYLES.put("UU2", new Style(Shape.RECT, Style.rgb(233, 78, 15), Style.WHITE));
        STYLES.put("UU12", new Style(Shape.RECT, Style.rgb(84, 131, 47), Style.rgb(215, 25, 16), Style.WHITE, 0));
        STYLES.put("UU3", new Style(Shape.RECT, Style.rgb(0, 153, 130), Style.WHITE));
        STYLES.put("UU4", new Style(Shape.RECT, Style.rgb(255, 213, 0), Style.BLACK));
        STYLES.put("UU5", new Style(Shape.RECT, Style.rgb(129, 82, 56), Style.WHITE));
        STYLES.put("UU55", new Style(Shape.RECT, Style.rgb(91, 31, 16), Style.WHITE));
        STYLES.put("UU6", new Style(Shape.RECT, Style.rgb(132, 109, 170), Style.WHITE));
        STYLES.put("UU7", new Style(Shape.RECT, Style.rgb(0, 155, 217), Style.WHITE));
        STYLES.put("UU8", new Style(Shape.RECT, Style.rgb(0, 89, 154), Style.WHITE));
        STYLES.put("UU9", new Style(Shape.RECT, Style.rgb(241, 135, 0), Style.WHITE));

        STYLES.put("TM1", new Style(Shape.RECT, Style.rgb(99, 185, 233), Style.WHITE));
        STYLES.put("TM2", new Style(Shape.RECT, Style.rgb(122, 185, 41), Style.WHITE));
        STYLES.put("TM4", new Style(Shape.RECT, Style.rgb(202, 18, 20), Style.WHITE));
        STYLES.put("TM5", new Style(Shape.RECT, Style.rgb(200, 137, 59), Style.WHITE));
        STYLES.put("TM6", new Style(Shape.RECT, Style.rgb(0, 86, 149), Style.WHITE));
        STYLES.put("TM8", new Style(Shape.RECT, Style.rgb(238, 114, 3), Style.WHITE));
        STYLES.put("TM10", new Style(Shape.RECT, Style.rgb(0, 123, 61), Style.WHITE));
        STYLES.put("TM13", new Style(Shape.RECT, Style.rgb(0, 160, 146), Style.WHITE));
        STYLES.put("TM17", new Style(Shape.RECT, Style.rgb(166, 66, 42), Style.WHITE));

        STYLES.put("T12", new Style(Shape.RECT, Style.rgb(136, 112, 171), Style.WHITE));
        STYLES.put("T16", new Style(Shape.RECT, Style.rgb(0, 127, 171), Style.WHITE));
        STYLES.put("T18", new Style(Shape.RECT, Style.rgb(214, 173, 0), Style.WHITE));
        STYLES.put("T21", new Style(Shape.RECT, Style.rgb(188, 144, 193), Style.WHITE));
        STYLES.put("T27", new Style(Shape.RECT, Style.rgb(203, 98, 26), Style.WHITE));
        STYLES.put("T37", new Style(Shape.RECT, Style.rgb(129, 82, 56), Style.WHITE));
        STYLES.put("T50", new Style(Shape.RECT, Style.rgb(235, 144, 0), Style.WHITE));
        STYLES.put("T60", new Style(Shape.RECT, Style.rgb(0, 155, 217), Style.WHITE));
        STYLES.put("T61", new Style(Shape.RECT, Style.rgb(227, 6, 19), Style.WHITE));
        STYLES.put("T62", new Style(Shape.RECT, Style.rgb(0, 81, 45), Style.WHITE));
        STYLES.put("T63", new Style(Shape.RECT, Style.rgb(238, 114, 3), Style.WHITE));
        STYLES.put("T67", new Style(Shape.RECT, Style.rgb(221, 108, 166), Style.WHITE));
        STYLES.put("T68", new Style(Shape.RECT, Style.rgb(101, 179, 46), Style.WHITE));

        STYLES.put("B", new Style(Shape.RECT, Style.parseColor("#993399"), Style.WHITE));
        STYLES.put("B:N", new Style(Shape.RECT, Style.rgb(26, 26, 24), Style.WHITE));
        STYLES.put("BM11", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("BM19", new Style(Shape.RECT, Style.rgb(228, 0, 125), Style.WHITE));
        STYLES.put("BM21", new Style(Shape.RECT, Style.rgb(0, 141, 53), Style.WHITE));
        STYLES.put("BM27", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("BM29", new Style(Shape.RECT, Style.rgb(43, 44, 130), Style.WHITE));
        STYLES.put("BM32", new Style(Shape.RECT, Style.rgb(166, 19, 128), Style.WHITE));
        STYLES.put("BM36", new Style(Shape.RECT, Style.rgb(51, 168, 224), Style.WHITE));
        STYLES.put("BM37", new Style(Shape.RECT, Style.rgb(229, 49, 40), Style.WHITE));
        STYLES.put("BM41", new Style(Shape.RECT, Style.rgb(0, 102, 50), Style.WHITE));
        STYLES.put("BM43", new Style(Shape.RECT, Style.rgb(0, 176, 234), Style.WHITE));
        STYLES.put("BM44", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("BM45", new Style(Shape.RECT, Style.rgb(213, 5, 80), Style.WHITE));
        STYLES.put("BM46", new Style(Shape.RECT, Style.rgb(0, 149, 63), Style.WHITE));
        STYLES.put("BM48", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("BM49", new Style(Shape.RECT, Style.rgb(97, 77, 66), Style.WHITE));
        STYLES.put("BM76", new Style(Shape.RECT, Style.rgb(43, 44, 130), Style.WHITE));
        STYLES.put("BM77", new Style(Shape.RECT, Style.rgb(242, 144, 0), Style.WHITE));
        STYLES.put("BM82", new Style(Shape.RECT, Style.rgb(226, 0, 15), Style.WHITE));
        STYLES.put("BM85", new Style(Shape.RECT, Style.rgb(166, 19, 128), Style.WHITE));
        STYLES.put("BX7", new Style(Shape.RECT, Style.rgb(226, 0, 15), Style.WHITE));
        STYLES.put("BX10", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("BX11", new Style(Shape.RECT, Style.rgb(0, 102, 50), Style.WHITE));
        STYLES.put("BX21", new Style(Shape.RECT, Style.rgb(226, 0, 15), Style.WHITE));
        STYLES.put("BX33", new Style(Shape.RECT, Style.rgb(41, 171, 101), Style.WHITE));
        STYLES.put("BX34", new Style(Shape.RECT, Style.rgb(200, 156, 102), Style.WHITE));
        STYLES.put("BX36", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("BX37", new Style(Shape.RECT, Style.rgb(146, 94, 54), Style.WHITE));
        STYLES.put("BX49", new Style(Shape.RECT, Style.rgb(0, 123, 60), Style.WHITE));
        STYLES.put("BX54", new Style(Shape.RECT, Style.rgb(0, 102, 50), Style.WHITE));
        STYLES.put("BX69", new Style(Shape.RECT, Style.rgb(0, 176, 234), Style.WHITE));
        STYLES.put("BX71", new Style(Shape.RECT, Style.rgb(131, 108, 170), Style.WHITE));
        STYLES.put("BX76", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("BX83", new Style(Shape.RECT, Style.rgb(131, 108, 170), Style.WHITE));
        STYLES.put("B100", new Style(Shape.RECT, Style.rgb(226, 0, 15), Style.WHITE));
        STYLES.put("B101", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B106", new Style(Shape.RECT, Style.rgb(101, 34, 129), Style.WHITE));
        STYLES.put("B107", new Style(Shape.RECT, Style.rgb(232, 77, 24), Style.WHITE));
        STYLES.put("B108", new Style(Shape.RECT, Style.rgb(226, 0, 15), Style.WHITE));
        STYLES.put("B109", new Style(Shape.RECT, Style.rgb(131, 108, 170), Style.WHITE));
        STYLES.put("B110", new Style(Shape.RECT, Style.rgb(255, 212, 0), Style.BLACK));
        STYLES.put("B112", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("B114", new Style(Shape.RECT, Style.rgb(233, 77, 10), Style.WHITE));
        STYLES.put("B115", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B118", new Style(Shape.RECT, Style.rgb(51, 168, 224), Style.WHITE));
        STYLES.put("B120", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B122", new Style(Shape.RECT, Style.rgb(176, 126, 73), Style.WHITE));
        STYLES.put("B123", new Style(Shape.RECT, Style.rgb(242, 144, 0), Style.WHITE));
        STYLES.put("B124", new Style(Shape.RECT, Style.rgb(166, 19, 128), Style.WHITE));
        STYLES.put("B125", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B128", new Style(Shape.RECT, Style.rgb(148, 193, 28), Style.WHITE));
        STYLES.put("B130", new Style(Shape.RECT, Style.rgb(200, 156, 102), Style.WHITE));
        STYLES.put("B131", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("B133", new Style(Shape.RECT, Style.rgb(0, 102, 50), Style.WHITE));
        STYLES.put("B134", new Style(Shape.RECT, Style.rgb(54, 168, 52), Style.WHITE));
        STYLES.put("B135", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B136", new Style(Shape.RECT, Style.rgb(238, 113, 0), Style.WHITE));
        STYLES.put("B137", new Style(Shape.RECT, Style.rgb(242, 144, 0), Style.WHITE));
        STYLES.put("B139", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B140", new Style(Shape.RECT, Style.rgb(242, 144, 0), Style.WHITE));
        STYLES.put("B142", new Style(Shape.RECT, Style.rgb(228, 0, 125), Style.WHITE));
        STYLES.put("B143", new Style(Shape.RECT, Style.rgb(233, 77, 10), Style.WHITE));
        STYLES.put("B147", new Style(Shape.RECT, Style.rgb(146, 94, 54), Style.WHITE));
        STYLES.put("B150", new Style(Shape.RECT, Style.rgb(131, 108, 170), Style.WHITE));
        STYLES.put("B154", new Style(Shape.RECT, Style.rgb(0, 89, 153), Style.WHITE));
        STYLES.put("B155", new Style(Shape.RECT, Style.rgb(54, 168, 52), Style.WHITE));
        STYLES.put("B156", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B158", new Style(Shape.RECT, Style.rgb(226, 0, 15), Style.WHITE));
        STYLES.put("B160", new Style(Shape.RECT, Style.rgb(241, 135, 0), Style.WHITE));
        STYLES.put("B161", new Style(Shape.RECT, Style.rgb(0, 102, 50), Style.WHITE));
        STYLES.put("B162", new Style(Shape.RECT, Style.rgb(233, 77, 10), Style.WHITE));
        STYLES.put("B163", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("B164", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("B165", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B166", new Style(Shape.RECT, Style.rgb(121, 104, 88), Style.WHITE));
        STYLES.put("B168", new Style(Shape.RECT, Style.rgb(0, 101, 173), Style.WHITE));
        STYLES.put("B169", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("B170", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B171", new Style(Shape.RECT, Style.rgb(166, 19, 128), Style.WHITE));
        STYLES.put("B172", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B175", new Style(Shape.RECT, Style.rgb(232, 77, 24), Style.WHITE));
        STYLES.put("B179", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B181", new Style(Shape.RECT, Style.rgb(155, 42, 72), Style.WHITE));
        STYLES.put("B184", new Style(Shape.RECT, Style.rgb(129, 81, 55), Style.WHITE));
        STYLES.put("B186", new Style(Shape.RECT, Style.rgb(163, 136, 122), Style.WHITE));
        STYLES.put("B187", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("B188", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B190", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B191", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("B192", new Style(Shape.RECT, Style.rgb(121, 104, 88), Style.WHITE));
        STYLES.put("B194", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B195", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("B197", new Style(Shape.RECT, Style.rgb(166, 19, 128), Style.WHITE));
        STYLES.put("B200", new Style(Shape.RECT, Style.rgb(232, 77, 24), Style.WHITE));
        STYLES.put("B204", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B215", new Style(Shape.RECT, Style.rgb(99, 99, 99), Style.WHITE));
        STYLES.put("B218", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B220", new Style(Shape.RECT, Style.rgb(100, 179, 44), Style.WHITE));
        STYLES.put("B221", new Style(Shape.RECT, Style.rgb(155, 42, 72), Style.WHITE));
        STYLES.put("B222", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("B234", new Style(Shape.RECT, Style.rgb(131, 108, 170), Style.WHITE));
        STYLES.put("B237", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("B240", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("B245", new Style(Shape.RECT, Style.rgb(0, 141, 53), Style.WHITE));
        STYLES.put("B246", new Style(Shape.RECT, Style.rgb(229, 23, 114), Style.WHITE));
        STYLES.put("B247", new Style(Shape.RECT, Style.rgb(41, 171, 101), Style.WHITE));
        STYLES.put("B248", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("B249", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("B250", new Style(Shape.RECT, Style.rgb(125, 77, 35), Style.WHITE));
        STYLES.put("B255", new Style(Shape.RECT, Style.rgb(51, 168, 224), Style.WHITE));
        STYLES.put("B256", new Style(Shape.RECT, Style.rgb(242, 144, 0), Style.WHITE));
        STYLES.put("B259", new Style(Shape.RECT, Style.rgb(163, 136, 122), Style.WHITE));
        STYLES.put("B260", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B263", new Style(Shape.RECT, Style.rgb(101, 34, 129), Style.WHITE));
        STYLES.put("B265", new Style(Shape.RECT, Style.rgb(54, 168, 52), Style.WHITE));
        STYLES.put("B269", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B271", new Style(Shape.RECT, Style.rgb(0, 176, 234), Style.WHITE));
        STYLES.put("B275", new Style(Shape.RECT, Style.rgb(54, 168, 52), Style.WHITE));
        STYLES.put("B277", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B282", new Style(Shape.RECT, Style.rgb(41, 171, 101), Style.WHITE));
        STYLES.put("B283", new Style(Shape.RECT, Style.rgb(0, 141, 53), Style.WHITE));
        STYLES.put("B284", new Style(Shape.RECT, Style.rgb(51, 168, 224), Style.WHITE));
        STYLES.put("B285", new Style(Shape.RECT, Style.rgb(232, 77, 24), Style.WHITE));
        STYLES.put("B291", new Style(Shape.RECT, Style.rgb(0, 141, 53), Style.WHITE));
        STYLES.put("B294", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B296", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("B300", new Style(Shape.RECT, Style.rgb(131, 108, 170), Style.WHITE));
        STYLES.put("B309", new Style(Shape.RECT, Style.rgb(54, 168, 52), Style.WHITE));
        STYLES.put("B310", new Style(Shape.RECT, Style.rgb(51, 168, 224), Style.WHITE));
        STYLES.put("B312", new Style(Shape.RECT, Style.rgb(99, 99, 99), Style.WHITE));
        STYLES.put("B316", new Style(Shape.RECT, Style.rgb(146, 94, 54), Style.WHITE));
        STYLES.put("B318", new Style(Shape.RECT, Style.rgb(0, 102, 50), Style.WHITE));
        STYLES.put("B320", new Style(Shape.RECT, Style.rgb(129, 81, 55), Style.WHITE));
        STYLES.put("B322", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("B324", new Style(Shape.RECT, Style.rgb(26, 112, 183), Style.WHITE));
        STYLES.put("B326", new Style(Shape.RECT, Style.rgb(189, 20, 33), Style.WHITE));
        STYLES.put("B327", new Style(Shape.RECT, Style.rgb(248, 177, 50), Style.WHITE));
        STYLES.put("B334", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("B337", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B339", new Style(Shape.RECT, Style.rgb(99, 99, 99), Style.WHITE));
        STYLES.put("B347", new Style(Shape.RECT, Style.rgb(147, 25, 128), Style.WHITE));
        STYLES.put("B349", new Style(Shape.RECT, Style.rgb(0, 160, 153), Style.WHITE));
        STYLES.put("B350", new Style(Shape.RECT, Style.rgb(147, 192, 28), Style.WHITE));
        STYLES.put("B353", new Style(Shape.RECT, Style.rgb(0, 123, 60), Style.WHITE));
        STYLES.put("B358", new Style(Shape.RECT, Style.rgb(99, 99, 99), Style.WHITE));
        STYLES.put("B363", new Style(Shape.RECT, Style.rgb(96, 173, 43), Style.WHITE));
        STYLES.put("B365", new Style(Shape.RECT, Style.rgb(147, 25, 128), Style.WHITE));
        STYLES.put("B369", new Style(Shape.RECT, Style.rgb(121, 104, 88), Style.WHITE));
        STYLES.put("B371", new Style(Shape.RECT, Style.rgb(0, 141, 53), Style.WHITE));
        STYLES.put("B372", new Style(Shape.RECT, Style.rgb(146, 94, 54), Style.WHITE));
        STYLES.put("B377", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("B380", new Style(Shape.RECT, Style.rgb(0, 89, 153), Style.WHITE));
        STYLES.put("B390", new Style(Shape.RECT, Style.rgb(100, 179, 44), Style.WHITE));
        STYLES.put("B395", new Style(Shape.RECT, Style.rgb(0, 157, 226), Style.WHITE));
        STYLES.put("B396", new Style(Shape.RECT, Style.rgb(200, 156, 102), Style.WHITE));
        STYLES.put("B398", new Style(Shape.RECT, Style.rgb(221, 107, 166), Style.WHITE));
        STYLES.put("B399", new Style(Shape.RECT, Style.rgb(174, 88, 55), Style.WHITE));
        STYLES.put("B638", new Style(Shape.RECT, Style.rgb(155, 155, 155), Style.WHITE));
        STYLES.put("B744", new Style(Shape.RECT, Style.rgb(155, 155, 155), Style.WHITE));
        STYLES.put("B893", new Style(Shape.RECT, Style.rgb(155, 155, 155), Style.WHITE));

        STYLES.put("FF1", new Style(Style.BLUE, Style.WHITE)); // Potsdam
        STYLES.put("FF10", new Style(Style.BLUE, Style.WHITE));
        STYLES.put("FF11", new Style(Style.BLUE, Style.WHITE));
        STYLES.put("FF12", new Style(Style.BLUE, Style.WHITE));
        STYLES.put("FF21", new Style(Style.BLUE, Style.WHITE));
        STYLES.put("FF23", new Style(Style.BLUE, Style.WHITE));
        STYLES.put("FF24", new Style(Style.BLUE, Style.WHITE));

        // Regional lines Brandenburg:
        STYLES.put("RRE1", new Style(Shape.RECT, Style.parseColor("#EE1C23"), Style.WHITE));
        STYLES.put("RRE2", new Style(Shape.RECT, Style.parseColor("#FFD403"), Style.BLACK));
        STYLES.put("RRE3", new Style(Shape.RECT, Style.parseColor("#F57921"), Style.WHITE));
        STYLES.put("RRE4", new Style(Shape.RECT, Style.parseColor("#952D4F"), Style.WHITE));
        STYLES.put("RRE5", new Style(Shape.RECT, Style.parseColor("#0072BC"), Style.WHITE));
        STYLES.put("RRE6", new Style(Shape.RECT, Style.parseColor("#DB6EAB"), Style.WHITE));
        STYLES.put("RRE7", new Style(Shape.RECT, Style.parseColor("#00854A"), Style.WHITE));
        STYLES.put("RRE10", new Style(Shape.RECT, Style.parseColor("#A7653F"), Style.WHITE));
        STYLES.put("RRE11", new Style(Shape.RECT, Style.parseColor("#059EDB"), Style.WHITE));
        STYLES.put("RRE11", new Style(Shape.RECT, Style.parseColor("#EE1C23"), Style.WHITE));
        STYLES.put("RRE15", new Style(Shape.RECT, Style.parseColor("#FFD403"), Style.BLACK));
        STYLES.put("RRE18", new Style(Shape.RECT, Style.parseColor("#00A65E"), Style.WHITE));
        STYLES.put("RRB10", new Style(Shape.RECT, Style.parseColor("#60BB46"), Style.WHITE));
        STYLES.put("RRB12", new Style(Shape.RECT, Style.parseColor("#A3238E"), Style.WHITE));
        STYLES.put("RRB13", new Style(Shape.RECT, Style.parseColor("#F68B1F"), Style.WHITE));
        STYLES.put("RRB13", new Style(Shape.RECT, Style.parseColor("#00A65E"), Style.WHITE));
        STYLES.put("RRB14", new Style(Shape.RECT, Style.parseColor("#A3238E"), Style.WHITE));
        STYLES.put("RRB20", new Style(Shape.RECT, Style.parseColor("#00854A"), Style.WHITE));
        STYLES.put("RRB21", new Style(Shape.RECT, Style.parseColor("#5E6DB3"), Style.WHITE));
        STYLES.put("RRB22", new Style(Shape.RECT, Style.parseColor("#0087CB"), Style.WHITE));
        STYLES.put("ROE25", new Style(Shape.RECT, Style.parseColor("#0087CB"), Style.WHITE));
        STYLES.put("RNE26", new Style(Shape.RECT, Style.parseColor("#00A896"), Style.WHITE));
        STYLES.put("RNE27", new Style(Shape.RECT, Style.parseColor("#EE1C23"), Style.WHITE));
        STYLES.put("RRB30", new Style(Shape.RECT, Style.parseColor("#00A65E"), Style.WHITE));
        STYLES.put("RRB31", new Style(Shape.RECT, Style.parseColor("#60BB46"), Style.WHITE));
        STYLES.put("RMR33", new Style(Shape.RECT, Style.parseColor("#EE1C23"), Style.WHITE));
        STYLES.put("ROE35", new Style(Shape.RECT, Style.parseColor("#5E6DB3"), Style.WHITE));
        STYLES.put("ROE36", new Style(Shape.RECT, Style.parseColor("#A7653F"), Style.WHITE));
        STYLES.put("RRB43", new Style(Shape.RECT, Style.parseColor("#5E6DB3"), Style.WHITE));
        STYLES.put("RRB45", new Style(Shape.RECT, Style.parseColor("#FFD403"), Style.BLACK));
        STYLES.put("ROE46", new Style(Shape.RECT, Style.parseColor("#DB6EAB"), Style.WHITE));
        STYLES.put("RMR51", new Style(Shape.RECT, Style.parseColor("#DB6EAB"), Style.WHITE));
        STYLES.put("RRB51", new Style(Shape.RECT, Style.parseColor("#DB6EAB"), Style.WHITE));
        STYLES.put("RRB54", new Style(Shape.RECT, Style.parseColor("#FFD403"), Style.BLACK));
        STYLES.put("RRB55", new Style(Shape.RECT, Style.parseColor("#F57921"), Style.WHITE));
        STYLES.put("ROE60", new Style(Shape.RECT, Style.parseColor("#60BB46"), Style.WHITE));
        STYLES.put("ROE63", new Style(Shape.RECT, Style.parseColor("#FFD403"), Style.BLACK));
        STYLES.put("ROE65", new Style(Shape.RECT, Style.parseColor("#0072BC"), Style.WHITE));
        STYLES.put("RRB66", new Style(Shape.RECT, Style.parseColor("#60BB46"), Style.WHITE));
        STYLES.put("RPE70", new Style(Shape.RECT, Style.parseColor("#FFD403"), Style.BLACK));
        STYLES.put("RPE73", new Style(Shape.RECT, Style.parseColor("#00A896"), Style.WHITE));
        STYLES.put("RPE74", new Style(Shape.RECT, Style.parseColor("#0072BC"), Style.WHITE));
        STYLES.put("T89", new Style(Shape.RECT, Style.parseColor("#EE1C23"), Style.WHITE));
        STYLES.put("RRB91", new Style(Shape.RECT, Style.parseColor("#A7653F"), Style.WHITE));
        STYLES.put("RRB93", new Style(Shape.RECT, Style.parseColor("#A7653F"), Style.WHITE));
    }
}
