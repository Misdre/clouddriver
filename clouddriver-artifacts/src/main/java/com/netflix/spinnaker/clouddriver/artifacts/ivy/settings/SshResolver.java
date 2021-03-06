/*
 * Copyright 2018 Pivotal, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.artifacts.ivy.settings;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
final class SshResolver extends Resolver<org.apache.ivy.plugins.resolver.SshResolver> {
  /** The username to provide as a credential. */
  @JacksonXmlProperty(isAttribute = true)
  private String user;

  /** The password to provide as a credential. */
  @JacksonXmlProperty(isAttribute = true)
  private String password;

  /** The host to connect to. Defaults to host given on the patterns, failing if none is set. */
  @JacksonXmlProperty(isAttribute = true)
  @Nullable
  private String host;

  /** The port to connect to. */
  @JacksonXmlProperty(isAttribute = true)
  @Nullable
  private Integer port;

  /** Defines a pattern for Ivy files, using the pattern attribute. */
  @JacksonXmlElementWrapper(useWrapping = false)
  @Nullable
  private List<Pattern> ivy;

  /** Defines a pattern for artifacts, using the pattern attribute */
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<Pattern> artifact;

  @Override
  public org.apache.ivy.plugins.resolver.SshResolver toIvyModel() {
    org.apache.ivy.plugins.resolver.SshResolver sshResolver =
        new org.apache.ivy.plugins.resolver.SshResolver();
    sshResolver.setHost(host);
    if (port != null) {
      sshResolver.setPort(port);
    }
    sshResolver.setUser(user);
    sshResolver.setUserPassword(password);
    if (ivy != null) {
      ivy.forEach(pattern -> sshResolver.addIvyPattern(pattern.getPattern()));
    }
    artifact.forEach(pattern -> sshResolver.addArtifactPattern(pattern.getPattern()));
    return super.toIvyModel(sshResolver);
  }
}
