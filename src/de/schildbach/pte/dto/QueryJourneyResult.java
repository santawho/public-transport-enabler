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

import static java.util.Objects.requireNonNull;

import java.io.Serializable;

import javax.annotation.Nullable;

/**
 * @author Andreas Schildbach
 */
public final class QueryJourneyResult implements Serializable {
    private static final long serialVersionUID = -6893303645828170447L;

    public enum Status {
        OK, NO_JOURNEY, SERVICE_DOWN
    }

    public final @Nullable ResultHeader header;
    public final Status status;

    public final String queryUri;
    public final JourneyRef journeyRef;
    public final Trip.Public journeyLeg;

    public QueryJourneyResult(final ResultHeader header, final String queryUri,
                              final JourneyRef journeyRef, final Trip.Public journeyLeg) {
        this.header = header;
        this.status = Status.OK;
        this.queryUri = queryUri;
        this.journeyRef = journeyRef;
        this.journeyLeg = requireNonNull(journeyLeg);
    }

    public QueryJourneyResult(final ResultHeader header, final Status status) {
        this.header = header;
        this.status = requireNonNull(status);

        this.queryUri = null;
        this.journeyRef = null;
        this.journeyLeg = null;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                status + "," +
                (status == Status.OK && journeyLeg != null ?
                        "journeyLeg=" + journeyLeg : "") +
                "}";
    }

    public String toShortString() {
        if (status == Status.OK)
            return "journey " + journeyRef;
        else
            return status.toString();
    }
}
