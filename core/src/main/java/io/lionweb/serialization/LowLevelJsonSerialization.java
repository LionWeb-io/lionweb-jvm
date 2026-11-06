package io.lionweb.serialization;

import static io.lionweb.serialization.JsonSerializationKeys.*;

import com.google.common.base.Charsets;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import io.lionweb.LionWebVersion;
import io.lionweb.serialization.data.*;
import io.lionweb.utils.IdUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 * This class is responsible for handling serialization and deserialization from JSON and the
 * low-level representation of models composed by SerializationBlock and the related classes.
 */
public class LowLevelJsonSerialization {

  private static final List<String> NODE_MEMBERS =
      List.of(ID, CLASSIFIER, PROPERTIES, CONTAINMENTS, REFERENCES, ANNOTATIONS, PARENT);
  private static final List<String> META_POINTER_MEMBERS = List.of(LANGUAGE, VERSION, KEY);

  private final boolean strict;

  /**
   * Create a strict instance, which enforces the structure of a chunk as prescribed by the LionWeb
   * serialization specification. In addition to the checks done by the tolerant mode, it verifies
   * that:
   *
   * <ul>
   *   <li>there are no unspecified members in nodes, properties, containments, references,
   *       reference targets and meta-pointers (this includes the legacy {@code children} member of
   *       nodes)
   *   <li>ids (when present), language keys, feature keys and versions are well-formed
   *   <li>{@code languages} has no duplicates and lists all the languages used by meta-pointers
   *   <li>when parsing text or files, the JSON is strictly RFC 8259
   * </ul>
   */
  public LowLevelJsonSerialization() {
    this(true);
  }

  /**
   * @param strict when false, the checks listed in {@link #LowLevelJsonSerialization()} are not
   *     performed, to be able to load broken chunks.
   */
  public LowLevelJsonSerialization(boolean strict) {
    this.strict = strict;
  }

  /** Create an instance which tolerates chunks that do not respect the specified structure. */
  public static LowLevelJsonSerialization tolerant() {
    return new LowLevelJsonSerialization(false);
  }

  /**
   * This will return a lower-level representation of the information stored in JSON. It is intended
   * to load broken models.
   *
   * <p>Possible usages: repair a broken model, extract a language from the model ("model
   * archeology"), etc.
   *
   * <p>This method follows a "best-effort" approach, try to limit exception thrown and return data
   * whenever is possible, in the measure that it is possible.
   */
  public SerializationChunk deserializeSerializationBlock(JsonElement jsonElement) {
    SerializationChunk serializationChunk = new SerializationChunk();
    if (jsonElement.isJsonObject()) {
      JsonObject topLevel = jsonElement.getAsJsonObject();
      checkNoExtraKeys(topLevel, List.of(NODES, SERIALIZATION_FORMAT_VERSION, LANGUAGES));
      readSerializationFormatVersion(serializationChunk, topLevel);
      Set<LanguageVersion> declaredLanguages = readLanguages(serializationChunk, topLevel);
      deserializeClassifierInstances(serializationChunk, topLevel, declaredLanguages);
      return serializationChunk;
    } else {
      throw new IllegalArgumentException(
          "We expected a Json Object, we got instead: " + jsonElement);
    }
  }

  /**
   * This will return a lower-level representation of the information stored in JSON. It is intended
   * to load broken models.
   *
   * <p>Possible usages: repair a broken model, extract a language from the model ("model
   * archeology"), etc.
   *
   * <p>This method follows a "best-effort" approach, try to limit exception thrown and return data
   * whenever is possible, in the measure that it is possible.
   */
  public SerializationChunk deserializeSerializationBlock(String json) {
    return deserializeSerializationBlock(parse(new StringReader(json)));
  }

  /**
   * This will return a lower-level representation of the information stored in JSON. It is intended
   * to load broken models.
   *
   * <p>Possible usages: repair a broken model, extract a language from the model ("model
   * archeology"), etc.
   *
   * <p>This method follows a "best-effort" approach, try to limit exception thrown and return data
   * whenever is possible, in the measure that it is possible.
   */
  public SerializationChunk deserializeSerializationBlock(File file, Charset charset)
      throws FileNotFoundException {
    try (Reader reader = new InputStreamReader(new FileInputStream(file), charset)) {
      return deserializeSerializationBlock(parse(reader));
    } catch (FileNotFoundException e) {
      throw e;
    } catch (IOException e) {
      throw new DeserializationException("Cannot read " + file + ": " + e.getMessage());
    }
  }

