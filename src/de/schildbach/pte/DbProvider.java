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

import java.util.EnumSet;
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
}
