package io.lionweb.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.lionweb.LionWebVersion;
import io.lionweb.language.Concept;
import io.lionweb.language.Language;
import io.lionweb.language.Property;
import io.lionweb.model.ClassifierInstance;
import io.lionweb.model.ClassifierInstanceUtils;
import io.lionweb.model.Node;
import io.lionweb.model.impl.ProxyNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Specific tests of JsonSerialization using the Library example. */
public class SerializationOfLibraryTest extends SerializationTest {

  @Test
  public void deserializeLibraryToConcreteClasses() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/library-language.json");
    JsonElement jsonElement = JsonParser.parseReader(new InputStreamReader(inputStream));
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    List<Node> deserializedNodes = jsonSerialization.deserializeToNodes(jsonElement);

    Concept library = conceptByID(deserializedNodes, "library-Library");
    Property libraryName = library.getPropertyByName("name");
    assertNotNull(libraryName.getKey());

    Node book =
        deserializedNodes.stream().filter(n -> n.getID().equals("library-Book")).findFirst().get();
    assertEquals("Book", ClassifierInstanceUtils.getPropertyValueByName(book, "name"));
    assertEquals("library-Book", ClassifierInstanceUtils.getPropertyValueByName(book, "key"));

    Concept guidedBookWriter =
        (Concept)
            deserializedNodes.stream()
                .filter(n -> n.getID().equals("library-GuideBookWriter"))
                .findFirst()
                .get();
    assertEquals(
        "GuideBookWriter",
        ClassifierInstanceUtils.getPropertyValueByName(guidedBookWriter, "name"));
    assertNotNull(guidedBookWriter.getExtendedConcept());
  }

  @Test
  public void reserializeLibrary() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/library-language.json");
    JsonElement jsonElement = JsonParser.parseReader(new InputStreamReader(inputStream));
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    List<Node> deserializedNodes = jsonSerialization.deserializeToNodes(jsonElement);
    JsonElement reserialized =
        jsonSerialization.serializeTreeToJsonElement(deserializedNodes.get(0));
    SerializedJsonComparisonUtils.assertEquivalentLionWebJson(
        jsonElement.getAsJsonObject(), reserialized.getAsJsonObject());
  }

  @Test
  public void serializeLibraryInstance() {
    Library library = new Library("lib-1", "Language Engineering Library");
    Writer mv = new Writer("mv", "Markus Völter");
    Writer mb = new Writer("mb", "Meinte Boersma");
    Book de = new Book("de", "DSL Engineering", mv).setPages(558);
    Book bfd = new Book("bfd", "Business-Friendly DSLs", mb).setPages(517);
    library.addBook(de);
    library.addBook(bfd);

    // The library MM is not using the standard primitive types but its own, so we need to specify
    // how to serialize
    // those values
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    jsonSerialization
        .getPrimitiveValuesSerialization()
        .registerSerializer(
            "INhBvWyXvxwNsePuX0rdNGB_J9hi85cTb1Q0APXCyJ0",
            (DataTypesValuesSerialization.DataTypeSerializer<String>) value -> value);
    jsonSerialization
        .getPrimitiveValuesSerialization()
        .registerSerializer(
            "gVp8_QSmXE2k4pd-sQZgjYMoW95SLLaVIH4yMYqqbt4",
            (DataTypesValuesSerialization.DataTypeSerializer<Integer>) value -> value.toString());
    JsonObject jsonSerialized =
        jsonSerialization.serializeTreeToJsonElement(library).getAsJsonObject();
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/langeng-library.json");
    JsonObject jsonRead =
        JsonParser.parseReader(new InputStreamReader(inputStream)).getAsJsonObject();
    SerializedJsonComparisonUtils.assertEquivalentLionWebJson(jsonRead, jsonSerialized);
  }

  @Test
  public void deserializeLanguageWithDuplicateIDs() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/library-language-with-duplicate.json");
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    assertThrows(
        IllegalStateException.class, () -> jsonSerialization.deserializeToNodes(inputStream));
  }

  @TempDir public File temporaryFolder;

  @Test
  public void reserializeJsonFile() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/library-language.json");
    JsonElement jsonElement = JsonParser.parseReader(new InputStreamReader(inputStream));
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    List<Node> deserializedNodes = jsonSerialization.deserializeToNodes(jsonElement);

    Language libraryLanguage = (Language) deserializedNodes.get(0);

    try {
      File outputFile = new File(temporaryFolder, "test-library-lang.json");
      JsonSerialization.saveLanguageToFile(libraryLanguage, outputFile);

      Language loadedLang = jsonSerialization.loadLanguage(outputFile);

      assertEquals(libraryLanguage.getName(), loadedLang.getName());
      assertEquals(libraryLanguage.getKey(), loadedLang.getKey());
      assertEquals(libraryLanguage.getVersion(), loadedLang.getVersion());
      assertEquals(libraryLanguage.getLionWebVersion(), loadedLang.getLionWebVersion());

      Concept book = (Concept) loadedLang.getElementByName("Book");
      assertEquals(book.getLionWebVersion(), libraryLanguage.getLionWebVersion());

      Property bookTitle = (Property) book.getFeatureByName("title");
      assertEquals(bookTitle.getLionWebVersion(), libraryLanguage.getLionWebVersion());
    } catch (IOException ioe) {
      System.err.println(
          "Error creating temporary test file in " + this.getClass().getSimpleName());
    }
  }

  @Test
  public void deserializeExtendedLibrary() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/library-language.json");
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    List<Node> deserializedNodes = jsonSerialization.deserializeToNodes(inputStream);

    Language libraryLanguage = (Language) deserializedNodes.get(0);

    InputStream inputStream2 =
        this.getClass().getResourceAsStream("/serialization/extendedlibrary-language.json");
    JsonSerialization jsonSerialization2 =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    jsonSerialization2.registerLanguage(libraryLanguage);
    List<Node> deserializedNodes2 = jsonSerialization2.deserializeToNodes(inputStream2);

    Concept localLibrary = conceptByID(deserializedNodes2, "extendedlibrary-LocalLibrary");
    Property libraryName = localLibrary.getPropertyByName("name");
    assertNotNull(libraryName.getKey());
  }

  /**
   * We provide the SerializationChunk containing a series of subtrees (i.e., we removed the
   * Language instance which is containing everything else). When the unavailableParentPolicy is set
   * to THROW_ERROR we cannot deserialize the chunk into nodes.
   */
  @Test
  public void deserializeSubtreesWithThrowErrorPolicy() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/partial-library-language.json");
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    jsonSerialization.unavailableParentPolicy = UnavailableNodePolicy.THROW_ERROR;
    assertThrows(
        DeserializationException.class, () -> jsonSerialization.deserializeToNodes(inputStream));
  }

  /**
   * We provide the SerializationChunk containing a series of subtrees (i.e., we removed the
   * Language instance which is containing everything else). When the unavailableParentPolicy is set
   * to NULL_REFERENCES we can deserialize the chunk into nodes. For the parents which are not part
   * of the chunk will be set to null.
   */
  @Test
  public void deserializeSubtreesWithNullPolicy() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/partial-library-language.json");
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    jsonSerialization.unavailableParentPolicy = UnavailableNodePolicy.NULL_REFERENCES;
    List<Node> deserializedNodes = jsonSerialization.deserializeToNodes(inputStream);
    List<Node> concepts =
        deserializedNodes.stream()
            .filter(node -> node instanceof Concept)
            .collect(Collectors.toList());
    assertEquals(5, concepts.size());
    assertTrue(concepts.stream().allMatch(n -> n.getParent() == null));
  }

  /**
   * We provide the SerializationChunk containing a series of subtrees (i.e., we removed the
   * Language instance which is containing everything else). When the unavailableParentPolicy is set
   * to PROXY_NODES we can deserialize the chunk into nodes. For the parents which are not part of
   * the chunk will be set to ProxyNodes.
   */
  @Test
  public void deserializeSubtreesWithProxyPolicy() {
    InputStream inputStream =
        this.getClass().getResourceAsStream("/serialization/partial-library-language.json");
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    jsonSerialization.unavailableParentPolicy = UnavailableNodePolicy.PROXY_NODES;
    List<Node> deserializedNodes = jsonSerialization.deserializeToNodes(inputStream);
    List<Node> concepts =
        deserializedNodes.stream()
            .filter(node -> node instanceof Concept)
            .collect(Collectors.toList());
    assertEquals(5, concepts.size());
    assertTrue(
        concepts.stream()
            .allMatch(
                n ->
                    n.getParent() instanceof ProxyNode && n.getParent().getID().equals("library")));
  }

  @Test
  public void serializeEmptyFeatures() {
    Library lib = new Library("lib", "lib");
    Book book = new Book("book");
    List<ClassifierInstance<?>> nodes = Arrays.asList(lib, book);

    JsonSerialization standardSerialization =
            SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2023_1);
    SerializationChunk standardChunk = standardSerialization.serializeNodesToSerializationChunk(nodes);
    SerializedClassifierInstance standardLib = standardChunk.getInstanceByID("lib");
    assertEquals(1, standardLib.getContainments().size());
    SerializedClassifierInstance standardBook = standardChunk.getInstanceByID("book");
    assertEquals(1, standardBook.getReferences().size());

    JsonSerialization efficientSerialization =
            SerializationProvider.getEfficientJsonSerialization(LionWebVersion.v2023_1);
    SerializationChunk efficientChunk = efficientSerialization.serializeNodesToSerializationChunk(nodes);
    SerializedClassifierInstance efficientLib = efficientChunk.getInstanceByID("lib");
    assertEquals(0, efficientLib.getContainments().size());
    SerializedClassifierInstance efficientBook = efficientChunk.getInstanceByID("book");
    assertEquals(0, efficientBook.getReferences().size());
  }
}
