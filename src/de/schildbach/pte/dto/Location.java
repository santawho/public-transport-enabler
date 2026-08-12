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

package de.schildbach.pte.dto;

import static de.schildbach.pte.util.Preconditions.checkArgument;
import static de.schildbach.pte.util.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import de.schildbach.pte.util.MessagePackUtils;

/**
 * @author Andreas Schildbach
 */
public final class Location implements MessagePackUtils.PackableSerializable {
    private static final long serialVersionUID = -2124775933106309127L;

    public final LocationType type;
    public final @Nullable String id;
    public final @Nullable String identityId;
    public final @Nullable String displayId;
    public final @Nullable Point coord;
    public final @Nullable String place;
    public final @Nullable String name;
    public final @Nullable Set<Product> products;
    public final @Nullable String language;
    public final @Nullable MessagePackUtils.PackableSerializable additionalData;

    public Location(
            final LocationType type,
            final @Nullable String id,
            final String identityId,
            final String displayId,
            final @Nullable Point coord,
            final @Nullable String place,
            final @Nullable String name,
            final @Nullable Set<Product> products,
            final @Nullable String language,
            final @Nullable MessagePackUtils.PackableSerializable additionalData) {
        this.type = requireNonNull(type);
        this.id = id;
        this.identityId = identityId == null ? id : identityId;
        this.displayId = displayId == null ? id : displayId;
        this.coord = coord;
        this.place = place;
        this.name = name;
        this.products = products;
        this.language = language;
        this.additionalData = additionalData;

        checkArgument(id == null || !id.isEmpty(), () ->
                "ID cannot be the empty string");
        checkArgument(place == null || name != null, () ->
                "place '" + place + "' without name cannot exist");
        if (type == LocationType.ANY) {
            checkArgument(id == null, () -> "type ANY cannot have ID");
        } else if (type == LocationType.COORD) {
            checkArgument(hasCoord(), () -> "coordinates missing");
            checkArgument(place == null && name == null, () -> "coordinates cannot have place or name");
        }
    }

    public Location(
            final LocationType type, final String id, final Point coord,
            final String place, final String name,
            final Set<Product> products,
            final @Nullable MessagePackUtils.PackableSerializable additionalData) {
        this(type, id, id, id, coord, place, name, products, "de", additionalData);
    }

    public Location(
            final LocationType type, final String id, final Point coord,
            final String place, final String name,
            final Set<Product> products) {
        this(type, id, id, id, coord, place, name, products, "de", null);
    }

    public Location(
            final LocationType type, final String id, final Point coord,
            final String place, final String name) {
        this(type, id, coord, place, name, null);
    }

    public Location(
            final LocationType type, final String id,
            final String place, final String name) {
        this(type, id, null, place, name);
    }

    public Location(final LocationType type, final String id, final Point coord) {
        this(type, id, coord, null, null);
    }

    public Location(final LocationType type, final String id) {
        this(type, id, null, null);
    }

    public static Location unpackFromMessage(final MessageUnpacker unpacker) throws IOException {
        return new Location(
                LocationType.valueOf(unpacker.unpackString()),
                MessagePackUtils.unpackNullableString(unpacker),
                MessagePackUtils.unpackNullable(unpacker, Point::unpackFromMessage),
                MessagePackUtils.unpackNullableString(unpacker),
                MessagePackUtils.unpackNullableString(unpacker),
                Product.unpackFromMessage(unpacker));
    }

    @Override
    public void packToMessage(final MessagePacker packer) throws IOException {
        packer.packString(type.name());
        MessagePackUtils.packNullableString(packer, id);
        MessagePackUtils.packNullable(packer, coord);
        MessagePackUtils.packNullableString(packer, place);
        MessagePackUtils.packNullableString(packer, name);
        Product.packToMessage(packer, products);
    }

    public boolean hasId() {
        return id != null && !id.isEmpty();
    }

    public boolean hasCoord() {
        return coord != null;
    }

    public double getLatAsDouble() {
        checkState(hasCoord(), () -> "missing coordinates: " + this);
        return coord.getLatAsDouble();
    }

    public double getLonAsDouble() {
        checkState(hasCoord(), () -> "missing coordinates: " + this);
        return coord.getLonAsDouble();
    }

    public int getLatAs1E6() {
        checkState(hasCoord(), () -> "missing coordinates: " + this);
        return coord.getLatAs1E6();
    }

    public int getLonAs1E6() {
        checkState(hasCoord(), () -> "missing coordinates: " + this);
        return coord.getLonAs1E6();
    }

    public boolean hasName() {
        return name != null;
    }

    public boolean isIdentified() {
        if (type == LocationType.STATION)
            return hasId();

        if (type == LocationType.POI)
            return true;

        if (type == LocationType.ADDRESS || type == LocationType.COORD)
            return hasCoord();

        return false;
    }

    private static final String[] NON_UNIQUE_NAMES = { "Hauptbahnhof", "Hbf", "Bahnhof", "Bf", "Busbahnhof", "ZOB",
            "Schiffstation", "Schiffst.", "Zentrum", "Markt", "Dorf", "Kirche", "Nord", "Ost", "Süd", "West" };

    static {
        Arrays.sort(NON_UNIQUE_NAMES);
    }

    public String uniqueShortName() {
        if (place != null && name != null && Arrays.binarySearch(NON_UNIQUE_NAMES, name) >= 0)
            return place + ", " + name;
        else if (name != null)
            return name;
        else if (hasId())
            return id;
        else
            return null;
    }

    public String fullName(final boolean placeLast) {
        return place == null || name == null
                ? uniqueShortName()
                : placeLast
                ? name + ", " + place
                : place + ", " + name;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Location))
            return false;
        final Location other = (Location) o;
        if (!Objects.equals(this.type, other.type))
            return false;
        if (this.identityId != null && other.identityId != null && this.identityId.equals(other.identityId))
            return true;
        if (this.id != null)
            return this.id.equals(other.id);
        if (this.coord != null)
            return Objects.equals(this.coord, other.coord);

        // only discriminate by name/place if no ids are given
        if (!Objects.equals(this.place, other.place))
            return false;
        if (!Objects.equals(this.name, other.name))
            return false;
        return true;
    }

    public boolean equalsAllFields(final Location other) {
        if (other == this)
            return true;
        if (other == null)
            return false;
        if (!Objects.equals(this.type, other.type))
            return false;
        if (!Objects.equals(this.id, other.id))
            return false;
        if (!Objects.equals(this.coord, other.coord))
            return false;
        if (!Objects.equals(this.place, other.place))
            return false;
        if (!Objects.equals(this.name, other.name))
            return false;
        if (!Objects.equals(this.products, other.products))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        if (id != null)
            return Objects.hash(type.name(), id);
        else
            return Objects.hash(type.name(), coord);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                type + "," +
                (id != null ? id + "," : "") +
                (hasCoord() ? coord + "," : "") +
                (place != null ? "place=" + place + "," : "") +
                (name != null ? "name=" + name + "," : "") +
                "products=" + products + "}";
    }
}
