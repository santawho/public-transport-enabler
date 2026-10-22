/*
 * Copyright 2013-2015 the original author or authors.
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

package de.schildbach.pte.exception;

import androidx.annotation.Nullable;

import okhttp3.HttpUrl;

@SuppressWarnings("serial")
public class RateLimitException extends AbstractHttpException {
    public final boolean isLocal;

    public RateLimitException(final boolean isLocal, final HttpUrl url, final CharSequence bodyPeek) {
        super(url, bodyPeek);
        this.isLocal = isLocal;
    }

    @Nullable
    @Override
    public String getMessage() {
        return (isLocal ? "local" : "remote") + "rate limit exceeded";
    }
}
