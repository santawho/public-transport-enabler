/*
 * Copyright 2010-2015 the original author or authors.
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

import de.schildbach.pte.dto.Point;
import de.schildbach.pte.provider.hafas.AvvAachenProvider;
import de.schildbach.pte.provider.hafas.AvvAugsburgProvider;
import de.schildbach.pte.provider.hafas.BartProvider;
import de.schildbach.pte.provider.efa.BayernProvider;
import de.schildbach.pte.provider.efa.BsvagProvider;
import de.schildbach.pte.provider.hafas.BvgProvider;
import de.schildbach.pte.provider.db.DbHafasProvider;
import de.schildbach.pte.provider.db.DbMovasProvider;
import de.schildbach.pte.provider.db.DbProvider;
import de.schildbach.pte.provider.db.DbWebProvider;
import de.schildbach.pte.provider.motis.TransitousProvider;
import de.schildbach.pte.provider.other.DeutschlandTicketProvider;
import de.schildbach.pte.provider.efa.DingProvider;
import de.schildbach.pte.provider.hafas.DsbProvider;
import de.schildbach.pte.provider.efa.DubProvider;
import de.schildbach.pte.provider.hafas.EireannProvider;
import de.schildbach.pte.provider.efa.GvhProvider;
import de.schildbach.pte.provider.hafas.InvgProvider;
import de.schildbach.pte.provider.efa.KvvProvider;
import de.schildbach.pte.provider.efa.LinzProvider;
import de.schildbach.pte.provider.hafas.LuProvider;
import de.schildbach.pte.provider.efa.MerseyProvider;
import de.schildbach.pte.provider.efa.MvgProvider;
import de.schildbach.pte.provider.efa.MvvProvider;
import de.schildbach.pte.provider.hafas.NasaProvider;
import de.schildbach.pte.provider.other.NegentweeProvider;
import de.schildbach.pte.provider.NetworkProvider;
import de.schildbach.pte.provider.hafas.NsProvider;
import de.schildbach.pte.provider.efa.NvbwProvider;
import de.schildbach.pte.provider.hafas.NvvProvider;
import de.schildbach.pte.provider.hafas.OebbProvider;
import de.schildbach.pte.provider.hafas.OoevvProvider;
import de.schildbach.pte.provider.hafas.PlProvider;
import de.schildbach.pte.provider.hafas.RmvProvider;
import de.schildbach.pte.provider.hafas.RtProvider;
import de.schildbach.pte.provider.hafas.SeProvider;
import de.schildbach.pte.provider.hafas.ShProvider;
import de.schildbach.pte.provider.efa.StvProvider;
import de.schildbach.pte.provider.hafas.SvvProvider;
import de.schildbach.pte.provider.efa.SydneyProvider;
import de.schildbach.pte.provider.efa.TlemProvider;
import de.schildbach.pte.provider.hafas.VaoProvider;
import de.schildbach.pte.provider.hafas.VbbProvider;
import de.schildbach.pte.provider.efa.VblProvider;
import de.schildbach.pte.provider.hafas.VbnProvider;
import de.schildbach.pte.provider.efa.VgnProvider;
import de.schildbach.pte.provider.hafas.VgsProvider;
import de.schildbach.pte.provider.hafas.VmobilProvider;
import de.schildbach.pte.provider.hafas.VmtProvider;
import de.schildbach.pte.provider.efa.VmvProvider;
import de.schildbach.pte.provider.hafas.VorProvider;
import de.schildbach.pte.provider.efa.VrnProvider;
import de.schildbach.pte.provider.efa.VrrProvider;
import de.schildbach.pte.provider.other.VrsProvider;
import de.schildbach.pte.provider.efa.VvmProvider;
import de.schildbach.pte.provider.efa.VvoProvider;
import de.schildbach.pte.provider.efa.VvsProvider;
import de.schildbach.pte.provider.hafas.VvtProvider;
import de.schildbach.pte.provider.efa.VvvProvider;
import de.schildbach.pte.provider.efa.WienProvider;
import de.schildbach.pte.provider.hafas.ZvvProvider;

/**
 * @author Andreas Schildbach
 */
public enum NetworkId {
    // World
    TRANSITOUS(Descriptor.from(TransitousProvider.class, Descriptor.GROUP_WORLD, "DE;AT;CH;BE;LU;NL;DK;SE;NO;FI;GB;SI;HU;RO;BG;PL;SK;IT;ES;PT;US;CA;AU",
            new Point[]{Point.fromDouble(90, 0), Point.fromDouble(90, 180), Point.fromDouble(-90, 180), Point.fromDouble(-90, 0)})),

