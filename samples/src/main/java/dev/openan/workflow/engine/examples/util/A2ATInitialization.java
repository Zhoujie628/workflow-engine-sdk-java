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

package dev.openan.workflow.engine.examples.util;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Initializes SDK facades with privately owned jar connections during catalog discovery. The
 * current SDK closes the catalog's JarFile; disabling caching on those connections prevents it from
 * closing another facade's shared handle. No global URL cache setting is changed.
 */
public final class A2ATInitialization {
  private A2ATInitialization() {}

  /**
   * Temporarily isolates catalog URLs on the current thread and always restores its classloader.
   */
  public static <T> T create(Supplier<T> factory) {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    ClassLoader parent = original == null ? A2ATInitialization.class.getClassLoader() : original;
    thread.setContextClassLoader(
        new ClassLoader(parent) {
          @Override
          public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> urls = Collections.list(parent.getResources(name));
            List<URL> owned = new ArrayList<>();
            for (URL url : urls) owned.add(uncachedJar(url));
            return Collections.enumeration(owned);
          }
        });
    try {
      return factory.get();
    } finally {
      thread.setContextClassLoader(original);
    }
  }

  private static URL uncachedJar(URL original) throws MalformedURLException {
    if (!"jar".equals(original.getProtocol())) return original;
    return new URL(
        null,
        original.toExternalForm(),
        new URLStreamHandler() {
          @Override
          protected URLConnection openConnection(URL ignored) throws IOException {
            URLConnection connection = original.openConnection();
            connection.setUseCaches(false);
            return connection;
          }
        });
  }
}
