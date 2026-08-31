/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.examples.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Sample workbench outbound-client settings. Vendor-specific settings live separately. */
@Component
@ConfigurationProperties(prefix = "a2a")
public class WorkbenchClientProperties {
  private String orchUrl = "https://127.0.0.1:5001";
  private String credentialsPath = "";
  private boolean sslVerify;
  private String a2atEnvPath = "";
  private boolean demoNegotiationEnabled;

  /**
   * Internal sample-host setting; SpringSpnDemo supplies its local-only default per application
   * context.
   */
  public boolean isDemoNegotiationEnabled() {
    return demoNegotiationEnabled;
  }

  /** Enables missing-input demonstration for this host only, never a JVM-wide switch. */
  public void setDemoNegotiationEnabled(boolean enabled) {
    demoNegotiationEnabled = enabled;
  }

  public String getOrchUrl() {
    return orchUrl;
  }

  public void setOrchUrl(String orchUrl) {
    this.orchUrl = orchUrl;
  }

  public String getCredentialsPath() {
    return credentialsPath;
  }

  public void setCredentialsPath(String credentialsPath) {
    this.credentialsPath = credentialsPath;
  }

  public boolean isSslVerify() {
    return sslVerify;
  }

  public void setSslVerify(boolean sslVerify) {
    this.sslVerify = sslVerify;
  }

  public String getA2atEnvPath() {
    return a2atEnvPath;
  }

  public void setA2atEnvPath(String a2atEnvPath) {
    this.a2atEnvPath = a2atEnvPath;
  }
}
