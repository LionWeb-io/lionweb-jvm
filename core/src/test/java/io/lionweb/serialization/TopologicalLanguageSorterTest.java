package io.lionweb.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lionweb.LionWebVersion;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class TopologicalLanguageSorterTest {

  @Test
  public void sortStarlasuSpecsLanguages() {
    List<String> paths =
        Arrays.asList(
            "/starlasuspecs/codebase.language.v2.json",
            "/starlasuspecs/ast.language.v1.json",
            "/starlasuspecs/codebase.language.v1.json",
            "/starlasuspecs/comments.language.v1.json",
            "/starlasuspecs/migration.language.v1.json",
            "/starlasuspecs/ast.language.v2.json",
            "/starlasuspecs/pipeline.language.v1.json");
    List<SerializationChunk> chunks =
        paths.stream()
            .map(
                path -> {
                  try {
                    LowLevelJsonSerialization jsonSerialization = new LowLevelJsonSerialization();
                    InputStream inputStream = this.getClass().getResourceAsStream(path);
                    String text = read(inputStream);
                    return jsonSerialization.deserializeSerializationBlock(text);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());
    TopologicalLanguageSorter sorter = new TopologicalLanguageSorter(LionWebVersion.v2023_1);
    List<SerializationChunk> sortedChunks = sorter.topologicalSort(chunks);
    assertEquals(7, sortedChunks.size());
    List<String> ids =
        sortedChunks.stream()
            .map(
                chunk -> {
                  SerializedClassifierInstance root =
                      chunk.getClassifierInstances().stream()
                          .filter(n -> n.getParentNodeID() == null)
                          .findFirst()
                          .get();
                  return root.getID();
                })
            .collect(Collectors.toList());
    assertEquals(7, ids.size());
    int indexOfStarlasuV1 = ids.indexOf("com-strumenta-StarLasu");
    int indexOfStarlasuV2 = ids.indexOf("com-strumenta-Starlasu-v2");
    int indexOfCodebaseV1 = ids.indexOf("strumenta-codebase");
    int indexOfCodebaseV2 = ids.indexOf("strumenta-codebase-v2");
    int indexOfCommentsV1 = ids.indexOf("com-strumenta-starlasu-comments");
    int indexOfMigrationV1 = ids.indexOf("strumenta-migration");
    int indexOfPipelineV1 = ids.indexOf("com-strumenta-Pipeline");
    assertTrue(indexOfStarlasuV1 != -1);
    assertTrue(indexOfStarlasuV2 != -1);
    assertTrue(indexOfCodebaseV1 != -1);
    assertTrue(indexOfCodebaseV2 != -1);
    assertTrue(indexOfCommentsV1 != -1);
    assertTrue(indexOfMigrationV1 != -1);
    assertTrue(indexOfPipelineV1 != -1);
    assertTrue(indexOfStarlasuV1 < indexOfCodebaseV1);
    assertTrue(indexOfStarlasuV1 < indexOfMigrationV1);
    assertTrue(indexOfStarlasuV1 < indexOfPipelineV1);
    assertTrue(indexOfStarlasuV2 < indexOfCodebaseV2);
  }

  @Test
  public void unknownDependenciesCanBeAccepted() throws IOException {
    LowLevelJsonSerialization jsonSerialization = new LowLevelJsonSerialization();
    SerializationChunk codebaseV1 =
        jsonSerialization.deserializeSerializationBlock(
            read(this.getClass().getResourceAsStream("/starlasuspecs/codebase.language.v1.json")));
    TopologicalLanguageSorter sorter = new TopologicalLanguageSorter(LionWebVersion.v2023_1);
    List<SerializationChunk> chunks = Arrays.asList(codebaseV1);

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> sorter.topologicalSort(chunks));
    assertEquals("Unknown dependency: com-strumenta-StarLasu", e.getMessage());

    assertThrows(
        IllegalStateException.class,
        () -> sorter.topologicalSort(chunks, id -> id.equals("another-language")));
    assertEquals(
        Arrays.asList(codebaseV1),
        sorter.topologicalSort(chunks, id -> id.equals("com-strumenta-StarLasu")));
    assertEquals(Arrays.asList(codebaseV1), sorter.topologicalSort(chunks, id -> true));
  }

  private static String read(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      char[] buf = new char[2048];
      int n;
      while ((n = r.read(buf)) != -1) {
        sb.append(buf, 0, n);
      }
    }
    return sb.toString();
  }
}
