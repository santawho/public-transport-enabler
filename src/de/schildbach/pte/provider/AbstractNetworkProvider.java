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

package de.schildbach.pte.provider;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.msgpack.core.MessageUnpacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.Standard;
import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.TransferDetails;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.dto.TripShare;
import de.schildbach.pte.provider.efa.AbstractEfaProvider;
import de.schildbach.pte.provider.locationsearch.AbstractLocationSearchProvider;

/**
 * @author Andreas Schildbach
 */
public abstract class AbstractNetworkProvider extends AbstractLocationSearchProvider implements NetworkApiProvider {
    protected final NetworkId network;

    protected Charset requestUrlEncoding = StandardCharsets.ISO_8859_1;
    protected TimeZone timeZone;
    protected int numTripsRequested = 6;
    protected @Nullable Map<String, Style> styles = null;

    protected AbstractNetworkProvider(final NetworkId network) {
        this.network = network;
        setTimeZone("Europe/Berlin");
    }

    @Override
    public final NetworkId id() {
        return network;
    }

    @Override
    public final boolean hasCapabilities(final Capability... capabilities) {
        return getCapabilities().containsAll(Set.of(capabilities));
    }

    protected abstract Set<Capability> getCapabilities();

    protected boolean hasCapability(final Capability capability) {
        return getCapabilities().contains(capability);
    }

    protected boolean isStationBoardDestinationCommonlyDirection() {
        return false;
    }

    protected boolean isPublicLegDestinationCommonlyDirection() {
        return true;
    }

    protected boolean isJourneyDestinationCommonlyDirection() {
        return false;
    }

    @Deprecated
    @Override
    public QueryTripsResult queryTrips(
            final Location from, @Nullable final Location via, final Location to, final Date date, final boolean dep,
            @Nullable final Set<Product> products, @Nullable final Optimize optimize, @Nullable final WalkSpeed walkSpeed,
            @Nullable final Accessibility accessibility, @Nullable final Set<TripFlag> flags, final boolean loadPath) throws IOException {
        return queryTrips(
                from, via, to, date, dep,
                new TripOptions(products, optimize, walkSpeed, null, null, accessibility, flags),
                loadPath);
    }

    @Override
    public QueryTripsResult queryReloadTrip(final TripRef tripRef, final boolean loadPath) throws IOException {
        throw new UnsupportedOperationException("queryReloadTrip(\"" + tripRef + "\")");
    }

    @Override
    public QueryJourneyResult queryJourney(final JourneyRef journeyRef, final boolean loadPath) throws IOException {
        throw new UnsupportedOperationException("queryJourney(\"" + journeyRef + "\")");
    }

    @Override
    public Set<Product> defaultProducts() {
        return Product.ALL_EXCEPT_HIGHSPEED;
    }

    protected AbstractNetworkProvider setRequestUrlEncoding(final Charset requestUrlEncoding) {
        this.requestUrlEncoding = requestUrlEncoding;
        return this;
    }

    protected AbstractNetworkProvider setTimeZone(final String timeZoneId) {
        this.timeZone = TimeZone.getTimeZone(timeZoneId);
        return this;
    }

    public TimeZone getTimeZone() {
        return this.timeZone;
    }

    protected AbstractNetworkProvider setNumTripsRequested(final int numTripsRequested) {
        this.numTripsRequested = numTripsRequested;
        return this;
    }

    protected AbstractNetworkProvider setStyles(final Map<String, Style> styles) {
        this.styles = styles;
        return this;
    }

    public Style lineStyle(
            final @Nullable String network,
            final @Nullable Product product,
            final @Nullable String label) {
        return lineStyle(network, product, label, null);
    }

    @Override
    public Style lineStyle(
            final @Nullable String network,
            final @Nullable Product product,
            final @Nullable String label,
            final @Nullable Style styleFromNetwork) {
        return Standard.resolveLineStyle(styles, network, product, label, styleFromNetwork);
    }

