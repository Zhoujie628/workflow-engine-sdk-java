/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

/** Same northbound failure contract through the real vendor SDK and the local Order simulator. */
class SpringSpnDemoOrderFailureE2ETest extends SpringSpnDemoFailureE2ETest {
  @Override protected String[] arguments(String city1, String city2) throws Exception {
    var args = new java.util.ArrayList<>(java.util.List.of(super.arguments(city1, city2)));
    args.remove("--a2a.transport-mode=direct");
    int port;
    try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
    args.addAll(java.util.List.of(
        "--a2a.transport-mode=order", "--a2a.order.simulator-enabled=true",
        "--a2a.order.host=127.0.0.1", "--a2a.order.port=" + port,
        "--a2a.order.username=sim-user", "--a2a.order.password=sim-password",
        "--a2a.order.client-id=sim-client", "--a2a.order.client-secret=sim-secret",
        "--a2a.order.city1-ne=sim-city1", "--a2a.order.city2-ne=sim-city2",
        "--a2a.order.simulator-city1-target-url=" + city1,
        "--a2a.order.simulator-city2-target-url=" + city2,
        "--a2a.order.omc-auth-enabled=false", "--a2a.order.timeout-seconds=10"));
    return args.toArray(String[]::new);
  }
}
