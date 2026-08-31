/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.demo;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentedCallbackExampleTest {
    @Test void bilingualCallbackAndSdkExamplesCompileAgainstCurrentPublicApi() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("docs"))) root = root.getParent();
        for (String language : List.of("en", "zh")) {
            String doc = Files.readString(root.resolve("docs/" + language + "/BUSINESS_CALLBACKS.md"));
            String callbacks = block(doc, "ControlPoint callbacks =");
            String generated = block(doc, "MetadataContent generated =");
            String source = """
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
                """ + callbacks + "\nreturn callbacks;\n}\n"
                + "MessageContent generate(A2ATClient sdk, Map<String,Object> data, Map<String,Object> schema) {\n"
                + generated + "\nreturn outgoing;\n}\n}";
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "Tests require a JDK, not a JRE");
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///DocumentedCallbacks.java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
            };
            try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
                 var output = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                     @Override public JavaFileObject getJavaFileForOutput(Location location, String name,
                             JavaFileObject.Kind kind, FileObject sibling) {
                         return new SimpleJavaFileObject(URI.create("bytes:///" + name + kind.extension), kind) {
                             @Override public java.io.OutputStream openOutputStream() {
                                 return new java.io.ByteArrayOutputStream();
                             }
                         };
                     }
                 }) {
                boolean ok = compiler.getTask(null, output, diagnostics,
                        List.of("-proc:none", "-classpath", System.getProperty("java.class.path")),
                        null, List.of(input)).call();
                assertTrue(ok, () -> language + ": " + diagnostics.getDiagnostics());
            }
        }
    }

    private static String block(String doc, String marker) {
        int begin = doc.indexOf(marker);
        assertTrue(begin >= 0, marker);
        int end = doc.indexOf("\n\u0060\u0060\u0060", begin);
        assertTrue(end > begin, marker);
        return doc.substring(begin, end);
    }
}
