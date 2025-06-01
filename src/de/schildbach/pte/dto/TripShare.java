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

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.io.Serializable;

import de.schildbach.pte.util.MessagePackUtils;

public class TripShare implements Serializable, MessagePackUtils.Packable {
    private static final long serialVersionUID = 4482294794417818748L;

    public final TripRef simplifiedTripRef;

    public TripShare(final TripRef simplifiedTripRef) {
        this.simplifiedTripRef = simplifiedTripRef;
    }

    public TripShare(
            final TripRef simplifiedTripRef,
            final MessageUnpacker unpacker) {
        this.simplifiedTripRef = simplifiedTripRef;
    }

    @Override
    public void packToMessage(final MessagePacker packer) throws IOException {
        simplifiedTripRef.packToMessage(packer);
    }
}
