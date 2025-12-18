package de.schildbach.pte;

public class TransitousProvider extends AbstractMotisProvider {
    public TransitousProvider() {
        super(NetworkId.TRANSITOUS, "https://api.transitous.org/");
    }
}
