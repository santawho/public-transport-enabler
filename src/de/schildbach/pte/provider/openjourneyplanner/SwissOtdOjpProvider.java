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

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.provider.AbstractApiProvider;
import okhttp3.HttpUrl;

/*
 *  Open Journey Planner provided by opentransportdata.swiss
 */
public class SwissOtdOjpProvider extends AbstractOpenJourneyPlannerProvider {
    private static final HttpUrl API_ENDPOINT = HttpUrl.parse("https://api.opentransportdata.swiss/ojp20");

    private String authorization;

    public SwissOtdOjpProvider() {
        this(NetworkId.SWISSOTD);
    }

    protected SwissOtdOjpProvider(final NetworkId networkId) {
        super(networkId, API_ENDPOINT);
    }

    protected void setAuthorization(final String authorization) {
        this.authorization = authorization;
    }

    @Override
    public void setCredentials(final String credentials) {
        setAuthorization("Bearer " + credentials);
    }

    @Override
    protected String getAuthorization() {
        return authorization;
    }

    @Override
    public UserAgentType getUserAgentType() {
        return UserAgentType.APP;
    }

    @Override
    public AbstractApiProvider setUserAgent(final String userAgent) {
        super.setUserAgent(userAgent);
        setRequestorRef(userAgent);
        return this;
    }
}
