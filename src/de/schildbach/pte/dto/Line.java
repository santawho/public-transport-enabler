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

import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import de.schildbach.pte.NetworkId;

/**
 * @author Andreas Schildbach
 */
public final class Line implements Serializable, Comparable<Line> {
    public enum Attr {
        CIRCLE_CLOCKWISE, CIRCLE_ANTICLOCKWISE, SERVICE_REPLACEMENT, LINE_AIRPORT, WHEEL_CHAIR_ACCESS, BICYCLE_CARRIAGE
    }

    private static final long serialVersionUID = -5642533805998375070L;

    public final @Nullable String id;
    public final @Nullable NetworkId networkId;
    public final @Nullable String operator;
    public final @Nullable Product product;
    public final @Nullable String label;
    public final @Nullable String name;
    public final @Nullable Style style;
    public final @Nullable Set<Attr> attrs;
    public final @Nullable String message;

    public static final Line FOOTWAY = new Line();
    public static final Line TRANSFER = new Line();
    public static final Line SECURE_CONNECTION = new Line();
    public static final Line DO_NOT_CHANGE = new Line();

    private Line() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public Line(
            final @Nullable NetworkId networkId, final @Nullable String id,
            final @Nullable String operator, final @Nullable Product product,
            final @Nullable String label, final @Nullable String name,
            final @Nullable Style style, final @Nullable Set<Attr> attrs,
            final @Nullable String message) {
        this.networkId = networkId;
        this.id = id;
        this.operator = operator;
        this.product = product;
        this.label = label;
        this.name = name;
        this.style = style;
        this.attrs = attrs;
        this.message = message;
    }

    private String operator() {
        return operator;
    }

    private @Nullable Product product() {
        return product;
    }

    public @Nonnull Product validProduct() {
        final Product product = this.product;
        if (product == null)
            return Product.UNKNOWN;
        return product;
    }

    private String label() {
        return label;
    }

    public boolean hasAttr(final Attr attr) {
        final Set<Attr> attrs = this.attrs;
        return attrs != null && attrs.contains(attr);
    }

    public boolean isTrain() {
        return product != null && product.isTrain();
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Line))
            return false;
        final Line other = (Line) o;
        if (!Objects.equals(this.operator, other.operator))
            return false;
        if (!Objects.equals(this.product, other.product))
            return false;
        if (!Objects.equals(this.label, other.label))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, product == null ? null : product.name(), label);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                (operator != null ? operator + "," : "") +
                (product != null ? product + "," : "") +
                (label != null ? label + "," : "") +
                "'" + name + "'}";
    }

    @Override
    public int compareTo(final Line other) {
        return Comparator.comparing(Line::operator, Comparator.nullsLast(String::compareTo))
                .thenComparing(Line::product, Comparator.nullsLast(Enum::compareTo))
                .thenComparing(Line::label, Comparator.nullsLast(String::compareTo))
                .compare(this, other);
    }
}
