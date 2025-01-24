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
import java.util.Set;

import de.schildbach.pte.dto.Product;

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

    public static final Set<Product> FERNVERKEHR_PRODUCTS;

    static {
        FERNVERKEHR_PRODUCTS = EnumSet.allOf(Product.class);
    }

    public static final Set<Product> REGIO_PRODUCTS;

    static {
        REGIO_PRODUCTS = EnumSet.allOf(Product.class);
        REGIO_PRODUCTS.remove(Product.HIGH_SPEED_TRAIN);
    }

    public DbProvider() {
        super(NetworkId.DB);
    }
}
