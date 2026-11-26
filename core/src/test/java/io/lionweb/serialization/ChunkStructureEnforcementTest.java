package io.lionweb.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonSyntaxException;
import io.lionweb.serialization.data.SerializationChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The structure of a chunk is enforced as prescribed by the serialization specification. The rules
 * checked here are the same in LionWeb 2023.1 and 2024.1, so each case runs against both.
 */
public class ChunkStructureEnforcementTest {

  private static final List<String> VERSIONS = List.of("2023.1", "2024.1");

  /** A valid chunk. Single quotes stand for double quotes, to keep the JSON readable. */
  private static String validChunk(String version) {
    return ("{'serializationFormatVersion': '"
            + version
            + "',"
            + " 'languages': [{'key': 'my-lang', 'version': '1'}],"
            + " 'nodes': [{"
            + "   'id': 'n1',"
            + "   'classifier': {'language': 'my-lang', 'version': '1', 'key': 'c'},"
            + "   'properties': [{'property': {'language': 'my-lang', 'version': '1', 'key': 'p'},"
            + "                   'value': 'v'}],"
            + "   'containments': [{'containment': {'language': 'my-lang', 'version': '1',"
            + "                                     'key': 'k'},"
            + "                     'children': ['n2']}],"
            + "   'references': [{'reference': {'language': 'my-lang', 'version': '1', 'key': 'r'},"
            + "                   'targets': [{'resolveInfo': 'x', 'reference': 'n3'}]}],"
            + "   'annotations': ['a1'],"
            + "   'parent': 'n0'}]}")
        .replace('\'', '"');
  }

  /** Replace some text of the valid chunk (written with single quotes) to make it invalid. */
  private static String chunkWith(String version, String original, String replacement) {
    String chunk = validChunk(version);
    String o = original.replace('\'', '"');
    assertTrue(chunk.contains(o), "bad test: " + o + " not found");
    return chunk.replace(o, replacement.replace('\'', '"'));
  }

  private static void assertRejected(
      String original, String replacement, String... expectedInMessage) {
    for (String version : VERSIONS) {
      String json = chunkWith(version, original, replacement);
      DeserializationException e =
          assertThrows(
              DeserializationException.class,
              () -> new LowLevelJsonSerialization().deserializeSerializationBlock(json),
              json);
      for (String expected : expectedInMessage) {
        assertTrue(
            e.getMessage().contains(expected),
            "'" + expected + "' not found in the message: " + e.getMessage());
      }
      // The same chunk is still loaded in tolerant mode
      assertNotNull(LowLevelJsonSerialization.tolerant().deserializeSerializationBlock(json));
    }
  }

  //
  // No unspecified members
  //

  @Test
  public void validChunkIsAccepted() {
    for (String version : VERSIONS) {
      SerializationChunk chunk =
          new LowLevelJsonSerialization().deserializeSerializationBlock(validChunk(version));
      assertEquals(1, chunk.getClassifierInstances().size());
      assertEquals("n1", chunk.getClassifierInstances().get(0).getID());
    }
  }

  @Test
  public void unspecifiedMemberInNodeIsRejected() {
    assertRejected("'parent': 'n0'", "'parent': 'n0', 'extra': 1", "node 'n1'", "extra");
  }

  @Test
  public void legacyChildrenMemberInNodeIsRejected() {
    assertRejected("'parent': 'n0'", "'parent': 'n0', 'children': []", "node 'n1'", "children");
  }

  @Test
  public void unspecifiedMemberInMetaPointerIsRejected() {
    assertRejected("'key': 'c'}", "'key': 'c', 'name': 'C'}", "node 'n1'", "classifier", "name");
    assertRejected("'key': 'p'}", "'key': 'p', 'name': 'P'}", "node 'n1'", "property 'p'", "name");
  }

  @Test
  public void unspecifiedMemberInPropertyIsRejected() {
    assertRejected("'value': 'v'", "'value': 'v', 'extra': 1", "node 'n1'", "property 'p'");
  }

  @Test
  public void unspecifiedMemberInContainmentIsRejected() {
    assertRejected(
        "'children': ['n2']", "'children': ['n2'], 'extra': 1", "node 'n1'", "containment 'k'");
  }

  @Test
  public void unspecifiedMemberInReferenceIsRejected() {
    assertRejected(
        "'targets': [", "'extra': 1, 'targets': [", "node 'n1'", "reference 'r'", "extra");
  }

