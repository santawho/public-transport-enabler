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

package de.schildbach.pte.provider.hafas.austria;

import java.util.HashMap;
import java.util.Map;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Style;
import okhttp3.HttpUrl;

/**
 * Provider implementation for Steiermark, Austria.
 */
public class StvProvider extends VaoProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://verkehrsauskunft.verbundlinie.at/hamm/");
    private static final String DEFAULT_API_CLIENT = "{\"id\":\"VAO\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_stv\"}";
    private static final String WEBAPP_CONFIG_URL = "https://verkehrsauskunft.verbundlinie.at/webapp/config/webapp.config.json";

    public StvProvider() {
        this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public StvProvider(final String apiAuthorization) {
        this(DEFAULT_API_CLIENT, apiAuthorization);
    }

    public StvProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.STV, API_BASE, apiClient, apiAuthorization);
        setStyles(STYLES);
    }

    private static final Style.Shape STV_SHAPE = Style.Shape.ROUNDED;

    protected static final Map<String, Style> STYLES = new HashMap<>();

    static {
        // Holding Graz – Graz Linien

        // Straßenbahn
        STYLES.put("Graz Linien|T1", new Style(STV_SHAPE, Style.rgb(0, 173, 230), Style.WHITE));
        STYLES.put("Graz Linien|T3", new Style(STV_SHAPE, Style.rgb(108, 182, 64), Style.WHITE));
        STYLES.put("Graz Linien|T4", new Style(STV_SHAPE, Style.rgb(209, 170, 28), Style.WHITE));
        STYLES.put("Graz Linien|T5", new Style(STV_SHAPE, Style.rgb(1, 83, 142), Style.WHITE));
        STYLES.put("Graz Linien|T6", new Style(STV_SHAPE, Style.rgb(2, 100, 66), Style.WHITE));
        STYLES.put("Graz Linien|T7", new Style(STV_SHAPE, Style.rgb(205, 21, 29), Style.WHITE));
        STYLES.put("Graz Linien|T16", new Style(STV_SHAPE, Style.rgb(2, 100, 66), Style.WHITE));
        STYLES.put("Graz Linien|T17", new Style(STV_SHAPE, Style.rgb(205, 21, 29), Style.WHITE));
        STYLES.put("Graz Linien|T23", new Style(STV_SHAPE, Style.rgb(156, 100, 169), Style.WHITE));

        // Bus
        STYLES.put("Graz Linien|B30", new Style(STV_SHAPE, Style.rgb(244, 121, 33), Style.WHITE));
        STYLES.put("Graz Linien|B31", new Style(STV_SHAPE, Style.rgb(8, 167, 224), Style.WHITE));
        STYLES.put("Graz Linien|B31E", new Style(STV_SHAPE, Style.rgb(8, 167, 224), Style.WHITE));
        STYLES.put("Graz Linien|B32", new Style(STV_SHAPE, Style.rgb(252, 184, 20), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B33", new Style(STV_SHAPE, Style.rgb(0, 133, 74), Style.WHITE));
        STYLES.put("Graz Linien|B34", new Style(STV_SHAPE, Style.rgb(189, 205, 49), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B34E", new Style(STV_SHAPE, Style.rgb(189, 205, 49), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B39", new Style(STV_SHAPE, Style.rgb(188, 27, 141), Style.WHITE));
        STYLES.put("Graz Linien|B40", new Style(STV_SHAPE, Style.rgb(96, 187, 70), Style.WHITE));
        STYLES.put("Graz Linien|B41", new Style(STV_SHAPE, Style.rgb(0, 133, 74), Style.WHITE));
        STYLES.put("Graz Linien|B41E", new Style(STV_SHAPE, Style.rgb(0, 133, 74), Style.WHITE));
        STYLES.put("Graz Linien|B48", new Style(STV_SHAPE, Style.rgb(252, 184, 20), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B48S", new Style(STV_SHAPE, Style.rgb(252, 184, 20), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B52", new Style(STV_SHAPE, Style.rgb(167, 84, 51), Style.WHITE));
        STYLES.put("Graz Linien|B52E", new Style(STV_SHAPE, Style.rgb(167, 84, 51), Style.WHITE));
        STYLES.put("Graz Linien|B52Z", new Style(STV_SHAPE, Style.rgb(167, 84, 51), Style.WHITE));
        STYLES.put("Graz Linien|B53", new Style(STV_SHAPE, Style.rgb(244, 121, 33), Style.WHITE));
        STYLES.put("Graz Linien|B58", new Style(STV_SHAPE, Style.rgb(0, 114, 188), Style.WHITE));
        STYLES.put("Graz Linien|B60", new Style(STV_SHAPE, Style.rgb(189, 205, 49), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B61", new Style(STV_SHAPE, Style.rgb(96, 187, 70), Style.WHITE));
        STYLES.put("Graz Linien|B62", new Style(STV_SHAPE, Style.rgb(241, 124, 176), Style.WHITE));
        STYLES.put("Graz Linien|B63", new Style(STV_SHAPE, Style.rgb(252, 184, 20), Style.rgb(75, 76, 77)));
        STYLES.put("Graz Linien|B64", new Style(STV_SHAPE, Style.rgb(195, 138, 87), Style.WHITE));
        STYLES.put("Graz Linien|B64E", new Style(STV_SHAPE, Style.rgb(195, 138, 87), Style.WHITE));
        STYLES.put("Graz Linien|B65", new Style(STV_SHAPE, Style.rgb(188, 27, 141), Style.WHITE));
        STYLES.put("Graz Linien|B65A", new Style(STV_SHAPE, Style.rgb(188, 27, 141), Style.WHITE));
        STYLES.put("Graz Linien|B66", new Style(STV_SHAPE, Style.rgb(0, 114, 188), Style.WHITE));
        STYLES.put("Graz Linien|B67", new Style(STV_SHAPE, Style.rgb(3, 185, 180), Style.WHITE));
        STYLES.put("Graz Linien|B67E", new Style(STV_SHAPE, Style.rgb(3, 185, 180), Style.WHITE));
        STYLES.put("Graz Linien|B68", new Style(STV_SHAPE, Style.rgb(0, 133, 74), Style.WHITE));
        STYLES.put("Graz Linien|B69", new Style(STV_SHAPE, Style.rgb(8, 167, 224), Style.WHITE));
        STYLES.put("Graz Linien|B72", new Style(STV_SHAPE, Style.rgb(96, 187, 70), Style.WHITE));
        STYLES.put("Graz Linien|B74", new Style(STV_SHAPE, Style.rgb(244, 121, 33), Style.WHITE));
        STYLES.put("Graz Linien|B75", new Style(STV_SHAPE, Style.rgb(3, 185, 180), Style.WHITE));
        STYLES.put("Graz Linien|B78", new Style(STV_SHAPE, Style.rgb(3, 185, 180), Style.WHITE));
        STYLES.put("Graz Linien|B80", new Style(STV_SHAPE, Style.rgb(0, 114, 188), Style.WHITE));
        STYLES.put("Graz Linien|B81", new Style(STV_SHAPE, Style.rgb(128, 130, 133), Style.WHITE));
        STYLES.put("Graz Linien|B82", new Style(STV_SHAPE, Style.rgb(3, 185, 180), Style.WHITE));

        // Nightline
        STYLES.put("Graz Linien|BN1", new Style(STV_SHAPE, Style.rgb(166, 206, 57), Style.WHITE));
        STYLES.put("Graz Linien|BN2", new Style(STV_SHAPE, Style.rgb(252, 184, 20), Style.rgb(27, 6, 0)));
        STYLES.put("Graz Linien|BN3", new Style(STV_SHAPE, Style.rgb(241, 124, 176), Style.WHITE));
        STYLES.put("Graz Linien|BN4", new Style(STV_SHAPE, Style.rgb(167, 84, 51), Style.WHITE));
        STYLES.put("Graz Linien|BN5", new Style(STV_SHAPE, Style.rgb(10, 155, 217), Style.WHITE));
        STYLES.put("Graz Linien|BN6", new Style(STV_SHAPE, Style.rgb(1, 122, 71), Style.WHITE));
        STYLES.put("Graz Linien|BN7", new Style(STV_SHAPE, Style.rgb(237, 26, 58), Style.WHITE));
        STYLES.put("Graz Linien|BN8", new Style(STV_SHAPE, Style.rgb(169, 103, 170), Style.WHITE));
        STYLES.put("Graz Linien|BN9", new Style(STV_SHAPE, Style.rgb(0, 175, 165), Style.WHITE));
    }
}
