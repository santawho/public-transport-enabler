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
 * Provider implementation for Deutsche Bahn (Germany), regional traffic preferred.
 * 
 * @author Andreas Schildbach
 */
public final class DbRegioHafasProvider extends DbHafasProvider {
    public static final Set<Product> REGIO_PRODUCTS;

    static {
        REGIO_PRODUCTS = EnumSet.allOf(Product.class);
        REGIO_PRODUCTS.remove(Product.HIGH_SPEED_TRAIN);
    }

    public DbRegioHafasProvider(final String apiAuthorization, final byte[] salt) {
        super(NetworkId.DBREGIOHAFAS, DbHafasProvider.DEFAULT_API_CLIENT, apiAuthorization, salt);
        setUseAddName(true);
    }

    @Override
    public Set<Product> defaultProducts() {
        return REGIO_PRODUCTS;
    }
}
