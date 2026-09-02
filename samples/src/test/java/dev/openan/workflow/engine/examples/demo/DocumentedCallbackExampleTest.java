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
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;

class DocumentedCallbackExampleTest {
  private static String block(String doc, String marker) {
    int begin = doc.indexOf(marker);
    assertTrue(begin >= 0, marker);
    int end = doc.indexOf("\n\u0060\u0060\u0060", begin);
    assertTrue(end > begin, marker);
    return doc.substring(begin, end);
  }

  @Test
  void bilingualCallbackAndSdkExamplesCompileAgainstCurrentPublicApi() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    if (!Files.isDirectory(root.resolve("docs"))) root = root.getParent();
    for (String language : List.of("en", "zh")) {
      String doc = Files.readString(root.resolve("docs/" + language + "/BUSINESS_CALLBACKS.md"));
      String callbacks = block(doc, "ControlPoint callbacks =");
      String generated = block(doc, "MetadataContent generated =");
      String callbackSource =
          """
                import java.util.*;
                import java.util.concurrent.*;
                import dev.openan.workflow.engine.control.*;
                import dev.openan.workflow.engine.model.*;
                import dev.openan.workflow.engine.client.*;
                import net.openan.a2at.sdk.client.A2ATClient;
                import net.openan.a2at.sdk.core.model.*;
                import org.a2aproject.sdk.spec.TextPart;
                class DocumentedCallbacks {
                    ControlPoint callbacks() {
                """
              + callbacks
              + "\nreturn callbacks;\n}\n"
              + "MessageContent generate(A2ATClient sdk, Map<String,Object> data, Map<String,Object> schema) {\n"
              + generated
              + "\nreturn outgoing;\n}\n}";
      String integration = Files.readString(root.resolve("docs/" + language + "/INTEGRATION_GUIDE.md"));
      String quickStartSource = """
          import java.util.*;
          import java.util.concurrent.*;
          import com.fasterxml.jackson.databind.ObjectMapper;
          import org.a2aproject.sdk.spec.AgentCard;
          import dev.openan.workflow.engine.control.*;
          import dev.openan.workflow.engine.model.*;
          import dev.openan.workflow.engine.client.*;
          import dev.openan.workflow.engine.registry.*;
          import dev.openan.workflow.engine.runner.ExecutePsop;
          class DocumentedIntegration {
            void run() throws Exception {
          """
          + block(integration, "Workflow workflow =") + "\n"
          + block(integration, "RegistryClient registry =") + "\n"
          + block(integration, "ControlPoint callbacks =") + "\n"
          + block(integration, "CompletableFuture<ExecutionResult> execution =") + "\n}}";
      for (String source : List.of(callbackSource, quickStartSource)) {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      assertNotNull(compiler, "Tests require a JDK, not a JRE");
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      JavaFileObject input =
          new SimpleJavaFileObject(
              URI.create("string:///DocumentedCallbacks.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return source;
            }
          };
      try (StandardJavaFileManager standard =
              compiler.getStandardFileManager(diagnostics, null, null);
          var output =
              new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(
                    Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
                  return new SimpleJavaFileObject(
                      URI.create("bytes:///" + name + kind.extension), kind) {
                    @Override
                    public java.io.OutputStream openOutputStream() {
                      return new java.io.ByteArrayOutputStream();
                    }
                  };
                }
              }) {
        boolean ok =
            compiler
                .getTask(
                    null,
                    output,
                    diagnostics,
                    List.of("-proc:none", "-classpath", System.getProperty("java.class.path")),
                    null,
                    List.of(input))
                .call();
        assertTrue(ok, () -> language + ": " + diagnostics.getDiagnostics());
      }
      }
    }
  }
}
