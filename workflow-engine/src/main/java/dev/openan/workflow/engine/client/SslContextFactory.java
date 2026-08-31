/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSL context factory for outbound HTTPS calls.
 *
 * <p>All configuration errors fail closed. Disabling certificate-chain verification creates a
 * per-client trust-all context, but deliberately does not change the JVM-wide JDK HTTP client
 * hostname-verification property.
 */
public final class SslContextFactory {

  private static final Logger log = LoggerFactory.getLogger(SslContextFactory.class);

  private SslContextFactory() {}

  /**
   * Build an SSLContext for outbound HTTPS.
   *
   * @param verifyServer whether to verify remote server certificates
   * @param caCertsPath optional path to a PEM CA trust store file
   * @return the configured SSLContext
   */
  public static Optional<SSLContext> create(boolean verifyServer, String caCertsPath) {
    return create(verifyServer, caCertsPath, null, null, null, null);
  }

  /**
   * Build an SSLContext for outbound HTTPS with full mTLS support.
   *
   * @param verifyServer whether to verify remote server certificates
   * @param caCertsPath optional path to a PEM CA trust store file
   * @param certPath optional path to client certificate (for mTLS)
   * @param keyPath optional path to client private key (for mTLS)
   * @param keyPassword optional password for the private key
   * @param crlPath optional path to a CRL file
   * @return the configured SSLContext
   */
  public static Optional<SSLContext> create(
      boolean verifyServer,
      String caCertsPath,
      String certPath,
      String keyPath,
      String keyPassword,
      String crlPath) {
    try {
      javax.net.ssl.KeyManager[] keyManagers = loadKeyManagers(certPath, keyPath, keyPassword);
      X509TrustManager trustManager =
          verifyServer ? createTrustManager(caCertsPath) : trustAllManager();
      trustManager = applyCrl(trustManager, crlPath);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(keyManagers, new TrustManager[] {trustManager}, null);
      log.info(
          "Client SSL: context initialized (verifyServer={}, ca_certs={})",
          verifyServer,
          caCertsPath);
      return Optional.of(ctx);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build outbound TLS context", e);
    }
  }