  public SerializationChunk deserializeSerializationBlock(File file) throws FileNotFoundException {
    return deserializeSerializationBlock(file, Charsets.UTF_8);
  }

  public JsonElement serializeToJsonElement(SerializationChunk serializationChunk) {
    JsonObject topLevel = new JsonObject();
    topLevel.addProperty(
        SERIALIZATION_FORMAT_VERSION, serializationChunk.getSerializationFormatVersion());

    JsonArray languages = new JsonArray();
    serializationChunk.getLanguages().forEach(m -> languages.add(serializeToJsonElement(m)));
    topLevel.add(LANGUAGES, languages);

    JsonArray nodes = new JsonArray();
    for (SerializedClassifierInstance node : serializationChunk.getClassifierInstances()) {
      JsonObject nodeJson = new JsonObject();
      nodeJson.addProperty(ID, node.getID());

      nodeJson.add(CLASSIFIER, serializeToJsonElement(node.getClassifier()));

      JsonArray properties = new JsonArray();
      for (SerializedPropertyValue propertyValue : node.getProperties()) {
        JsonObject property = new JsonObject();
        property.add(PROPERTY, serializeToJsonElement(propertyValue.getMetaPointer()));
        property.addProperty(VALUE, propertyValue.getValue());
        properties.add(property);
      }
      nodeJson.add(PROPERTIES, properties);

      JsonArray containments = new JsonArray();
      for (SerializedContainmentValue childrenValue : node.getContainments()) {
        JsonObject children = new JsonObject();
        children.add(CONTAINMENT, serializeToJsonElement(childrenValue.getMetaPointer()));
        children.add(CHILDREN, SerializationUtils.toJsonArray(childrenValue.getChildrenIds()));
        containments.add(children);
      }
      nodeJson.add(CONTAINMENTS, containments);

      JsonArray references = new JsonArray();
      for (SerializedReferenceValue referenceValue : node.getReferences()) {
        JsonObject reference = new JsonObject();
        reference.add(REFERENCE, serializeToJsonElement(referenceValue.getMetaPointer()));
        reference.add(
            TARGETS, SerializationUtils.toJsonArrayOfReferenceValues(referenceValue.getValue()));
        references.add(reference);
      }
      nodeJson.add(REFERENCES, references);

      JsonArray annotations = new JsonArray();
      for (String annotationID : node.getAnnotations()) {
        annotations.add(annotationID);
      }
      nodeJson.add(ANNOTATIONS, annotations);

      nodeJson.addProperty(PARENT, node.getParentNodeID());

      nodes.add(nodeJson);
    }
    topLevel.add(NODES, nodes);

    return topLevel;
  }

  public String serializeToJsonString(SerializationChunk serializationChunk) {
    return new GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()
        .toJson(serializeToJsonElement(serializationChunk));
  }

  /** Create a SerializationChunk containing the given nodes. */
  public static SerializationChunk groupNodesIntoSerializationBlock(
      Collection<SerializedClassifierInstance> serializedClassifierInstances,
      LionWebVersion lionWebVersion) {
    SerializationChunk serializationChunk = new SerializationChunk();
    serializationChunk.setSerializationFormatVersion(lionWebVersion.getVersionString());
    for (SerializedClassifierInstance serializedClassifierInstance :
        serializedClassifierInstances) {
      serializationChunk.addClassifierInstance(serializedClassifierInstance);
    }
    serializationChunk.populateUsedLanguages();
    return serializationChunk;
  }

  //
  // Private methods
  //

  private void readSerializationFormatVersion(
      SerializationChunk serializationChunk, JsonObject topLevel) {
    if (!topLevel.has(SERIALIZATION_FORMAT_VERSION)) {
      throw new IllegalArgumentException("serializationFormatVersion not specified");
    }
    JsonElement serializationFormatVersion = topLevel.get(SERIALIZATION_FORMAT_VERSION);
    requireIsString(serializationFormatVersion, SERIALIZATION_FORMAT_VERSION);
    String serializationFormatVersionValue = serializationFormatVersion.getAsString();
    serializationChunk.setSerializationFormatVersion(serializationFormatVersionValue);
  }

