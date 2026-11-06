package io.lionweb.gradleplugin.tasks;

import io.lionweb.LionWebVersion;
import io.lionweb.gradleplugin.generators.NodeClassesJavaCodeGenerator;
import io.lionweb.language.Language;
import io.lionweb.serialization.JsonSerialization;
import io.lionweb.serialization.SerializationProvider;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.*;

@CacheableTask
/** Gradle task that generates Java node classes from serialized LionWeb language files. */
public abstract class GenerateNodeClassesTask extends AbstractGenerationTask {

  @TaskAction
  public void run() {
    getLogger().info("GenerateNodeClassesTask - Starting");
    if (!getGenerationDirectory().isPresent()) {
      throw new GradleException("Generation directory not specified");
    }
    File languagesDirectory = null;
    if (getLanguagesDirectory().getAsFile().isPresent()) {
      languagesDirectory = getLanguagesDirectory().getAsFile().get();
    }
    if (languagesDirectory != null
        && (!languagesDirectory.exists() || !languagesDirectory.isDirectory())) {
      getLogger()
          .error(
              "GenerateNodeClassesTask - Languages directory does not exist or is not a directory");
      throw new GradleException("Languages directory does not exist or is not a directory");
    }
    File generationDirectory = getGenerationDirectory().getAsFile().get();
    NodeClassesJavaCodeGenerator nodeClassesJavaCodeGenerator =
        new NodeClassesJavaCodeGenerator(generationDirectory, getMappings().get());
    nodeClassesJavaCodeGenerator.setLogger(getLogger());
    try {
      List<SerializationChunk> dependenciesChunks = loadDependenciesChunks();
      List<SerializationChunk> projectChunks =
          languagesDirectory == null ? List.of() : loadProjectChunks(languagesDirectory);
      Arrays.stream(LionWebVersion.values())
          .forEach(
              lionWebVersion -> {
                generateNodeClassesForChunks(
                    lionWebVersion,
                    projectChunks,
                    dependenciesChunks,
                    nodeClassesJavaCodeGenerator);
              });
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    getLogger().lifecycle("Java code for LionWeb Node Classes generated");
  }

  private void generateNodeClassesForChunks(
      LionWebVersion lionWebVersion,
      List<SerializationChunk> projectChunks,
      List<SerializationChunk> dependenciesChunks,
      NodeClassesJavaCodeGenerator nodeClassesJavaCodeGenerator) {
    getLogger().info("Generating Node Classes for LionWeb Version " + lionWebVersion);
    getLogger()
        .info(
            "Project Chunks: "
                + projectChunks.size()
                + ", Dependencies Chunks: "
                + dependenciesChunks.size());
    List<SerializationChunk> allChunks =
        new ArrayList<>(projectChunks.size() + dependenciesChunks.size());
    allChunks.addAll(dependenciesChunks);
    allChunks.addAll(projectChunks);
    List<SerializationChunk> sortedChunks =
        sortLanguageChunks(
            lionWebVersion,
            allChunks.stream()
                .filter(
                    c ->
                        c.getSerializationFormatVersion().equals(lionWebVersion.getVersionString()))
                .collect(Collectors.toList()));
    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(lionWebVersion);
    serialization.enableDynamicNodes(); // for dynamic annotations
    Set<Language> languagesLoadedFromProjectChunks = new HashSet<>();
    Set<Language> languages =
        sortedChunks.stream()
            .map(
                chunk -> {
                  try {
                    Language language =
                        (Language)
                            serialization.deserializeSerializationChunk(chunk).stream()
                                .filter(n -> n.getParent() == null)
                                .findFirst()
                                .get();
                    getLogger().info("LionWeb Language loaded: " + language.getName());
                    serialization.registerLanguage(language);
                    if (projectChunks.contains(chunk)) {
                      languagesLoadedFromProjectChunks.add(language);
                    }
                    return language;
                  } catch (Exception e) {
                    SerializedClassifierInstance root =
                        chunk.getClassifierInstances().stream()
                            .filter(n -> n.getParentNodeID() == null)
                            .findFirst()
                            .orElse(null);
                    throw new RuntimeException("Issue deserializing language " + root, e);
                  }
                })
            .collect(Collectors.toSet());
    getLogger()
        .info("LionWeb Version " + lionWebVersion + " - Languages loaded: " + languages.size());
    List<Language> languagesToGenerate = new ArrayList<>();
    if (getLanguagesToGenerate().isPresent() && !getLanguagesToGenerate().get().isEmpty()) {
      Set<String> specifiedLanguagesToGenerate = getLanguagesToGenerate().get();
      getLogger()
          .info(
              "LionWeb Version "
                  + lionWebVersion
                  + " - Languages to generate specified as "
                  + specifiedLanguagesToGenerate.size());
      languages.stream()
          .filter(
              l ->
                  specifiedLanguagesToGenerate.contains(l.getName())
                      || specifiedLanguagesToGenerate.contains(l.getID())
                      || specifiedLanguagesToGenerate.contains(l.getKey()))
          .forEach(l -> languagesToGenerate.add(l));
      if (languagesToGenerate.isEmpty()) {
        getLogger()
            .warn(
                "LionWeb Version "
                    + lionWebVersion
                    + " - No Languages to generate matched the specified languages");
      } else {
        getLogger()
            .info(
                "LionWeb Version "
                    + lionWebVersion
                    + " - Identified Languages to generate: "
                    + languagesToGenerate.stream()
                        .map(Language::getName)
                        .collect(Collectors.joining(", ")));
      }
    } else {
      getLogger()
          .info("LionWeb Version " + lionWebVersion + " - Languages to generate not specified");
      languagesToGenerate.addAll(languagesLoadedFromProjectChunks);
    }

    nodeClassesJavaCodeGenerator.generate(
        languagesToGenerate,
        getDefaultPackageName().getOrNull(),
        getLanguagesSpecificPackages().getOrElse(Map.of()),
        getPrimitiveTypes().getOrElse(Map.of()),
        getLanguagesClassNames().getOrElse(Map.of()));
  }
}
