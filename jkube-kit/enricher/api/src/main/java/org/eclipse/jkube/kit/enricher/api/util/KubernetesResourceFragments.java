/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.enricher.api.util;

import com.fasterxml.jackson.core.type.TypeReference;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jkube.kit.common.util.KindFilenameMapperUtil;
import org.eclipse.jkube.kit.common.util.Serialization;
import org.eclipse.jkube.kit.config.resource.MappingConfig;
import org.eclipse.jkube.kit.config.resource.ResourceVersioning;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;

import static org.eclipse.jkube.kit.common.util.KubernetesHelper.FILENAME_PATTERN;

public class KubernetesResourceFragments {

  private static final Map<String,String> FILENAME_TO_KIND_MAPPER = new HashMap<>();
  private static final Map<String, String> KIND_TO_FILENAME_MAPPER = new HashMap<>();
  private static final BiConsumer<String, List<String>> KIND_FILENAME_MAPPER_UPDATER = (kind, fileNameTypes) -> {
    for (String filenameType : fileNameTypes) {
      FILENAME_TO_KIND_MAPPER.put(filenameType, kind);
    }
    // In previous version, last one wins, so we do the same.
    KIND_TO_FILENAME_MAPPER.put(kind, fileNameTypes.get(fileNameTypes.size() - 1));
  };
  static final Set<String> EXCLUDED_RESOURCE_FILENAME_SUFFIXES = new HashSet<>();
  static {
    EXCLUDED_RESOURCE_FILENAME_SUFFIXES.add(".helm.yaml");
    EXCLUDED_RESOURCE_FILENAME_SUFFIXES.add(".helm.yml");
    KindFilenameMapperUtil.loadMappings().forEach(KIND_FILENAME_MAPPER_UPDATER);
  }

  private KubernetesResourceFragments() {
  }

  /**
   * Upsert the static list of kind-filename mappings.
   * @param mappings the mappings to add.
   */
  public static void updateKindFilenameMappings(List<MappingConfig> mappings) {
    if (mappings != null) {
      final Map<String, List<String>> mappingKindFilename = new HashMap<>();
      for (MappingConfig mappingConfig : mappings) {
        if (mappingConfig.isValid()) {
          mappingKindFilename.put(mappingConfig.getKind(), Arrays.asList(mappingConfig.getFilenamesAsArray()));
        } else {
          throw new IllegalArgumentException(String.format("Invalid mapping for Kind %s and Filename Types %s",
            mappingConfig.getKind(), mappingConfig.getFilenameTypes()));
        }
      }
      mappingKindFilename.forEach(KIND_FILENAME_MAPPER_UPDATER);
    }
  }

  public static String getNameWithSuffix(String name, String kind) {
    String suffix =  KIND_TO_FILENAME_MAPPER.get(kind);
    return String.format("%s-%s", name, suffix != null ? suffix : "cr");
  }


  /**
   * Read all Kubernetes resource fragments from a directory and create a {@link KubernetesListBuilder} which
   * can be adapted later.
   *
   * @param apiVersions the api versions to use
   * @param defaultName the default name to use when none is given
   * @param resourceFiles files to add.
   * @return the list builder
   * @throws IOException in case file is not found
   */
  public static KubernetesListBuilder readResourceFragmentsFrom(
    ResourceVersioning apiVersions, String defaultName, File... resourceFiles)
    throws IOException {

    final KubernetesListBuilder builder = new KubernetesListBuilder();
    if (resourceFiles != null) {
      for (File file : resourceFiles) {
        if (EXCLUDED_RESOURCE_FILENAME_SUFFIXES.stream()
          .noneMatch(s -> file.getName().toLowerCase(Locale.ROOT).endsWith(s))) {
          builder.addToItems(getResource(apiVersions, file, defaultName));
        }
      }
    }
    return builder;
  }

  /**
   * Read a Kubernetes resource fragment and add meta information extracted from the filename
   * to the resource descriptor. I.e. the following elements are added if not provided in the fragment:
   *
   * <ul>
   *     <li>name - Name of the resource added to metadata</li>
   *     <li>kind - Resource's kind</li>
   *     <li>apiVersion - API version (given as parameter to this method)</li>
   * </ul>
   *
   * @param apiVersions the API versions to add if not given.
   * @param file file to read.
   * @param appName resource name specifying resources belonging to this application
   * @return HasMetadata object for resource
   * @throws IOException in case file loading is failed
   */
  private static HasMetadata getResource(
    ResourceVersioning apiVersions, File file, String appName) throws IOException {
    final Map<String, Object> fragment = readAndEnrichFragment(apiVersions, file, appName);
    try {
      return Serialization.convertValue(fragment, HasMetadata.class);
    } catch (ClassCastException exp) {
      throw new IllegalArgumentException(String.format("Resource fragment %s has an invalid syntax (%s)", file.getPath(), exp.getMessage()));
    }
  }

  // Read fragment and add default values
  private static Map<String, Object> readAndEnrichFragment(
    ResourceVersioning apiVersions, File file, String appName) throws IOException {

    Matcher matcher = FILENAME_PATTERN.matcher(file.getName());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
        String.format("Resource file name '%s' does not match pattern <name>-<type>.(yaml|yml|json)", file.getName()));
    }
    String name = matcher.group("name");
    String type = matcher.group("type");

    final Map<String,Object> fragment = Serialization.unmarshal(file, new TypeReference<Map<String, Object>>() {});

    final String kind;
    if (type != null) {
      kind = getAndValidateKindFromType(file, type);
    } else {
      // Try name as type
      kind = FILENAME_TO_KIND_MAPPER.get(name.toLowerCase());
      if (kind != null) {
        // Name is in fact the type, so lets erase the name.
        name = null;
      }
    }

    addKind(fragment, kind, file.getName());

    String apiVersion = apiVersions.getForKind((String)fragment.get("kind"));

    fragment.putIfAbsent("apiVersion", apiVersion);

    Map<String, Object> metaMap = getMetadata(fragment);
    // No name means: generated app name should be taken as resource name
    String value = StringUtils.isNotBlank(name) ? name : appName;
    metaMap.putIfAbsent("name", value);

    return fragment;
  }

  private static String getAndValidateKindFromType(File file, String type) {
    String kind;
    kind = FILENAME_TO_KIND_MAPPER.get(type.toLowerCase());
    if (kind == null) {
      throw new IllegalArgumentException(
        String.format("Unknown type '%s' for file %s. Must be one of : %s",
          type, file.getName(), StringUtils.join(FILENAME_TO_KIND_MAPPER.keySet().iterator(), ", ")));
    }
    return kind;
  }

  private static void addKind(Map<String, Object> fragment, String kind, String fileName) {
    if (kind == null && !fragment.containsKey("kind")) {
      throw new IllegalArgumentException(
        "No type given as part of the file name (e.g. 'app-rc.yml') " +
          "and no 'Kind' defined in resource descriptor " + fileName);
    }
    fragment.putIfAbsent("kind", kind);
  }

  private static Map<String, Object> getMetadata(Map<String, Object> fragment) {
    Object mo = fragment.get("metadata");
    Map<String, Object> meta;
    if (mo == null) {
      meta = new HashMap<>();
      fragment.put("metadata", meta);
      return meta;
    } else if (mo instanceof Map) {
      return (Map<String, Object>) mo;
    } else {
      throw new IllegalArgumentException("Metadata is expected to be a Map, not a " + mo.getClass());
    }
  }
}