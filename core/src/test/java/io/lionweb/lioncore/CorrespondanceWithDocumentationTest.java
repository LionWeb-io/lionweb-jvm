package io.lionweb.lioncore;

import static io.lionweb.serialization.SerializationProvider.getStandardJsonSerialization;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lionweb.LionWebVersion;
import io.lionweb.language.Language;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.model.Node;
import io.lionweb.serialization.JsonSerialization;
import io.lionweb.utils.ModelComparator;
import java.io.*;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CorrespondanceWithDocumentationTest {

  private static final String SPECIFICATION_2023_1_COMMIT_CONSIDERED =
      "73b1c88e8e8f365c76bcf13340da310ed74d5f8e";

  private static final String SPECIFICATION_2024_1_COMMIT_CONSIDERED =
      "73b1c88e8e8f365c76bcf13340da310ed74d5f8e";

  private static final String SPECIFICATION_LIONCORE_2023_1_PATH =
      "/2023.1/metametamodel/lioncore.json";
  private static final String SPECIFICATION_LIONCORE_2024_1_PATH =
      "/2024.1/metametamodel/lioncore.json";
  private static final String SPECIFICATION_LIONCOREBUILTINS_2023_1_PATH =
      "/2023.1/metametamodel/builtins.json";
  private static final String SPECIFICATION_LIONCOREBUILTINS_2024_1_PATH =
      "/2024.1/metametamodel/builtins.json";

  @Test
  public void lioncoreIsTheSameAsInTheOrganizationRepo2023_1() throws IOException {
    JsonSerialization jsonSer = getStandardJsonSerialization(LionWebVersion.v2023_1);
    // The published 2023.1 files do not list all the languages they use in 'languages'
    jsonSer.setStrictChunkStructure(false);

    URL url =
        new URL(
            "https://raw.githubusercontent.com/LionWeb-io/specification/"
                + SPECIFICATION_2023_1_COMMIT_CONSIDERED
                + SPECIFICATION_LIONCORE_2023_1_PATH);
    List<Node> nodes = jsonSer.deserializeToNodes(url);

    Language deserializedLioncore = (Language) nodes.get(0);
    ModelComparator.ComparisonResult comparison =
        new ModelComparator()
            .compare(deserializedLioncore, LionCore.getInstance(LionWebVersion.v2023_1));
    System.out.println("Differences " + comparison.getDifferences().size());
    for (String difference : comparison.getDifferences()) {
      System.out.println(" - " + difference);
    }
    assertTrue(comparison.areEquivalent(), comparison.toString());
  }

  @Test
  public void lioncoreIsTheSameAsInTheOrganizationRepo2024_1() throws IOException {
    JsonSerialization jsonSer = getStandardJsonSerialization(LionWebVersion.v2024_1);

    URL url =
        new URL(
            "https://raw.githubusercontent.com/LionWeb-io/specification/"
                + SPECIFICATION_2024_1_COMMIT_CONSIDERED
                + SPECIFICATION_LIONCORE_2024_1_PATH);
    List<Node> nodes = jsonSer.deserializeToNodes(url);

    Language deserializedLioncore = (Language) nodes.get(0);
    ModelComparator.ComparisonResult comparison =
        new ModelComparator()
            .compare(deserializedLioncore, LionCore.getInstance(LionWebVersion.v2024_1));
    System.out.println("Differences " + comparison.getDifferences().size());
    for (String difference : comparison.getDifferences()) {
      System.out.println(" - " + difference);
    }
    assertTrue(comparison.areEquivalent(), comparison.toString());
  }

  @Test
  public void builtInIsTheSameAsInTheOrganizationRepo2023_1() throws IOException {
    JsonSerialization jsonSer = getStandardJsonSerialization(LionWebVersion.v2023_1);
    // The published 2023.1 files do not list all the languages they use in 'languages'
    jsonSer.setStrictChunkStructure(false);

    URL url =
        new URL(
            "https://raw.githubusercontent.com/LionWeb-io/specification/"
                + SPECIFICATION_2023_1_COMMIT_CONSIDERED
                + SPECIFICATION_LIONCOREBUILTINS_2023_1_PATH);
    List<Node> nodes = jsonSer.deserializeToNodes(url);

    Language deserializedBuiltins = (Language) nodes.get(0);
    ModelComparator.ComparisonResult comparison =
        new ModelComparator()
            .compare(deserializedBuiltins, LionCoreBuiltins.getInstance(LionWebVersion.v2023_1));
    System.out.println("Differences " + comparison.getDifferences().size());
    for (String difference : comparison.getDifferences()) {
      System.out.println(" - " + difference);
    }
    assertTrue(comparison.areEquivalent(), comparison.toString());
  }

  @Test
  public void builtInIsTheSameAsInTheOrganizationRepo2024_1() throws IOException {
    JsonSerialization jsonSer = getStandardJsonSerialization(LionWebVersion.v2024_1);

    URL url =
        new URL(
            "https://raw.githubusercontent.com/LionWeb-io/specification/"
                + SPECIFICATION_2024_1_COMMIT_CONSIDERED
                + SPECIFICATION_LIONCOREBUILTINS_2024_1_PATH);
    List<Node> nodes = jsonSer.deserializeToNodes(url);

    Language deserializedBuiltins = (Language) nodes.get(0);
    ModelComparator.ComparisonResult comparison =
        new ModelComparator()
            .compare(deserializedBuiltins, LionCoreBuiltins.getInstance(LionWebVersion.v2024_1));
    System.out.println("Differences " + comparison.getDifferences().size());
    for (String difference : comparison.getDifferences()) {
      System.out.println(" - " + difference);
    }
    assertTrue(comparison.areEquivalent(), comparison.toString());
  }
}
