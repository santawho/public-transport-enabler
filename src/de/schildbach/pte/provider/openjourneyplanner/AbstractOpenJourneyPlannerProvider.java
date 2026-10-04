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

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
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
import java.util.Objects;
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
import de.schildbach.pte.dto.QueryJourneyResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import de.schildbach.pte.dto.TripRef;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.provider.AbstractNetworkProvider;
import de.schildbach.pte.util.MessagePackUtils;
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
        Capability.ARRIVALS,
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
        public final String lineRef;
        public final String directionRef;
        public final String productCategoryRef;

        public OJPJourneyRef(
                final String journeyId,
                final String opDay,
                final String lineRef,
                final String directionRef,
                final String productCategoryRef) {
            this.journeyId = journeyId;
            this.opDay = opDay;
            this.lineRef = lineRef;
            this.directionRef = directionRef;
            this.productCategoryRef = productCategoryRef;
        }

        @Override
        public String getUniqueId() {
            return journeyId + "@" + opDay;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof OJPJourneyRef)) return false;
            final OJPJourneyRef that = (OJPJourneyRef) o;
            return Objects.equals(journeyId, that.journeyId)
                    && Objects.equals(opDay, that.opDay);
        }

        @Override
        public int hashCode() {
            return Objects.hash(journeyId, opDay);
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

    public static String isoTimestamp(final Date date) {
        if (date == null)
            return null;
        return ISO_DATE_TIME_UTC_FORMAT.format(date);
    }

    private static PTDate parseIsoTimestamp(final String time) {
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
        return parsePositionElement(position);
    }

    private static Position parsePositionElement(final String position) {
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

        public PTDate getTimestampElement(final Element parent, final String elementName) {
            return parseIsoTimestamp(getTextElement(parent, elementName));
        }
    }

    protected OJPResponse doRequest(final OJPRequest document) throws IOException {
        return doRequest(document, 30);
    }

    protected OJPResponse doRequest(final OJPRequest request, final long callTimeoutSecs) throws IOException {
        final String xmlRequest = request.toXml();
        httpClient.setHeader("Content-Type", "application/xml");
        final String authorization = getAuthorization();
        if (authorization != null)
            httpClient.setHeader("Authorization", authorization);
        final String xmlResponse = httpClient.get(apiEndpoint, xmlRequest, null, callTimeoutSecs).toString();
        return new OJPResponse(xmlResponse);
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
            put("urbanRail", Product.SUBURBAN_TRAIN);
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
            put("coach", "CoachSubmode"); // value may be "internationalCoach"
        }
    };

    private static Product productForPtMode(final OJPResponse response, final Element mode) {
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

    private static Element createModeElement(
            final OJPRequest document, final Element parent,
            final String elementName) {
        final Element element = document.createElement(parent, elementName);
        document.createTextElement(element, "Exclude", false);
        return element;
    }

    private static void createPtModeElement(
            final OJPRequest document, final Element parent,
            final String modeName) {
        document.createTextElement(parent, "PtMode", modeName);
    }

    private static void createSubModeElement(
            final OJPRequest document, final Element parent,
            final String elementName, final String subModeName, final String subModeValue) {
        document.createTextElement(createModeElement(document, parent, elementName), NS_SIRI, subModeName, subModeValue);
    }

    private static void createModeElements(
            final OJPRequest document, final Element parent,
            final String elementName, final Set<Product> products) {
        if (products == null)
            return;

        // first element: include all main PtModes
        final Element element = createModeElement(document, parent, elementName);
        if (products.contains(Product.HIGH_SPEED_TRAIN)) {
            createSubModeElement(document, parent, elementName, "siri:RailSubmode", "international");
            createSubModeElement(document, parent, elementName, "siri:RailSubmode", "highSpeedRail");
        }
        if (products.contains(Product.REGIONAL_TRAIN)) {
            // createPtModeElement(document, element, "rail");
            createSubModeElement(document, parent, elementName, "siri:RailSubmode", "interregionalRail");
            createSubModeElement(document, parent, elementName, "siri:RailSubmode", "local");
        }
        if (products.contains(Product.SUBURBAN_TRAIN)) {
            createSubModeElement(document, parent, elementName, "siri:RailSubmode", "railShuttle");
            createPtModeElement(document, element, "urbanRail");
        }
        if (products.contains(Product.BUS)) {
            createPtModeElement(document, element, "bus");
        }
        if (products.contains(Product.COACH)) {
            createPtModeElement(document, element, "coach");
        }
        if (products.contains(Product.SUBWAY)) {
            createPtModeElement(document, element, "metro");
        }
        if (products.contains(Product.TRAM)) {
            createPtModeElement(document, element, "tram");
        }
        if (products.contains(Product.FERRY)) {
            createPtModeElement(document, element, "water");
            createPtModeElement(document, element, "ferry");
        }
        if (products.contains(Product.REPLACEMENT_SERVICE)) {
            createPtModeElement(document, element, "replacementRailService");
        }
        if (products.contains(Product.CABLECAR)) {
            createPtModeElement(document, element, "funicular");
            createPtModeElement(document, element, "telecabin");
//            createPtModeElement(document, element, "gondola");
//            createPtModeElement(document, element, "cableCar");
        }
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
                    final Point geoPosition = response.getGeoPoint(place, "GeoPosition");
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

    private static Element createPlaceElement(
            final OJPRequest document, final Element request,
            final String elementName, final String placeRefName, final String stationId) {
        final Element element = document.createElement(request, elementName);
        final Element placeRef = document.createElement(element, placeRefName != null ? placeRefName : "PlaceRef");
        // document.createTextElement(placeRef, "StopPlaceRef", stationId);
        document.createTextElement(placeRef, NS_SIRI, "siri:StopPointRef", stationId);
        document.createTranslatedTextElement(placeRef, "Name", "n/a");
        return element;
    }

    private static Element createPlaceElement(
            final OJPRequest document, final Element parent,
            final String elementName, final String placeRefName, final Location location) {
        final Element element = document.createElement(parent, elementName);
        final Element placeRef = document.createElement(element, placeRefName != null ? placeRefName : "PlaceRef");
        if (location.hasId()) {
            document.createTextElement(placeRef, "StopPlaceRef", location.id);
            // document.createTextElement(placeRef, NS_SIRI, "siri:StopPointRef", location.id);
        } else if (location.hasCoord()) {
            document.createGeoPoint(placeRef, "GeoPosition", location.coord);
        }
        document.createTranslatedTextElement(placeRef, "Name", "n/a");
        return element;
    }

    @Override
    public QueryDeparturesResult queryStationBoard(
            final String stationId,
            @Nullable final Date time,
            final boolean arrivals,
            final int maxDepartures,
            final EquivalentStationsMode equivsMode,
            final Set<Product> products) throws IOException {
        assertStationBoardMode(arrivals);
        try {
            final OJPRequest document = new OJPRequest();
            final Element request = document.createRequest("OJPStopEventRequest");
            final Element locationElement = createPlaceElement(document, request, "Location", null, stationId);
            document.createTextElement(locationElement, "DepArrTime", time);
            final Element params = document.createElement(request, "Params");
            // document.createTextElement(params, "IncludeAllRestrictedLines", false);
            document.createTextElement(params, "StopEventType", arrivals ? "arrival" : "departure");
            document.createTextElement(params, "NumberOfResults", maxDepartures);
//            document.createTextElement(params, "IncludePreviousCalls", false);
//            document.createTextElement(params, "IncludeOnwardCalls", false);
            document.createTextElement(params, "UseRealtimeData", "explanatory");
            createModeElements(document, params, "ModeFilter", products);

            final OJPResponse response = doRequest(document);
            final Element stopEventDelivery = response.getResponse("OJPStopEventDelivery");

            final Element stopEventResponseContext = response.getElement(stopEventDelivery, "StopEventResponseContext");
            final StopPlaceMap stopPlaceMap = stopEventResponseContext == null ? null :
                    new StopPlaceMap(response, response.getElement(stopEventResponseContext, "Places"));

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
                final PTDate plannedTime = response.getTimestampElement(serviceDeparture, "TimetabledTime");
                final PTDate predictedTime = response.getTimestampElement(serviceDeparture, "EstimatedTime");

                final Position plannedPosition = parsePosition(response.getTranslatedText(callAtStop, "PlannedQuay"));
                final Position predictedPosition = parsePosition(response.getTranslatedText(callAtStop, "EstimatedQuay"));

                final boolean cancelled = response.getBooleanElement(callAtStop, "NotServicedStop", false);

                final Element serviceElement = response.getElement(stopEvent, "Service");
                final Service service = new Service(response, serviceElement);

                final Departure departure = new Departure(
                        arrivals,
                        plannedTime, predictedTime,
                        service.line,
                        plannedPosition, predictedPosition,
                        service.destination,
                        cancelled,
                        null,
                        service.message,
                        service.journeyRef);

                stationDepartures.departures.add(departure);
            }

            return departuresResult;
        } catch (final IOException | RuntimeException e) {
            log.error("error getting locations", e);
            return new QueryDeparturesResult(this.resultHeader, QueryDeparturesResult.Status.SERVICE_DOWN);
        }
    }

    public static class OJPTripsContext implements QueryTripsContext {
        @Serial
        private static final long serialVersionUID = 3369508237895354567L;

        public final Location from, via, to;
        public final boolean dep;
        public final TripOptions options;
        public final Date earliestTime, latestTime;

        public OJPTripsContext(
                final Location from, final @Nullable Location via, final Location to,
                final boolean dep,
                final TripOptions options,
                final Date earliestTime, final Date latestTime) {
            this.from = from;
            this.via = via;
            this.to = to;
            this.dep = dep;
            this.options = options;
            this.earliestTime = earliestTime;
            this.latestTime = latestTime;
        }

        @Override
        public boolean canQueryLater() {
            return true;
        }

        @Override
        public boolean canQueryEarlier() {
            return true;
        }
    }

    public static class OJPTripRef extends TripRef {
        @Serial
        private static final long serialVersionUID = 1807986075078252014L;

        public final String tripId;
        public final String tripRequestData;

        public OJPTripRef(
                final NetworkId network,
                final Location from, final Location via, final Location to,
                final String tripId,
                final String tripRequestData) {
            super(network, from, via, to);
            this.tripId = tripId;
            this.tripRequestData = tripRequestData;
        }

        public OJPTripRef(final NetworkId network, final MessageUnpacker unpacker) throws IOException {
            super(network, unpacker);
            this.tripId = MessagePackUtils.unpackNullableString(unpacker);
            this.tripRequestData = MessagePackUtils.unpackNullableString(unpacker);
        }

        @Override
        public void packToMessage(final MessagePacker packer) throws IOException {
            super.packToMessage(packer);
            MessagePackUtils.packNullableString(packer, tripId);
            MessagePackUtils.packNullableString(packer, tripRequestData);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof OJPTripRef)) return false;
            final OJPTripRef that = (OJPTripRef) o;
            return super.equals(that)
                    && Objects.equals(tripId, that.tripId)
                    && Objects.equals(tripRequestData, that.tripRequestData);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), tripId, tripRequestData);
        }
    }

    @Override
    public TripRef unpackTripRefFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new OJPTripRef(network, unpacker);
    }

    private static void createMaxDistanceElement(
            final OJPRequest document, final Element parent,
            final int maxWalkDistanceMeters) {
        final Element individualTransportOption = document.createElement(parent, "IndividualTransportOption");

        final Element itModeAndModeOfOperation = document.createElement(individualTransportOption, "ItModeAndModeOfOperation");
        document.createTextElement(itModeAndModeOfOperation, "PersonalMode", "other");
        document.createTextElement(itModeAndModeOfOperation, "PersonalModeOfOperation", "own");

        document.createTextElement(individualTransportOption, "MaxDistance", maxWalkDistanceMeters);
    }

    private static int NUM_TRIPS_PER_BATCH = 10;

    @Override
    public QueryTripsResult queryTrips(
            final Location from,
            @Nullable final Location via,
            final Location to,
            final Date date,
            final boolean dep,
            @Nullable final TripOptions options,
            final boolean loadPath) throws IOException {
        return doQueryTrips(from, via, to, date, dep, options, loadPath, NUM_TRIPS_PER_BATCH, 0);
    }

    @Override
    public QueryTripsResult queryMoreTrips(
            final QueryTripsContext context,
            final boolean later,
            final boolean loadPath) throws IOException {
        final OJPTripsContext ctx = (OJPTripsContext) context;
        return doQueryTrips(
                ctx.from, ctx.via, ctx.to,
                later ? ctx.latestTime : ctx.earliestTime,
                ctx.dep,
                ctx.options,
                loadPath,
                later ? NUM_TRIPS_PER_BATCH : 1,
                later ? 0 : NUM_TRIPS_PER_BATCH);
    }

    private QueryTripsResult doQueryTrips(
            final Location from,
            @Nullable final Location via,
            final Location to,
            final Date date,
            final boolean dep,
            @Nullable final TripOptions options,
            final boolean loadPath,
            final int numResults,
            final int numResultsBefore) throws IOException {
        try {
            final OJPRequest document = new OJPRequest();
            final Element request = document.createRequest("OJPTripRequest");

            final Element origin = createPlaceElement(document, request, "Origin", null, from);
            final Element destination = createPlaceElement(document, request, "Destination", null, to);
            if (via != null)
                createPlaceElement(document, request, "Via", "ViaPoint", via);
            document.createTextElement(dep ? origin : destination, "DepArrTime", date);
            final Element params = document.createElement(request, "Params");
            document.createTextElement(params, "NumberOfResults", numResults);
            if (numResultsBefore > 0)
                document.createTextElement(params, "NumberOfResultsBefore", numResultsBefore);
            document.createTextElement(params, "UseRealtimeData", "explanatory");
            document.createTextElement(params, "IncludeIntermediateStops", true);
            if (loadPath) {
                document.createTextElement(params, "IncludeTrackSections", true);
                document.createTextElement(params, "IncludeLegProjection", true);
            }
            if (options != null) {
                createModeElements(document, params, "ModeAndModeOfOperationFilter", options.products);
                if (options.maxWalkDistanceMeters != null) {
                    createMaxDistanceElement(document, origin, options.maxWalkDistanceMeters);
                    createMaxDistanceElement(document, destination, options.maxWalkDistanceMeters);
                }
                if (options.walkSpeed != null) {
                    final String speed;
                    switch (options.walkSpeed) {
                        case SLOW: speed = "66"; break;
                        case FAST: speed = "150"; break;
                        case NORMAL:
                        default:
                            speed = "100"; break;
                    }
                    document.createTextElement(params, "WalkSpeed", speed);
                }
                if (options.flags != null) {
                    if (options.flags.contains(TripFlag.DIRECT))
                        document.createTextElement(params, "TransferLimit", 0);
                    if (options.flags.contains(TripFlag.BIKE))
                        document.createTextElement(params, "BikeTransport", true);
                }
                if (Optimize.LEAST_CHANGES.equals(options.optimize))
                    document.createTextElement(params, "OptimisationMethod", "minChanges");
                // options.minTransferTimeMinutes; --> there is no option for this in OJP
                // options.accessibility; --> none of the OJP options are supported yet
            }

            final OJPResponse response = doRequest(document);
            final Element tripDelivery = response.getResponse("OJPTripDelivery");
            final Date now = new Date();

            final List<Trip> trips = parseTripResult(response, tripDelivery, from, via, to, now);
            if (trips.isEmpty())
                return new QueryTripsResult(resultHeader, QueryTripsResult.Status.NO_TRIPS);

            final Trip lastTrip = trips.get(trips.size() - 1);
            final OJPTripsContext tripsContext = new OJPTripsContext(from, via, to, dep, options,
                    date, dep ? lastTrip.getMinTime() : lastTrip.getMaxTime() );
            return new QueryTripsResult(
                    resultHeader, null,
                    from, via, to,
                    tripsContext,
                    trips);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting trips", e);
            return new QueryTripsResult(resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        }
    }

    private List<Trip> parseTripResult(
            final OJPResponse response, final Element delivery,
            final Location from, final Location via, final Location to,
            final Date now) {
        final Element tripResponseContext = response.getElement(delivery, "TripResponseContext");
        final StopPlaceMap stopPlaceMap = tripResponseContext == null ? null :
                new StopPlaceMap(response, response.getElement(tripResponseContext, "Places"));

        final List<Trip> trips = new ArrayList<>();
        final NodeList tripResults = response.getElements(delivery, "TripResult");
        for (int eventIndex = 0; eventIndex < tripResults.getLength(); ++eventIndex) {
            final Element tripResult = (Element) tripResults.item(eventIndex);
            final Element tripData = response.getElement(tripResult, "Trip");
            final String tripId = response.getTextElement(tripData, "Id");
            final PTDate tripStartTime = response.getTimestampElement(tripData, "StartTime");
            final PTDate tripEndTime = response.getTimestampElement(tripData, "EndTime");

            final List<Trip.Leg> legs = new ArrayList<>();

            PTDate lastEndTime = tripStartTime;
            final List<Element> delayedTransferLegs = new ArrayList<>();

            final NodeList legsList = response.getElements(tripData, "Leg");
            for (int legIndex = 0; legIndex < legsList.getLength(); ++legIndex) {
                final Element legData = (Element) legsList.item(legIndex);

                final Element timedLeg = response.getElement(legData, "TimedLeg");
                if (timedLeg != null) {
                    final Trip.Public publicLeg = parseTimedLeg(
                            response, timedLeg,
                            stopPlaceMap, now);
                    if (!delayedTransferLegs.isEmpty()) {
                        final Trip.Individual individualLeg = parseTransferLegs(
                                response, delayedTransferLegs,
                                lastEndTime, publicLeg.getDepartureTime(), stopPlaceMap);
                        legs.add(individualLeg);
                        delayedTransferLegs.clear();
                    }
                    legs.add(publicLeg);
                    lastEndTime = publicLeg.getArrivalTime();
                } else {
                    final Element transferLeg = response.getElement(legData, "TransferLeg");
                    if (transferLeg != null) {
                        delayedTransferLegs.add(transferLeg);
                    } else {
                        final Element continuousLeg = response.getElement(legData, "ContinuousLeg");
                        if (continuousLeg != null) {
                            delayedTransferLegs.add(continuousLeg);
                        }
                    }
                }
            }
            if (!delayedTransferLegs.isEmpty()) {
                final Trip.Individual individualLeg = parseTransferLegs(
                        response, delayedTransferLegs,
                        lastEndTime, tripEndTime, stopPlaceMap);
                legs.add(individualLeg);
            }

            final StringBuilder tripRequestData = new StringBuilder();
            for (final Trip.Leg aLeg : legs) {
                if (!(aLeg instanceof Trip.Public))
                    continue;
                if (tripRequestData.length() > 0)
                    tripRequestData.append("~");
                final Trip.Public leg = (Trip.Public) aLeg;
                tripRequestData.append(leg.departureStop.location.id); // [0]
                tripRequestData.append("@");
                tripRequestData.append(isoTimestamp(leg.departureStop.plannedDepartureTime)); // [1]
                tripRequestData.append("@");
                tripRequestData.append(leg.arrivalStop.location.id); // [2]
                tripRequestData.append("@");
                tripRequestData.append(isoTimestamp(leg.arrivalStop.plannedArrivalTime));  // [3]
                tripRequestData.append("@");
                final OJPJourneyRef journeyRef = (OJPJourneyRef) leg.journeyRef;
                tripRequestData.append(journeyRef.journeyId); // [4]
                tripRequestData.append("@");
                tripRequestData.append(journeyRef.opDay); // [5]
                tripRequestData.append("@");
                tripRequestData.append(journeyRef.lineRef); // [6]
                tripRequestData.append("@");
                tripRequestData.append(journeyRef.directionRef); // [7]
                tripRequestData.append("@");
                tripRequestData.append(journeyRef.productCategoryRef); // [8]
            }
            final OJPTripRef tripRef = new OJPTripRef(network, from, via, to, tripId, tripRequestData.toString());
            final Trip trip = new Trip(now, tripId, tripRef, from, to, legs, null, null, null);
            trips.add(trip);
        }
        return trips;
    }

    private static Location parseLocationElement(
            final OJPResponse response, final Element parent, final String elementName,
            final StopPlaceMap stopPlaceMap) {
        final Element element = response.getElement(parent, elementName);
        final String stopPointRef = response.getTextElement(element, NS_SIRI, "StopPointRef");
        final String stopPlaceRef = stopPlaceMap.getStopPlaceRefForStopPoint(stopPointRef);
        return stopPlaceMap.getStopPlaceLocation(stopPlaceRef);
    }

    private static Stop parseStopElement(
            final OJPResponse response, final Element element,
            final StopPlaceMap stopPlaceMap) {
        final String stopPointRef = response.getTextElement(element, NS_SIRI, "StopPointRef");
        final String stopPlaceRef = stopPlaceMap.getStopPlaceRefForStopPoint(stopPointRef);
        final Location location = stopPlaceMap.getStopPlaceLocation(stopPlaceRef);
        final Position plannedPosition = parsePositionElement(response.getTranslatedText(element, "PlannedQuay"));
        final Position predictedPosition = parsePositionElement(response.getTranslatedText(element, "EstimatedQuay"));
        final boolean cancelled = response.getBooleanElement(element, "NotServicedStop", false);

        final PTDate plannedArrivalTime;
        final PTDate predictedArrivalTime;
        final Position plannedArrivalPosition;
        final Position predictedArrivalPosition;
        final boolean arrivalCancelled;
        final Element serviceArrival = response.getElement(element, "ServiceArrival");
        if (serviceArrival != null) {
            plannedArrivalTime = response.getTimestampElement(serviceArrival, "TimetabledTime");
            predictedArrivalTime = response.getTimestampElement(serviceArrival, "EstimatedTime");
            plannedArrivalPosition = plannedPosition;
            predictedArrivalPosition = predictedPosition;
            arrivalCancelled = cancelled;
        } else {
            plannedArrivalTime = null;
            predictedArrivalTime = null;
            plannedArrivalPosition = null;
            predictedArrivalPosition = null;
            arrivalCancelled = false;
        }
        final PTDate plannedDepartureTime;
        final PTDate predictedDepartureTime;
        final Position plannedDeparturePosition;
        final Position predictedDeparturePosition;
        final boolean departureCancelled;
        final Element serviceDeparture = response.getElement(element, "ServiceDeparture");
        if (serviceDeparture != null) {
            plannedDepartureTime = response.getTimestampElement(serviceDeparture, "TimetabledTime");
            predictedDepartureTime = response.getTimestampElement(serviceDeparture, "EstimatedTime");
            plannedDeparturePosition = plannedPosition;
            predictedDeparturePosition = predictedPosition;
            departureCancelled = cancelled;
        } else {
            plannedDepartureTime = null;
            predictedDepartureTime = null;
            plannedDeparturePosition = null;
            predictedDeparturePosition = null;
            departureCancelled = false;
        }

        return new Stop(
                location,
                plannedArrivalTime, predictedArrivalTime,
                plannedArrivalPosition, predictedArrivalPosition,
                arrivalCancelled,
                plannedDepartureTime, predictedDepartureTime,
                plannedDeparturePosition, predictedDeparturePosition,
                departureCancelled);
    }

    private List<Stop> parseStopList(
            final OJPResponse response, final Element parent,
            final String elementName,
            final StopPlaceMap stopPlaceMap) {
        final List<Stop> stops = new ArrayList<>();
        final int numStops = parseStopList(stops, response, parent, elementName, stopPlaceMap);
        return numStops == 0 ? null : stops;
    }

    private int parseStopList(
            final List<Stop> stops,
            final OJPResponse response, final Element parent,
            final String elementName,
            final StopPlaceMap stopPlaceMap) {
        final NodeList stopList = response.getElements(parent, elementName);
        final int numStops = stopList.getLength();
        for (int eventIndex = 0; eventIndex < numStops; ++eventIndex) {
            final Element call = (Element) stopList.item(eventIndex);
            final Stop stop = parseStopElement(response, call, stopPlaceMap);
            stops.add(stop);
        }
        return numStops;
    }

    private class Service {
        final Line line;
        final Destination destination;
        final OJPJourneyRef journeyRef;
        final String message;

        private Service(final OJPResponse response, final Element service) {
            final String journeyRefString = response.getTextElement(service, "JourneyRef");
            journeyRef = journeyRefString == null ? null : new OJPJourneyRef(
                    journeyRefString,
                    response.getTextElement(service, "OperatingDayRef"),
                    response.getTextElement(service, NS_SIRI, "LineRef"),
                    response.getTextElement(service, NS_SIRI, "DirectionRef"),
                    response.getTextElement(response.getElement(service, "ProductCategory"), "ProductCategoryRef"));

            final Product product = productForPtMode(response, response.getElement(service, "Mode"));
            final String publishedServiceName = response.getTranslatedText(service, "PublishedServiceName");
            final String trainNumber = response.getTextElement(service, "TrainNumber");

            line = new Line(
                    null,
                    null,
                    product,
                    publishedServiceName,
                    trainNumber == null ? publishedServiceName : publishedServiceName + "-" + trainNumber,
                    lineStyle(null, product, publishedServiceName));

            final String destinationText = response.getTranslatedText(service, "DestinationText");
            destination = new Destination(destinationText, createLocation(LocationType.DIRECTION, null, null, destinationText));

            final List<String> messages = new ArrayList<>();
            int numImportant = 0;

            final String operatorRef = response.getTextElement(service, NS_SIRI, "OperatorRef");
            if (operatorRef != null) {
                // TODO ??
                // final String operatorName;
                // messages.add("&#8226; " + operatorName);
            }

            final NodeList attributeList = response.getElements(service, "Attribute");
            final int numAttributes = attributeList.getLength();
            for (int attrIndex = 0; attrIndex < numAttributes; ++attrIndex) {
                final Element attribute = (Element) attributeList.item(attrIndex);
                final String userText = response.getTranslatedText(attribute, "UserText");
                messages.add(userText);
            }
            if (messages.isEmpty()) {
                message = null;
            } else {
                if (messagesAsSimpleHtml)
                    messages.add(0, LESS_IMPORTANT_HTML_SPLIT_MARKER);
                final String s = String.join(messagesAsSimpleHtml ? "<br>" : " - ", messages);
                if (numImportant == 0)
                    message = s.replace(LESS_IMPORTANT_HTML_SPLIT_MARKER + "<br>", LESS_IMPORTANT_HTML_SPLIT_MARKER);
                else
                    message = s.replace("<br>" + LESS_IMPORTANT_HTML_SPLIT_MARKER, LESS_IMPORTANT_HTML_SPLIT_MARKER);
            }
        }
    }

    private Trip.Public parseTimedLeg(
            final OJPResponse response, final Element timedLeg,
            final StopPlaceMap stopPlaceMap, final Date now) {
        final Stop departureStop = parseStopElement(response,
                response.getElement(timedLeg, "LegBoard"),
                stopPlaceMap);
        final Stop arrivalStop = parseStopElement(response,
                response.getElement(timedLeg, "LegAlight"),
                stopPlaceMap);

        final Element serviceElement = response.getElement(timedLeg, "Service");
        final Service service = new Service(response, serviceElement);
        final List<Stop> intermediateStops = parseStopList(response, timedLeg, "LegIntermediate", stopPlaceMap);

        return new Trip.Public(
                service.line,
                service.destination,
                departureStop,
                arrivalStop,
                intermediateStops,
                service.message,
                service.journeyRef,
                now);
    }

    private Trip.Individual parseTransferLegs(
            final OJPResponse response, final List<Element> transferLegs,
            final PTDate departureTime, final PTDate arrivalTime,
            final StopPlaceMap stopPlaceMap) {
        if (transferLegs.isEmpty())
            return null;

        final Trip.Individual.Type type;
        if (transferLegs.size() > 1) {
            type = Trip.Individual.Type.TRANSFER;
        } else {
            final Element transferLeg = transferLegs.get(0);
            final String transferType = response.getTextElement(transferLeg, "TransferType");
            if (transferType != null) {
                if ("walk".equals(transferType))
                    type = Trip.Individual.Type.WALK;
                else if ("bikeAndRide".equals(transferType))
                    type = Trip.Individual.Type.BIKE;
                else if ("parkAndRide".equals(transferType))
                    type = Trip.Individual.Type.CAR;
                else if ("checkIn".equals(transferType))
                    type = Trip.Individual.Type.CHECK_IN;
                else if ("checkOut".equals(transferType))
                    type = Trip.Individual.Type.CHECK_OUT;
                else
                    type = Trip.Individual.Type.TRANSFER;
            } else {
                final Element service = response.getElement(transferLeg, "Service");
                if (service == null) {
                    type = Trip.Individual.Type.TRANSFER;
                } else {
                    final String personalMode = response.getTextElement(service, "PersonalMode");
                    if (personalMode == null)
                        type = Trip.Individual.Type.TRANSFER;
                    else if ("foot".equals(personalMode))
                        type = Trip.Individual.Type.WALK;
                    else if ("bicycle".equals(personalMode))
                        type = Trip.Individual.Type.BIKE;
                    else if ("car".equals(personalMode))
                        type = Trip.Individual.Type.CAR;
                    else
                        type = Trip.Individual.Type.TRANSFER;
                }
            }
        }

        final Location legStart = parseLocationElement(response,
                transferLegs.get(0), "LegStart", stopPlaceMap);
        final Location legEnd = parseLocationElement(response,
                transferLegs.get(transferLegs.size() - 1), "LegEnd", stopPlaceMap);
        return new Trip.Individual(
                type,
                legStart, departureTime,
                legEnd, arrivalTime,
                -1);
    }

    @Override
    public QueryJourneyResult queryJourney(
            final JourneyRef aJourneyRef,
            final boolean splitSubJourneys,
            final boolean loadPath) throws IOException {
        final OJPJourneyRef journeyRef = (OJPJourneyRef) aJourneyRef;
        try {
            final OJPRequest document = new OJPRequest();
            final Element request = document.createRequest("OJPTripInfoRequest");

            document.createTextElement(request, "JourneyRef", journeyRef.journeyId);
            document.createTextElement(request, "OperatingDayRef", journeyRef.opDay);
            final Element params = document.createElement(request, "Params");
            document.createTextElement(params, "UseRealtimeData", "explanatory");
            document.createTextElement(params, "IncludePlacesContext", true);
            document.createTextElement(params, "IncludeCalls", true);
            document.createTextElement(params, "IncludeService", true);
            document.createTextElement(params, "IncludeSituationsContext", true);
            document.createTextElement(params, "IncludeTrackProjection", loadPath);

            final OJPResponse response = doRequest(document);
            final Element stopEventDelivery = response.getResponse("OJPTripInfoDelivery");
            final Date now = new Date();

            final Element tripResponseContext = response.getElement(stopEventDelivery, "TripInfoResponseContext");
            final StopPlaceMap stopPlaceMap = tripResponseContext == null ? null :
                    new StopPlaceMap(response, response.getElement(tripResponseContext, "Places"));

            final Element tripInfoResult = response.getElement(stopEventDelivery, "TripInfoResult");
            final Element serviceElement = response.getElement(tripInfoResult, "Service");
            if (serviceElement == null)
                return new QueryJourneyResult(resultHeader, QueryJourneyResult.Status.NO_JOURNEY);
            final Service service = new Service(response, serviceElement);

            final List<Stop> stops = new ArrayList<>();
            parseStopList(stops, response, tripInfoResult, "PreviousCall", stopPlaceMap);
            parseStopList(stops, response, tripInfoResult, "OnwardCall", stopPlaceMap);
            final int numStops = stops.size();

            final Stop arrivalStop = stops.remove(numStops - 1);
            final Stop departureStop = stops.remove(0);

            final Trip.Public journey = new Trip.Public(
                    service.line,
                    service.destination,
                    departureStop,
                    arrivalStop,
                    stops.isEmpty() ? null : stops,
                    service.message,
                    service.journeyRef,
                    now);

            return new QueryJourneyResult(
                    resultHeader, null,
                    journeyRef, journey);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting journey", e);
            return new QueryJourneyResult(resultHeader, QueryJourneyResult.Status.SERVICE_DOWN);
        }
    }

    @Override
    public QueryTripsResult queryReloadTrip(
            final TripRef aTripRef,
            final boolean loadPath) throws IOException {
        final OJPTripRef tripRef = (OJPTripRef) aTripRef;

        try {
            final OJPRequest document = new OJPRequest();
            final Element request = document.createRequest("OJPTripRefineRequest");

            final Element params = document.createElement(request, "RefineParams");
            document.createElement(params, "RefineLegRef"); // empty element -> refine all
            document.createTextElement(params, "UseRealtimeData", "explanatory");
            document.createTextElement(params, "IncludeIntermediateStops", true);
            if (loadPath) {
                document.createTextElement(params, "IncludeTrackSections", true);
                document.createTextElement(params, "IncludeLegProjection", true);
            }
            final Element tripResult = document.createElement(request, "TripResult");
            // document.createTextElement(tripResult, "Id", tripRef.tripId);
            final Element trip = document.createElement(tripResult, "Trip");
            document.createTextElement(trip, "Id", tripRef.tripId);
            final String[] legs = tripRef.tripRequestData.split("~");
            for (int iLeg = 0; iLeg < legs.length; iLeg++) {
                final String[] legData = legs[iLeg].split("@");
                final Element eLeg = document.createElement(trip, "Leg");
                document.createTextElement(eLeg, "Id", iLeg + 1);
                final Element timedLeg = document.createElement(eLeg, "TimedLeg");

                final Element legBoard = document.createElement(timedLeg, "LegBoard");
                document.createTextElement(legBoard, NS_SIRI, "siri:StopPointRef", legData[0]);
                final Element serviceDeparture = document.createElement(legBoard, "ServiceDeparture");
                document.createTextElement(serviceDeparture, "TimetabledTime", legData[1]);

                final Element legAlight = document.createElement(timedLeg, "LegAlight");
                document.createTextElement(legAlight, NS_SIRI, "siri:StopPointRef", legData[2]);
                final Element serviceArrival = document.createElement(legAlight, "ServiceArrival");
                document.createTextElement(serviceArrival, "TimetabledTime", legData[3]);

                final Element service = document.createElement(timedLeg, "Service");
                document.createTextElement(service, "JourneyRef", legData[4]);
                document.createTextElement(service, "OperatingDayRef", legData[5]);
                document.createTextElement(service, NS_SIRI, "siri:LineRef", legData[6]);
                document.createTextElement(service, NS_SIRI, "siri:DirectionRef", legData[7]);
                document.createElement(service, "Mode");
                document.createTextElement(
                        document.createElement(service, "ProductCategory"),
                        "ProductCategoryRef", legData[8]);
            }

            final OJPResponse response = doRequest(document);
            final Element tripRefineDelivery = response.getResponse("OJPTripRefineDelivery");
            final Date now = new Date();

            final List<Trip> trips = parseTripResult(response, tripRefineDelivery, tripRef.from, tripRef.via, tripRef.to, now);
            if (trips.isEmpty())
                return new QueryTripsResult(resultHeader, QueryTripsResult.Status.NO_TRIPS);

            return new QueryTripsResult(
                    resultHeader, null,
                    tripRef.from, tripRef.via, tripRef.to,
                    null,
                    trips);
        } catch (final IOException | RuntimeException e) {
            log.error("error getting trip", e);
            return new QueryTripsResult(resultHeader, QueryTripsResult.Status.SERVICE_DOWN);
        }
    }
}
