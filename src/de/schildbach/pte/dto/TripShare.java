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