  private Set<LanguageVersion> readLanguages(
      SerializationChunk serializationChunk, JsonObject topLevel) {
    if (!topLevel.has(LANGUAGES)) {
      throw new IllegalArgumentException("languages not specified");
    }
    Set<LanguageVersion> declaredLanguages = new HashSet<>();
    if (topLevel.get(LANGUAGES).isJsonArray()) {
      topLevel.get(LANGUAGES).getAsJsonArray().asList().stream()
          .forEach(
              element -> {
                try {
                  LanguageVersion languageKeyVersion;
                  if (element.isJsonObject()) {
                    JsonObject jsonObject = element.getAsJsonObject();
                    checkNoExtraKeys(jsonObject, List.of(KEY, VERSION));
                    if (!jsonObject.has(KEY) || !jsonObject.has(VERSION)) {
                      throw new IllegalArgumentException(
                          "Language should have keys key and version. Found: " + element);
                    }
                    requireIsString(jsonObject.get(KEY), KEY);
                    requireIsString(jsonObject.get(VERSION), VERSION);
                    languageKeyVersion =
                        LanguageVersion.of(
                            jsonObject.get(KEY).getAsString(),
                            jsonObject.get(VERSION).getAsString());
                  } else {
                    throw new IllegalArgumentException(
                        "Language should be an object. Found: " + element);
                  }
                  if (strict) {
                    if (!IdUtils.isValidID(languageKeyVersion.getKey())) {
                      throw new InvalidChunkException(
                          "Invalid language key '"
                              + languageKeyVersion.getKey()
                              + "': it should be a non-empty string containing only letters,"
                              + " digits, '_' and '-'");
                    }
                    if (languageKeyVersion.getVersion().isEmpty()) {
                      throw new InvalidChunkException(
                          "Invalid version for language '"
                              + languageKeyVersion.getKey()
                              + "': it should not be empty");
                    }
                    if (!declaredLanguages.add(languageKeyVersion)) {
                      throw new InvalidChunkException(
                          "Language "
                              + languageKeyVersion.getKey()
                              + " (version "
                              + languageKeyVersion.getVersion()
                              + ") is listed more than once in 'languages'");
                    }
                  }
                  serializationChunk.addLanguage(languageKeyVersion);
                } catch (InvalidChunkException e) {
                  throw e;
                } catch (Exception e) {
                  throw new RuntimeException("Issue while deserializing " + element, e);
                }
              });
    } else {
      throw new IllegalArgumentException(
          "We expected a Json Array, we got instead: " + topLevel.get(LANGUAGES));
    }
    return declaredLanguages;
  }

  private void deserializeClassifierInstances(
      SerializationChunk serializationChunk,
      JsonObject topLevel,
      Set<LanguageVersion> declaredLanguages) {
    if (!topLevel.has(NODES)) {
      throw new IllegalArgumentException("nodes not specified");
    }
    if (topLevel.get(NODES).isJsonArray()) {
      topLevel.get(NODES).getAsJsonArray().asList().stream()
          .forEach(
              element -> {
                try {
                  SerializedClassifierInstance instance =
                      deserializeClassifierInstance(element, declaredLanguages);
                  serializationChunk.addClassifierInstance(instance);
                } catch (InvalidChunkException e) {
                  throw e;
                } catch (DeserializationException e) {
                  throw new DeserializationException(
                      "Issue while deserializing classifier instances", e);
                } catch (Exception e) {
                  throw new RuntimeException("Issue while deserializing " + element, e);
                }
              });
    } else {
      throw new IllegalArgumentException(
          "We expected a Json Array, we got instead: " + topLevel.get(NODES));
    }
  }

  private JsonElement serializeToJsonElement(MetaPointer metapointer) {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty(LANGUAGE, metapointer.getLanguage());
    jsonObject.addProperty(VERSION, metapointer.getVersion());
    jsonObject.addProperty(KEY, metapointer.getKey());
    return jsonObject;
  }

  private JsonElement serializeToJsonElement(LanguageVersion languageKeyVersion) {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty(KEY, languageKeyVersion.getKey());
    jsonObject.addProperty(VERSION, languageKeyVersion.getVersion());
    return jsonObject;
  }

