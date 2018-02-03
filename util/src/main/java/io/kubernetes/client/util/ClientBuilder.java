/*
Copyright 2017 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package io.kubernetes.client.util;

import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.KubeconfigAuthentication;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.log4j.Logger;

import io.kubernetes.client.ApiClient;

import static io.kubernetes.client.util.Config.ENV_KUBECONFIG;
import static io.kubernetes.client.util.Config.ENV_SERVICE_HOST;
import static io.kubernetes.client.util.Config.ENV_SERVICE_PORT;
import static io.kubernetes.client.util.Config.SERVICEACCOUNT_CA_PATH;
import static io.kubernetes.client.util.Config.SERVICEACCOUNT_TOKEN_PATH;
import static io.kubernetes.client.util.KubeConfig.*;

/**
 * A Builder which allows the construction of {@link ApiClient}s in a fluent fashion.
 */
public class ClientBuilder {

  private static final Logger log = Logger.getLogger(ClientBuilder.class);

  private String basePath = Config.DEFAULT_FALLBACK_HOST;
  private byte[] caCertBytes = null;
  private boolean verifyingSsl = true;
  private Authentication authentication;

  /**
   * Creates an {@link ApiClient} by calling {@link #standard()} and {@link #build()}.
   *
   * @return An <tt>ApiClient</tt> configured using the precedence specified for {@link #standard()}.
   * @throws IOException
   *  if the configuration file or a file specified in a configuration file cannot be read.
   */
  public static ApiClient defaultClient() throws IOException {
    return ClientBuilder.standard().build();
  }

  /**
   * Creates a builder which is pre-configured in the following way
   *
   * <ul>
   *   <li>If $KUBECONFIG is defined, use that config file.</li>
   *   <li>If $HOME/.kube/config can be found, use that.</li>
   *   <li>If the in-cluster service account can be found, assume in cluster config.</li>
   *   <li>Default to localhost:8080 as a last resort.</li>
   * </ul>
   *
   * @return <tt>ClientBuilder</tt> pre-configured using the above precedence
   * @throws IOException
   *  if the configuration file or a file specified in a configuration file cannot be read.
   */
  public static ClientBuilder standard() throws IOException {
    final FileReader kubeConfigReader = findConfigFromEnv();
    if(kubeConfigReader != null) {
      return kubeconfig(loadKubeConfig(kubeConfigReader));
    }
    final FileReader configReader = findConfigInHomeDir();
    if(configReader != null) {
      return kubeconfig(loadKubeConfig(configReader));
    }
    final File clusterCa = new File(SERVICEACCOUNT_CA_PATH);
    if (clusterCa.exists()) {
      return cluster();
    }
    return new ClientBuilder();
  }

  private static FileReader findConfigFromEnv() {
    try {
      String kubeConfig = System.getenv(ENV_KUBECONFIG);
      if(kubeConfig == null) {
        return null;
      }
      return new FileReader(kubeConfig);
    } catch (FileNotFoundException e) {
      log.info("Could not find file specified in $KUBECONFIG");
      return null;
    }
  }

  private static FileReader findConfigInHomeDir() {
    try {
      File config = new File(new File(System.getenv(ENV_HOME), KUBEDIR), KUBECONFIG);
      return new FileReader(config);
    } catch (FileNotFoundException e) {
      log.info("Could not find ~/.kube/config");
      return null;
    }
  }

  /**
   * Creates a builder which is pre-configured from the cluster configuration.
   *
   * @return <tt>ClientBuilder</tt> configured from the cluster configuration.
   * @throws IOException
   *  if the Service Account Token Path or CA Path is not readable.
   */
  public static ClientBuilder cluster() throws IOException {
    final ClientBuilder builder = new ClientBuilder();

    final String host = System.getenv(ENV_SERVICE_HOST);
    final String port = System.getenv(ENV_SERVICE_PORT);
    builder.setBasePath("https://" + host + ":" + port);

    final String token = new String(Files.readAllBytes(Paths.get(SERVICEACCOUNT_TOKEN_PATH)),
        Charset.defaultCharset());
    builder.setCertificateAuthority(Files.readAllBytes(Paths.get(SERVICEACCOUNT_CA_PATH)));
    builder.setAuthentication(new AccessTokenAuthentication(token));

    return builder;
  }

  /**
   * Creates a builder which is pre-configured from a {@link KubeConfig}.
   *
   * To load a <tt>KubeConfig</tt>, see {@link KubeConfig#loadKubeConfig(Reader)}.
   *
   * @param config
   *  The {@link KubeConfig} to configure the builder from.
   * @return <tt>ClientBuilder</tt> configured from the provided <tt>KubeConfig</tt>
   * @throws IOException
   *  if the files specified in the provided <tt>KubeConfig</tt> are not readable
   */
  public static ClientBuilder kubeconfig(KubeConfig config) throws IOException {
    final ClientBuilder builder = new ClientBuilder();

    String server = config.getServer();
    if (!server.startsWith("http://") && !server.startsWith("https://")) {
      if (server.contains(":443")) {
        server = "https://" + server;
      } else {
        server = "http://" + server;
      }
    }

    if(config.verifySSL()) {
      final byte[] caBytes = KubeConfig.getDataOrFile(config.getCertificateAuthorityData(),
          config.getCertificateAuthorityFile());
      builder.setCertificateAuthority(caBytes);
    } else {
      builder.setVerifyingSsl(false);
    }

    builder.setBasePath(server);
    builder.setAuthentication(new KubeconfigAuthentication(config));
    return builder;
  }

  public String getBasePath() {
    return basePath;
  }

  public ClientBuilder setBasePath(String basePath) {
    this.basePath = basePath;
    return this;
  }

  public Authentication getAuthentication() {
    return authentication;
  }

  public ClientBuilder setAuthentication(final Authentication authentication) {
    this.authentication = authentication;
    return this;
  }

  public ClientBuilder setCertificateAuthority(final byte[] caCertBytes) {
    this.caCertBytes = caCertBytes;
    this.verifyingSsl = true;
    return this;
  }

  public boolean isVerifyingSsl() {
    return verifyingSsl;
  }

  public ClientBuilder setVerifyingSsl(boolean verifyingSsl) {
    this.verifyingSsl = verifyingSsl;
    return this;
  }

  public ApiClient build() {
    final ApiClient client = new ApiClient();

    if (basePath != null) {
      if (basePath.endsWith("/")) {
        basePath = basePath.substring(0, basePath.length() - 1);
      }
      client.setBasePath(basePath);
    }

    client.setVerifyingSsl(verifyingSsl);

    if (caCertBytes != null) {
      client.setSslCaCert(new ByteArrayInputStream(caCertBytes));
    }

    if (authentication != null) {
      authentication.provide(client);
    }

    return client;
  }
}