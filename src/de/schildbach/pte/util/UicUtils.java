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

package de.schildbach.pte.util;

import java.util.HashMap;
import java.util.Map;

public class UicUtils {
    public interface UicMapper {
        String uicToTypeName(final String uic, final String country, final String typeClass, final String vehicleNbr);
    };

    private static class FixedUicType implements UicMapper {
        private final String typeName;

        private FixedUicType(final String typeName) {
            this.typeName = typeName;
        }

        @Override
        public String uicToTypeName(final String uic, final String country, final String typeClass, final String vehicleNbr) {
            return typeName;
        }
    }

    public static String uicToTypeName(final String decoratedUIC) {
        final StringBuilder b = new StringBuilder();
        final int decoratedLength = decoratedUIC.length();
        for (int i = 0; i < decoratedLength; ++i) {
            final char c = decoratedUIC.charAt(i);
            if (Character.isDigit(c)) {
              b.append(c);
            }
        }
        final String uic = b.toString();
        final int length = uic.length();
        if (length < 4)
            return null;
        final String typeClass = uic.substring(0, 2);
        final String country = uic.substring(2, 4);
        final String vehicleNbr = (length >= 11) ? uic.substring(4, 11) : uic.substring(4);
        return global.uicToTypeName(uic, country, typeClass, vehicleNbr);
    }

    private static final UicMapper global = new UicMapper() {
        private static final Map<String, UicMapper> countries = new HashMap<>();

        @Override
        public String uicToTypeName(final String uic, final String country, final String typeClass, final String vehicleNbr) {
            final UicMapper mapper = countries.get(country);
            if (mapper == null)
                return null;
            return mapper.uicToTypeName(uic, country, typeClass, vehicleNbr);
        }

        static {
            countries.put("80", new Germany());
        }
    };

    private interface VehicleNbrMapper {
        String vehicleNbrToTypeName(String typeClass, String vehicleNbr);
    }

    private static class FixedVehicleType implements VehicleNbrMapper {
        private final String typeName;

        private FixedVehicleType(final String typeName) {
            this.typeName = typeName;
        }

        @Override
        public String vehicleNbrToTypeName(final String typeClass, final String vehicleNbr) {
            return typeName;
        }
    }

    private static class VehiclePrefixMapper implements VehicleNbrMapper {
        final Map<Integer, Map<String, String>> map = new HashMap<>();
        final String defaultTypeName;

        VehiclePrefixMapper() {
            this.defaultTypeName = null;
        }

        VehiclePrefixMapper(final String defaultTypeName) {
            this.defaultTypeName = defaultTypeName;
        }

        void add(final String prefix, final String typeName) {
            final int length = prefix.length();
            Map<String, String> lengthMapper = map.get(length);
            if (lengthMapper == null) {
                lengthMapper = new HashMap<>();
                map.put(length, lengthMapper);
            }
            lengthMapper.put(prefix, typeName);
        }

        String vehicleNbrToTypeName(final String vehicleNbr) {
            for (final Map.Entry<Integer, Map<String, String>> entry : map.entrySet()) {
                final Integer prefixLength = entry.getKey();
                if (vehicleNbr.length() >= prefixLength) {
                    final String prefix = vehicleNbr.substring(0, prefixLength);
                    final String typeName = entry.getValue().get(prefix);
                    if (typeName != null)
                        return typeName;
                }
            }
            return defaultTypeName;
        }

        @Override
        public String vehicleNbrToTypeName(final String typeClass, final String vehicleNbr) {
            return vehicleNbrToTypeName(vehicleNbr);
        }
    }

    static class Germany implements UicMapper {
        private static final Map<String, VehicleNbrMapper> types = new HashMap<>();

        @Override
        public String uicToTypeName(final String uic, final String country, final String typeClass, final String vehicleNbr) {
            final VehicleNbrMapper mapper = types.get(typeClass);
            if (mapper == null)
                return null;
            return mapper.vehicleNbrToTypeName(typeClass, vehicleNbr);
        }

        static {
            types.put("91", new VehiclePrefixMapper("E-Lok") {{
                add("0446", "Dosto");
            }});
            types.put("92", new FixedVehicleType("D-Lok"));
            types.put("93", new VehiclePrefixMapper() {{
                add("5401", "ICE 1");
                add("5402", "ICE 2");
                add("5403", "ICE 3");
                add("5406", "ICE 3M");
                add("5407", "ICE 3");
                add("5408", "ICE 3neo");
                add("5411", "ICE T-7");
                add("5412", "ICE 4");
                add("5415", "ICE T-5");
                add("681", "ICE 4-7");
                add("781", "ICE 4-7");
            }});
            types.put("94", new VehiclePrefixMapper() {{
                add("0420", "BR 420");
                add("0422", "BR 422");
                add("0423", "BR 423");
                add("0424", "BR 424");
                add("0425", "BR 425");
                add("04260", "BR426");
                add("04261", "Flirt");
                add("2526", "Flirt");
                add("0427", "Flirt");
                add("1427", "Flirt");
                add("3427", "Flirt");
                add("0428", "Flirt");
                add("1428", "Flirt");
                add("0429", "Flirt");
                add("1429", "Flirt");
                add("2429", "Flirt");
                add("3429", "Flirt");
                add("0430", "BR 430");
                add("1430", "Flirt");
                add("0440", "BR 440");
                add("14401", "BR 1440");
                add("14402", "BR 440");
                add("14403", "BR 1440");
                add("14404", "BR 1440");
                add("0442", "Talent 2");
                add("1442", "Talent 2");
                add("2442", "Talent 2");
                add("3442", "Talent 2");
                add("8442", "Talent 3");
                add("9442", "Talent 2");
                add("0445", "KISS");
                add("0452", "Citadis");
                add("0455", "Nexio");
                add("0460", "Desiro ML");
                add("0462", "Desiro HC");
                add("1462", "Desiro HC");
                add("2462", "Desiro HC");
                add("3462", "Desiro HC");
                add("4462", "Desiro HC");
                add("0463", "Mireo");
                add("1463", "Mireo");
                add("2463", "Mireo");
                add("0563", "Mireo Plus B");
                add("3464", "Mireo Smart");
                add("0472", "BR 472");
                add("0474", "BR 474");
                add("0480", "BR 480");
                add("0481", "BR 481");
                add("0483", "BR 483");
                add("0484", "BR 484");
                add("0485", "BR 485");
                add("0490", "BR 490");
                add("0490", "BR 490");
                add("0492", "Tram-Train");
            }});
            types.put("95", new VehiclePrefixMapper() {{
                add("0605", "ICE TD");
                add("0612", "BR 612");
                add("0615", "Itino");
                add("0620", "LINT 81");
                add("0622", "LINT 54");
                add("0623", "LINT 41");
                add("0626", "NE 81");
                add("0628", "BR 628");
                add("0631", "PESA LINK");
                add("0632", "PESA LINK");
                add("0633", "PESA LINK");
                add("0640", "LINT 27");
                add("0641", "A TER");
                add("0642", "Desiro Classic");
                add("0643", "Talent");
                add("0644", "Talent");
                add("0648", "LINT 41");
                add("1648", "LINT 41");
                add("2648", "LINT 41");
                add("0650", "RS1");
                add("06540", "RegioSprinter");
                add("06541", "iLint");
                add("0670", "BR 670");
            }});
            types.put("50", new VehiclePrefixMapper() {{
                add("26", "Dosto");
                add("36", "Dosto");
                add("86", "Dosto");
            }});
        }
    }
}
