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

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Style;

import okhttp3.HttpUrl;

/**
 * Provider implementation for the Salzburger Verkehrsverbund (Salzburg, Austria).
 * 
 * @author Andreas Schildbach
 */
public class SvvProvider extends VaoProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://fahrplan.salzburg-verkehr.at/hamm/");
    private static final String DEFAULT_API_CLIENT = "{\"id\":\"VAO\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_svv\"}";
    private static final String WEBAPP_CONFIG_URL = "https://fahrplan.salzburg-verkehr.at/webapp/config/webapp.config.json";

    public SvvProvider() {
        this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public SvvProvider(final String apiAuthorization) {
        this(DEFAULT_API_CLIENT, apiAuthorization);
    }

    public SvvProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.SVV, API_BASE, apiClient, apiAuthorization);
    }

    static {
        STYLES.put("svv|SS1", new Style(Style.parseColor("#b61d33"), Style.WHITE));
        STYLES.put("svv|SS2", new Style(Style.parseColor("#0069b4"), Style.WHITE));
        STYLES.put("svv|SS3", new Style(Style.parseColor("#0aa537"), Style.WHITE));
        STYLES.put("svv|SS4", new Style(Style.parseColor("#a862a4"), Style.WHITE));
        STYLES.put("svv|SS11", new Style(Style.parseColor("#b61d33"), Style.WHITE));

        STYLES.put("svv|B1", new Style(Style.parseColor("#e3000f"), Style.WHITE));
        STYLES.put("svv|B2", new Style(Style.parseColor("#0069b4"), Style.WHITE));
        STYLES.put("svv|B3", new Style(Style.parseColor("#956b27"), Style.WHITE));
        STYLES.put("svv|B4", new Style(Style.parseColor("#ffcc00"), Style.WHITE));
        STYLES.put("svv|B5", new Style(Style.parseColor("#04bbee"), Style.WHITE));
        STYLES.put("svv|B6", new Style(Style.parseColor("#85bc22"), Style.WHITE));
        STYLES.put("svv|B7", new Style(Style.parseColor("#009a9b"), Style.WHITE));
        STYLES.put("svv|B8", new Style(Style.parseColor("#f39100"), Style.WHITE));
        STYLES.put("svv|B10", new Style(Style.parseColor("#f8baa2"), Style.BLACK));
        STYLES.put("svv|B12", new Style(Style.parseColor("#b9dfde"), Style.WHITE));
        STYLES.put("svv|B14", new Style(Style.parseColor("#cfe09a"), Style.WHITE));
    }
}
