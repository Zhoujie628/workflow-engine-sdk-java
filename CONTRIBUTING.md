# Contributing to workflow-engine-sdk-java

Thank you for your interest in contributing! This document covers the contribution process and coding standards.

## Prerequisites

- JDK 17+
- Maven 3.6+
- Git

## Development Setup

```bash
git clone https://github.com/project-openan/workflow-engine-sdk-java.git
cd workflow-engine-sdk-java
mvn -B clean verify
```

## Project Structure

```
workflow-engine-sdk-java/
|-- workflow-engine/       SDK engine module
|   +-- src/main/java/dev/openan/workflow/engine/
|       |-- client/       A2A transport, auth, extensions (package-private internals)
|       |-- control/      User-facing: ControlPoint, EventCallback, EventType
|       |-- core/         Internal: WorkflowExecutor, ContextBuilder (package-private)
|       |-- model/        Data models
|       |-- registry/     LoadPsop, RegistryClient
|       +-- runner/       ExecutePsop (entry point)
|-- samples/              Demo applications
|-- docs/                 Documentation
|-- pom.xml               Parent POM (reactor)
```

## Coding Standards

### Java

- Java 17 language features (records, sealed, switch expressions)
- Methods should not exceed 50 lines; extract subroutines
- No raw `Object` types where a specific type exists
- Suppress warnings only when unavoidable; prefer type-safe alternatives
- Package-private for internal classes; `public` only for user-facing API
- All public methods must have Javadoc

### File Encoding

- Source files: UTF-8
- No BOM in source files
- Maven `sourceEncoding` is UTF-8 in the parent POM

### License Headers

Every Java file must start with the Apache 2.0 license header:

```java
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
```

### Naming

- `PascalCase` for classes, `camelCase` for methods/variables
- `UPPER_SNAKE_CASE` for constants
- Interface names: nouns or adjectives (`WorkflowEngineClient`, `AuthProvider`)
- Builder classes: nested `Builder` static class

### Logging

- SLF4J Logger per class
- INFO: key lifecycle events, auth success/failure, negotiation rounds
- DEBUG: full message content, prompt text, response details
- WARN: recoverable failures, fallbacks
- ERROR: auth failures, agent call failures
- Dedicated `PROTOCOL` logger for protocol-level request/response dumps
- Always anonymize credentials; protocol bodies have configurable size limits and sensitive-data redaction

### Testing

- JUnit 5
- Unit tests for all public methods
- Integration tests for end-to-end workflows
- Run the complete reactor: `mvn -B clean verify`; `-o` is only for a fully populated local dependency cache

## Commit Process

1. Fork the repository and create a feature branch
2. Write code following the standards above
3. Add/update tests
4. Run `mvn -B clean verify` and ensure all tests pass (including samples)
5. Add DCO signoff to your commit:
   ```
   Signed-off-by: Your Name <your.email@example.com>
   ```
6. Use conventional commit messages:
    - `feat:` new feature
    - `fix:` bug fix
    - `docs:` documentation
    - `refactor:` code restructuring
    - `test:` test additions
    - `chore:` build/config

## Pull Request

1. Ensure your branch is up to date with `main`
2. Squash unrelated commits
3. Write a clear PR description with:
    - What changed and why
    - Any breaking changes
    - Test results
4. Link related issues

## Issue Reporting

- Use GitHub Issues
- Include: Java version, Maven version, error log, reproduction steps
- For protocol issues: include the `PROTOCOL` logger output

## Release acceptance

- Run the complete `mvn -B clean verify` reactor; sample failures block release.
- Documentation code snippets are compiled by DocumentedCallbackExampleTest; HostQuickStartTest executes the minimal host flow.
- Check default single-city negotiation, final serialized protocol logs, independent extension lifecycle, cancellation and secret anonymization.
- Verify published POM coordinates, sources/Javadoc artifacts and the absence of private configuration/reference documents.
- Record the tested commit and Surefire reports. Offline providers and local OMC simulators are not live acceptance.
- Live model/OMC validation requires authorized endpoints and a separate acceptance record. Never commit customer integration notes.
- Do not publish, tag, or push as a side effect of running tests.

### Eastcom release gate (dev)

The `eastcom-sdk` self-hosted runner must be provisioned with the licensed
`com.eastcom.apollo:order-shaded-client:1.1.18` artifact and JDK/Maven.
Restrict that runner to trusted repositories/branch pushes and manual runs; it must never execute untrusted fork PR code.
The CI sample job runs the full reactor, including direct and Order simulator tests, without `continue-on-error`.
A missing/offline runner is an unmet release prerequisite, not permission to skip the gate.
Configuring runner access and branch protection is a repository-administration step, separate from this source change.