    // Europe
    RT(Descriptor.from(RtProvider.class, Descriptor.GROUP_EUROPE, "DE;AT;CH;BE;LU;NL;DK;SE;NO;FI;GB;SI;HU;RO;BG;PL;SK;IT;ES;PT")),
    DBINTERNATIONAL(Descriptor.from(DbProvider.International.class, Descriptor.GROUP_EUROPE, "DE;AT;CH;BE;LU;NL;DK;SE;NO;FI;GB;SI;HU;RO;BG;PL;SK;IT;ES;PT")),

    // Germany
    DEUTSCHLANDTICKET(Descriptor.from(DeutschlandTicketProvider.class, "de-DE", "DE")),
    DB(Descriptor.from(DbProvider.Default.class, "de-DE", "DE")),
    DBREGIO(Descriptor.from(DbProvider.Regio.class, "de-DE", "DE")),
    DBDEUTSCHLANDTICKET(Descriptor.from(DbProvider.DeutschlandTicket.class, "de-DE", "DE", State.disabled)),
    DBWEB(Descriptor.from(DbProvider.Fernverkehr.class, "de-DE", "DE", State.disabled)),
    DBREGIOWEB(Descriptor.from(DbWebProvider.Regio.class, "de-DE", "DE", State.disabled)),
    DBDEUTSCHLANDTICKETWEB(Descriptor.from(DbWebProvider.DeutschlandTicket.class, "de-DE", "DE", State.disabled)),
    DBMOVAS(Descriptor.from(DbMovasProvider.Fernverkehr.class, "de-DE", "DE")),
    DBREGIOMOVAS(Descriptor.from(DbMovasProvider.Regio.class, "de-DE", "DE")),
    DBDEUTSCHLANDTICKETMOVAS(Descriptor.from(DbMovasProvider.DeutschlandTicket.class, "de-DE", "DE")),
    DBHAFAS(Descriptor.from(DbHafasProvider.Fernverkehr.class, "de-DE", "DE", State.defunct)),
    DBREGIOHAFAS(Descriptor.from(DbHafasProvider.Regio.class, "de-DE", "DE", State.defunct)),
    BVG(Descriptor.from(BvgProvider.class, "de-DE", "Brandenburg;Berlin",
            new Point[] { Point.fromDouble(52.674189, 13.074604), Point.fromDouble(52.341100, 13.757130) })),
    VBB(Descriptor.from(VbbProvider.class, "de-DE", "Brandenburg")),
    NVV(Descriptor.from(NvvProvider.class, "de-DE", "Hessen;Kassel")),
    RMV(Descriptor.from(RmvProvider.class, "de-DE", "Rhein-Main;Frankfurt;Wiesbaden;Darmstadt;Fulda")),
    BAYERN(Descriptor.from(BayernProvider.class, "de-DE", "Bayern;Würzburg;Regensburg")),
    MVV(Descriptor.from(MvvProvider.class, "de-DE", "Bayern;München",
            new Point[] { Point.fromDouble(48.140377, 11.560643) })),
    INVG(Descriptor.from(InvgProvider.class, "de-DE", "Ingolstadt")),
    AVV_AUGSBURG(Descriptor.from(AvvAugsburgProvider.class, "de-DE", "Augsburg")),
    VGN(Descriptor.from(VgnProvider.class, "de-DE", "Nürnberg;Fürth;Erlangen")),
    VVM(Descriptor.from(VvmProvider.class, "de-DE", "Schwaben;Mittelschwaben;Krumbach;Günzburg;Memmingen")),
    VMV(Descriptor.from(VmvProvider.class, "de-DE", "Mecklenburg-Vorpommern;Schwerin")),
    SH(Descriptor.from(ShProvider.class, "de-DE", "Schleswig-Holstein;Kiel;Lübeck;Hamburg")),
    GVH(Descriptor.from(GvhProvider.class, "de-DE", "Niedersachsen;Hannover;Hamburg")),
    BSVAG(Descriptor.from(BsvagProvider.class, "de-DE", "Braunschweig;Wolfsburg")),
    VBN(Descriptor.from(VbnProvider.class, "de-DE", "Niedersachsen;Hamburg;Bremen;Bremerhaven;Oldenburg (Oldenburg);Osnabrück;Göttingen;Rostock")),
    NASA(Descriptor.from(NasaProvider.class, "de-DE", "Sachsen;Leipzig;Sachsen-Anhalt;Magdeburg;Halle")),
    VMT(Descriptor.from(VmtProvider.class, "de-DE", "Thüringen;Mittelthüringen;Erfurt;Jena;Gera;Weimar;Gotha")),
    VVO(Descriptor.from(VvoProvider.class, "de-DE", "Sachsen;Dresden;Mittelsachsen;Chemnitz")),
    VGS(Descriptor.from(VgsProvider.class, "de-DE", "Saarland;Saarbrücken")),
    VRR(Descriptor.from(VrrProvider.class, "de-DE", "Nordrhein-Westfalen;Essen;Dortmund;Düsseldorf;Münster;Paderborn;Höxter;Bielefeld")),
    VRS(Descriptor.from(VrsProvider.class, "de-DE", "Köln;Bonn",
            new Point[] { Point.from1E6(50937531, 6960279) })),
    AVV_AACHEN(Descriptor.from(AvvAachenProvider.class, "de-DE", "Aachen")),
    MVG(Descriptor.from(MvgProvider.class, "de-DE", "Märkischer Kreis;Lüdenscheid")),
    VRN(Descriptor.from(VrnProvider.class, "de-DE", "Baden-Württemberg;Rheinland-Pfalz;Mannheim;Mainz;Trier")),
    VVS(Descriptor.from(VvsProvider.class, "de-DE", "Baden-Württemberg;Stuttgart",
            new Point[] { Point.fromDouble(48.784068, 9.181713) })),
    DING(Descriptor.from(DingProvider.class, "de-DE", "Baden-Württemberg;Ulm;Neu-Ulm")),
    KVV(Descriptor.from(KvvProvider.class, "de-DE", "Baden-Württemberg;Karlsruhe")),
    NVBW(Descriptor.from(NvbwProvider.class, "de-DE", "Baden-Württemberg;Konstanz;Basel;Basel-Stadt;Reutlingen;Rottweil;Tübingen;Sigmaringen;Freiburg im Breisgau;Elsass;Bas-Rhin;Straßburg")),
    VVV(Descriptor.from(VvvProvider.class, "de-DE", "Vogtland;Plauen")),

