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

package de.schildbach.pte.provider.db.bahnvorhersage;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import de.schildbach.pte.dto.TransferDetails;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.TransferEvaluationApiProvider;
import de.schildbach.pte.provider.db.DbProvider;
import okhttp3.HttpUrl;

public final class BahnvorhersageProviderV1 extends AbstractBahnvorhersageProvider implements TransferEvaluationApiProvider {
    private final HttpUrl refreshJourneyEndpoint;

    public BahnvorhersageProviderV1(final DbProvider dbProvider) {
        super(dbProvider);
        this.refreshJourneyEndpoint = API_BASE.newBuilder().addPathSegments("refresh-journey").build();
    }

    @Override
    public List<TransferDetails> evaluateTransfersForTrip(final Trip trip) throws IOException {
        if (!checkPreconditions(trip))
            return null;

        final String refreshToken = getRefreshTokenForTrip(trip);
        if (refreshToken == null)
            return null;

        return queryTransferDetailsForRefreshToken(refreshToken);
    }

    private List<TransferDetails> queryTransferDetailsForRefreshToken(final String refreshToken) throws IOException {
        final String request = "{\"refresh_token\":\"" + refreshToken + "\"}";
        final HttpUrl url = refreshJourneyEndpoint;

        String page = null;
        try {
            page = doRequest(url, request);
            final JSONObject res = new JSONObject(page);
            return parseLegsFromJourneyResult(res);
        } catch (final IOException | RuntimeException e) {
            log.error("service is down");
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }

        return null;
    }
}