  private static X509TrustManager createTrustManager(String caCertsPath) throws Exception {
    if (caCertsPath != null && !caCertsPath.isEmpty()) {
      try (FileInputStream fis = new FileInputStream(caCertsPath)) {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Certificate cert : cf.generateCertificates(fis).toArray(new Certificate[0])) {
          ks.setCertificateEntry("ca-" + i, cert);
          i++;
        }
        if (i == 0) throw new IllegalArgumentException("CA file contains no certificates");
        log.info("Client SSL: loaded CA trust store from {}", caCertsPath);
        javax.net.ssl.TrustManagerFactory tmf =
            javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return (X509TrustManager) tmf.getTrustManagers()[0];
      }
    }
    javax.net.ssl.TrustManagerFactory tmf =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    tmf.init((KeyStore) null);
    return (X509TrustManager) tmf.getTrustManagers()[0];
  }

  private static javax.net.ssl.KeyManager[] loadKeyManagers(
      String certPath, String keyPath, String keyPassword) throws Exception {
    boolean hasCert = certPath != null && !certPath.isBlank();
    boolean hasKey = keyPath != null && !keyPath.isBlank();
    if (!hasCert && !hasKey) return null;
    if (!hasCert || !hasKey) {
      throw new IllegalArgumentException(
          "Both client certificate and private key are required for mTLS");
    }
    Path certificateFile = Path.of(certPath);
    Path privateKeyFile = Path.of(keyPath);
    if (!Files.isRegularFile(certificateFile) || !Files.isRegularFile(privateKeyFile)) {
      throw new IllegalArgumentException("mTLS certificate or private-key file not found");
    }

    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    List<Certificate> chain;
    try (FileInputStream certFis = new FileInputStream(certificateFile.toFile())) {
      chain = new ArrayList<>(cf.generateCertificates(certFis));
    }
    if (chain.isEmpty()) throw new IllegalArgumentException("mTLS certificate chain is empty");

    PrivateKey privateKey = parsePkcs8PrivateKey(privateKeyFile, keyPassword);
    char[] password = keyPassword != null ? keyPassword.toCharArray() : new char[0];
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    keyStore.setKeyEntry("client-key", privateKey, password, chain.toArray(new Certificate[0]));
    javax.net.ssl.KeyManagerFactory kmf =
        javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, password);
    log.info("Client SSL: loaded client identity certificate chain for mTLS");
    return kmf.getKeyManagers();
  }

  private static PrivateKey parsePkcs8PrivateKey(Path keyPath, String keyPassword)
      throws Exception {
    byte[] raw = Files.readAllBytes(keyPath);
    String text = new String(raw, StandardCharsets.US_ASCII);
    byte[] der = raw;
    if (text.contains("-----BEGIN")) {
      boolean encrypted = text.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
      if (!encrypted && !text.contains("-----BEGIN PRIVATE KEY-----")) {
        throw new IllegalArgumentException("Only PKCS#8 PEM private keys are supported");
      }
      String base64 =
          text.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("-----BEGIN ENCRYPTED PRIVATE KEY-----", "")
              .replace("-----END ENCRYPTED PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      der = Base64.getDecoder().decode(base64);
      if (encrypted) der = decryptPkcs8PrivateKey(der, keyPassword);
    }
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    Exception last = null;
    for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (Exception e) {
        last = e;
      }
    }
    throw new IllegalArgumentException("Unsupported PKCS#8 private-key algorithm", last);
  }

  private static byte[] decryptPkcs8PrivateKey(byte[] encrypted, String keyPassword)
      throws Exception {
    if (keyPassword == null || keyPassword.isEmpty()) {
      throw new IllegalArgumentException("Encrypted private key requires a password");
    }
    EncryptedPrivateKeyInfo encryptedInfo = new EncryptedPrivateKeyInfo(encrypted);
    SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedInfo.getAlgName());
    SecretKey key = keyFactory.generateSecret(new PBEKeySpec(keyPassword.toCharArray()));
    Cipher cipher = Cipher.getInstance(encryptedInfo.getAlgName());
    cipher.init(Cipher.DECRYPT_MODE, key, encryptedInfo.getAlgParameters());
    return encryptedInfo.getKeySpec(cipher).getEncoded();
  }

  private static X509TrustManager applyCrl(X509TrustManager delegate, String crlPath)
      throws Exception {
    if (crlPath == null || crlPath.isBlank()) return delegate;
    Path file = Path.of(crlPath);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("CRL file not found: " + crlPath);
    }
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    Collection<? extends java.security.cert.CRL> parsed;
    try (FileInputStream input = new FileInputStream(file.toFile())) {
      parsed = cf.generateCRLs(input);
    }
    List<X509CRL> crls =
        parsed.stream().filter(X509CRL.class::isInstance).map(X509CRL.class::cast).toList();
    if (crls.isEmpty()) throw new IllegalArgumentException("CRL file contains no X.509 CRL");
    log.info("Client SSL: loaded {} CRL(s) from {}", crls.size(), crlPath);
    return new RevocationCheckingTrustManager(delegate, crls);
  }

  public static SSLContext createTrustAll() {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {trustAllManager()}, null);
      log.warn(
          "Outbound TLS certificate-chain verification disabled; hostname verification remains enabled");
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create trust-all SSL context", e);
    }
  }

  private static X509TrustManager trustAllManager() {
    return new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      public void checkServerTrusted(X509Certificate[] chain, String authType) {}

      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  private record RevocationCheckingTrustManager(X509TrustManager delegate, List<X509CRL> crls)
      implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      delegate.checkServerTrusted(chain, authType);
      for (X509Certificate certificate : chain) {
        for (X509CRL crl : crls) {
          if (crl.isRevoked(certificate)) {
            throw new java.security.cert.CertificateException(
                "Server certificate has been revoked: " + certificate.getSerialNumber());
          }
        }
      }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }
}
