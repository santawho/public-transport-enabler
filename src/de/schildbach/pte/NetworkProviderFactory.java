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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import de.schildbach.pte.provider.NetworkProvider;

public class NetworkProviderFactory {
    public static class ExtraNetworkConfigurator<T extends NetworkProvider> {
        private final NetworkId.State state;
        private final Supplier<T> factory;

        public ExtraNetworkConfigurator(final NetworkId.State state) {
            this(state, null);
        }

        public ExtraNetworkConfigurator(final Supplier<T> factory) {
            this(null, factory);
        }

        public ExtraNetworkConfigurator(final NetworkId.State state, final Supplier<T> factory) {
            this.state = state;
            this.factory = factory;
        }

        public NetworkId.State getState() {
            return state;
        }

        public T create() {
            if (factory == null)
                return null;
            return factory.get();
        }
    }

    private final Map<Class<? extends NetworkProvider>, ExtraNetworkConfigurator<? extends NetworkProvider>>
            configurators = new HashMap<>();

    protected <T extends NetworkProvider> void addConfigurator(
            final Class<T> providerClass,
            final ExtraNetworkConfigurator<T> configurator) {
        configurators.put(providerClass, configurator);
    }

    protected <T extends NetworkProvider> void addConfigurator(
            final Class<T> providerClass,
            final NetworkId.State state,
            final Supplier<T> factory) {
        configurators.put(providerClass, new ExtraNetworkConfigurator<>(state, factory));
    }

    protected <T extends NetworkProvider> void addConfigurator(
            final Class<T> providerClass,
            final NetworkId.State state) {
        configurators.put(providerClass, new ExtraNetworkConfigurator<>(state));
    }

    protected <T extends NetworkProvider> void addConfigurator(
            final Class<T> providerClass,
            final Supplier<T> factory) {
        configurators.put(providerClass, new ExtraNetworkConfigurator<>(factory));
    }

    public NetworkProvider getNetworkProvider(final NetworkId networkId) {
        final NetworkId.Descriptor descriptor = networkId.getDescriptor();
        if (descriptor == null)
            return null;
        final Class<? extends NetworkProvider> providerClass = descriptor.getNetworkProviderClass();
        final ExtraNetworkConfigurator<? extends NetworkProvider> configurator = configurators.get(providerClass);
        try {
            NetworkProvider networkProvider = null;
            if (configurator != null)
                networkProvider = configurator.create();
            if (networkProvider == null)
                networkProvider = providerClass.newInstance();
            return networkProvider;
        } catch (final IllegalAccessException | InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    public List<NetworkId.Descriptor> getAvailableNetworks() {
        return getAvailableNetworks(null);
    }

    public List<NetworkId.Descriptor> getAvailableNetworks(final NetworkId.State aExcludeState) {
        final NetworkId.State excludeState = aExcludeState == null ? NetworkId.State.disabled : aExcludeState;
        final List<NetworkId.Descriptor> descriptors = new ArrayList<>();

        for (final NetworkId networkId : NetworkId.values()) {
            final NetworkId.Descriptor descriptor = networkId.getDescriptor();
            final ExtraNetworkConfigurator<? extends NetworkProvider> configurator = configurators.get(descriptor.getNetworkProviderClass());
            NetworkId.State state = null;
            if (configurator != null)
                state = configurator.getState();
            if (state == null)
                state = descriptor.getState();
            if (state == null)
                state = NetworkId.State.active;
            if (state.getValue() >= excludeState.getValue())
                continue;
            descriptors.add(NetworkId.Descriptor.from(
                    networkId,
                    descriptor.getNetworkProviderClass(),
                    descriptor.getGroup(),
                    descriptor.getCoverage(),
                    state));
        }

        return descriptors;
    }
}