    protected static String normalizeStationId(final String stationId) {
        if (stationId == null || stationId.length() == 0)
            return null;

        if (stationId.charAt(0) != '0')
            return stationId;

        final StringBuilder normalized = new StringBuilder(stationId);
        while (normalized.length() > 0 && normalized.charAt(0) == '0')
            normalized.deleteCharAt(0);

        return normalized.toString();
    }

    private static final Pattern P_NAME_SECTION = Pattern.compile("(\\d{1,5})\\s*" + //
            "([A-Z](?:\\s*-?\\s*[A-Z])?)?");

    private static final Pattern P_NAME_NOSW = Pattern.compile("(\\d{1,5})\\s*" + //
            "(Nord|Süd|Ost|West)", Pattern.CASE_INSENSITIVE);

    protected Position parsePosition(final String position) {
        if (position == null)
            return null;

        final Matcher mSection = P_NAME_SECTION.matcher(position);
        if (mSection.matches()) {
            final String name = Integer.toString(Integer.parseInt(mSection.group(1)));
            if (mSection.group(2) != null)
                return new Position(name, mSection.group(2).replaceAll("\\s+", ""));
            else
                return new Position(name);
        }

        final Matcher mNosw = P_NAME_NOSW.matcher(position);
        if (mNosw.matches())
            return new Position(Integer.toString(Integer.parseInt(mNosw.group(1))), mNosw.group(2).substring(0, 1));

        return new Position(position);
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        throw new UnsupportedOperationException("unpackTripRefFromMessage");
    }

    @Override
    public TripShare unpackTripShareFromMessage(final MessageUnpacker unpacker) throws IOException {
        throw new UnsupportedOperationException("unpackTripRefFromMessage");
    }

    @Override
    public String getOpenLink(final Trip trip) throws IOException {
        throw new UnsupportedOperationException("getOpenLink");
    }

    @Override
    public String getShareLink(final Trip trip) throws IOException {
        throw new UnsupportedOperationException("getShareLink");
    }

    @Override
    public TripShare shareTrip(final Trip trip) throws IOException {
        throw new UnsupportedOperationException("shareTrip");
    }

    @Override
    public QueryTripsResult loadSharedTrip(
            final TripShare tripShare, final boolean loadPath) throws IOException {
        throw new UnsupportedOperationException("loadSharedTrip");
    }

    @Override
    public TripRef createTripRefFromPreviousTripWithNewLegs(final Trip trip, final List<Trip.Leg> newLegs) {
        return null;
    }

    @Override
    public Trip queryTripDetails(final Trip trip, final List<TripDetails> whichDetails) throws IOException {
        if ((whichDetails == null || whichDetails.contains(TripDetails.TRANSFERS))
                && trip.transferDetails == null) {
            final TransferEvaluationProvider transferEvaluationProvider = getTransferEvaluationProvider();
            if (transferEvaluationProvider != null) {
                final List<TransferDetails> transferDetails = transferEvaluationProvider.evaluateTransfersForTrip(trip);
                if (transferDetails != null) {
                    final int numTransfers = transferDetails.size();
                    int numPublicLegs = 0;
                    for (final Trip.Leg tripLeg : trip.legs) {
                        if (tripLeg instanceof Trip.Public)
                            numPublicLegs += 1;
                    }
                    if (numTransfers == numPublicLegs - 1) {
                        trip.transferDetails = transferDetails.toArray(new TransferDetails[0]);
                    } else {
                        log.warn("unexpected {} transfers for {} public legs", numTransfers, numPublicLegs);
                    }
                }
            }
        }

        return trip;
    }

    @Override
    public TransferEvaluationProvider getTransferEvaluationProvider() {
        return null;
    }

    @Override
    public String getLocationInfoUrl(final Location location) {
        return null;
    }

