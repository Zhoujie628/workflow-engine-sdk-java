/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.gateway;

import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.core.common.ServerInfo;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpClient;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpRequestConfig;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.HttpResponse;
import com.eastcom.apollo.orders.internal.shaded.v11x.com.eastcom.apollo.orders.client.http.internal.RequestBodyUriSpec;

import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Obtains and caches OMC bearer tokens through the Eastcom {@link HttpClient}. */
final class EastcomTokenService implements EastcomAuthProvider.TokenService {
    private static final Logger log = LoggerFactory.getLogger(EastcomTokenService.class);
    private static final String NE_USERNAME = "${ne:username}";
    private static final String NE_PASSWORD = "${ne:password}";

    @FunctionalInterface
    interface TokenFetcher {
        String fetch(String agentName, String ne);
    }

    private final Map<String, String> agentNeRoutes;
    private final String defaultNe;
    private final Duration tokenTtl;
    private final Clock clock;
    private final TokenFetcher tokenFetcher;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> refreshLocks = new ConcurrentHashMap<>();

    EastcomTokenService(OrderGatewayProperties properties) {
        this(
                routes(properties),
                blankToNull(properties.getDefaultNe()),
                Duration.ofSeconds(positive(properties.getOmcTokenTtlSeconds(), "omcTokenTtlSeconds")),
                Clock.systemUTC(),
                sdkFetcher(properties));
    }

    EastcomTokenService(
            Map<String, String> agentNeRoutes,
            String defaultNe,
            Duration tokenTtl,
            Clock clock,
            TokenFetcher tokenFetcher) {
        this.agentNeRoutes = Map.copyOf(agentNeRoutes);
        this.defaultNe = blankToNull(defaultNe);
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("tokenTtl must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenFetcher = Objects.requireNonNull(tokenFetcher, "tokenFetcher");
    }

    @Override
    public String getOrRefresh(String agentName) {
        String key = requireText(agentName, "agentName");
        Instant now = clock.instant();
        CachedToken current = cache.get(key);
        if (current != null && current.isUsableAt(now, refreshSkew())) {
            return current.value();
        }
        Object lock = refreshLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            now = clock.instant();
            current = cache.get(key);
            if (current != null && current.isUsableAt(now, refreshSkew())) {
                return current.value();
            }
            String ne = resolveNe(key);
            log.info("[EastcomAuth] TOKEN_REFRESH_START agent={}, ne={}", key, ne);
            String token = requireText(tokenFetcher.fetch(key, ne), "OMC bearer token");
            cache.put(key, new CachedToken(token, now.plus(tokenTtl)));
            log.info(
                    "[EastcomAuth] TOKEN_REFRESH_DONE agent={}, ne={}, ttlSeconds={}",
                    key,
                    ne,
                    tokenTtl.toSeconds());
            return token;
        }
    }

    void invalidate(String agentName) {
        if (agentName != null) {
            cache.remove(agentName);
        }
    }

    private Duration refreshSkew() {
        long seconds = Math.min(30, Math.max(1, tokenTtl.toSeconds() / 10));
        return Duration.ofSeconds(seconds);
    }

    private String resolveNe(String agentName) {
        String ne = agentNeRoutes.get(agentName);
        if (ne == null || ne.isBlank()) {
            ne = defaultNe;
        }
        if (ne == null || ne.isBlank()) {
            throw new IllegalArgumentException(
                    "No Eastcom NE route configured for agent " + agentName);
        }
        return ne;
    }

    private static TokenFetcher sdkFetcher(OrderGatewayProperties properties) {
        ServerInfo serverInfo =
                ServerInfo.builder()
                        .host(requireText(properties.getHost(), "host"))
                        .port(positive(properties.getPort(), "port"))
                        .username(requireText(properties.getUsername(), "username"))
                        .password(requireText(properties.getPassword(), "password"))
                        .clientId(requireText(properties.getClientId(), "clientId"))
                        .clientSecret(blankToNull(properties.getClientSecret()))
                        .build();
        String loginPath = requirePath(properties.getOmcLoginPath());
        String loginMethod = requireText(properties.getOmcLoginMethod(), "omcLoginMethod");
        String responseHeader =
                requireText(properties.getOmcTokenResponseHeader(), "omcTokenResponseHeader");
        String usernameField =
                requireText(properties.getOmcUsernameField(), "omcUsernameField");
        String passwordField =
                requireText(properties.getOmcPasswordField(), "omcPasswordField");
        Duration timeout =
                Duration.ofSeconds(
                        positive(properties.getLoginTimeoutSeconds(), "loginTimeoutSeconds"));

        return (agentName, ne) -> {
            HttpRequestConfig requestConfig =
                    HttpRequestConfig.builder().deviceName(ne).build();
            HttpClient client =
                    EastcomOrder118ByteBufWorkaround.createClient(serverInfo, requestConfig);
            client.responseTimeout(timeout);
            RequestBodyUriSpec request = request(client, loginMethod);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grantType", "password");
            body.put(usernameField, NE_USERNAME);
            body.put(passwordField, NE_PASSWORD);
            body.put("ipaddr", "*");
            HttpResponse response =
                    request.uri(loginPath)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .send();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SecurityException(
                        "OMC login through Eastcom failed for agent "
                                + agentName
                                + " with status "
                                + response.statusCode());
            }
            String token = headerIgnoreCase(response, responseHeader);
            if (token == null || token.isBlank()) {
                throw new SecurityException(
                        "OMC login response for agent "
                                + agentName
                                + " is missing header "
                                + responseHeader);
            }
            return token;
        };
    }

    private static RequestBodyUriSpec request(HttpClient client, String method) {
        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "POST" -> client.post();
            case "PUT" -> client.put();
            default -> throw new IllegalArgumentException(
                    "Unsupported OMC login method " + method + "; use POST or PUT");
        };
    }

    private static String headerIgnoreCase(HttpResponse response, String name) {
        String exact = response.headers().get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : response.headers().entries()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> routes(OrderGatewayProperties properties) {
        Map<String, String> routes = new LinkedHashMap<>();
        putIfPresent(routes, "SPN Domain Agent City1", properties.getCity1Ne());
        putIfPresent(routes, "SPN Domain Agent City2", properties.getCity2Ne());
        return routes;
    }

    private static void putIfPresent(Map<String, String> routes, String agent, String ne) {
        if (ne != null && !ne.isBlank()) {
            routes.put(agent, ne.trim());
        }
    }

    private static String requirePath(String value) {
        String path = requireText(value, "omcLoginPath");
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CachedToken(String value, Instant expiresAt) {
        private boolean isUsableAt(Instant now, Duration skew) {
            return now.isBefore(expiresAt.minus(skew));
        }
    }
}
