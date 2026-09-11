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

package de.schildbach.pte.provider.openjourneyplanner;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import de.schildbach.pte.NetworkId;
import okhttp3.HttpUrl;

/*
 *  Open Journey Planner provided by BLS
 */
public class BlsOjpProvider extends AbstractOpenJourneyPlannerProvider {
    private static final HttpUrl API_ENDPOINT = HttpUrl.parse("https://api.bls.ch/mmzd/rest/ojp/v2.0/servicerequest");
    private static final HttpUrl TOKEN_ENDPOINT = HttpUrl.parse("https://fahrplan.bls.ch/token");

    private String authorization;
    private long tokenRenewAfter;

    public BlsOjpProvider() {
        super(NetworkId.BLSOJP, API_ENDPOINT);
        setRequestorRef("fahrplan");
    }

    @Override
    protected String getAuthorization() {
        final long now = System.currentTimeMillis();
        if (now > tokenRenewAfter) {
            try {
                updateToken(now);
            } catch (final Exception e) {
                log.error("error getting token", e);
                // keep previous token
            }
        }
        return authorization;
    }

    @Override
    public UserAgentType getUserAgentType() {
        return UserAgentType.BROWSER;
    }

    private void updateToken(final long now) throws JSONException, IOException {
        final CharSequence response = httpClient.get(
                TOKEN_ENDPOINT,
                "grant_type=client_credentials",
                "application/x-www-form-urlencoded");
        final JSONObject credentials = new JSONObject(response.toString());
        final String token = credentials.getString("access_token");
        final int validity = credentials.getInt("expires_in");
        tokenRenewAfter = now + (1000L * validity) - 60000;
        authorization = "Bearer " + token;
    }
}
