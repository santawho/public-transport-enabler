package de.schildbach.pte.provider.motis;

import de.schildbach.pte.NetworkId;

public class TransitousProvider extends AbstractMotisProvider {
    public TransitousProvider() {
        super(NetworkId.TRANSITOUS, "https://api.transitous.org/");
    }
}
