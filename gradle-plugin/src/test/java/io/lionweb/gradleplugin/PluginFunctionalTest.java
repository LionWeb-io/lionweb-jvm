package io.lionweb.gradleplugin;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PluginFunctionalTest {

  @TempDir File testProjectDir;
  private File settingsFile;
  private File buildFile;

  @BeforeEach
  public void setup() {
    settingsFile = new File(testProjectDir, "settings.gradle.kts");
    buildFile = new File(testProjectDir, "build.gradle.kts");
  }

  @Test
  public void testGenerateLWLanguagesTask() throws IOException {
    writeFile(settingsFile, "rootProject.name = \"my-project-1\"");
    String buildFileContent =
        "plugins {\n"
            + "            id(\"io.lionweb\")\n"
            + "        }\n"
            + "lionweb { defaultPackageName.set(\"io.lionweb.test\") }";
    writeFile(buildFile, buildFileContent);
    File libraryLanguage = new File(testProjectDir, "src/main/lionweb/library.json");
    writeFile(libraryLanguage, readResource("/library-language.json"));

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("generateLWLanguages", "--info")
            .build();

    System.out.println(result.getOutput());
    assertTrue(result.getOutput().contains("LionWeb Language loaded: library"));
    assertEquals(SUCCESS, result.task(":generateLWLanguages").getOutcome());

    Path javaFile =
        Files.walk(testProjectDir.toPath())
            .filter(
                f ->
                    "build/generated-lionweb/io/lionweb/test/LibraryLanguage.java"
                        .equals(testProjectDir.toPath().relativize(f).toString()))
            .findFirst()
            .get();
    String javaCode = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
    System.out.println(javaCode);
  }

  @Test
  public void testGenerateLWLanguagesWithDependenciesOnLionCore() throws IOException {
    setupProjectWithLionCoreDependencies();

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("generateLWLanguages", "--info")
            .build();

    assertEquals(SUCCESS, result.task(":generateLWLanguages").getOutcome());
    String markersLanguage = readGeneratedFile("io/lionweb/test/MarkersLanguage.java");
    assertTrue(
        markersLanguage.contains(
            "this.addDependency(LionCore.getInstance(LionWebVersion.v2023_1));"),
        markersLanguage);
    assertTrue(
        markersLanguage.contains(
            "this.addDependency(LionCoreBuiltins.getInstance(LionWebVersion.v2023_1));"),
        markersLanguage);
    String notesLanguage = readGeneratedFile("io/lionweb/test/NotesLanguage.java");
    assertTrue(
        notesLanguage.contains(
            "this.addDependency(LionCoreBuiltins.getInstance(LionWebVersion.v2023_1));"),
        notesLanguage);
  }

  @Test
  public void testGenerateLWNodeClassesWithDependenciesOnLionCore() throws IOException {
    setupProjectWithLionCoreDependencies();

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("generateLWNodeClasses", "--info")
            .build();

    assertEquals(SUCCESS, result.task(":generateLWNodeClasses").getOutcome());
    assertTrue(result.getOutput().contains("LionWeb Language loaded: markers"));
    assertTrue(
        new File(testProjectDir, "build/generated-lionweb/io/lionweb/test/Note.java").exists(),
        result.getOutput());
  }

  private void setupProjectWithLionCoreDependencies() throws IOException {
    writeFile(settingsFile, "rootProject.name = \"my-project-1\"");
    writeFile(
        buildFile,
        "plugins {\n"
            + "  id(\"io.lionweb\")\n"
            + "}\n"
            + "lionweb { defaultPackageName.set(\"io.lionweb.test\") }");
    // Annotates Concept, so it declares dependencies on LionCore M3 and builtins
    writeFile(
        new File(testProjectDir, "src/main/lionweb/markers.json"),
        readResource("/markers.language.json"));
    // Declares a dependency on builtins only
    writeFile(
        new File(testProjectDir, "src/main/lionweb/notes.json"),
        readResource("/notes.language.json"));
  }

  private String readGeneratedFile(String path) throws IOException {
    return new String(
        Files.readAllBytes(
            testProjectDir.toPath().resolve("build/generated-lionweb").resolve(path)),
        StandardCharsets.UTF_8);
  }

  private String readResource(String path) throws IOException {
    String text;
    try (InputStream in = getClass().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("Resource not found");
      }
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    return text;
  }

  private void writeFile(File destination, String content) throws IOException {
    if (!destination.getParentFile().exists()) {
      destination.getParentFile().mkdirs();
    }
    BufferedWriter output = null;
    try {
      output = new BufferedWriter(new FileWriter(destination));
      output.write(content);
    } finally {
      if (output != null) {
        output.close();
      }
    }
  }
}
