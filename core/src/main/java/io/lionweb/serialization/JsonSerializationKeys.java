package io.lionweb.serialization;

/** The names of the members of the JSON serialization format. */
public final class JsonSerializationKeys {

  private JsonSerializationKeys() {}

  // Chunk
  public static final String SERIALIZATION_FORMAT_VERSION = "serializationFormatVersion";
  public static final String LANGUAGES = "languages";
  public static final String NODES = "nodes";

  // Language and meta-pointer
  public static final String KEY = "key";
  public static final String VERSION = "version";
  public static final String LANGUAGE = "language";

  // Node
  public static final String ID = "id";
  public static final String CLASSIFIER = "classifier";
  public static final String PROPERTIES = "properties";
  public static final String CONTAINMENTS = "containments";
  public static final String REFERENCES = "references";
  public static final String ANNOTATIONS = "annotations";
  public static final String PARENT = "parent";

  // Property, containment and reference entries
  public static final String PROPERTY = "property";
  public static final String VALUE = "value";
  public static final String CONTAINMENT = "containment";
  public static final String CHILDREN = "children";
  public static final String REFERENCE = "reference";
  public static final String TARGETS = "targets";

  // Reference target
  public static final String RESOLVE_INFO = "resolveInfo";
}
