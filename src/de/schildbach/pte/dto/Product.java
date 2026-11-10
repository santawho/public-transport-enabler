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

package de.schildbach.pte.dto;

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

import de.schildbach.pte.util.MessagePackUtils;

/**
 * @author Andreas Schildbach
 */
public enum Product {
    UNKNOWN('?', 100),
    HIGH_SPEED_TRAIN('I', 1),
    REGIONAL_TRAIN('R', 2),
    SUBURBAN_TRAIN('S', 3),
    SUBWAY('U', 10),
    TRAM('T', 11),
    BUS('B', 20),
    REPLACEMENT_SERVICE('E', 21),
    ON_DEMAND('P', 22),
    COACH('H', 23),
    FERRY('F', 30),
    CABLECAR('C', 31);

    private static EnumSet<Product> Join(final EnumSet<Product> a, final EnumSet<Product> b) {
        final EnumSet<Product> s = EnumSet.copyOf(a);
        s.addAll(b);
        return s;
    }

    public static final EnumSet<Product> NON_SELECTABLE = EnumSet
            .of(REPLACEMENT_SERVICE, UNKNOWN);
    public static final Set<Product> ALL_SELECTABLE = EnumSet
            .complementOf(NON_SELECTABLE);
    public static final Set<Product> ALL_INCLUDING_HIGHSPEED = EnumSet
            .complementOf(NON_SELECTABLE);
    public static final Set<Product> ALL_EXCEPT_HIGHSPEED = EnumSet
            .complementOf(Join(EnumSet.of(HIGH_SPEED_TRAIN, COACH), NON_SELECTABLE));
    public static final Set<Product> ALL_EXCEPT_HIGHSPEED_AND_ONDEMAND = EnumSet
            .complementOf(Join(EnumSet.of(HIGH_SPEED_TRAIN, COACH, ON_DEMAND), NON_SELECTABLE));

    public static final EnumSet<Product> TRAIN_PRODUCTS = EnumSet.of(
            HIGH_SPEED_TRAIN,
            REGIONAL_TRAIN,
            SUBURBAN_TRAIN,
            SUBWAY);

    public static final EnumSet<Product> LOCAL_PRODUCTS = EnumSet.of(
            REGIONAL_TRAIN,
            SUBURBAN_TRAIN,
            SUBWAY,
            TRAM,
            BUS,
            ON_DEMAND);

    public final char code;
    private final int order;

    Product(final char code, final int order) {
        this.code = code;
        this.order = order;
    }

    public static final Comparator<Product> NATURAL_ORDER = Comparator.comparingInt(p -> p.order);

    public boolean isTrain() {
        return TRAIN_PRODUCTS.contains(this);
    }

    public static Product fromCode(final char code) {
        if (code == HIGH_SPEED_TRAIN.code)
            return HIGH_SPEED_TRAIN;
        else if (code == REGIONAL_TRAIN.code)
            return REGIONAL_TRAIN;
        else if (code == SUBURBAN_TRAIN.code)
            return SUBURBAN_TRAIN;
        else if (code == SUBWAY.code)
            return SUBWAY;
        else if (code == TRAM.code)
            return TRAM;
        else if (code == BUS.code)
            return BUS;
        else if (code == FERRY.code)
            return FERRY;
        else if (code == CABLECAR.code)
            return CABLECAR;
        else if (code == ON_DEMAND.code)
            return ON_DEMAND;
        else if (code == COACH.code)
            return COACH;
        else if (code == REPLACEMENT_SERVICE.code)
            return REPLACEMENT_SERVICE;
        else
            throw new IllegalArgumentException("unknown code: '" + code + "'");
    }

    public static Set<Product> fromCodes(final char[] codes) {
        if (codes == null)
            return null;

        final Set<Product> products = EnumSet.noneOf(Product.class);
        for (final char c : codes)
            products.add(fromCode(c));
        return products;
    }

    public static char[] toCodes(final Set<Product> products) {
        if (products == null)
            return null;

        final char[] codes = new char[products.size()];
        int i = 0;
        for (final Product product : products)
            codes[i++] = product.code;
        return codes;
    }

    public static void packToMessage(final MessagePacker packer, final Set<Product> products) throws IOException {
        MessagePackUtils.packNullableString(packer, products == null ? null : new String(toCodes(products)));
    }

    public static Set<Product> unpackFromMessage(final MessageUnpacker unpacker) throws IOException {
        final String s = MessagePackUtils.unpackNullableString(unpacker);
        if (s == null)
            return null;
        return fromCodes(s.toCharArray());
    }
}
