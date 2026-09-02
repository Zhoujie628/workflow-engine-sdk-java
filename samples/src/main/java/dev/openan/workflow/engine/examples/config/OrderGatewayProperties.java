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
package dev.openan.workflow.engine.examples.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Eastcom Order SDK settings, intentionally isolated from the generic starter. */
@Component
@ConfigurationProperties(prefix = "a2a.order")
public class OrderGatewayProperties {
  private boolean simulatorEnabled;
  private String host = "";
  private int port;
  private String username = "";
  private String password = "";
  private String clientId = "";
  private String clientSecret = "";
  private String defaultNe = "";
  private String city1Ne = "sim-city1";
  private String city2Ne = "sim-city2";
  private String simulatorCity1TargetUrl = "https://127.0.0.1:26335";
  private String simulatorCity2TargetUrl = "https://127.0.0.1:26336";
  private String simulatorCity1Username = "admin";
  private String simulatorCity1Password = "Admin@123";
  private String simulatorCity2Username = "admin";
  private String simulatorCity2Password = "Admin@123";
  private int simulatorConnectTimeoutSeconds = 30;
  private int simulatorReadTimeoutSeconds = 30;
  private int loginTimeoutSeconds = 15;
  private int timeoutSeconds = 600;
  private boolean omcAuthEnabled = true;
  private String omcCredentialsPath = "classpath:spn_agent_credentials.json";
  private String omcLoginPath = "/rest/plat/smapp/v1/oauth/token";
  private String omcLoginMethod = "PUT";
  private String omcTokenResponseHeader = "accessSession";
  private String omcRequestAuthHeader = "Authorization";
  private String omcRequestAuthScheme = "Bearer";
  private int omcTokenTtlSeconds = 3600;
  private String omcUsernameField = "userName";
  private String omcPasswordField = "value";

  public boolean isSimulatorEnabled() {
    return simulatorEnabled;
  }

  public void setSimulatorEnabled(boolean value) {
    simulatorEnabled = value;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String value) {
    host = value;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int value) {
    port = value;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String value) {
    username = value;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String value) {
    password = value;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String value) {
    clientId = value;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String value) {
    clientSecret = value;
  }

  public String getDefaultNe() {
    return defaultNe;
  }

  public void setDefaultNe(String value) {
    defaultNe = value;
  }

  public String getCity1Ne() {
    return city1Ne;
  }

  public void setCity1Ne(String value) {
    city1Ne = value;
  }

  public String getCity2Ne() {
    return city2Ne;
  }

  public void setCity2Ne(String value) {
    city2Ne = value;
  }

  public String getSimulatorCity1TargetUrl() {
    return simulatorCity1TargetUrl;
  }

  public void setSimulatorCity1TargetUrl(String value) {
    simulatorCity1TargetUrl = value;
  }

  public String getSimulatorCity2TargetUrl() {
    return simulatorCity2TargetUrl;
  }

  public void setSimulatorCity2TargetUrl(String value) {
    simulatorCity2TargetUrl = value;
  }

  public int getSimulatorConnectTimeoutSeconds() {
    return simulatorConnectTimeoutSeconds;
  }

  public String getSimulatorCity1Username() {
    return simulatorCity1Username;
  }

  public void setSimulatorCity1Username(String value) {
    simulatorCity1Username = value;
  }

  public String getSimulatorCity1Password() {
    return simulatorCity1Password;
  }

  public void setSimulatorCity1Password(String value) {
    simulatorCity1Password = value;
  }

  public String getSimulatorCity2Username() {
    return simulatorCity2Username;
  }

  public void setSimulatorCity2Username(String value) {
    simulatorCity2Username = value;
  }

  public String getSimulatorCity2Password() {
    return simulatorCity2Password;
  }

  public void setSimulatorCity2Password(String value) {
    simulatorCity2Password = value;
  }

  public void setSimulatorConnectTimeoutSeconds(int value) {
    simulatorConnectTimeoutSeconds = value;
  }

  public int getSimulatorReadTimeoutSeconds() {
    return simulatorReadTimeoutSeconds;
  }

  public void setSimulatorReadTimeoutSeconds(int value) {
    simulatorReadTimeoutSeconds = value;
  }

  public int getLoginTimeoutSeconds() {
    return loginTimeoutSeconds;
  }

  public void setLoginTimeoutSeconds(int value) {
    loginTimeoutSeconds = value;
  }

  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public void setTimeoutSeconds(int value) {
    timeoutSeconds = value;
  }

  public boolean isOmcAuthEnabled() {
    return omcAuthEnabled;
  }

  public void setOmcAuthEnabled(boolean value) {
    omcAuthEnabled = value;
  }

  public String getOmcCredentialsPath() {
    return omcCredentialsPath;
  }

  public void setOmcCredentialsPath(String value) {
    omcCredentialsPath = value;
  }

  public String getOmcLoginPath() {
    return omcLoginPath;
  }

  public void setOmcLoginPath(String value) {
    omcLoginPath = value;
  }

  public String getOmcLoginMethod() {
    return omcLoginMethod;
  }

  public void setOmcLoginMethod(String value) {
    omcLoginMethod = value;
  }

  public String getOmcTokenResponseHeader() {
    return omcTokenResponseHeader;
  }

  public void setOmcTokenResponseHeader(String value) {
    omcTokenResponseHeader = value;
  }

  public String getOmcRequestAuthHeader() {
    return omcRequestAuthHeader;
  }

  public void setOmcRequestAuthHeader(String value) {
    omcRequestAuthHeader = value;
  }

  public String getOmcRequestAuthScheme() {
    return omcRequestAuthScheme;
  }

  public void setOmcRequestAuthScheme(String value) {
    omcRequestAuthScheme = value;
  }

  public int getOmcTokenTtlSeconds() {
    return omcTokenTtlSeconds;
  }

  public void setOmcTokenTtlSeconds(int value) {
    omcTokenTtlSeconds = value;
  }

  public String getOmcUsernameField() {
    return omcUsernameField;
  }

  public void setOmcUsernameField(String value) {
    omcUsernameField = value;
  }

  public String getOmcPasswordField() {
    return omcPasswordField;
  }

  public void setOmcPasswordField(String value) {
    omcPasswordField = value;
  }
}
