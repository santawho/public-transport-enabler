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

package de.schildbach.pte.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Inflater;

import javax.annotation.Nullable;
import javax.annotation.Nonnull;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.pte.exception.BlockedException;
import de.schildbach.pte.exception.InternalErrorException;
import de.schildbach.pte.exception.NotFoundException;
import de.schildbach.pte.exception.SessionExpiredException;
import de.schildbach.pte.exception.UnexpectedRedirectException;

import de.schildbach.pte.provider.ApiProvider;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.CompressionInterceptor;
import okhttp3.Cookie;
import okhttp3.Gzip;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Response.Builder;
import okhttp3.ResponseBody;
import okhttp3.brotli.Brotli;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.zstd.Zstd;
import okio.BufferedSource;
import okio.InflaterSource;
import okio.Source;

import static java.util.Objects.requireNonNull;

/**
 * @author Andreas Schildbach
 */
public final class HttpClient {
    public interface UserAgentFactory {
        String getUserAgent(ApiProvider.UserAgentType forType);
    }
    private static UserAgentFactory userAgentFactory;
    public static void setUserAgentFactory(final UserAgentFactory userAgentFactory) {
        HttpClient.userAgentFactory = userAgentFactory;
    }
    public static String getUserAgent(final ApiProvider.UserAgentType forType) {
        if (forType == ApiProvider.UserAgentType.NONE)
            return null;
        if (userAgentFactory == null)
            return null;
        return userAgentFactory.getUserAgent(forType);
    }

    @Nullable
    private String userAgent = null;
    private final Map<String, String> headers = new HashMap<>();
    @Nullable
    private String sessionCookieName = null;
    @Nullable
    private Cookie sessionCookie = null;
    @Nullable
    private Proxy proxy = null;
    private boolean trustAllCertificates = false;
    private boolean useClientCertificate = false;
    @Nullable
    private KeyManager[] clientCertificateKeyManagers = null;

    @Nullable
    private CertificatePinner certificatePinner = null;
    private String defaultReferer = null;
    private String defaultOrigin = null;

    private static final Set<Integer> RESPONSE_CODES_BLOCKED =
            Stream.of(HttpURLConnection.HTTP_BAD_REQUEST, HttpURLConnection.HTTP_UNAUTHORIZED,
                            HttpURLConnection.HTTP_FORBIDDEN, HttpURLConnection.HTTP_NOT_ACCEPTABLE,
                            HttpURLConnection.HTTP_UNAVAILABLE, 429)
                    .collect(Collectors.toSet());
    private static final Set<Integer> RESPONSE_CODES_NOT_FOUND =
            Stream.of(HttpURLConnection.HTTP_NOT_FOUND)
                    .collect(Collectors.toSet());
    private static final Set<Integer> RESPONSE_CODES_REDIRECT =
            Stream.of(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)
                    .collect(Collectors.toSet());
    private static final Set<Integer> RESPONSE_CODES_INTERNAL_ERROR =
            Stream.of(HttpURLConnection.HTTP_INTERNAL_ERROR, HttpURLConnection.HTTP_BAD_GATEWAY)
                    .collect(Collectors.toSet());

    private static final String SCRAPE_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final int SCRAPE_PEEK_SIZE = 8192;

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    private OkHttpClient okHttpClient;

    public HttpClient() {
        setHeader("Accept", SCRAPE_ACCEPT);
    }

    public void setUserAgent(@Nullable final String userAgent) {
        this.userAgent = userAgent;
    }

    public void setHeader(final String headerName, final String headerValue) {
        this.headers.put(headerName, headerValue);
    }

    public void setSessionCookieName(@Nullable final String sessionCookieName) {
        this.sessionCookieName = sessionCookieName;
    }

    public void setProxy(@Nullable final Proxy proxy) {
        this.proxy = proxy;
    }

    public void setTrustAllCertificates(final boolean trustAllCertificates) {
        this.trustAllCertificates = trustAllCertificates;
    }

    public void setClientCertificate(final byte[] clientCertificate) {
        setClientCertificate(clientCertificate, null);
    }

