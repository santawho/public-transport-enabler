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
import okhttp3.HttpUrl;

/**
 * Provider implementation for the Verkehrsverbund Kärnten (Austria).
 */
public class VkgProvider extends VaoProvider {
    private static final HttpUrl API_BASE = HttpUrl.parse("https://routenplaner.kaerntner-linien.at/hamm/");
    private static final String DEFAULT_API_CLIENT = "{\"id\":\"VAO\",\"type\":\"WEB\",\"name\":\"webapp\",\"l\":\"vs_vkg\"}";
    private static final String WEBAPP_CONFIG_URL = "https://routenplaner.kaerntner-linien.at/webapp/config/webapp.config.json";

    public VkgProvider() {
        this(DEFAULT_API_CLIENT, WEBAPP_CONFIG_URL);
    }

    public VkgProvider(final String apiAuthorization) {
        this(DEFAULT_API_CLIENT, apiAuthorization);
    }

    public VkgProvider(final String apiClient, final String apiAuthorization) {
        super(NetworkId.VKG, API_BASE, apiClient, apiAuthorization);
    }
}
