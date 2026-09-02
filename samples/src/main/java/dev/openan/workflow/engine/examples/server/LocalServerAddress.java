/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
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
