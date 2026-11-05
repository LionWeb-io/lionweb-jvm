package io.lionweb.gradleplugin.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.lionweb.LionWebVersion;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.lioncore.LionCore;
import io.lionweb.serialization.LowLevelJsonSerialization;
import io.lionweb.serialization.TopologicalLanguageSorter;
import io.lionweb.serialization.data.SerializationChunk;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.Optional;

@CacheableTask
/**
 * Base Gradle task providing shared language-loading logic for all LionWeb code-generation tasks.
 */
public abstract class AbstractGenerationTask extends DefaultTask {
  @InputDirectory
  @Optional
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getLanguagesDirectory();

  @OutputDirectory
  public abstract DirectoryProperty getGenerationDirectory();

  @Input
  @Optional
  public abstract Property<String> getDefaultPackageName();

  @Input
  public abstract MapProperty<String, String> getPrimitiveTypes();

  @Input
  public abstract MapProperty<String, String> getLanguagesSpecificPackages();

  @Input
  public abstract MapProperty<String, String> getLanguagesClassNames();

  @Input
  @Optional
  public abstract SetProperty<String> getLanguagesToGenerate();

  @Input
  public abstract MapProperty<String, String> getMappings();

  /**
   * Sorts the given language chunks so that each language follows the languages it depends on.
   *
   * <p>Dependencies on LionCore M3 and LionCore builtins are accepted even if they are not among
   * the given chunks, as those languages are always available.
   */
  static List<SerializationChunk> sortLanguageChunks(
      LionWebVersion lionWebVersion, Collection<SerializationChunk> chunks) {
    Set<String> knownDependencies =
        Set.of(
            LionCore.getInstance(lionWebVersion).getID(),
            LionCoreBuiltins.getInstance(lionWebVersion).getID());
    return new TopologicalLanguageSorter(lionWebVersion)
        .topologicalSort(chunks, knownDependencies::contains);
  }

  protected List<SerializationChunk> loadDependenciesChunks() throws IOException {
    List<SerializationChunk> dependenciesChunks = new LinkedList<>();
    Set<File> classpath = Set.of();
    try {
      classpath = getProject().getConfigurations().getByName("compileClasspath").resolve();
    } catch (UnknownConfigurationException e) {
      getLogger()
          .warn("No compileClasspath configuration found, skipping LionWeb dependency scanning");
    }
    getLogger()
        .debug(
            "Scanning classpath file for LionWeb dependencies: "
                + classpath.stream().map(File::getName).collect(Collectors.joining(", ")));
    classpath.stream()
        .filter(f -> f.getName().endsWith(".jar"))
        .forEach(
            jar -> {
              getLogger()
                  .debug("Scanning jar file for LionWeb dependencies: " + jar.getAbsolutePath());
              try (JarFile jarFile = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                  JarEntry entry = entries.nextElement();
                  if (entry.getName().startsWith("META-INF/lionweb/")
                      && entry.getName().endsWith(".json")) {
                    getLogger().debug("Identified entry " + entry.getName() + " in jar file");
                    try {
                      InputStream inputStream = jarFile.getInputStream(entry);
                      JsonElement je =
                          JsonParser.parseReader(new java.io.InputStreamReader(inputStream));
                      dependenciesChunks.add(
                          new LowLevelJsonSerialization().deserializeSerializationBlock(je));
                    } catch (Exception e) {
                      getLogger()
                          .error(
                              "Error reading jar file entry: "
                                  + jar.getAbsolutePath()
                                  + "#"
                                  + entry.getName(),
                              e);
                    }
                  }
                }
              } catch (IOException e) {
                getLogger().error("Error reading jar file: " + jar.getAbsolutePath(), e);
              }
            });
    return dependenciesChunks;
  }

  protected List<SerializationChunk> loadProjectChunks(File languagesDirectory) throws IOException {
    try (Stream<Path> stream = Files.walk(languagesDirectory.toPath())) {
      List<Path> files =
          stream
              .filter(Files::isRegularFile)
              .filter(f -> f.toString().endsWith(".json") || f.toString().endsWith(".pb"))
              .collect(Collectors.toList());
      if (files.isEmpty()) {
        getLogger().warn("GenerateLanguageTask - No files found");
        return List.of();
      }
      getLogger().lifecycle("Language files found: " + files.size());
      List<SerializationChunk> projectChunks =
          files.stream()
              .map(
                  f -> {
                    if (f.toString().endsWith(".json")) {
                      try {
                        return new LowLevelJsonSerialization()
                            .deserializeSerializationBlock(f.toFile());
                      } catch (Exception e) {
                        getLogger()
                            .warn(
                                "Failed to load file: "
                                    + f.toString()
                                    + " because "
                                    + e.getMessage());
                        getLogger()
                            .warn(
                                "We will assume this is not a LionWeb language and skipping file");
                        return null;
                      }
                    } else if (f.toString().endsWith(".pb")) {
                      throw new UnsupportedOperationException("Protobuf not yet supported");
                    } else {
                      throw new UnsupportedOperationException(
                          "Unsupported file extension: <" + f.toString() + ">");
                    }
                  })
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
      return projectChunks;
    }
  }
}