  @Test
  public void unspecifiedMemberInReferenceTargetIsRejected() {
    assertRejected(
        "'resolveInfo': 'x'", "'resolveInfo': 'x', 'extra': 1", "node 'n1'", "reference 'r'");
  }

  //
  // Strict JSON
  //

  @Test
  public void duplicateKeysKeepTheLastValue() {
    // Gson does not report duplicate keys, so they are not rejected: the last value wins
    String json = chunkWith("2024.1", "'parent': 'n0'", "'parent': 'n0', 'parent': null");
    for (LowLevelJsonSerialization serialization :
        List.of(new LowLevelJsonSerialization(), LowLevelJsonSerialization.tolerant())) {
      SerializationChunk chunk = serialization.deserializeSerializationBlock(json);
      assertNull(chunk.getClassifierInstances().get(0).getParentNodeID());
    }
  }

  @Test
  public void jsonWhichIsNotStrictlyValidIsRejected() {
    String unquotedNames = validChunk("2024.1").replace("\"parent\"", "parent");
    assertThrows(
        JsonSyntaxException.class,
        () -> new LowLevelJsonSerialization().deserializeSerializationBlock(unquotedNames));
    assertNotNull(
        LowLevelJsonSerialization.tolerant().deserializeSerializationBlock(unquotedNames));
  }

  //
  // Ids, keys and versions
  //

  @Test
  public void idsMayUseLettersDigitsUnderscoreAndHyphen() {
    String json = chunkWith("2024.1", "'id': 'n1'", "'id': 'My_node-1'");
    assertEquals(
        "My_node-1",
        new LowLevelJsonSerialization()
            .deserializeSerializationBlock(json)
            .getClassifierInstances()
            .get(0)
            .getID());
  }

  @Test
  public void invalidNodeIdIsRejected() {
    assertRejected("'id': 'n1'", "'id': 'n 1!'", "Invalid id 'n 1!'");
    assertRejected("'id': 'n1'", "'id': ''", "Invalid id ''");
    assertRejected("'id': 'n1'", "'id': 'a$b'", "Invalid id 'a$b'");
  }

  @Test
  public void invalidParentIdIsRejected() {
    assertRejected("'parent': 'n0'", "'parent': 'n 0'", "Invalid id 'n 0'", "parent", "node 'n1'");
  }

  @Test
  public void invalidChildIdIsRejected() {
    assertRejected("'children': ['n2']", "'children': ['n.2']", "Invalid id 'n.2'", "child");
  }

  @Test
  public void invalidAnnotationIdIsRejected() {
    assertRejected("'annotations': ['a1']", "'annotations': ['a 1']", "Invalid id 'a 1'");
  }

  @Test
  public void invalidReferenceTargetIdIsRejected() {
    assertRejected("'reference': 'n3'", "'reference': 'n 3'", "Invalid id 'n 3'", "target");
  }

  @Test
  public void nullReferenceTargetIsAccepted() {
    String json = chunkWith("2024.1", "'reference': 'n3'", "'reference': null");
    assertNotNull(new LowLevelJsonSerialization().deserializeSerializationBlock(json));
  }

  @Test
  public void invalidFeatureKeyIsRejected() {
    assertRejected("'key': 'p'", "'key': 'p p'", "Invalid key 'p p'", "node 'n1'");
    assertRejected("'key': 'c'", "'key': ''", "Invalid key ''", "classifier");
  }

  @Test
  public void invalidLanguageKeyInMetaPointerIsRejected() {
    assertRejected(
        "'classifier': {'language': 'my-lang'",
        "'classifier': {'language': 'my.lang'",
        "Invalid language key 'my.lang'",
        "classifier");
  }

  @Test
  public void emptyVersionInMetaPointerIsRejected() {
    assertRejected(
        "'classifier': {'language': 'my-lang', 'version': '1'",
        "'classifier': {'language': 'my-lang', 'version': ''",
        "Invalid version ''",
        "classifier");
  }

  @Test
  public void invalidLanguageKeyInLanguagesIsRejected() {
    assertRejected(
        "'languages': [{'key': 'my-lang'",
        "'languages': [{'key': 'my lang'",
        "Invalid language key 'my lang'");
  }

  @Test
  public void emptyLanguageVersionInLanguagesIsRejected() {
    assertRejected(
        "'languages': [{'key': 'my-lang', 'version': '1'}]",
        "'languages': [{'key': 'my-lang', 'version': ''}]",
        "Invalid version",
        "my-lang");
  }

