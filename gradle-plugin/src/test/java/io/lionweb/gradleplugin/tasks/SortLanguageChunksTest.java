package io.lionweb.gradleplugin.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.lionweb.LionWebVersion;
import io.lionweb.serialization.LowLevelJsonSerialization;
import io.lionweb.serialization.data.SerializationChunk;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

public class SortLanguageChunksTest {

  @Test
  public void acceptsDependenciesOnLionCoreM3AndBuiltins() {
    SerializationChunk markers = loadChunk("/markers.language.json");
    SerializationChunk notes = loadChunk("/notes.language.json");

    List<SerializationChunk> sorted =
        AbstractGenerationTask.sortLanguageChunks(LionWebVersion.v2023_1, List.of(markers, notes));

    assertEquals(2, sorted.size());
    assertTrue(sorted.contains(markers));
    assertTrue(sorted.contains(notes));
  }

  @Test
  public void stillRejectsUnknownDependencies() {
    // Same as notes.language.json, with an additional dependency on a language that is not
    // available
    SerializationChunk library = loadChunk("/unknown-dependency.language.json");

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                AbstractGenerationTask.sortLanguageChunks(
                    LionWebVersion.v2023_1, List.of(library)));
    assertTrue(e.getMessage().contains("missing-language"), e.getMessage());
  }

  private SerializationChunk loadChunk(String resource) {
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      return new LowLevelJsonSerialization()
          .deserializeSerializationBlock(
              JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
