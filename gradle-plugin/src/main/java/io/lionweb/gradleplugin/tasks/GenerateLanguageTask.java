package io.lionweb.gradleplugin.tasks;

import io.lionweb.LionWebVersion;
import io.lionweb.gradleplugin.generators.LanguageJavaCodeGenerator;
import io.lionweb.language.Language;
import io.lionweb.serialization.JsonSerialization;
import io.lionweb.serialization.SerializationProvider;
import io.lionweb.serialization.data.SerializationChunk;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.*;

/**
 * The GenerateLanguageTask is an abstract Gradle task used for processing LionWeb Language files,
 * generating Java code representations for the defined languages. The task supports multiple
 * serialization formats and handles language dependencies using a topological sort mechanism.
 *
 * <p>This task works by reading serialized language definitions from a specified directory,
 * processing the provided files in the supported formats (currently JSON), and generating Java
 * classes in the specified output directory.
 *
 * <p>Users can configure the input directory for the language definitions, the target directory for
 * generated code, and the package name to be used for the generated classes. The task validates the
 * input directories, processes the files, and applies the appropriate serialization format for
 * language chunks.
 *
 * <p>Tasks performed include: - Loading serialized language chunks from files in the input
 * directory, filtered by `LionWebVersion`. - Deserializing language specifications and resolving
 * dependencies through a topological sort. - Generating Java code using the specified package name
 * and output directory.
 *
 * <p>If unsupported file formats (such as Protobuf) or issues with serialization are encountered
 * during execution, appropriate exception handling and logging are performed to assist in
 * debugging.
 *
 * <p>Gradle properties: - `languagesDirectory` - Optional directory containing serialized language
 * files. - `generationDirectory` - Optional output directory for generated Java files. -
 * `packageName` - Required property specifying the package for generated Java classes.
 */
@CacheableTask
public abstract class GenerateLanguageTask extends AbstractGenerationTask {

  @TaskAction
  public void run() {
    getLogger().info("GenerateLanguageTask - Starting");
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
          .error("GenerateLanguageTask - Languages directory does not exist or is not a directory");
      throw new GradleException("Languages directory does not exist or is not a directory");
    }
    File generationDirectory = getGenerationDirectory().getAsFile().get();
    LanguageJavaCodeGenerator languageJavaCodeGenerator =
        new LanguageJavaCodeGenerator(generationDirectory, getMappings().get());
    try {
      List<SerializationChunk> dependenciesChunks = loadDependenciesChunks();
      getLogger().info("GenerateLanguageTask - Dependencies chunks: " + dependenciesChunks.size());
      List<SerializationChunk> projectChunks =
          languagesDirectory == null ? List.of() : loadProjectChunks(languagesDirectory);
      getLogger().info("GenerateLanguageTask - Project chunks: " + projectChunks.size());
      Arrays.stream(LionWebVersion.values())
          .forEach(
              lionWebVersion ->
                  generateLanguagesForChunks(
                      lionWebVersion,
                      projectChunks,
                      dependenciesChunks,
                      languageJavaCodeGenerator));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    getLogger().lifecycle("Java code for LionWeb Languages generated");
  }

  private void generateLanguagesForChunks(
      LionWebVersion lionWebVersion,
      List<SerializationChunk> projectChunks,
      List<SerializationChunk> dependenciesChunks,
      LanguageJavaCodeGenerator languageJavaCodeGenerator) {
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
    // Useful for annotations
    serialization.enableDynamicNodes();
    Set<Language> languagesLoadedFromProjectChunks = new HashSet<>();
    List<Language> languages =
        sortedChunks.stream()
            .map(
                chunk -> {
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
                })
            .collect(Collectors.toList());
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
    } else {
      getLogger()
          .info("LionWeb Version " + lionWebVersion + " - Languages to generate not specified");
      languagesToGenerate.addAll(languagesLoadedFromProjectChunks);
    }
    getLogger()
        .info(
            "LionWeb Version "
                + lionWebVersion
                + " - Languages considered for generation: "
                + languagesToGenerate.size());
    if (languagesToGenerate.isEmpty()) {
      getLogger()
          .info("LionWeb Version " + lionWebVersion + " - No LionWeb Languages to generate.");
    } else {
      getLogger()
          .info(
              "LionWeb Version "
                  + lionWebVersion
                  + " - Generation of LionWeb Languages: "
                  + languagesToGenerate.stream()
                      .map(Language::getName)
                      .collect(Collectors.joining(", ")));
    }
    languageJavaCodeGenerator.generate(
        languagesToGenerate,
        getDefaultPackageName().getOrNull(),
        getLanguagesSpecificPackages().getOrElse(Map.of()),
        getLanguagesClassNames().getOrElse(Map.of()));
  }
}
