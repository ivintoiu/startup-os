/*
 * Copyright 2018 The StartupOS Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.startupos.tools.deps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.util.JsonFormat;
import com.google.startupos.tools.deps.Protos.Dependency;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class PackageLockWhitelistTest {
  private Map<String, List<String>> whitelist;
  private List<String> packageLockLines;
  private List<Dependency> parsedDependencies;

  private Pattern MAVEN_ARTIFACT_LINE = Pattern.compile("[\\s\\{]+\"artifact\":\\s+\"([^\"]+).*$");
  private String MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

  @Before
  public void setUp() throws Exception {
    whitelist = new Yaml().load(new FileInputStream(new File("whitelist.yaml")));
    packageLockLines = Files.readAllLines(Paths.get("third_party/maven/package-lock.bzl"));
    parsedDependencies = new ArrayList();
  }

  @Test
  public void parseDependencies() throws Exception {
    boolean depListStarted = false;
    for (String line : packageLockLines) {
      if (line.equals("def list_dependencies():")) {
        depListStarted = true;
        continue;
      }

      if (line.equals("def maven_dependencies(callback = declare_maven)")) {
        break;
      }

      if (depListStarted && line.contains("://")) {
        Dependency.Builder message = Dependency.newBuilder();
        JsonFormat.parser().merge(line, message);
        parsedDependencies.add(message.build());
      }
    }
    assertFalse("Parsed dependencies list should not be empty", parsedDependencies.isEmpty());
  }

  @Test
  public void validateDependencies() throws Exception {
    List<String> validPackageGroups = whitelist.get("maven_dependencies");
    for (Dependency dep : parsedDependencies) {

      assertEquals(
          MAVEN_CENTRAL_URL, dep.getRepository(), "Artifact %s is not in the Maven Central");

      boolean isValidPackage = false;
      for (String validPackageGroup : validPackageGroups) {
        if (dep.getArtifact().startsWith(validPackageGroup)) {
          isValidPackage = true;
          break;
        }
      }

      assertTrue(
          String.format("Artifact %s is not in the whitelist", dep.getArtifact()), isValidPackage);
    }
  }
}