  @Nullable
  private SerializedClassifierInstance deserializeClassifierInstance(
      JsonElement jsonElement, Set<LanguageVersion> declaredLanguages) {
    if (!jsonElement.isJsonObject()) {
      throw new IllegalArgumentException(
          "Malformed JSON. Object expected but found " + jsonElement);
    }
    try {
      JsonObject jsonObject = jsonElement.getAsJsonObject();
      if (strict) {
        checkNodeStructure(jsonObject);
      }

      SerializedClassifierInstance serializedClassifierInstance =
          new SerializedClassifierInstance();
      serializedClassifierInstance.setClassifier(
          SerializationUtils.tryToGetMetaPointerProperty(jsonObject, CLASSIFIER));
      serializedClassifierInstance.setParentNodeID(
          SerializationUtils.getStringOrNullProperty(jsonObject, PARENT));

      serializedClassifierInstance.setID(
          SerializationUtils.getStringOrNullProperty(jsonObject, ID));

      JsonArray properties = jsonObject.get(PROPERTIES).getAsJsonArray();
      properties.forEach(
          property -> {
            JsonObject propertyJO = property.getAsJsonObject();
            serializedClassifierInstance.unsafeAppendPropertyValue(
                SerializedPropertyValue.get(
                    SerializationUtils.tryToGetMetaPointerProperty(propertyJO, PROPERTY),
                    SerializationUtils.getStringOrNullProperty(propertyJO, VALUE)));
          });

      JsonArray containments;
      if (jsonObject.has(CHILDREN) && !strict) {
        containments = jsonObject.get(CHILDREN).getAsJsonArray();
      } else if (jsonObject.has(CONTAINMENTS)) {
        containments = jsonObject.get(CONTAINMENTS).getAsJsonArray();
      } else {
        throw new UnsupportedOperationException(
            "Node is missing containments entry: " + jsonObject);
      }

      containments.forEach(
          containmentEntry -> {
            JsonObject containmentJO = containmentEntry.getAsJsonObject();
            serializedClassifierInstance.unsafeAppendContainmentValue(
                new SerializedContainmentValue(
                    SerializationUtils.tryToGetMetaPointerProperty(containmentJO, CONTAINMENT),
                    SerializationUtils.tryToGetArrayOfIDs(containmentJO, CHILDREN)));
          });

      JsonArray references = jsonObject.get(REFERENCES).getAsJsonArray();
      references.forEach(
          referenceEntry -> {
            JsonObject referenceJO = referenceEntry.getAsJsonObject();
            serializedClassifierInstance.unsafeAppendReferenceValue(
                new SerializedReferenceValue(
                    SerializationUtils.tryToGetMetaPointerProperty(referenceJO, REFERENCE),
                    SerializationUtils.tryToGetArrayOfReferencesProperty(referenceJO, TARGETS)));
          });

      JsonElement annotationsJE = jsonObject.get(ANNOTATIONS);
      if (annotationsJE != null) {
        JsonArray annotations = annotationsJE.getAsJsonArray();
        serializedClassifierInstance.setAnnotations(
            StreamSupport.stream(annotations.spliterator(), false)
                .map(
                    annotationEntry -> {
                      JsonPrimitive annotationJP = annotationEntry.getAsJsonPrimitive();
                      return annotationJP.getAsString();
                    })
                .collect(Collectors.toList()));
      }

      if (strict) {
        checkNodeValues(serializedClassifierInstance, declaredLanguages);
      }

      return serializedClassifierInstance;
    } catch (InvalidChunkException e) {
      throw e;
    } catch (DeserializationException e) {
      throw new DeserializationException("Issue occurred while deserializing " + jsonElement, e);
    }
  }

