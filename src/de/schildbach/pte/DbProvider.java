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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;

/**
 * Provider implementation for movas API of Deutsche Bahn (Germany).
 * 
 * @author Andreas Schildbach
 */
public final class DbProvider extends DbWebProvider.Fernverkehr {
    public static final class Fernverkehr extends DbWebProvider.Fernverkehr {
        public Fernverkehr() {
            super(NetworkId.DB);
        }
    }

    public static final class Regio extends DbWebProvider.Regio {
        public Regio() {
            super(NetworkId.DBREGIO);
        }
    }

    public static final class International extends DbMovasProvider.Fernverkehr {
        public International() {
            super(NetworkId.DBINTERNATIONAL);
        }
    }

    public static final class DeutschlandTicket extends DbWebProvider.DeutschlandTicket {
        public DeutschlandTicket() {
            super(NetworkId.DBDEUTSCHLANDTICKET);
        }
    }

    public static final Set<Product> FERNVERKEHR_PRODUCTS = Product.ALL_INCLUDING_HIGHSPEED;

    public static final Set<Product> REGIO_PRODUCTS = Product.ALL_EXCEPT_HIGHSPEED;

    public DbProvider() {
        super(NetworkId.DB);
    }

    public static final String OPERATOR_DB_FERNVERKEHR = "DB Fernverkehr AG";
    public static final int COLOR_BACKGROUND_NON_DB_HIGH_SPEED_TRAIN = Style.parseColor("#e8d1be");
    public static final Style STYLE_NON_DB_HIGH_SPEED_TRAIN = new Style(Style.Shape.RECT, COLOR_BACKGROUND_NON_DB_HIGH_SPEED_TRAIN, Style.RED, Style.RED);

    public static Style lineStyle(
            final @Nullable Map<String, Style> styles,
            @Nullable final String network,
            @Nullable final Product product,
            @Nullable final String label) {
        Style styleFromNetwork = null;
        if (product != null && product.equals(Product.HIGH_SPEED_TRAIN)) {
            if (network != null) {
                if (!OPERATOR_DB_FERNVERKEHR.equals(network))
                    styleFromNetwork = STYLE_NON_DB_HIGH_SPEED_TRAIN;
            } else {
                if (label == null || !(label.startsWith("ICE ") || label.startsWith("IC ")))
                    styleFromNetwork = STYLE_NON_DB_HIGH_SPEED_TRAIN;
            }
        }
        return Standard.resolveLineStyle(styles, network, product, label, styleFromNetwork);
    }

    public static Description getDbDescription() {
        return new Description.Base() {
            @Override
            public String getName() {
                return "Deutsche Bahn AG";
            }

            @Override
            public String getDescriptionText() {
                return "Federal German railways operator";
            }

            @Override
            public String getUrl() {
                return "https://bahn.de";
            }
        };
    }

    public static class CtxRecon {
        public final String ctxRecon;
        public final Map<String, String> entries;
        public final String shortRecon;
        public final String startLocation;
        public final String endLocation;
        public final String tripId;
        public final List<String> journeyRequestIds;

        public CtxRecon(final String ctxRecon) {
            this.ctxRecon = ctxRecon;
            this.entries = parseMap("¶", ctxRecon);
            if (entries != null) {
                final StringBuilder sb = new StringBuilder();
                for (String key : entries.keySet()) {
                    if ("KCC".equals(key) || "SC".equals(key))
                        continue;
                    final String value = entries.get(key);
                    sb.append("¶");
                    sb.append(key);
                    sb.append("¶");
                    sb.append(value);
                }
                shortRecon = sb.toString();
            } else {
                shortRecon = null;
            }
            String startLocation = null;
            String endLocation = null;
            this.tripId = getEntry("HKI");
            journeyRequestIds = parseArray("§", tripId);
            if (journeyRequestIds != null) {
                if (!journeyRequestIds.isEmpty()) {
                    final List<String> firstLeg = parseArray("\\$", journeyRequestIds.get(0));
                    final List<String> lastLeg = parseArray("\\$", journeyRequestIds.get(journeyRequestIds.size() - 1));
                    if (firstLeg != null && firstLeg.size() >= 2)
                        startLocation = firstLeg.get(1);
                    if (lastLeg != null && lastLeg.size() >= 3)
                        endLocation = lastLeg.get(2);
                }
            }
            this.startLocation = startLocation;
            this.endLocation = endLocation;
        }

        public String getEntry(final String key) {
            if (key == null || entries == null)
                return null;
            return entries.get(key);
        }

        public static Map<String, String> parseMap(final String separator, final String value) {
            if (value == null)
                return null;
            final HashMap<String, String> entries = new HashMap<>();
            final String[] split = value.split(separator);
            for (int i = 2, splitLength = split.length; i < splitLength; i += 2) {
                final String k = split[i-1];
                final String v = split[i];
                entries.put(k, v);
            }
            return entries;
        }
    }
}
