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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.msgpack.core.MessageUnpacker;

import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.dto.TripShare;

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

    protected boolean hasCapability(Capability capability) {
        return getCapabilities().contains(capability);
    }

    @Deprecated
    @Override
    public QueryTripsResult queryTrips(Location from, @Nullable Location via, Location to, Date date, boolean dep,
            @Nullable Set<Product> products, @Nullable Optimize optimize, @Nullable WalkSpeed walkSpeed,
            @Nullable Accessibility accessibility, @Nullable Set<TripFlag> flags) throws IOException {
        return queryTrips(from, via, to, date, dep,
                new TripOptions(products, optimize, walkSpeed, null, accessibility, flags));
    }

    @Override
    public QueryTripsResult queryReloadTrip(final TripRef tripRef) throws IOException {
        throw new UnsupportedOperationException("queryReloadTrip(\"" + tripRef + "\")");
    }

    @Override
    public QueryJourneyResult queryJourney(final JourneyRef journeyRef) throws IOException {
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

    @Override
    public Style lineStyle(final @Nullable String network, final @Nullable Product product,
                           final @Nullable String label) {
        final Style specialStyle = Standard.specialLineStyle(styles, network, product, label);
        if (specialStyle != null) return specialStyle;
        return Standard.defaultLineStyle(network, product, label);
    }

    @Override
    public Point[] getArea() throws IOException {
        return null;
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
            "([A-Z](?:\\s*-?\\s*[A-Z])?)?", Pattern.CASE_INSENSITIVE);

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
    public QueryTripsResult loadSharedTrip(final TripShare tripShare) throws IOException {
        throw new UnsupportedOperationException("loadSharedTrip");
    }

    @Override
    public Trip queryTripDetails(final Trip aTrip) throws IOException {
        if (aTrip.isDetailsLoaded)
            return aTrip;

        Trip trip = aTrip;

        final TransferEvaluationProvider transferEvaluationProvider = getTransferEvaluationProvider();
        if (transferEvaluationProvider != null)
            trip = transferEvaluationProvider.evaluateTransfersForTrip(trip);

        trip.isDetailsLoaded = true;
        return trip;
    }

    @Override
    public TransferEvaluationProvider getTransferEvaluationProvider() {
        return null;
    }
}