    public void setClientCertificate(final byte[] clientCertificate, final String clientCertificatePassword) {
        useClientCertificate = clientCertificate != null;
        if (useClientCertificate) {
            try {
                final char[] keyStorePassword = (clientCertificatePassword == null ? "" : clientCertificatePassword).toCharArray();
                final KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(new ByteArrayInputStream(clientCertificate), keyStorePassword);
                final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, keyStorePassword);
                clientCertificateKeyManagers = keyManagerFactory.getKeyManagers();
            } catch (final Exception x) {
                throw new RuntimeException(x);
            }
        }
    }

    public void setCertificatePin(final String host, final String... hashes) {
        this.certificatePinner = new CertificatePinner.Builder().add(host, hashes).build();
    }

    public void setReferer(final String referer) {
        this.defaultReferer = referer;
    }

    public void setOrigin(final String origin) {
        this.defaultOrigin = origin;
    }

    boolean compressionGzip = true;
    boolean compressionDeflate = true;
    boolean compressionBrotli = true;
    boolean compressionZstandard = true;

    public void setCompressionGzip(final boolean compressionGzip) {
        this.compressionGzip = compressionGzip;
    }

    public void setCompressionDeflate(final boolean compressionDeflate) {
        this.compressionDeflate = compressionDeflate;
    }

    public void setCompressionBrotli(final boolean compressionBrotli) {
        this.compressionBrotli = compressionBrotli;
    }

    public void setCompressionZstandard(final boolean compressionZstandard) {
        this.compressionZstandard = compressionZstandard;
    }

    private OkHttpClient getOkHttpClient() {
        if (okHttpClient == null) {
            final HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                    new HttpLoggingInterceptor.Logger() {
                        @Override
                        public void log(@Nonnull final String message) {
                            log.debug(message);
                        }
                    });
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

            final Interceptor xmlEncodingInterceptor = new Interceptor() {
                private final Pattern P_XML_PRAGMA = Pattern.compile("<\\?xml.*?encoding=\"(.*?)\".*?\\?>");
                private final String HEADER_CONTENT_TYPE = "Content-Type";

                @Override
                @Nonnull
                public Response intercept(final Interceptor.Chain chain) throws IOException {
                    Response response = chain.proceed(chain.request());
                    final MediaType originalContentType = response.body().contentType();
                    if (originalContentType != null && "text".equalsIgnoreCase(originalContentType.type())
                            && "xml".equalsIgnoreCase(originalContentType.subtype())
                            && originalContentType.charset() == null) {
                        final String peek = response.peekBody(64).string();
                        final Matcher matcher = P_XML_PRAGMA.matcher(peek);
                        if (matcher.find()) {
                            final String encoding = matcher.group(1);
                            final MediaType contentType = MediaType.get(originalContentType.type() + '/'
                                    + originalContentType.subtype() + ";charset=" + encoding);
                            final ResponseBody body = response.body();
                            final Builder responseBuilder = response.newBuilder();
                            responseBuilder.header(HEADER_CONTENT_TYPE, contentType.toString());
                            responseBuilder.body(ResponseBody.create(contentType, body.contentLength(), body.source()));
                            response = responseBuilder.build();
                            log.debug("Deriving missing {} encoding from XML pragma", encoding);
                        }
                    }
                    return response;
                }
            };

            final Interceptor retryInterceptor = chain -> {
                final Request request = chain.request();
                Response response = null;
                response = chain.proceed(request);
                if (response.isSuccessful() && response.peekBody(1).bytes().length == 0) {
                    log.info("Got empty response, retrying {}", request.url());
                    response.close();
                    return chain.proceed(request); // retry
                }
                return response;
            };

            final CompressionInterceptor.DecompressionAlgorithm DeflateInstance = new CompressionInterceptor.DecompressionAlgorithm() {
                @Nonnull
                @Override
                public String getEncoding() {
                    return "deflate";
                }

                @Nonnull
                @Override
                public Source decompress(@Nonnull final BufferedSource compressedSource) {
                    return new InflaterSource(compressedSource, new Inflater());
                }
            };

            final List<CompressionInterceptor.DecompressionAlgorithm> decompressionAlgorithms = new ArrayList<>();
            if (compressionBrotli)
                decompressionAlgorithms.add(Brotli.INSTANCE);
            if (compressionGzip)
                decompressionAlgorithms.add(Gzip.INSTANCE);
            if (compressionDeflate)
                decompressionAlgorithms.add(DeflateInstance);
            if (compressionZstandard)
                decompressionAlgorithms.add(Zstd.INSTANCE);

            final Interceptor compressionInterceptor = new CompressionInterceptor(
                    decompressionAlgorithms.toArray(new CompressionInterceptor.DecompressionAlgorithm[]{}));

            final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(false)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .addNetworkInterceptor(loggingInterceptor)
                    .addInterceptor(retryInterceptor)
                    .addInterceptor(xmlEncodingInterceptor)
                    .addInterceptor(compressionInterceptor);

            if (proxy != null || trustAllCertificates || certificatePinner != null || useClientCertificate) {
                if (proxy != null)
                    builder.proxy(proxy);
                if (trustAllCertificates || useClientCertificate)
                    configureSSL(builder);
                if (certificatePinner != null)
                    builder.certificatePinner(certificatePinner);
            }

            okHttpClient = builder.build();
        }
        return okHttpClient;
    }

    public CharSequence get(
            final HttpUrl url)
            throws IOException {
        return get(url, null, null, defaultReferer, defaultOrigin, 0);
    }

    public CharSequence get(
            final HttpUrl url,
            final long callTimeoutSecs)
            throws IOException {
        return get(url, null, null, defaultReferer, defaultOrigin, callTimeoutSecs);
    }

    public CharSequence get(
            final HttpUrl url,
            final String postRequest,
            final String requestContentType)
            throws IOException {
        return get(url, postRequest, requestContentType, defaultReferer, defaultOrigin, 0);
    }

    public CharSequence get(
            final HttpUrl url,
            final String postRequest,
            final String requestContentType,
            final long callTimeoutSecs)
            throws IOException {
        return get(url, postRequest, requestContentType, defaultReferer, defaultOrigin, callTimeoutSecs);
    }

    public CharSequence get(
            final HttpUrl url,
            final String postRequest,
            final String requestContentType,
            final String referer,
            final String origin)
            throws IOException {
        return get(url, postRequest, requestContentType, referer, origin, 0);
    }

    public CharSequence get(
            final HttpUrl url,
            final String postRequest,
            final String requestContentType,
            final String referer,
            final String origin,
            final long callTimeoutSecs)
            throws IOException {
        final StringBuilder buffer = new StringBuilder();
        final Callback callback = (bodyPeek, body) -> buffer.append(body.string());
        getInputStream(callback, url, postRequest, requestContentType, referer, origin, callTimeoutSecs);
        return buffer;
    }

    public interface Callback {
        void onSuccessful(CharSequence bodyPeek, ResponseBody body) throws IOException;
    }

    public void getInputStream(
            final Callback callback, final HttpUrl url) throws IOException {
        getInputStream(callback, url, null, null, defaultReferer, defaultOrigin, 0);
    }

    public void getInputStream(
            final Callback callback, final HttpUrl url,
            final long callTimeoutSecs) throws IOException {
        getInputStream(callback, url, null, null, defaultReferer, defaultOrigin, callTimeoutSecs);
    }

    public void getInputStream(
            final Callback callback, final HttpUrl url, final String referer) throws IOException {
        getInputStream(callback, url, null, null, referer, defaultOrigin, 0);
    }

    public void getInputStream(
            final Callback callback, final HttpUrl url, final String referer,
            final long callTimeoutSecs) throws IOException {
        getInputStream(callback, url, null, null, referer, defaultOrigin, callTimeoutSecs);
    }

    public void getInputStream(
            final Callback callback, final HttpUrl url, final String postRequest,
            final String requestContentType) throws IOException {
        getInputStream(callback, url, postRequest, requestContentType, defaultReferer, defaultOrigin, 0);
    }

    public void getInputStream(
            final Callback callback, final HttpUrl url, final String postRequest,
            final String requestContentType,
            final long callTimeoutSecs) throws IOException {
        getInputStream(callback, url, postRequest, requestContentType, defaultReferer, defaultOrigin, callTimeoutSecs);
    }

    public void getInputStream(
            final Callback callback,
            final HttpUrl url,
            final String postRequest,
            final String requestContentType,
            final String referer,
            final String origin,
            final long callTimeoutSecs) throws IOException {
        requireNonNull(callback);
        requireNonNull(url);

        final Request.Builder request = new Request.Builder();
        request.url(url);
        request.headers(Headers.of(headers));
        if (postRequest != null) {
            final MediaType m = requestContentType != null ? MediaType.parse(requestContentType) : null;
            request.post(RequestBody.create(m, postRequest));
        }
        if (userAgent != null)
            request.header("User-Agent", userAgent);
        if (referer != null)
            request.header("Referer", referer);
        if (origin != null)
            request.header("Origin", origin);
        final Cookie sessionCookie = this.sessionCookie;
        if (sessionCookie != null && sessionCookie.name().equals(sessionCookieName))
            request.header("Cookie", sessionCookie.toString());

        OkHttpClient callSpecificHttpClient = getOkHttpClient();
        if (callTimeoutSecs != 0) {
            callSpecificHttpClient = callSpecificHttpClient.newBuilder()
                    .readTimeout(callTimeoutSecs, TimeUnit.SECONDS)
                    .callTimeout(callTimeoutSecs, TimeUnit.SECONDS)
                    .build();
        }

        final Call call = callSpecificHttpClient.newCall(request.build());
        try (final Response response = call.execute()) {
            final int responseCode = response.code();
            final String bodyPeek = response.peekBody(SCRAPE_PEEK_SIZE).string().replaceAll("\\p{C}", "");
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {

                final HttpUrl redirectUrl = testRedirect(url, bodyPeek);
                if (redirectUrl != null)
                    throw new UnexpectedRedirectException(url, redirectUrl);

                if (testExpired(bodyPeek))
                    throw new SessionExpiredException();
                if (testInternalError(bodyPeek))
                    throw new InternalErrorException(url, bodyPeek);

                // save cookie
                if (sessionCookieName != null) {
                    final List<Cookie> cookies = Cookie.parseAll(url, response.headers());
                    for (final Cookie cookie : cookies) {
                        if (cookie.name().equals(sessionCookieName)) {
                            this.sessionCookie = cookie;
                            break;
                        }
                    }
                }

                callback.onSuccessful(bodyPeek, response.body());
                return;
            } else if (RESPONSE_CODES_BLOCKED.contains(responseCode)) {
                throw new BlockedException(url, bodyPeek);
            } else if (RESPONSE_CODES_NOT_FOUND.contains(responseCode)) {
                throw new NotFoundException(url, bodyPeek);
            } else if (RESPONSE_CODES_REDIRECT.contains(responseCode)) {
                throw new UnexpectedRedirectException(url, HttpUrl.parse(response.header("Location")));
            } else if (RESPONSE_CODES_INTERNAL_ERROR.contains(responseCode)) {
                throw new InternalErrorException(url, bodyPeek);
            } else {
                final String message = "got response: " + responseCode + " " + response.message();
                throw new IOException(message + ": " + url);
            }
        }
    }

    private static final Pattern P_REDIRECT_HTTP_EQUIV = Pattern.compile(
            "<META\\s+http-equiv=\"?refresh\"?\\s+content=\"\\d+;\\s*URL=([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_REDIRECT_SCRIPT = Pattern.compile(
            "<script\\s+(?:type=\"text/javascript\"|language=\"javascript\")>\\s*(?:window.location|location.href)\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    public static HttpUrl testRedirect(final HttpUrl base, final String content) {
        // check for redirect by http-equiv meta tag header
        final Matcher mHttpEquiv = P_REDIRECT_HTTP_EQUIV.matcher(content);
        if (mHttpEquiv.find())
            return base.resolve(mHttpEquiv.group(1));

        // check for redirect by window.location javascript
        final Matcher mScript = P_REDIRECT_SCRIPT.matcher(content);
        if (mScript.find())
            return base.resolve(mScript.group(1));

        return null;
    }

    private static final Pattern P_EXPIRED = Pattern.compile(
            ">\\s*(Your session has expired\\.|Session Expired|Ihre Verbindungskennung ist nicht mehr g.ltig\\.)\\s*<");

    public static boolean testExpired(final String content) {
        // check for expired session
        final Matcher mSessionExpired = P_EXPIRED.matcher(content);
        if (mSessionExpired.find())
            return true;

        return false;
    }

    private static final Pattern P_INTERNAL_ERROR = Pattern.compile(
            ">\\s*(Internal Error|Server ein Fehler aufgetreten|Internal error in gateway|VRN - Keine Verbindung zum Server m.glich)\\s*<");

    public static boolean testInternalError(final String content) {
        // check for internal error
        final Matcher m = P_INTERNAL_ERROR.matcher(content);
        if (m.find())
            return true;

        return false;
    }

    private void configureSSL(final OkHttpClient.Builder okHttpClientBuilder) {
        try {
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(clientCertificateKeyManagers, new TrustManager[] { TRUST_ALL_CERTIFICATES }, null);
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            okHttpClientBuilder.sslSocketFactory(sslSocketFactory, TRUST_ALL_CERTIFICATES);
        } catch (final Exception x) {
            throw new RuntimeException(x);
        }
    }

    private static final X509TrustManager TRUST_ALL_CERTIFICATES = new X509TrustManager() {
        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
