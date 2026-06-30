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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.TransferDetails;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.provider.AbstractApiProvider;
import de.schildbach.pte.provider.TransferEvaluationApiProvider;
import de.schildbach.pte.provider.db.DbProvider;
import okhttp3.HttpUrl;

public abstract class AbstractBahnvorhersageProvider extends AbstractApiProvider implements TransferEvaluationApiProvider {
    public static int USE_VERSION = 2;

    public static AbstractBahnvorhersageProvider createInstance(final DbProvider dbProvider) {
        switch (USE_VERSION) {
            case 1:
                return new BahnvorhersageProviderV1(dbProvider);
            case 2:
                return new BahnvorhersageProviderV2(dbProvider);
        }
        return null;
    }

    public interface BahnvorhersageTripRef {
        String getBahnvorhersageRefreshToken();
    }

    public interface BahnvorhersageJourneyRef {
        String getBahnvorhersageRefreshJourneyId();
    }

    protected static final HttpUrl API_BASE = HttpUrl.parse("https://bahnvorhersage.de/api/");

    private static final boolean TRAINS_ONLY = false;

    protected final DbProvider dbProvider;

    protected AbstractBahnvorhersageProvider(final DbProvider dbProvider) {
        this.dbProvider = dbProvider;
    }

    @Override
    public UserAgentType getUserAgentType() {
        return UserAgentType.APP;
    }

    protected static boolean checkPreconditions(final Trip trip) {
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

    protected String getRefreshTokenForTrip(final Trip trip) {
        final String refreshToken = DbProvider.refreshTokenFromPublicLegs(trip.legs);
        if (refreshToken != null)
            return refreshToken;
        final TripRef tripRef = trip.tripRef;
        if (tripRef == null)
            return null;

        if (!(tripRef instanceof BahnvorhersageTripRef))
            throw new RuntimeException("trip is not compatible with Bahn-Vorhersage: tripRef=" + tripRef.getClass().getName());

        return ((BahnvorhersageTripRef) tripRef).getBahnvorhersageRefreshToken();
    }

    protected String doRequest(final HttpUrl url, final String body) throws IOException {
        final String cType = "application/json";
        httpClient.setHeader("Accept", cType);
        if (body != null) httpClient.setHeader("Content-Type", cType);
        if (userInterfaceLanguage != null)
            httpClient.setHeader("Accept-Language", userInterfaceLanguage);
        return httpClient.get(url, body, null).toString();
    }

    @Override
    public Description getDescription() {
        return new Description.Base() {
            @Override
            public String getName() {
                return "Bahn-Vorhersage";
            }

            @Override
            public String getDescriptionText() {
                return "Bahn-Vorhersage provides transfer probabilities.";
            }

            @Override
            public String getUrl() {
                return "https://bahnvorhersage.de";
            }
        };
    }

    protected List<TransferDetails> parseLegsFromJourneyResult(final JSONObject journey) throws JSONException {
        final List<TransferDetails> transferDetailsList = new ArrayList<>();
        final JSONArray legs = journey.getJSONArray("legs");
        final int lastIndex = legs.length() - 1;
        for (int index = 0; index <= lastIndex; ++index) {
            final JSONObject leg = legs.getJSONObject(index);
            final String type = leg.getString("type");
            if ("transfer".equals(type)) {
                if (index == 0 || index >= lastIndex)
                    continue;
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
    }
}
