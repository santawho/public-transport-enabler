package de.schildbach.pte;

public class TransitousProvider extends AbstractMOTISProvider {
    public TransitousProvider() {
        super(NetworkId.TRANSITOUS, "https://api.transitous.org/");
    }
}