  //
  // Complete and unique languages
  //

  @Test
  public void duplicateLanguageIsRejected() {
    for (String version : VERSIONS) {
      String json =
          chunkWith(
              version,
              "'languages': [{'key': 'my-lang', 'version': '1'}]",
              "'languages': [{'key': 'my-lang', 'version': '1'},"
                  + " {'key': 'my-lang', 'version': '1'}]");
      DeserializationException e =
          assertThrows(
              DeserializationException.class,
              () -> new LowLevelJsonSerialization().deserializeSerializationBlock(json));
      assertTrue(e.getMessage().contains("my-lang"), e.getMessage());
      assertTrue(e.getMessage().contains("more than once"), e.getMessage());
      assertEquals(
          1,
          LowLevelJsonSerialization.tolerant()
              .deserializeSerializationBlock(json)
              .getLanguages()
              .size());
    }
  }

  @Test
  public void sameLanguageInDifferentVersionsIsNotADuplicate() {
    String json =
        chunkWith(
            "2024.1",
            "'languages': [{'key': 'my-lang', 'version': '1'}]",
            "'languages': [{'key': 'my-lang', 'version': '1'},"
                + " {'key': 'my-lang', 'version': '2'}]");
    assertEquals(
        2,
        new LowLevelJsonSerialization().deserializeSerializationBlock(json).getLanguages().size());
  }

  @Test
  public void languageUsedButNotListedIsRejected() {
    for (String version : VERSIONS) {
      String json =
          chunkWith(
              version, "'languages': [{'key': 'my-lang', 'version': '1'}]", "'languages': []");
      DeserializationException e =
          assertThrows(
              DeserializationException.class,
              () -> new LowLevelJsonSerialization().deserializeSerializationBlock(json));
      assertTrue(e.getMessage().contains("my-lang (version 1)"), e.getMessage());
      assertTrue(e.getMessage().contains("node 'n1'"), e.getMessage());
      assertTrue(e.getMessage().contains("not listed in 'languages'"), e.getMessage());
      // Tolerant mode adds the missing language
      assertEquals(
          1,
          LowLevelJsonSerialization.tolerant()
              .deserializeSerializationBlock(json)
              .getLanguages()
              .size());
    }
  }

  @Test
  public void languageUsedByAFeatureButNotListedIsRejected() {
    String json =
        chunkWith(
            "2024.1",
            "'property': {'language': 'my-lang'",
            "'property': {'language': 'other-lang'");
    DeserializationException e =
        assertThrows(
            DeserializationException.class,
            () -> new LowLevelJsonSerialization().deserializeSerializationBlock(json));
    assertTrue(e.getMessage().contains("other-lang"), e.getMessage());
    assertTrue(e.getMessage().contains("property"), e.getMessage());
  }

  @Test
  public void languageListedButNotUsedIsAccepted() {
    String json =
        chunkWith(
            "2024.1",
            "'languages': [{'key': 'my-lang', 'version': '1'}]",
            "'languages': [{'key': 'my-lang', 'version': '1'}, {'key': 'unused', 'version': '1'}]");
    assertEquals(
        2,
        new LowLevelJsonSerialization().deserializeSerializationBlock(json).getLanguages().size());
  }

  @Test
  public void jsonSerializationCanBeSwitchedToTolerantMode() {
    String json =
        ("{'serializationFormatVersion': '2024.1', 'languages': [],"
                + " 'nodes': [{'id': 'my-lang',"
                + "   'classifier': {'language': 'LionCore-M3', 'version': '2024.1',"
                + "                  'key': 'Language'},"
                + "   'properties': [], 'containments': [], 'references': [], 'annotations': [],"
                + "   'parent': null}]}")
            .replace('\'', '"');
    JsonSerialization jsonSerialization =
        SerializationProvider.getStandardJsonSerialization(io.lionweb.LionWebVersion.v2024_1);
    assertTrue(jsonSerialization.isStrictChunkStructure());
    DeserializationException e =
        assertThrows(
            DeserializationException.class, () -> jsonSerialization.deserializeToNodes(json));
    assertTrue(e.getMessage().contains("not listed in 'languages'"), e.getMessage());

    jsonSerialization.setStrictChunkStructure(false);
    assertEquals(1, jsonSerialization.deserializeToNodes(json).size());
  }
}
