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

import static java.util.Objects.requireNonNull;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serial;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Destination;
import de.schildbach.pte.dto.JourneyRef;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.PTDate;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import okhttp3.HttpUrl;

public abstract class AbstractOpenJourneyPlannerProvider extends AbstractNetworkProvider {
    protected static final String NS_OJP = "http://www.vdv.de/ojp";
    protected static final String NS_SIRI = "http://www.siri.org.uk/siri";
    protected static final String OJP_VERSION = "2.0";

    private static final int DEFAULT_MAX_LOCATIONS = 20;
    private static final int DEFAULT_MAX_DISTANCE = 10000;

    protected static final Set<Capability> CAPABILITIES = Set.of(
        Capability.SUGGEST_LOCATIONS,
        Capability.NEARBY_LOCATIONS,
        Capability.DEPARTURES,
        Capability.TRIPS,
        Capability.TRIPS_VIA,
        Capability.BIKE_OPTION,
        Capability.DIRECT_OPTION,
        Capability.MIN_TRANSFER_TIMES,
        Capability.JOURNEY,
        Capability.TRIP_RELOAD
    );

    public static class OJPJourneyRef extends JourneyRef {
        @Serial
        private static final long serialVersionUID = 4464112748447706292L;

        public final String journeyId;
        public final String opDay;

        public OJPJourneyRef(
                final String journeyId,
                final String opDay) {
            this.journeyId = journeyId;
            this.opDay = opDay;
        }

        @Override
        public String getUniqueId() {
            return journeyId + "@" + opDay;
        }
    }

    private final ResultHeader resultHeader;

    private final HttpUrl apiEndpoint;
    private String requestorRef;

    private final DocumentBuilder documentBuilder;
    private final Transformer transformer;

    protected AbstractOpenJourneyPlannerProvider(
            final NetworkId network,
            final HttpUrl apiEndpoint) {
        super(network);
        this.apiEndpoint = requireNonNull(apiEndpoint);

        try {
            final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            documentBuilder = documentBuilderFactory.newDocumentBuilder();

            final TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformer = transformerFactory.newTransformer();
        } catch (final ParserConfigurationException | TransformerConfigurationException e) {
            throw new RuntimeException(e);
        }

        this.resultHeader = new ResultHeader(network, "OJP");
    }

    protected String getAuthorization() {
        return null;
    }

    public void setRequestorRef(final String requestorRef) {
        this.requestorRef = requestorRef;
    }

