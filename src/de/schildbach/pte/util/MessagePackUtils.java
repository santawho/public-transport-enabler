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

package de.schildbach.pte.util;

import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ImmutableValue;

import java.io.IOException;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MessagePackUtils {
    public interface PackFunc<T> {
        void pack(final MessagePacker packer, final T value) throws IOException;
    }

    public interface UnpackFunc<T> {
        T unpack(final MessageUnpacker unpacker) throws IOException;
    }

    public interface Packable {
        // constructor(final MessageUnpacker unpacker);
        void packToMessage(final MessagePacker packer) throws IOException;
    }

    public static void packNullableString(final MessagePacker packer, final String value) throws IOException {
        if (value == null)
            packer.packNil();
        else
            packer.packString(value);
    }

    public static String unpackNullableString(final MessageUnpacker unpacker) throws IOException {
        final ImmutableValue value = unpacker.unpackValue();
        if (value.isNilValue())
            return null;
        return value.asStringValue().asString();
    }

    public static <T> void packNullable(final MessagePacker packer, final T value, final PackFunc<T> packFunc) throws IOException {
        if (value == null) {
            packer.packBoolean(false);
        } else {
            packer.packBoolean(true);
            packFunc.pack(packer, value);
        }
    }

    public static <T extends Packable> void packNullable(final MessagePacker packer, final T value) throws IOException {
        if (value == null) {
            packer.packBoolean(false);
        } else {
            packer.packBoolean(true);
            value.packToMessage(packer);
        }
    }

    public static <T> T unpackNullable(final MessageUnpacker unpacker, final UnpackFunc<T> unpackFunc) throws IOException {
        if (!unpacker.unpackBoolean())
            return null;
        return unpackFunc.unpack(unpacker);
    }


    public static <E, C extends Collection<E>> void packCollectionToMessage(
            final MessagePacker packer,
            final C collection,
            final Consumer<E> packElement
    ) throws IOException {
        int count = collection.size();
        packer.packArrayHeader(count);
        for (final E e : collection) {
            packElement.accept(e);
        }
    }

    public static <E, C extends Collection<E>> C unpackCollectionFromMessage(
            final MessageUnpacker unpacker,
            final C collection,
            final Supplier<E> unpackElement
    ) throws IOException {
        for (int count = unpacker.unpackArrayHeader(); count > 0; count -= 1) {
            final E e = unpackElement.get();
            collection.add(e);
        }
        return collection;
    }
}