    // Austria
    OEBB(Descriptor.from(OebbProvider.class, "de-AT", "AT")),
    VAO(Descriptor.from(VaoProvider.class, "de-AT", "AT", State.disabled)),
    VOR(Descriptor.from(VorProvider.class, "de-AT", "Niederösterreich;Burgenland;Wien")),
    WIEN(Descriptor.from(WienProvider.class, "de-AT", "Wien", State.dead)),
    OOEVV(Descriptor.from(OoevvProvider.class, "de-AT", "Oberösterreich")),
    LINZ(Descriptor.from(LinzProvider.class, "de-AT", "Oberösterreich;Linz")),
    SVV(Descriptor.from(SvvProvider.class, "de-AT", "Salzburg")),
    VVT(Descriptor.from(VvtProvider.class, "de-AT", "Tirol")),
    STV(Descriptor.from(StvProvider.class, "de-AT", "Steiermark;Graz;Marburg;Maribor;Klagenfurt")),
    VMOBIL(Descriptor.from(VmobilProvider.class, "de-AT", "Vorarlberg;Bregenz")),

    // Switzerland
    VBL(Descriptor.from(VblProvider.class, "de-CH", "Luzern")),
    ZVV(Descriptor.from(ZvvProvider.class, "de-CH", "Zürich")),

    // Netherlands
    NS(Descriptor.from(NsProvider.class, "nl-NL", "NL;Amsterdam", State.alpha)),
    NEGENTWEE(Descriptor.from(NegentweeProvider.class, "nl-NL", "NL;Amsterdam", State.disabled)),

    // Denmark
    DSB(Descriptor.from(DsbProvider.class, "da-DK", "DK;København")),

    // Sweden
    SE(Descriptor.from(SeProvider.class, "sv-SE", "SE;Stockholm")),

    // Luxembourg
    LU(Descriptor.from(LuProvider.class, "lb-LU", "LU;Luxemburg")),

    // United Kingdom
    TLEM(Descriptor.from(TlemProvider.class, "en-UK", "GB;Greater London;Derbyshire;Leicestershire;Rutland;Northamptonshire;Nottinghamshire;Lincolnshire;Berkshire;Buckinghamshire;East Sussex;Hampshire;Isle of Wight;Kent;Oxfordshire;Surrey;West Sussex;Essex;Hertfordshire;Bedfordshire;Cambridgeshire;Norfolk;Suffolk;Somerset;Gloucestershire;Wiltshire;Dorset;Devon;Cornwall;West Devon;Stowford;Eastleigh;Swindon;Gloucester;Plymouth;Torbay;Bournemouth;Poole;Birmingham")),
    MERSEY(Descriptor.from(MerseyProvider.class, "en-UK", "GB;Liverpool", State.beta)),