  /**
   * Check that the node, and the objects it contains, have no members other than the specified
   * ones.
   */
  private void checkNodeStructure(JsonObject node) {
    String where = "node " + describeId(node.get(ID));
    checkNoExtraMembers(node, NODE_MEMBERS, where);
    checkMetaPointerMembers(node.get(CLASSIFIER), where + ", classifier");
    checkEntries(node, PROPERTIES, where, List.of(PROPERTY, VALUE), PROPERTY);
    checkEntries(node, CONTAINMENTS, where, List.of(CONTAINMENT, CHILDREN), CONTAINMENT);
    checkEntries(node, REFERENCES, where, List.of(REFERENCE, TARGETS), REFERENCE);
    JsonElement references = node.get(REFERENCES);
    if (references != null && references.isJsonArray()) {
      for (JsonElement reference : references.getAsJsonArray()) {
        if (reference.isJsonObject() && reference.getAsJsonObject().get(TARGETS) != null) {
          JsonElement targets = reference.getAsJsonObject().get(TARGETS);
          if (targets.isJsonArray()) {
            for (JsonElement target : targets.getAsJsonArray()) {
              if (target.isJsonObject()) {
                checkNoExtraMembers(
                    target.getAsJsonObject(),
                    List.of(RESOLVE_INFO, REFERENCE),
                    where + ", a target of reference " + describeFeature(reference));
              }
            }
          }
        }
      }
    }
  }

  private void checkEntries(
      JsonObject node,
      String arrayName,
      String where,
      List<String> expectedMembers,
      String featureMember) {
    JsonElement array = node.get(arrayName);
    if (array == null || !array.isJsonArray()) {
      return;
    }
    for (JsonElement entry : array.getAsJsonArray()) {
      if (entry.isJsonObject()) {
        JsonObject entryJO = entry.getAsJsonObject();
        String entryWhere = where + ", " + featureMember + " " + describeFeature(entry);
        checkNoExtraMembers(entryJO, expectedMembers, entryWhere);
        checkMetaPointerMembers(entryJO.get(featureMember), entryWhere);
      }
    }
  }

  private void checkMetaPointerMembers(@Nullable JsonElement metaPointer, String where) {
    if (metaPointer != null && metaPointer.isJsonObject()) {
      checkNoExtraMembers(metaPointer.getAsJsonObject(), META_POINTER_MEMBERS, where);
    }
  }

  private static void checkNoExtraMembers(
      JsonObject jsonObject, Collection<String> allowedMembers, String where) {
    Set<String> extraMembers = new TreeSet<>(jsonObject.keySet());
    extraMembers.removeAll(allowedMembers);
    if (!extraMembers.isEmpty()) {
      throw new InvalidChunkException(
          "Unspecified member(s) "
              + extraMembers
              + " found in "
              + where
              + ". Allowed members: "
              + allowedMembers);
    }
  }

  private static String describeId(@Nullable JsonElement id) {
    return id != null && id.isJsonPrimitive() ? "'" + id.getAsString() + "'" : "<no id>";
  }

  private static String describeFeature(JsonElement entry) {
    if (entry.isJsonObject()) {
      for (JsonElement member : entry.getAsJsonObject().asMap().values()) {
        if (member.isJsonObject() && member.getAsJsonObject().has(KEY)) {
          return describeId(member.getAsJsonObject().get(KEY));
        }
      }
    }
    return "<unknown>";
  }

  /**
   * Check ids, keys and versions are well-formed, and that all the languages used by the node are
   * listed in the chunk.
   */
  private void checkNodeValues(
      SerializedClassifierInstance node, Set<LanguageVersion> declaredLanguages) {
    String id = node.getID();
    // Nodes without an id are tolerated (they are used to build nodes whose ids are assigned
    // later), but when an id is present it must be well-formed.
    if (id != null) {
      checkValidID(id, "node id", "the chunk");
    }
    String where = "node " + (id == null ? "<no id>" : "'" + id + "'");
    if (node.getParentNodeID() != null) {
      checkValidID(node.getParentNodeID(), PARENT, where);
    }
    checkMetaPointer(node.getClassifier(), CLASSIFIER, where, declaredLanguages);
    for (SerializedPropertyValue property : node.getProperties()) {
      checkMetaPointer(property.getMetaPointer(), PROPERTY, where, declaredLanguages);
    }
    for (SerializedContainmentValue containment : node.getContainments()) {
      checkMetaPointer(containment.getMetaPointer(), CONTAINMENT, where, declaredLanguages);
      for (String childID : containment.getChildrenIds()) {
        checkValidID(childID, "child in containment " + containment.getMetaPointer(), where);
      }
    }
    for (SerializedReferenceValue reference : node.getReferences()) {
      checkMetaPointer(reference.getMetaPointer(), REFERENCE, where, declaredLanguages);
      for (SerializedReferenceValue.Entry target : reference.getValue()) {
        if (target.getReference() != null) {
          checkValidID(
              target.getReference(), "target of reference " + reference.getMetaPointer(), where);
        }
      }
    }
    for (String annotationID : node.getAnnotations()) {
      checkValidID(annotationID, "annotation", where);
    }
  }

