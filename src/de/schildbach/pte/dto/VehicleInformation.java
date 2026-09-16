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

package de.schildbach.pte.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class VehicleInformation implements Serializable {
    @Serial
    private static final long serialVersionUID = -4680574868586102255L;

    public static class PlatformSection implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public double startMeters;
        public double endMeters;
    }

    public static class FeatureCounts implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public double total;
        public double available;
    }

    public String platformName;
    public PlatformSection platformSection;

    public static class VehicleData implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public PlatformSection platformSection;

        public boolean economyClass;
        public boolean firstClass;
        public boolean airCondition;
        public boolean toiletForWheelChair;
        public boolean seatsForDisabled;
        public boolean quietZone;
        public boolean familyZone;
        public boolean infoZone;
        public boolean childrenSpace;
        public boolean valuedCustomer;

        public FeatureCounts bicycleSpaces;
        public FeatureCounts wheelChairSpaces;
    }

    public static class VehicleGroup implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public List<VehicleData> vehicles = new ArrayList<>();

        public VehicleData addVehicle() {
            final VehicleData vehicleData = new VehicleData();
            vehicles.add(vehicleData);
            return vehicleData;
        }
    }

    public List<VehicleGroup> vehicleGroups = new ArrayList<>();

    public VehicleInformation() {
    }

    public VehicleGroup addVehicleGroup() {
        final VehicleGroup vehicleGroup = new VehicleGroup();
        vehicleGroups.add(vehicleGroup);
        return vehicleGroup;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
