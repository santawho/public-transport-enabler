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

import static java.util.Objects.requireNonNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public final class Destination implements Serializable {
    @Serial
    private static final long serialVersionUID = -8861838672174069406L;

    final public Location location;
    final public boolean isNotCommonType;

    public Destination(final Location location) {
        this(location, false);
    }

    public Destination(final Location location, final boolean isNotCommonType) {
        this.location = requireNonNull(location);
        this.isNotCommonType = isNotCommonType;
    }

    public String uniqueShortName() {
        return location.uniqueShortName();
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Destination))
            return false;
        final Destination other = (Destination) o;
        if (!Objects.equals(this.location, other.location))
            return false;
        if (this.isNotCommonType != other.isNotCommonType)
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(location, isNotCommonType);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "location=" + location +
                (isNotCommonType ? ",not-common" : "") +
                "}";
    }
}
