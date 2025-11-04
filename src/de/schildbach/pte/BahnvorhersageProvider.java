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

package de.schildbach.pte;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.TransferDetails;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.exception.ParserException;
import okhttp3.HttpUrl;

public final class BahnvorhersageProvider extends AbstractApiProvider implements TransferEvaluationApiProvider {
    private static final Logger log = LoggerFactory.getLogger(BahnvorhersageProvider.class);

    public interface BahnvorhersageTripRef {
        String getBahnvorhersageRefreshToken();
    }

    private static final HttpUrl API_BASE = HttpUrl.parse("https://bahnvorhersage.de/api/");
    private static final boolean TRAINS_ONLY = false;

    private final HttpUrl refreshJourneyEndpoint;

    public BahnvorhersageProvider() {
        this.refreshJourneyEndpoint = API_BASE.newBuilder().addPathSegments("refresh-journey").build();
    }

    @Override
    public List<TransferDetails> evaluateTransfersForTrip(final Trip trip) throws IOException {
        final TripRef tripRef = trip.tripRef;
        if (tripRef == null)
            return null;

        if (!(tripRef instanceof BahnvorhersageTripRef))
            throw new RuntimeException("trip is not compatible with Bahnvorhersage: tripRef=" + tripRef.getClass().getName());
        final String refreshToken = ((BahnvorhersageTripRef) tripRef).getBahnvorhersageRefreshToken();

        if (!checkPreconditions(trip))
            return null;

        return queryTransferDetailsForRefreshToken(refreshToken);
    }

    private boolean checkPreconditions(final Trip trip) {
        // at least one transfer must be train to train.
        // otherwise the result would contain no transfer evaluation at all,
        // because Bahnvorhersage supports trains only.
        // They say so! Is it true? Because there are evaluations for U-Bahn to Bus for example...
        boolean previousIsTrain = false;
        int numPublic = 0;
        for (final Trip.Leg leg : trip.legs) {
            if (!(leg instanceof Trip.Public))
                continue;
            ++numPublic;
            final Trip.Public publicLeg = (Trip.Public) leg;
            final Product product = publicLeg.line.product;
            if (product != null && product.isTrain()) {
                if (previousIsTrain)
                    return true;
                previousIsTrain = true;
            } else {
                previousIsTrain = false;
            }
        }
        if (numPublic < 2)
            return false;

        return !TRAINS_ONLY;
    }

    private List<TransferDetails> queryTransferDetailsForRefreshToken(final String refreshToken) throws IOException {
        final String request = "{\"refresh_token\":\"" + refreshToken + "\"}";
        final HttpUrl url = refreshJourneyEndpoint;

        String page = null;
        try {
            page = doRequest(url, request);
            final JSONObject res = new JSONObject(page);

            final List<TransferDetails> transferDetailsList = new ArrayList<>();
            final JSONArray legs = res.getJSONArray("legs");
            for (int index = 0; index < legs.length(); ++index) {
                final JSONObject leg = legs.getJSONObject(index);
                final String type = leg.getString("type");
                if ("transfer".equals(type)) {
                    final double transferScore = leg.optDouble("transferScore");
                    final TransferDetails transferDetails;
                    if (Double.isNaN(transferScore)) {
                        transferDetails = new TransferDetails(null);
                    } else {
                        transferDetails = new TransferDetails((float) transferScore);
                    }
                    transferDetailsList.add(transferDetails);
                }
            }
            return transferDetailsList;
        } catch (IOException | RuntimeException e) {
            log.error("service is down");
        } catch (final JSONException x) {
            throw new ParserException("cannot parse json: '" + page + "' on " + url, x);
        }

        return null;
    }

    private String doRequest(final HttpUrl url, final String body) throws IOException {
        final String cType = "application/json";
        httpClient.setHeader("Accept", cType);
        if (body != null) httpClient.setHeader("Content-Type", cType);
        if (userInterfaceLanguage != null)
            httpClient.setHeader("Accept-Language", userInterfaceLanguage);
        return httpClient.get(url, body, null).toString();
    }
}
