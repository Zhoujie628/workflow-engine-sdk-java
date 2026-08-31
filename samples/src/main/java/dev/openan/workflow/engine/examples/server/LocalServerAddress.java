/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.server;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;

/** Validates local listener addresses without opening a socket or contacting an OMC. */
public final class LocalServerAddress {
  private LocalServerAddress() {}

  /** Requires every resolved address to belong to this host, including loopback and wildcard. */
  public static void requireLocalHost(String host, String setting) {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException(setting + " must specify a local bind host");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (!address.isLoopbackAddress()
            && !address.isAnyLocalAddress()
            && NetworkInterface.getByInetAddress(address) == null) {
          throw new IllegalArgumentException(
              setting + " cannot bind a non-local address; for an external OMC set "
                  + "A2A_EMBEDDED_OMC_ENABLED=false; for an external platform disable its simulator");
        }
      }
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(setting + " cannot resolve a local bind address", e);
    }
  }

  /** Validates an advertised HTTP(S) endpoint before using it as a local server address. */
  public static URI requireLocalEndpoint(String url, String setting) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(setting + " must be an absolute HTTP(S) URL");
    }
    if (uri.getHost() == null || uri.getUserInfo() != null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || uri.getPort() > 65535) {
      throw new IllegalArgumentException(setting + " must be an absolute HTTP(S) URL without user info");
    }
    requireLocalHost(uri.getHost(), setting);
    return uri;
  }
}
