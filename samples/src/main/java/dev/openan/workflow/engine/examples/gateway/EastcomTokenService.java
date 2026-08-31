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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.CredentialCrypto;
import dev.openan.workflow.engine.examples.config.OrderGatewayProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Obtains and caches OMC bearer tokens through the Eastcom {@link HttpClient}. */
final class EastcomTokenService implements EastcomAuthProvider.TokenService {
  private static final Logger log = LoggerFactory.getLogger(EastcomTokenService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BEARER_AUTH = "bearerAuth";
  private static final String NE_USERNAME = "${ne:username}";
  private static final String NE_PASSWORD = "${ne:password}";
  private final Map<String, String> agentNeRoutes;
  private final String defaultNe;
  private final Duration defaultTokenTtl;
  private final Map<String, Duration> agentTokenTtls;
  private final Clock clock;
  private final TokenFetcher tokenFetcher;
  private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
  private final Map<String, Object> refreshLocks = new ConcurrentHashMap<>();
  EastcomTokenService(OrderGatewayProperties properties, String credentialsPath) {
    this(properties, EastcomCredentialConfigLoader.load(credentialsPath));
  }

  private EastcomTokenService(
      OrderGatewayProperties properties,
      Map<String, Map<String, Map<String, Object>>> credentials) {
    this(
        routes(properties),
        blankToNull(properties.getDefaultNe()),
        Duration.ofSeconds(positive(properties.getOmcTokenTtlSeconds(), "omcTokenTtlSeconds")),
        tokenTtls(credentials),
        Clock.systemUTC(),
        sdkFetcher(properties, credentials));
  }

  EastcomTokenService(
      Map<String, String> agentNeRoutes,
      String defaultNe,
      Duration tokenTtl,
      Clock clock,
      TokenFetcher tokenFetcher) {
    this(agentNeRoutes, defaultNe, tokenTtl, Map.of(), clock, tokenFetcher);
  }

  private EastcomTokenService(
      Map<String, String> agentNeRoutes,
      String defaultNe,
      Duration defaultTokenTtl,
      Map<String, Duration> agentTokenTtls,
      Clock clock,
      TokenFetcher tokenFetcher) {
    this.agentNeRoutes = Map.copyOf(agentNeRoutes);
    this.defaultNe = blankToNull(defaultNe);
    this.defaultTokenTtl = Objects.requireNonNull(defaultTokenTtl, "defaultTokenTtl");
    this.agentTokenTtls = Map.copyOf(agentTokenTtls);
    if (defaultTokenTtl.isZero() || defaultTokenTtl.isNegative()) {
      throw new IllegalArgumentException("defaultTokenTtl must be positive");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tokenFetcher = Objects.requireNonNull(tokenFetcher, "tokenFetcher");
  }

  private static Duration refreshSkew(Duration ttl) {
    long seconds = Math.min(30, Math.max(1, ttl.toSeconds() / 10));
    return Duration.ofSeconds(seconds);
  }

  private static TokenFetcher sdkFetcher(
      OrderGatewayProperties properties,
      Map<String, Map<String, Map<String, Object>>> credentials) {
    ServerInfo serverInfo =
        ServerInfo.builder()
            .host(requireText(properties.getHost(), "host"))
            .port(positive(properties.getPort(), "port"))
            .username(requireText(properties.getUsername(), "username"))
            .password(requireText(properties.getPassword(), "password"))
            .clientId(requireText(properties.getClientId(), "clientId"))
            .clientSecret(blankToNull(properties.getClientSecret()))
            .build();
    Duration timeout =
        Duration.ofSeconds(positive(properties.getLoginTimeoutSeconds(), "loginTimeoutSeconds"));

    return (agentName, ne) -> {
      Map<String, Object> scheme = credentialScheme(credentials, agentName);
      String loginPath = loginPath(scheme, properties.getOmcLoginPath());
      String loginMethod = textOrDefault(scheme.get("method"), properties.getOmcLoginMethod());
      String contentType = textOrDefault(scheme.get("content_type"), "application/json");
      HttpRequestConfig requestConfig = HttpRequestConfig.builder().deviceName(ne).build();
      HttpClient client = EastcomOrder118ByteBufWorkaround.createClient(serverInfo, requestConfig);
      client.responseTimeout(timeout);
      HttpResponse response =
          request(client, loginMethod)
              .uri(loginPath)
              .header("Content-Type", contentType)
              .body(loginBody(scheme, properties))
              .send();
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new SecurityException(
            "OMC login through Eastcom failed for agent "
                + agentName
                + " with status "
                + response.statusCode());
      }
      String responseHeader =
          textOrDefault(scheme.get("order_token_header"), properties.getOmcTokenResponseHeader());
      String token = headerIgnoreCase(response, responseHeader);
      String source = "header:" + responseHeader;
      if (token == null || token.isBlank()) {
        String tokenField = textOrDefault(scheme.get("token_field"), "accessSession");
        token = bodyToken(response, tokenField);
        source = "body:" + tokenField;
      }
      if (token == null || token.isBlank()) {
        throw new SecurityException(
            "OMC login response for agent "
                + agentName
                + " has neither header "
                + responseHeader
                + " nor body field "
                + textOrDefault(scheme.get("token_field"), "accessSession"));
      }
      log.info("[EastcomAuth] TOKEN_EXTRACTED agent={}, ne={}, source={}", agentName, ne, source);
      return token;
    };
  }

  private static Map<String, Object> credentialScheme(
      Map<String, Map<String, Map<String, Object>>> credentials, String agentName) {
    Map<String, Map<String, Object>> agent = credentials.get(agentName);
    if (agent == null || agent.isEmpty()) {
      throw new SecurityException("No OMC credential profile configured for agent " + agentName);
    }
    Map<String, Object> scheme = agent.get(BEARER_AUTH);
    if (scheme != null) {
      return scheme;
    }
    if (agent.size() == 1) {
      return agent.values().iterator().next();
    }
    throw new SecurityException(
        "No bearerAuth credential profile configured for agent " + agentName);
  }

  private static Map<String, Object> loginBody(
      Map<String, Object> scheme, OrderGatewayProperties properties) {
    Object configuredFields = scheme.get("request_fields");
    if (configuredFields instanceof Map<?, ?> fields) {
      Map<String, Object> body = new LinkedHashMap<>();
      fields.forEach(
          (key, value) ->
              body.put(
                  String.valueOf(key),
                  value instanceof String text ? CredentialCrypto.decryptIfNeeded(text) : value));
      return body;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("grantType", "password");
    body.put(requireText(properties.getOmcUsernameField(), "omcUsernameField"), NE_USERNAME);
    body.put(requireText(properties.getOmcPasswordField(), "omcPasswordField"), NE_PASSWORD);
    body.put("ipaddr", "*");
    return body;
  }

  private static String loginPath(Map<String, Object> scheme, String fallback) {
    Object configuredUrl = scheme.get("login_url");
    if (configuredUrl == null || String.valueOf(configuredUrl).isBlank()) {
      return requirePath(fallback);
    }
    URI uri = URI.create(String.valueOf(configuredUrl));
    String path = requirePath(uri.getRawPath());
    return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
  }

  private static String bodyToken(HttpResponse response, String field) {
    try {
      Map<String, Object> body =
          MAPPER.readValue(
              response.responseContent().asString(), new TypeReference<Map<String, Object>>() {});
      Object current = body;
      for (String part : field.split("\\.")) {
        if (!(current instanceof Map<?, ?> map)) {
          return null;
        }
        current = map.get(part);
      }
      return current == null ? null : String.valueOf(current);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Map<String, Duration> tokenTtls(
      Map<String, Map<String, Map<String, Object>>> credentials) {
    Map<String, Duration> result = new LinkedHashMap<>();
    credentials.forEach(
        (agent, schemes) -> {
          Map<String, Object> scheme = schemes.get(BEARER_AUTH);
          if (scheme != null && scheme.get("token_ttl") != null) {
            long seconds = Long.parseLong(String.valueOf(scheme.get("token_ttl")));
            if (seconds <= 0) {
              throw new IllegalArgumentException("token_ttl must be positive for " + agent);
            }
            result.put(agent, Duration.ofSeconds(seconds));
          }
        });
    return result;
  }

  private static RequestBodyUriSpec request(HttpClient client, String method) {
    return switch (method.trim().toUpperCase(Locale.ROOT)) {
      case "POST" -> client.post();
      case "PUT" -> client.put();
      default ->
          throw new IllegalArgumentException(
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

  private static String textOrDefault(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank()
        ? requireText(fallback, "fallback")
        : String.valueOf(value).trim();
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

  @Override
  public String getOrRefresh(String agentName) {
    String key = requireText(agentName, "agentName");
    Instant now = clock.instant();
    Duration ttl = agentTokenTtls.getOrDefault(key, defaultTokenTtl);
    CachedToken current = cache.get(key);
    if (current != null && current.isUsableAt(now, refreshSkew(ttl))) {
      return current.value();
    }
    Object lock = refreshLocks.computeIfAbsent(key, ignored -> new Object());
    synchronized (lock) {
      now = clock.instant();
      current = cache.get(key);
      if (current != null && current.isUsableAt(now, refreshSkew(ttl))) {
        return current.value();
      }
      String ne = resolveNe(key);
      log.info("[EastcomAuth] TOKEN_REFRESH_START agent={}, ne={}", key, ne);
      String token = requireText(tokenFetcher.fetch(key, ne), "OMC bearer token");
      cache.put(key, new CachedToken(token, now.plus(ttl)));
      log.info(
          "[EastcomAuth] TOKEN_REFRESH_DONE agent={}, ne={}, ttlSeconds={}",
          key,
          ne,
          ttl.toSeconds());
      return token;
    }
  }

  void invalidate(String agentName) {
    if (agentName != null) {
      cache.remove(agentName);
    }
  }

  private String resolveNe(String agentName) {
    String ne = agentNeRoutes.get(agentName);
    if (ne == null || ne.isBlank()) {
      ne = defaultNe;
    }
    if (ne == null || ne.isBlank()) {
      throw new IllegalArgumentException("No Eastcom NE route configured for agent " + agentName);
    }
    return ne;
  }

  @FunctionalInterface
  interface TokenFetcher {
    String fetch(String agentName, String ne);
  }

  private record CachedToken(String value, Instant expiresAt) {
    private boolean isUsableAt(Instant now, Duration skew) {
      return now.isBefore(expiresAt.minus(skew));
    }
  }
}