    @Override
    public Description getDescription() {
        return new Description.Base() {
            @Override
            public String getName() {
                final String simpleClassName = AbstractNetworkProvider.this.getClass().getSimpleName();
                if (simpleClassName.endsWith("Provider"))
                    return simpleClassName.substring(0, simpleClassName.length() - 8);
                return simpleClassName;
            }

            @Override
            public String getDescriptionText() {
                return "provides timetable information";
            }
        };
    }

    private static final String[] PLACE_PREFIXES = {
            "Bad",
            "St",
            "St.",
    };

    protected String[] parseSpaceDelimitedPlaceAndStation(
            final String placeAndName,
            final String[] specialPlaces) {
        final int posFirstComma = placeAndName.indexOf(',');
        if (posFirstComma >= 0) {
            // legacy comma separated
            return new String[] {
                    placeAndName.substring(0, posFirstComma).trim(),
                    placeAndName.substring(posFirstComma + 1).trim()
            };
        }

        for (final String place: specialPlaces) {
            if (!placeAndName.startsWith(place))
                continue;

            // probably matches one of the special places
            final int placeLength = place.length();
            final String trailer = placeAndName.substring(placeLength);
            if (trailer.startsWith("-"))
                return new String[] { place, trailer.substring(1) };

            if (trailer.startsWith(" - "))
                return new String[] { place, trailer.substring(3) };

            if (trailer.startsWith(" "))
                return new String[] { place, trailer.substring(1) };
        }

        final int length = placeAndName.length();
        if (length <= 2 || Character.isUpperCase(placeAndName.charAt(1))) {
            // quick check seems like all upper case
            // then this is a railway station without explicit place name
            return new String[] { null, placeAndName };
        }

        // now split into words separated by dots and spaces
        boolean inPrefixArea = true;
        boolean nextWordIsPlace = true;
        int parenthesisLevel = 0;
        int placeEnd = 0;
        int wordStart = -1;
        int wordEnd = -1;
        int pos = 0;
        do {
            if (pos >= length) {
                wordEnd = length;
                ++pos;
            } else {
                final char ch = placeAndName.charAt(pos);

                if (ch == '(') {
                    ++parenthesisLevel;
                } else if (ch == ')') {
                    if (--parenthesisLevel < 0)
                        parenthesisLevel = 0;
                }

                if (ch == ' ') {
                    // word ends with space
                    if (wordStart < 0) {
                        ++pos;
                        continue;
                    }
                    wordEnd = pos++;
                } else if (ch == '.' && parenthesisLevel == 0) {
                    if (wordStart < 0)
                        wordStart = pos;
                    wordEnd = ++pos;
                } else {
                    if (wordStart < 0)
                        wordStart = pos;
                    ++pos;
                    continue;
                }
            }
            // we have a word
            if (inPrefixArea) {
                final String word = placeAndName.substring(wordStart, wordEnd);
                inPrefixArea = false;
                for (final String placePrefix : PLACE_PREFIXES) {
                    if (word.equals(placePrefix)) {inPrefixArea = true;
                        break;
                    }
                }
            }
            final char firstChar = wordStart < 0 || wordStart >= length ? 0 : placeAndName.charAt(wordStart);
            wordStart = -1;
            if (inPrefixArea) {
                // a prefix is part of place
            } else if (Character.isLowerCase(firstChar)) {
                // lower case, force next word as part of place
                nextWordIsPlace = true;
            } else if (nextWordIsPlace) {
                // we know the word as part of place
                nextWordIsPlace = false;
            } else if (firstChar == '(') {
                // word in parenthesis is part of place
            } else {
                // this word is part of station name
                break;
            }
            placeEnd = wordEnd;
        } while (pos <= length);
        final String stationName = placeAndName.substring(placeEnd).trim();
        if (stationName.isEmpty())
            return new String[] { null, placeAndName };

        final String placeName = placeAndName.substring(0, placeEnd).trim();
        if (placeName.isEmpty())
            return new String[] { null, placeAndName };

        return new String[] { placeName, stationName };
    }
}
