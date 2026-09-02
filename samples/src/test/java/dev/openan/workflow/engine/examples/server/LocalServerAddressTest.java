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

import static org.junit.jupiter.api.Assertions.*;

import java.net.NetworkInterface;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LocalServerAddressTest {
  @Test
  void acceptsLoopbackWildcardAndActualInterfaceAddresses() throws Exception {
    for (String host : new String[] {"127.0.0.1", "localhost", "::1", "0.0.0.0"}) {
      assertDoesNotThrow(() -> LocalServerAddress.requireLocalHost(host, "test"));
    }
    for (var network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      for (var address : Collections.list(network.getInetAddresses())) {
        assertDoesNotThrow(() -> LocalServerAddress.requireLocalHost(address.getHostAddress(), "test"));
      }
    }
  }

  @Test
  void rejectsRemoteBindWithoutOpeningASocket() {
    var error = assertThrows(IllegalArgumentException.class,
        () -> LocalServerAddress.requireLocalHost("192.0.2.17", "Embedded OMC"));
    assertTrue(error.getMessage().contains("A2A_EMBEDDED_OMC_ENABLED=false"));
  }

  @Test
  void rejectsMissingHostInvalidPortAndUserInfo() {
    for (String url : new String[] {"not-a-url", "https://", "https://127.0.0.1:65536",
        "https://user:secret@127.0.0.1:26335"}) {
      var error = assertThrows(IllegalArgumentException.class,
          () -> LocalServerAddress.requireLocalEndpoint(url, "test"));
      assertFalse(error.getMessage().contains("secret"));
    }
  }
}