    // Ireland
    EIREANN(Descriptor.from(EireannProvider.class, "en-IE", "IE;Dublin", State.disabled)),

    // Poland
    PL(Descriptor.from(PlProvider.class, "pl-PL", "PL;Warschau")),

    // United Arab Emirates
    DUB(Descriptor.from(DubProvider.class, "ae-AE", "AE;Dubai", State.beta)),

    // United States
    BART(Descriptor.from(BartProvider.class, "us-US", "US;California;Kalifornien;San Francisco", State.beta)),

    // Australia
    SYDNEY(Descriptor.from(SydneyProvider.class, "en-AU", "AU;New South Wales;Sydney"));
    // MET(Descriptor.from(???, "en-AU", "AU;Victoria;Melbourne", State.disabled));

    private final Descriptor descriptor;

    NetworkId(final Descriptor descriptor) {
        this.descriptor = descriptor;
        if (descriptor != null)
            descriptor.setNetworkId(this);
    }

    public Descriptor getDescriptor() {
        return descriptor;
    }

    public enum State {
        show(-1),
        active(0),
        beta(1),
        dead(2),
        alpha(3),
        wip(4),
        unselectable(5),
        hide(6),
        unoperational(7),
        disabled(8),
        deprecated(9),
        defunct(10);

        private final int order;

        State(final int order) {
            this.order = order;
        }

        public boolean lessThan(final State other) {
            return order < other.order;
        }
    }

    public interface Descriptor {
        String GROUP_WORLD = "world";
        String GROUP_EUROPE = "eu";

        Class<? extends NetworkProvider> getNetworkProviderClass();
        NetworkId getNetworkId();
        void setNetworkId(final NetworkId networkId);

        static Descriptor from(
                final Class<? extends NetworkProvider> networkProviderClass,
                final String group,
                final String coverage) {
            return from(
                    networkProviderClass,
                    group,
                    coverage,
                    null,
                    State.active);
        }

        static Descriptor from(
                final Class<? extends NetworkProvider> networkProviderClass,
                final String group,
                final String coverage,
                final Point[] area) {
            return from(
                    networkProviderClass,
                    group,
                    coverage,
                    area,
                    State.active);
        }

        static Descriptor from(
                final Class<? extends NetworkProvider> networkProviderClass,
                final String group,
                final String coverage,
                final State state) {
            return from(
                    networkProviderClass,
                    group,
                    coverage,
                    null,
                    state);
        }

        static Descriptor from(
                final Class<? extends NetworkProvider> networkProviderClass,
                final String group,
                final String coverage,
                final Point[] area,
                final State state) {
            return new BasicDescriptor(
                    networkProviderClass,
                    group,
                    coverage,
                    area,
                    state);
        }

        static Descriptor from(
                final NetworkId networkId,
                final Class<? extends NetworkProvider> networkProviderClass,
                final String group,
                final String coverage,
                final State state) {
            final Descriptor d = from(
                    networkProviderClass,
                    group,
                    coverage,
                    null,
                    state);
            d.setNetworkId(networkId);
            return d;
        }

        String getGroup();
        String getCoverage();
        Point[] getArea();
        State getState();
    }

    public static class BasicDescriptor implements Descriptor {
        private final Class<? extends NetworkProvider> networkProviderClass;
        private NetworkId networkId;
        private final String group;
        private final String coverage;
        private final Point[] area;
        private final State state;

        public BasicDescriptor(
                final Class<? extends NetworkProvider> networkProviderClass,
                final String group,
                final String coverage,
                final Point[] area,
                final State state) {
            this.networkProviderClass = networkProviderClass;
            this.group = group;
            this.coverage = coverage;
            this.area = area;
            this.state = state;
        }

        @Override
        public void setNetworkId(final NetworkId networkId) {
            this.networkId = networkId;
        }

        @Override
        public Class<? extends NetworkProvider> getNetworkProviderClass() {
            return networkProviderClass;
        }

        public NetworkId getNetworkId() {
            return networkId;
        }

        public String getGroup() {
            return group;
        }

        public String getCoverage() {
            return coverage;
        }

        public Point[] getArea() {
            return area;
        }

        public State getState() {
            return state;
        }
    }
}
