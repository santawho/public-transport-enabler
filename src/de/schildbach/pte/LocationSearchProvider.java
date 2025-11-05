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

import java.io.IOException;
import java.util.Set;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.SuggestLocationsResult;

public interface LocationSearchProvider extends Provider {
    /**
     * Meant for auto-completion of location names, like in an Android AutoCompleteTextView.
     *
     * @param constraint
     *            input by user so far
     * @param types
     *            types of locations to suggest, or {@code null} for any
     * @param maxLocations
     *            maximum number of locations to suggest or {@code 0}
     * @return location suggestions
     * @throws IOException
     */
    SuggestLocationsResult suggestLocations(CharSequence constraint, @Nullable Set<LocationType> types,
                                            int maxLocations) throws IOException;

    @Deprecated
    SuggestLocationsResult suggestLocations(CharSequence constraint) throws IOException;
}
