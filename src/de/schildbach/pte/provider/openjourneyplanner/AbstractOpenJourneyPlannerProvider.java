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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
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

    private final ResultHeader resultHeader;

    private HttpUrl apiEndpoint;
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

    public HttpUrl getEndpoint() {
        return apiEndpoint;
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

        public String isoTimestamp(final Date date) {
            return ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        }

        public void createTextElement(final Node parent, final String namespaceURI, final String qualifiedName, final String textContent) {
            final Element element = createElement(parent, namespaceURI, qualifiedName);
            element.setTextContent(textContent);
        }

        public Element createElement(final Node parent, final String namespaceURI, final String qualifiedName) {
            final Element element = document.createElementNS(namespaceURI, qualifiedName);
            parent.appendChild(element);
            return element;
        }

        public void createTextElement(final Node parent, final String qualifiedName, final String textContent) {
            createTextElement(parent, NS_OJP, qualifiedName, textContent);
        }

        public Element createElement(final Node parent, final String qualifiedName) {
            return createElement(parent, NS_OJP, qualifiedName);
        }

        public void addRequestTimestamp(final Element siriServiceRequest) {
            final Element ts = createElement(siriServiceRequest, NS_SIRI, "siri:RequestTimestamp");
            ts.setTextContent(isoTimestamp(timestamp));
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
            createTextElement(element, NS_SIRI, "siri:Longitude", Double.toString(point.getLonAsDouble()));
            createTextElement(element, NS_SIRI, "siri:Latitude", Double.toString(point.getLatAsDouble()));
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
            serviceDelivery = getELement(rootElement, NS_SIRI, "ServiceDelivery");
        }

        public NodeList getELements(final Element parent, final String elementName) {
            return getELements(parent, NS_OJP, elementName);
        }

        public Element getELement(final Element parent, final String elementName) {
            return getELement(parent, NS_OJP, elementName);
        }

        public NodeList getELements(final Element parent, final String namespaceURI, final String elementName) {
            return parent.getElementsByTagNameNS(namespaceURI, elementName);
        }

        public Element getELement(final Element parent, final String namespaceURI, final String elementName) {
            final NodeList nodeList = parent.getElementsByTagNameNS(namespaceURI, elementName);
            final int length = nodeList.getLength();
            if (length == 0)
                return null;
            return (Element) nodeList.item(0);
        }

        public String getTextELement(final Element parent, final String elementName) {
            return getTextELement(parent, NS_OJP, elementName);
        }

        public String getTextELement(final Element parent, final String namespaceURI, final String elementName) {
            final NodeList nodeList = parent.getElementsByTagNameNS(namespaceURI, elementName);
            final int length = nodeList.getLength();
            if (length == 0)
                return null;
            final Element element = (Element) nodeList.item(0);
            return element.getTextContent();
        }

        public Element getResponse(final String expectedResponseElementName) throws IOException {
            final Element eLement = getELement(serviceDelivery, expectedResponseElementName);
            if (eLement == null)
                throw new ParserException("bad response: expected " + expectedResponseElementName);
            return eLement;
        }

        public String getTranslatedText(final Element parent, final String elementName) {
            final Element eLement = getELement(parent, elementName);
            final NodeList textElements = getELements(eLement, "Text");
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
            return getGeoPoint(getELement(parent, elementName));
        }

        public Point getGeoPoint(final Element parent, final String namespaceURI, final String elementName) {
            return getGeoPoint(getELement(parent, namespaceURI, elementName));
        }

        public Point getGeoPoint(final Element element) {
            if (element == null)
                return null;
            final String longitude = getTextELement(element, NS_SIRI, "Longitude");
            final String latitude = getTextELement(element, NS_SIRI, "Latitude");
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

    private static final Collection<LocationType> ALL_LOCATION_TYPES = Set.of(
            LocationType.STATION,
            LocationType.ADDRESS,
            LocationType.POI);

    private List<Location> findLocations(
            @Nullable final CharSequence constraint,
            @Nullable final Location centerLocation,
            final int maxDistance,
            @Nullable final EquivalentStationsMode equivsMode,
            @Nullable final Set<LocationType> types,
            final int maxLocations) throws IOException {
        final OJPRequest document = new OJPRequest();
        final Element request = document.createRequest("OJPLocationInformationRequest");
        final Element initialInput = document.createElement(request, "InitialInput");
        if (constraint != null) {
            document.createTextElement(initialInput, "Name", constraint.toString());
        }
        if (centerLocation != null) {
            final Element geoRestriction = document.createElement(initialInput, "GeoRestriction");
            final Element circle = document.createElement(geoRestriction, "Circle");
            document.createGeoPoint(circle, "Center", centerLocation.coord);
            document.createTextElement(circle, "Radius", Integer.toString(maxDistance));
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
        document.createTextElement(restrictions, "NumberOfResults", Integer.toString(maxLocations));
        document.createTextElement(restrictions, "IncludePtModes", "true");

        final OJPResponse response = doRequest(document);
        final Element locationInformation = response.getResponse("OJPLocationInformationDelivery");

        final List<Location> locations = new ArrayList<>();

        final NodeList placeResults = response.getELements(locationInformation, "PlaceResult");
        for (int placeResultIndex = 0; placeResultIndex < placeResults.getLength(); ++placeResultIndex) {
            final Element placeResult = (Element) placeResults.item(placeResultIndex);
            final Element place = response.getELement(placeResult, "Place");
            final String name = response.getTranslatedText(place, "Name");
            final Point geoPosition = response.getGeoPoint(place, "GeoPosition");
            final Element stopPlace = response.getELement(place, "StopPlace");
            Location location = null;
            if (stopPlace != null) {
                final Set<Product> products = new HashSet<>();
                final NodeList modes = response.getELements(place, "Mode");
                for (int modesIndex = 0; modesIndex < modes.getLength(); ++modesIndex) {
                    final Element mode = (Element) modes.item(modesIndex);
                    final String ptMode = response.getTextELement(mode, "PtMode");
                    if ("rail".equals(ptMode)) {
                        final String submode = response.getTextELement(mode, NS_SIRI, "RailSubmode");
                        if ("local".equals(submode))
                            products.add(Product.REGIONAL_TRAIN);
                        else if ("regionalRail".equals(submode))
                            products.add(Product.REGIONAL_TRAIN);
                        else if ("suburbanRailway".equals(submode))
                            products.add(Product.SUBURBAN_TRAIN);
                        else
                            products.add(Product.HIGH_SPEED_TRAIN);
                    } else if ("metro".equals(ptMode)) {
                        products.add(Product.SUBWAY);
                    } else if ("subway".equals(ptMode)) {
                        products.add(Product.SUBWAY);
                    } else if ("tram".equals(ptMode)) {
                        products.add(Product.TRAM);
                    } else if ("bus".equals(ptMode)) {
                        products.add(Product.BUS);
                    } else if ("coach".equals(ptMode)) {
                        products.add(Product.COACH);
                    } else if ("water".equals(ptMode)) {
                        products.add(Product.FERRY);
                    } else if ("ferry".equals(ptMode)) {
                        products.add(Product.FERRY);
                    } else if ("telecabin".equals(ptMode)) {
                        products.add(Product.CABLECAR);
                    } else if ("gondola".equals(ptMode)) {
                        products.add(Product.CABLECAR);
                    } else if ("cableCar".equals(ptMode)) {
                        products.add(Product.CABLECAR);
                    } else if ("funicular".equals(ptMode)) {
                        products.add(Product.CABLECAR);
                    }
                }
                final String stopPlaceName = response.getTranslatedText(stopPlace, "StopPlaceName");
                final String stopPlaceRef = response.getTextELement(stopPlace, "StopPlaceRef");
                final String[] placeAndName = splitStationName(stopPlaceName != null ? stopPlaceName : name);
                location = new Location(LocationType.STATION, stopPlaceRef, geoPosition, placeAndName[0], placeAndName[1], products);
            } else {
                final Element address = response.getELement(place, "Address");
                if (address != null) {
                    final String addressName = response.getTranslatedText(address, "Name");
                    final String publicCode = response.getTextELement(address, "PublicCode");
                    final String[] placeAndName = splitAddress(addressName != null ? addressName : name);
                    location = new Location(LocationType.ADDRESS, publicCode, geoPosition, placeAndName[0], placeAndName[1]);
                } else {
                    final Element pointOfInterest = response.getELement(place, "PointOfInterest");
                    if (pointOfInterest != null) {

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
            final List<Location> locations = findLocations(constraint, null, 0, null, types, maxLocations);
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
            final List<Location> locations = findLocations(null, location, maxDistance, equivsMode, types, maxLocations);
            return new NearbyLocationsResult(this.resultHeader, locations);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new NearbyLocationsResult(this.resultHeader, NearbyLocationsResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public QueryDeparturesResult queryDepartures(
            final String stationId,
            @Nullable final Date time,
            final int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        return null;
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
