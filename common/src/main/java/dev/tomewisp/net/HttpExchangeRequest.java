package dev.tomewisp.net;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Engine-neutral outbound request. Its string form never renders URI, headers, or body. */
public final class HttpExchangeRequest {
    private final URI uri;
    private final String method;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Duration timeout;

    private HttpExchangeRequest(Builder builder) {
        uri = Objects.requireNonNull(builder.uri, "uri");
        method = builder.method;
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        builder.headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
        headers = java.util.Collections.unmodifiableMap(copied);
        body = builder.body.clone();
        timeout = Objects.requireNonNull(builder.timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("HTTP request timeout must be positive");
        }
    }

    public static Builder newBuilder(URI uri) {
        return new Builder(uri);
    }

    public URI uri() {
        return uri;
    }

    public String method() {
        return method;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return "HttpExchangeRequest[method=" + method + "]";
    }

    public static final class Builder {
        private final URI uri;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private String method = "GET";
        private byte[] body = new byte[0];
        private Duration timeout = Duration.ofSeconds(60);

        private Builder(URI uri) {
            this.uri = Objects.requireNonNull(uri, "uri");
        }

        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value, "timeout");
            return this;
        }

        public Builder header(String name, String value) {
            if (name == null || name.isBlank() || value == null) {
                throw new IllegalArgumentException("HTTP header name and value are required");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder get() {
            method = "GET";
            body = new byte[0];
            return this;
        }

        public Builder postJson(String json) {
            method = "POST";
            body = Objects.requireNonNull(json, "json").getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public HttpExchangeRequest build() {
            return new HttpExchangeRequest(this);
        }
    }
}