    @Override
    protected Set<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    private static final DateFormat ISO_DATE_TIME_UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    static {
        ISO_DATE_TIME_UTC_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public String isoTimestamp(final Date date) {
        if (date == null)
            return null;
        return ISO_DATE_TIME_UTC_FORMAT.format(date);
    }

    private PTDate parseIsoTimestamp(final String time) {
        if (time == null)
            return null;
        try {
            return PTDate.withUnknownLocationSpecificOffset(ISO_DATE_TIME_UTC_FORMAT.parse(time).getTime());
        } catch (final ParseException x) {
            throw new RuntimeException(x);
        }
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

    public class OJPRequest {
        final Document document;
        final Element rootElement;
        final Date timestamp;

        Element ojpRequest;

        protected OJPRequest() {
            timestamp = new Date();
            document = documentBuilder.newDocument();
            rootElement = document.createElementNS(NS_OJP, "OJP");
            addNamespace(NS_OJP, null);
            addNamespace(NS_SIRI, "siri");
            rootElement.setAttributeNS(NS_OJP, "version", OJP_VERSION);
            document.appendChild(rootElement);
        }

        private void addNamespace(final String namespaceURI, final String prefix) {
            rootElement.setAttributeNS("http://www.w3.org/2000/xmlns/", prefix == null ? "xmlns" : ("xmlns:" + prefix), namespaceURI);
        }

        public void createTextElement(final Node parent, final String namespaceURI, final String qualifiedName, final String textContent) {
            final Element element = createElement(parent, namespaceURI, qualifiedName);
            element.setTextContent(textContent);
        }

        public void createTextElement(final Node parent, final String namespaceURI, final String qualifiedName, final int intContent) {
            createTextElement(parent, namespaceURI, qualifiedName, Integer.toString(intContent));
        }

        public void createTextElement(final Node parent, final String namespaceURI, final String qualifiedName, final boolean boolContent) {
            createTextElement(parent, namespaceURI, qualifiedName, Boolean.toString(boolContent));
        }

        public void createTextElement(final Node parent, final String namespaceURI, final String qualifiedName, final Date date) {
            createTextElement(parent, namespaceURI, qualifiedName, isoTimestamp(date));
        }

        public void createTextElement(final Node parent, final String namespaceURI, final String qualifiedName, final Object objContent) {
            createTextElement(parent, namespaceURI, qualifiedName, objContent.toString());
        }

        public Element createElement(final Node parent, final String namespaceURI, final String qualifiedName) {
            final Element element = document.createElementNS(namespaceURI, qualifiedName);
            parent.appendChild(element);
            return element;
        }

        public void createTextElement(final Node parent, final String qualifiedName, final String textContent) {
            createTextElement(parent, NS_OJP, qualifiedName, textContent);
        }

        public void createTextElement(final Node parent, final String qualifiedName, final int intContent) {
            createTextElement(parent, NS_OJP, qualifiedName, intContent);
        }

        public void createTextElement(final Node parent, final String qualifiedName, final boolean boolContent) {
            createTextElement(parent, NS_OJP, qualifiedName, boolContent);
        }

        public void createTextElement(final Node parent, final String qualifiedName, final Date date) {
            createTextElement(parent, NS_OJP, qualifiedName, date);
        }

        public void createTextElement(final Node parent, final String qualifiedName, final Object objContent) {
            createTextElement(parent, NS_OJP, qualifiedName, objContent);
        }

        public Element createElement(final Node parent, final String qualifiedName) {
            return createElement(parent, NS_OJP, qualifiedName);
        }

        public Element createTranslatedTextElement(final Node parent, final String qualifiedName, final String text) {
            final Element element = createElement(parent, NS_OJP, qualifiedName);
            createTextElement(element, "Text", text);
            return element;
        }

        public void addRequestTimestamp(final Element parent) {
            createTextElement(parent, NS_SIRI, "siri:RequestTimestamp", timestamp);
        }

        public Element createRequest(final String requestElementName) {
            ojpRequest = createElement(rootElement, NS_OJP, "OJPRequest");
            final Element siriServiceRequest = createElement(ojpRequest, NS_SIRI, "siri:ServiceRequest");
            final Element siriServiceRequestContext = createElement(siriServiceRequest, NS_SIRI, "siri:ServiceRequestContext");
            createTextElement(siriServiceRequestContext, NS_SIRI, "siri:Language", userInterfaceLanguage == null ? "en" : userInterfaceLanguage);
            addRequestTimestamp(siriServiceRequest);
            createTextElement(siriServiceRequest, NS_SIRI, "siri:RequestorRef", requestorRef == null ? "client" : requestorRef);
            final Element request = createElement(siriServiceRequest, NS_OJP, requestElementName);
            addRequestTimestamp(request);
            return request;
        }

        public String toXml() {
            try {
                final StringWriter stringWriter = new StringWriter();
                transformer.transform(new DOMSource(document), new StreamResult(stringWriter));
                return stringWriter.toString();
            } catch (final TransformerException e) {
                throw new RuntimeException(e);
            }
        }

        public void createGeoPoint(final Element parent, final String qualifiedName, final Point point) {
            if (point != null)
                createGeoPoint(createElement(parent, qualifiedName), point);
        }

        public void createGeoPoint(final Element element, final Point point) {
            if (point == null)
                return;
            createTextElement(element, NS_SIRI, "siri:Longitude", point.getLonAsDouble());
            createTextElement(element, NS_SIRI, "siri:Latitude", point.getLatAsDouble());
        }
    }

    public class OJPResponse {
        final Document document;
        final Element rootElement;
        final Element serviceDelivery;

        protected OJPResponse(final String xmlSource) throws IOException {
            try {
                document = documentBuilder.parse(new ByteArrayInputStream(xmlSource.getBytes(StandardCharsets.UTF_8)));
            } catch (final SAXException e) {
                throw new IOException(e);
            }
            rootElement = document.getDocumentElement();
            final String rootName = rootElement.getTagName();
            if (!"OJP".equals(rootName))
                throw new ParserException("root element expected OJP, got " + rootName);
            serviceDelivery = getElement(rootElement, NS_SIRI, "ServiceDelivery");
        }

        public NodeList getElements(final Element parent, final String elementName) {
            return getElements(parent, NS_OJP, elementName);
        }

        public Element getElement(final Element parent, final String elementName) {
            return getElement(parent, NS_OJP, elementName);
        }

        public NodeList getElements(final Element parent, final String namespaceURI, final String elementName) {
            return parent.getElementsByTagNameNS(namespaceURI, elementName);
        }

        public Element getElement(final Element parent, final String namespaceURI, final String elementName) {
            final NodeList nodeList = parent.getElementsByTagNameNS(namespaceURI, elementName);
            final int length = nodeList.getLength();
            if (length == 0)
                return null;
            return (Element) nodeList.item(0);
        }

        public String getTextElement(final Element parent, final String elementName) {
            return getTextElement(parent, NS_OJP, elementName);
        }

        public boolean getBooleanElement(final Element parent, final String elementName, final boolean defaultValue) {
            final String value = getTextElement(parent, elementName);
            if (value == null)
                return defaultValue;
            if (value.equals("true"))
                return true;
            if (value.equals("false"))
                return false;
            return defaultValue;
        }

        public String getTextElement(final Element parent, final String namespaceURI, final String elementName) {
            final NodeList nodeList = parent.getElementsByTagNameNS(namespaceURI, elementName);
            final int length = nodeList.getLength();
            if (length == 0)
                return null;
            final Element element = (Element) nodeList.item(0);
            return element.getTextContent();
        }

        public Element getResponse(final String expectedResponseElementName) throws IOException {
            final Element eLement = getElement(serviceDelivery, expectedResponseElementName);
            if (eLement == null)
                throw new ParserException("bad response: expected " + expectedResponseElementName);
            return eLement;
        }

        public String getTranslatedText(final Element parent, final String elementName) {
            final Element element = getElement(parent, elementName);
            if (element == null)
                return null;
            final NodeList textElements = getElements(element, "Text");
            if (textElements == null)
                return null;
            String primary = null;
            String secondary = null;
            String found = null;
            for (int index = 0; index < textElements.getLength(); ++index) {
                final Element text = (Element) textElements.item(index);
                final String lang = text.getAttribute("xml:lang");
                final String content = text.getTextContent();
                if (lang == null) {
                    if (found == null)
                        found = content;
                } else if (lang.equals(userInterfaceLanguage)) {
                    primary = content;
                } else if (lang.equals("en")) {
                    secondary = content;
                }
            }
            if (primary != null)
                return primary;
            if (secondary != null)
                return secondary;
            return found;
        }

        public Point getGeoPoint(final Element parent, final String elementName) {
            return getGeoPoint(getElement(parent, elementName));
        }

        public Point getGeoPoint(final Element parent, final String namespaceURI, final String elementName) {
            return getGeoPoint(getElement(parent, namespaceURI, elementName));
        }

        public Point getGeoPoint(final Element element) {
            if (element == null)
                return null;
            final String longitude = getTextElement(element, NS_SIRI, "Longitude");
            final String latitude = getTextElement(element, NS_SIRI, "Latitude");
            return Point.fromDouble(Double.parseDouble(latitude), Double.parseDouble(longitude));
        }
    }

    protected OJPResponse doRequest(final OJPRequest document) throws IOException {
        return doRequest(document, 0);
    }

    protected OJPResponse doRequest(final OJPRequest request, final long callTimeoutSecs) throws IOException {
        final String xmlRequest = request.toXml();
        httpClient.setHeader("Content-Type", "application/xml");
        final String authorization = getAuthorization();
        if (authorization != null)
            httpClient.setHeader("Authorization", authorization);
        final CharSequence xmlResponse = httpClient.get(apiEndpoint, xmlRequest, null, callTimeoutSecs);
        return new OJPResponse(xmlResponse.toString());
    }

    protected String[] splitPlaceAndName(final String placeAndName, final Pattern p, final int place, final int name) {
        if (placeAndName == null)
            return new String[] { null, null };
        final Matcher m = p.matcher(placeAndName);
        if (m.matches())
            return new String[] { m.group(place), m.group(name) };
        return new String[] { null, placeAndName };
    }

    private static final Pattern P_SPLIT_NAME_ONE_COMMA = Pattern.compile("([^,(]*(\\([^)]*\\))?), ([^,]*)");
    protected String[] splitStationName(final String name) {
        return splitPlaceAndName(name, P_SPLIT_NAME_ONE_COMMA, 1, 3);
    }

    private static final Pattern P_SPLIT_NAME_FIRST_COMMA = Pattern.compile("([^,]*), (.*)");
    protected String[] splitAddress(final String address) {
        return splitPlaceAndName(address, P_SPLIT_NAME_FIRST_COMMA, 1, 2);
    }

    protected Location createLocation(
            final LocationType type,
            final String id,
            final Point coord,
            final String name) {
        final String[] placeAndName =
                type == LocationType.STATION ? splitStationName(name)
                        : type == LocationType.DIRECTION ? splitStationName(name)
                          : splitAddress(name);
        return new Location(type, id, coord, placeAndName[0], placeAndName[1], null);
    }

    private static final Collection<LocationType> ALL_LOCATION_TYPES = Set.of(
            LocationType.STATION,
            LocationType.ADDRESS,
            LocationType.POI);

    private static final Map<String, Product> PTMODE_MAP = new LinkedHashMap<>() {
        @Serial
        private static final long serialVersionUID = 6581845892244269924L;

        {
            put("rail", Product.HIGH_SPEED_TRAIN);
            put("rail/highSpeedRail", Product.HIGH_SPEED_TRAIN);
            put("rail/local", Product.REGIONAL_TRAIN);
            put("rail/regionalRail", Product.REGIONAL_TRAIN);
            put("rail/suburbanRailway", Product.SUBURBAN_TRAIN);
            put("metro", Product.SUBWAY);
            put("subway", Product.SUBWAY);
            put("tram", Product.TRAM);
            put("coach", Product.COACH);
            put("bus", Product.BUS);
            put("bus/demandAndResponseBus", Product.ON_DEMAND);
            put("water", Product.FERRY);
            put("ferry", Product.FERRY);
            put("telecabin", Product.CABLECAR);
            put("gondola", Product.CABLECAR);
            put("cableCar", Product.CABLECAR);
            put("funicular", Product.CABLECAR);
            put("replacementRailService", Product.REPLACEMENT_SERVICE);
            put("unknown", Product.UNKNOWN);
        }
    };

    private static final Map<String, String> SUBMODE_MAP = new LinkedHashMap<>() {
        @Serial
        private static final long serialVersionUID = 6581845892244269924L;

        {
            put("rail", "RailSubmode");
            put("bus", "BusSubmode");
        }
    };

    private Product productForPtMode(final OJPResponse response, final Element mode) {
        final String ptMode = response.getTextElement(mode, "PtMode");
        final String subModeElementName = SUBMODE_MAP.get(ptMode);
        if (subModeElementName != null) {
            final String submode = response.getTextElement(mode, NS_SIRI, subModeElementName);
            if (submode != null) {
                final Product product = PTMODE_MAP.get(ptMode + "/" + submode);
                if (product != null)
                    return product;
            }
        }
        return PTMODE_MAP.get(ptMode);
    }

    private List<Location> findLocations(
            @Nullable final CharSequence constraint,
            @Nullable final Location centerLocation,
            final int maxDistance,
            @Nullable final EquivalentStationsMode equivsMode,
            @Nullable final Set<LocationType> types,
            final int maxLocations,
            final boolean includePtModes) throws IOException {
        final OJPRequest document = new OJPRequest();
        final Element request = document.createRequest("OJPLocationInformationRequest");
        final Element initialInput = document.createElement(request, "InitialInput");
        if (constraint != null) {
            document.createTextElement(initialInput, "Name", constraint);
        }
        if (centerLocation != null) {
            final Element geoRestriction = document.createElement(initialInput, "GeoRestriction");
            final Element circle = document.createElement(geoRestriction, "Circle");
            document.createGeoPoint(circle, "Center", centerLocation.coord);
            document.createTextElement(circle, "Radius", maxDistance);
        }
        final Element restrictions = document.createElement(request, "Restrictions");
        final Set<String> locTypes = new HashSet<>();
        for (final LocationType type : (types == null || types.contains(LocationType.ANY)) ? ALL_LOCATION_TYPES : types) {
            switch (type) {
                case STATION: locTypes.add("stop"); break;
                case ADDRESS: locTypes.add("location"); break;
                case POI: locTypes.add("poi"); break;
            }
        }
        if (locTypes.size() < 3) { // if all include, then add no restrictions
            for (final String locType : locTypes) {
                document.createTextElement(restrictions, "Type", locType);
            }
        }
        document.createTextElement(restrictions, "NumberOfResults", maxLocations);
        document.createTextElement(restrictions, "IncludePtModes", includePtModes);

        final OJPResponse response = doRequest(document);
        final Element locationInformation = response.getResponse("OJPLocationInformationDelivery");

        final List<Location> locations = new ArrayList<>();

        final NodeList placeResults = response.getElements(locationInformation, "PlaceResult");
        for (int placeResultIndex = 0; placeResultIndex < placeResults.getLength(); ++placeResultIndex) {
            final Element placeResult = (Element) placeResults.item(placeResultIndex);
            final Element place = response.getElement(placeResult, "Place");
            final String name = response.getTranslatedText(place, "Name");
            final Point geoPosition = response.getGeoPoint(place, "GeoPosition");
            final Element stopPlace = response.getElement(place, "StopPlace");
            Location location = null;
            if (stopPlace != null) {
                final Set<Product> products = new HashSet<>();
                final NodeList modes = response.getElements(place, "Mode");
                for (int modesIndex = 0; modesIndex < modes.getLength(); ++modesIndex) {
                    final Element mode = (Element) modes.item(modesIndex);
                    final Product product = productForPtMode(response, mode);
                    if (product != null)
                        products.add(product);
                }
                final String stopPlaceName = response.getTranslatedText(stopPlace, "StopPlaceName");
                final String stopPlaceRef = response.getTextElement(stopPlace, "StopPlaceRef");
                final String[] placeAndName = splitStationName(stopPlaceName != null ? stopPlaceName : name);
                location = new Location(LocationType.STATION, stopPlaceRef, geoPosition, placeAndName[0], placeAndName[1], products);
            } else {
                final Element address = response.getElement(place, "Address");
                if (address != null) {
                    final String addressName = response.getTranslatedText(address, "Name");
                    final String publicCode = response.getTextElement(address, "PublicCode");
                    final String[] placeAndName = splitAddress(addressName != null ? addressName : name);
                    location = new Location(LocationType.ADDRESS, publicCode, geoPosition, placeAndName[0], placeAndName[1]);
                } else {
                    final Element pointOfInterest = response.getElement(place, "PointOfInterest");
                    if (pointOfInterest != null) {
                        // TODO
                    }
                }
            }

            if (location != null)
                locations.add(location);
        }

        return locations;
    }

    @Override
    public SuggestLocationsResult suggestLocations(
            final CharSequence constraint,
            @Nullable final Set<LocationType> types,
            final int maxLocations) throws IOException {
        try {
            final List<Location> locations = findLocations(constraint, null, 0, null, types, maxLocations, false);
            final List<SuggestedLocation> suggestedLocations = new ArrayList<>(locations.size());
            for (final Location location : locations)
                suggestedLocations.add(new SuggestedLocation(location));
            return new SuggestLocationsResult(this.resultHeader, suggestedLocations);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting locations", e);
                return new SuggestLocationsResult(this.resultHeader, SuggestLocationsResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(
            final Set<LocationType> types,
            final Location location,
            final EquivalentStationsMode equivsMode,
            int maxDistance,
            int maxLocations,
            final Set<Product> products) throws IOException {
        if (maxDistance == 0)
            maxDistance = DEFAULT_MAX_DISTANCE;
        if (maxLocations == 0)
            maxLocations = DEFAULT_MAX_LOCATIONS;
        try {
            final List<Location> locations = findLocations(null, location, maxDistance, equivsMode, types, maxLocations, true);
            return new NearbyLocationsResult(this.resultHeader, locations);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.SERVICE_DOWN);
        }
    }

    private class StopPlaceMap {
        private final Map<String, Location> stopPlaceMap = new HashMap<>(); // stopPlaceRef -> location
        private final Map<String, String> stopPointMap = new HashMap<>(); // stopPointRef -> stopPlaceRef

        StopPlaceMap(final OJPResponse response, final Element parent) {
            final NodeList places = response.getElements(parent, "Place");
            for (int index = 0; index < places.getLength(); ++index) {
                final Element place = (Element) places.item(index);
                final Element stopPlace = response.getElement(place, "StopPlace");
                if (stopPlace != null) {
                    final String stopPlaceRef = response.getTextElement(stopPlace, "StopPlaceRef");
                    final String stopPlaceName = response.getTranslatedText(stopPlace, "StopPlaceName");
                    final Point geoPosition = response.getGeoPoint(stopPlace, "GeoPosition");
                    final Location location = createLocation(LocationType.STATION, stopPlaceRef, geoPosition, stopPlaceName);
                    stopPlaceMap.put(stopPlaceRef, location);
                } else {
                    final Element stopPoint = response.getElement(place, "StopPoint");
                    if (stopPoint != null) {
                        final String stopPointRef = response.getTextElement(stopPoint, NS_SIRI, "StopPointRef");
                        final String parentRef = response.getTextElement(stopPoint, "ParentRef");
                        stopPointMap.put(stopPointRef, parentRef);
                    }
                }
            }
        }

        String getStopPlaceRefForStopPoint(final String stopPointRef) {
            return stopPointMap.get(stopPointRef);
        }

        Location getStopPlaceLocation(final String stopPlaceRef) {
            return stopPlaceMap.get(stopPlaceRef);
        }
    }

    @Override
    public QueryDeparturesResult queryDepartures(
            final String stationId,
            @Nullable final Date time,
            final int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        try {
            final OJPRequest document = new OJPRequest();
            final Element request = document.createRequest("OJPStopEventRequest");
            final Element locationElement = document.createElement(request, "Location");
            final Element placeRef = document.createElement(locationElement, "PlaceRef");
            document.createTextElement(placeRef, NS_SIRI, "siri:StopPointRef", stationId);
            document.createTranslatedTextElement(placeRef, "Name", "n/a");
            document.createTextElement(locationElement, "DepArrTime", time);
            final Element params = document.createElement(request, "Params");
            // document.createTextElement(params, "IncludeAllRestrictedLines", false);
            document.createTextElement(params, "StopEventType", "departure");
            document.createTextElement(params, "NumberOfResults", maxDepartures);
//            document.createTextElement(params, "IncludePreviousCalls", false);
//            document.createTextElement(params, "IncludeOnwardCalls", false);
            document.createTextElement(params, "UseRealtimeData", "explanatory");
            if (products != null) {
                final Element modeFilter = document.createElement(params, "ModeFilter");
                document.createTextElement(modeFilter, "Exclude", false);
                PTMODE_MAP.forEach((ptMode, product) -> {
                    if (!products.contains(product))
                        return;
                    final int slash = ptMode.indexOf('/');
                    if (slash >= 0) {
                        final String submode = ptMode.substring(slash + 1);
                        final String submodeElementName = SUBMODE_MAP.get(ptMode);
                        document.createTextElement(modeFilter, NS_SIRI, "siri:" + submodeElementName, submode);
                    } else {
                        document.createTextElement(modeFilter, "PtMode", ptMode);
                    }
                });
            }

            final OJPResponse response = doRequest(document);
            final Element stopEventDelivery = response.getResponse("OJPStopEventDelivery");

            final Element stopEventResponseContext = response.getElement(stopEventDelivery, "StopEventResponseContext");
            final StopPlaceMap stopPlaceMap = new StopPlaceMap(response, response.getElement(stopEventResponseContext, "Places"));

            final QueryDeparturesResult departuresResult = new QueryDeparturesResult(this.resultHeader);

            final NodeList stopEventResults = response.getElements(stopEventDelivery, "StopEventResult");
            for (int eventIndex = 0; eventIndex < stopEventResults.getLength(); ++eventIndex) {
                final Element stopEvent = (Element) stopEventResults.item(eventIndex);
                final Element call = response.getElement(stopEvent, "ThisCall");
                final Element callAtStop = response.getElement(call, "CallAtStop");
                final String stopPointRef = response.getTextElement(callAtStop, NS_SIRI, "StopPointRef");
                if (equivsMode == EquivalentStationsMode.USE_META && !stationId.equals(stopPointRef)) {
                    continue;
                }

                final String stopPlaceRef = stopPlaceMap.getStopPlaceRefForStopPoint(stopPointRef);
                final Location location = stopPlaceMap.getStopPlaceLocation(stopPlaceRef);
                StationDepartures stationDepartures = departuresResult.findStationDepartures(stopPlaceRef);
                if (stationDepartures == null) {
                    stationDepartures = new StationDepartures(location, new ArrayList<Departure>(8), null);
                    departuresResult.stationDepartures.add(stationDepartures);
                }

                final Element serviceDeparture = response.getElement(callAtStop, "ServiceDeparture");
                final PTDate plannedTime = parseIsoTimestamp(response.getTextElement(serviceDeparture, "TimetabledTime"));
                final PTDate predictedTime = parseIsoTimestamp(response.getTextElement(serviceDeparture, "EstimatedTime"));

                final Position plannedPosition = parsePosition(response.getTranslatedText(callAtStop, "PlannedQuay"));
                final Position predictedPosition = parsePosition(response.getTranslatedText(callAtStop, "EstimatedQuay"));

                final boolean cancelled = response.getBooleanElement(callAtStop, "NotServicedStop", false);

                final Element service = response.getElement(stopEvent, "Service");
                final String opDay = response.getTextElement(service, "OperatingDayRef");
                final String journeyRef = response.getTextElement(service, "JourneyRef");
                final Product product = productForPtMode(response, response.getElement(service, "Mode"));
                final String publishedServiceName = response.getTranslatedText(service, "PublishedServiceName");
                final String trainNumber = response.getTextElement(service, "TrainNumber");

                final Line line = new Line(
                        null,
                        null,
                        product,
                        publishedServiceName,
                        trainNumber == null ? publishedServiceName : publishedServiceName + "-" + trainNumber,
                        lineStyle(null, product, publishedServiceName));

                final String destinationText = response.getTranslatedText(service, "DestinationText");

                final String messages = null; // TODO: parse "situations"

                final Departure departure = new Departure(
                        plannedTime, predictedTime,
                        line,
                        plannedPosition, predictedPosition,
                        new Destination(createLocation(LocationType.DIRECTION, null, null, destinationText)),
                        cancelled,
                        null,
                        messages,
                        journeyRef == null ? null : new OJPJourneyRef(journeyRef, opDay));

                stationDepartures.departures.add(departure);
            }

            return departuresResult;
        } catch (final IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new QueryDeparturesResult(this.resultHeader, QueryDeparturesResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public QueryTripsResult queryTrips(
            final Location from,
            @Nullable final Location via,
            final Location to,
            final Date date,
            final boolean dep,
            @Nullable final TripOptions options,
            final boolean loadPath) throws IOException {
        return null;
    }

    @Override
    public QueryTripsResult queryMoreTrips(
            final QueryTripsContext context,
            final boolean later,
            final boolean loadPath) throws IOException {
        return null;
    }
}
