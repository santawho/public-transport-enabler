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

package de.schildbach.pte.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Proxy;

import de.schildbach.pte.Standard;
import de.schildbach.pte.util.EventCounter;
import de.schildbach.pte.util.HttpClient;
import de.schildbach.pte.util.RateLimiter;

public abstract class AbstractApiProvider implements ApiProvider {
    protected final Logger log;

    protected final HttpClient httpClient;
    protected String userInterfaceLanguage;
    protected boolean messagesAsSimpleHtml;

    protected AbstractApiProvider() {
        this.log = LoggerFactory.getLogger(this.getClass());
        final EventCounter.Factory eventCounterFactory = Standard.getNetworkEventCounterFactory();
        httpClient = new HttpClient(eventCounterFactory);
        httpClient.setRateLimiter(createRateLimiter(eventCounterFactory));
        setUserAgent(HttpClient.getUserAgent(getUserAgentType()));
    }

    public Logger getLog() {
        return log;
    }

    @Override
    public HttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public HttpClient.UsageStatistics getUsageStatistics(final long nowMs) {
        return httpClient.getUsageStatistics(nowMs);
    }

    protected RateLimiter createRateLimiter(final EventCounter.Factory eventCounterFactory) {
        return RateLimiter.multi(
            RateLimiter.delaying(1000, 5, 100, eventCounterFactory), // burst max 5 per seconds
            RateLimiter.failing(60000, 50, 1000, eventCounterFactory) // single rush of 50 per one minute
        );
    }

    public String setUserInterfaceLanguage(@javax.annotation.Nullable final String userInterfaceLanguage) {
        final String lang = userInterfaceLanguage == null ? null : userInterfaceLanguage.toLowerCase();
        final String[] validLangs = getValidUserInterfaceLanguages();
        String uiLang = null;
        if (validLangs != null) {
            for (final String validLang : validLangs) {
                if (validLang.equals(lang)) {
                    uiLang = validLang;
                    break;
                }
            }
            if (uiLang == null)
                uiLang = validLangs[0];
        }
        this.userInterfaceLanguage = uiLang;
        return this.userInterfaceLanguage;
    }

    protected String[] getValidUserInterfaceLanguages() {
        return null;
    }

    @Override
    public UserAgentType getUserAgentType() {
        return UserAgentType.ANY;
    }

    public AbstractApiProvider setUserAgent(final String userAgent) {
        if (userAgent != null && getUserAgentType() != UserAgentType.NONE) {
            httpClient.setUserAgent(userAgent);
        }
        return this;
    }

    public AbstractApiProvider setProxy(final Proxy proxy) {
        httpClient.setProxy(proxy);
        return this;
    }

    public AbstractApiProvider setTrustAllCertificates(final boolean trustAllCertificates) {
        httpClient.setTrustAllCertificates(trustAllCertificates);
        return this;
    }

    protected AbstractApiProvider setSessionCookieName(final String sessionCookieName) {
        httpClient.setSessionCookieName(sessionCookieName);
        return this;
    }

    public void setMessagesAsSimpleHtml(final boolean messagesAsSimpleHtml) {
        this.messagesAsSimpleHtml = messagesAsSimpleHtml;
    }
}
