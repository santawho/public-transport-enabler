/*
 * Copyright 2012-2015 the original author or authors.
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
import java.io.Serializable;
import java.util.Objects;

import de.schildbach.pte.NetworkId;
import de.schildbach.pte.util.MessagePackUtils;

/**
 * @author Andreas Schildbach
 */
public abstract class TripRef implements Serializable, MessagePackUtils.Packable {
    private static final long serialVersionUID = -7675828917328992747L;

    public final NetworkId network;
    public final Location from;
    public final Location via;
    public final Location to;

    public TripRef(
            final NetworkId network,
            final Location from, final Location via, final Location to) {
        this.network = network;
        this.from = from;
        this.via = via;
        this.to = to;
    }

    public TripRef(
            final NetworkId network,
            final MessageUnpacker unpacker
    ) throws IOException {
        this.network = network;
        this.from = MessagePackUtils.unpackNullable(unpacker, Location::unpackFromMessage);
        this.via = MessagePackUtils.unpackNullable(unpacker, Location::unpackFromMessage);
        this.to = MessagePackUtils.unpackNullable(unpacker, Location::unpackFromMessage);
    }

    public TripRef(final TripRef other) {
        this.network = other.network;
        this.from = other.from;
        this.via = other.via;
        this.to = other.to;
    }

    @Override
    public void packToMessage(final MessagePacker packer) throws IOException {
        MessagePackUtils.packNullable(packer, from);
        MessagePackUtils.packNullable(packer, via);
        MessagePackUtils.packNullable(packer, to);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TripRef)) return false;
        TripRef that = (TripRef) o;
        return network.equals(that.network);
    }

    @Override
    public int hashCode() {
        return Objects.hash(network);
    }
}
