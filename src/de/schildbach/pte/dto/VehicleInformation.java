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

    public static class PlatformSegment implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public double fromMeters;
        public double toMeters;

        protected void swap() {
            final double h = fromMeters;
            fromMeters = toMeters;
            toMeters = h;
        }
    }

    public static class PlatformSection extends PlatformSegment {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public String name;
    }

    public static class FeatureCounts implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public double total;
        public double available;
    }

    public static class VehicleData implements Serializable {
        @Serial
        private static final long serialVersionUID = -4680574868586102255L;

        public VehicleGroup group;
        public int indexInGroup;
        public String wagonLabel;
        public String vehicleIdentification;

        public PlatformSegment platformSegment;
        public String platformSectorName;

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

        public int indexInFormation;
        public List<VehicleData> vehicles = new ArrayList<>();

        public VehicleData addVehicle() {
            final VehicleData vehicleData = new VehicleData();
            vehicleData.group = this;
            vehicleData.indexInGroup = vehicles.size();
            vehicles.add(vehicleData);
            return vehicleData;
        }
    }

    public PlatformSection platform;
    public List<PlatformSection> platformSections;
    public List<VehicleGroup> vehicleGroups = new ArrayList<>();
    public boolean reverseOrientationAtPlatform;
    public boolean differsFromSchedule;

    public VehicleInformation() {
    }

    public VehicleGroup addVehicleGroup() {
        final VehicleGroup vehicleGroup = new VehicleGroup();
        vehicleGroup.indexInFormation = vehicleGroups.size();
        vehicleGroups.add(vehicleGroup);
        return vehicleGroup;
    }

    public void sanitize() {
        // reverse start/end if train starts at higher positions and ends at lower ones
        final VehicleInformation.VehicleGroup headGroup = vehicleGroups.get(0);
        final VehicleInformation.VehicleGroup tailGroup = vehicleGroups.get(vehicleGroups.size() - 1);
        final VehicleInformation.VehicleData headVehicle = headGroup.vehicles.get(0);
        final VehicleInformation.VehicleData tailVehicle = tailGroup.vehicles.get(tailGroup.vehicles.size() - 1);
        if (headVehicle.platformSegment != null && tailVehicle.platformSegment != null
                && headVehicle.platformSegment.fromMeters > tailVehicle.platformSegment.fromMeters) {
            reverseOrientationAtPlatform = true;
            for (final VehicleInformation.VehicleGroup vehicleGroup : vehicleGroups) {
                for (final VehicleInformation.VehicleData vehicle : vehicleGroup.vehicles) {
                    final VehicleInformation.PlatformSegment platformSegment = vehicle.platformSegment;
                    if (platformSegment != null)
                        platformSegment.swap();
                }
            }
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