  private static void checkValidID(String id, String role, String where) {
    if (!IdUtils.isValidID(id)) {
      throw new InvalidChunkException(
          "Invalid id '"
              + id
              + "' used as "
              + role
              + " in "
              + where
              + ": it should be a non-empty string containing only letters, digits, '_' and '-'");
    }
  }

  private static void checkMetaPointer(
      @Nullable MetaPointer metaPointer,
      String role,
      String where,
      Set<LanguageVersion> declaredLanguages) {
    if (metaPointer == null) {
      // A missing meta-pointer is not a matter of well-formedness of its values
      return;
    }
    if (!IdUtils.isValidID(metaPointer.getLanguage())) {
      throw new InvalidChunkException(
          "Invalid language key "
              + quote(metaPointer.getLanguage())
              + " in the "
              + role
              + " meta-pointer of "
              + where
              + ": it should be a non-empty string containing only letters, digits, '_' and '-'");
    }
    if (metaPointer.getVersion() == null || metaPointer.getVersion().isEmpty()) {
      throw new InvalidChunkException(
          "Invalid version "
              + quote(metaPointer.getVersion())
              + " in the "
              + role
              + " meta-pointer of "
              + where
              + ": it should not be empty");
    }
    if (!IdUtils.isValidID(metaPointer.getKey())) {
      throw new InvalidChunkException(
          "Invalid key "
              + quote(metaPointer.getKey())
              + " in the "
              + role
              + " meta-pointer of "
              + where
              + ": it should be a non-empty string containing only letters, digits, '_' and '-'");
    }
    if (!declaredLanguages.contains(LanguageVersion.fromMetaPointer(metaPointer))) {
      throw new InvalidChunkException(
          "Language "
              + metaPointer.getLanguage()
              + " (version "
              + metaPointer.getVersion()
              + ") is used by the "
              + role
              + " meta-pointer of "
              + where
              + " but it is not listed in 'languages'");
    }
  }

  private static String quote(@Nullable String value) {
    return value == null ? "(missing)" : "'" + value + "'";
  }

  //
  // JSON parsing
  //

  /**
   * Parse JSON text. When strict, the text must conform to RFC 8259, while the tolerant mode uses
   * Gson's lenient parser.
   *
   * <p>In both modes, if an object has duplicate keys the last value is kept: Gson does not report
   * duplicate keys when building a {@link JsonElement}.
   */
  JsonElement parse(Reader reader) {
    if (!strict) {
      return JsonParser.parseReader(reader);
    }
    JsonReader jsonReader = new JsonReader(reader);
    jsonReader.setStrictness(Strictness.STRICT);
    try {
      JsonElement result = JsonParser.parseReader(jsonReader);
      if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
        throw new JsonSyntaxException("Unexpected content after the end of the JSON document");
      }
      return result;
    } catch (MalformedJsonException e) {
      throw new JsonSyntaxException(e);
    } catch (IOException e) {
      throw new DeserializationException("Cannot read JSON: " + e.getMessage());
    }
  }

  private void checkNoExtraKeys(JsonObject jsonObject, Collection<String> expectedKeys) {
    Collection<String> extraKeys = new HashSet<>(jsonObject.keySet());
    extraKeys.removeAll(expectedKeys);
    if (!extraKeys.isEmpty()) {
      throw new RuntimeException(
          "Extra keys found: " + extraKeys + ". Expected keys: " + expectedKeys);
    }
  }

  private void requireIsString(JsonElement jsonElement, String desc) {
    if (jsonElement == null
        || !jsonElement.isJsonPrimitive()
        || !jsonElement.getAsJsonPrimitive().isString()) {
      throw new RuntimeException(desc + " should be present and be a string value");
    }
  }

  /** A chunk does not respect the structure prescribed by the specification. */
  private static class InvalidChunkException extends DeserializationException {
    InvalidChunkException(String message) {
      super(message);
    }
  }
}
