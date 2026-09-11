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

import java.util.Set;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Product;

/*
 *  oev-info.ch
 */
public class OevInfoChProvider extends SwissOtdOjpProvider {
    public OevInfoChProvider(final String authorization) {
        super(NetworkId.OEVINFOCH);
        setAuthorization(authorization);
        setRequestorRef("SBBQuovadislayout");
    }

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL_INCLUDING_HIGHSPEED;
    }

    @Override
    public void setCredentials(final String credentials) {
        // do nothing, authorization already set constantly
    }

    @Override
    public UserAgentType getUserAgentType() {
        return UserAgentType.BROWSER;
    }
}
