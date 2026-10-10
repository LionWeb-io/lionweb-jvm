package io.lionweb.client.delta;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.lionweb.client.delta.messages.AdditionalInfo;
import io.lionweb.client.delta.messages.BaseDeltaEvent;
import io.lionweb.client.delta.messages.DeltaCommand;
import io.lionweb.client.delta.messages.DeltaEvent;
import io.lionweb.client.delta.messages.DeltaQuery;
import io.lionweb.client.delta.messages.DeltaQueryResponse;
import io.lionweb.serialization.data.MetaPointer;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import io.lionweb.serialization.data.SerializedContainmentValue;
import io.lionweb.serialization.data.SerializedPropertyValue;
import io.lionweb.serialization.data.SerializedReferenceValue;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serializes and deserializes delta protocol JSON messages to/from their corresponding Java
 * classes.
 *
 * <p>Uses the {@code messageKind} discriminator field to determine the target type. Returns null
 * for unknown or unimplemented message kinds (custom_*, Chunked*, etc.).
 *
 * <p>The map from messageKind to class is built once at class-load time by scanning the {@code
 * io.lionweb.client.delta.messages} package. For every concrete subclass of {@link DeltaCommand},
 * {@link DeltaEvent}, {@link DeltaQuery}, or {@link DeltaQueryResponse} the simple class name is
 * used as the messageKind key. The single exception is {@code NoOp}, whose messageKind in the
 * protocol is {@code "NoOpEvent"}.
 */
public class DeltaMessageSerialization {

  private static final String PKG = "io.lionweb.client.delta.messages.";

  // Relative class names within PKG. Simple name == messageKind for all except NoOp → NoOpEvent.
  private static final String[] CLASS_NAMES = {
    "commands.partitions.AddPartition",
    "commands.partitions.DeletePartition",
    "commands.ChangeClassifier",
    "commands.CompositeCommand",
    "commands.children.AddChild",
    "commands.children.DeleteChild",
    "commands.children.MoveChildInSameContainment",
    "commands.children.MoveChildFromOtherContainment",
    "commands.children.MoveChildFromOtherContainmentInSameParent",
    "commands.children.MoveAndReplaceChildInSameContainment",
    "commands.children.MoveAndReplaceChildFromOtherContainment",
    "commands.children.MoveAndReplaceChildFromOtherContainmentInSameParent",
    "commands.children.ReplaceChild",
    "commands.properties.AddProperty",
    "commands.properties.ChangeProperty",
    "commands.properties.DeleteProperty",
    "commands.references.AddReference",
    "commands.references.ChangeReference",
    "commands.references.DeleteReference",
    "commands.annotations.AddAnnotation",
    "commands.annotations.DeleteAnnotation",
    "commands.annotations.ReplaceAnnotation",
    "commands.annotations.MoveAnnotationInSameParent",
    "commands.annotations.MoveAnnotationFromOtherParent",
    "commands.annotations.MoveAndReplaceAnnotationInSameParent",
    "commands.annotations.MoveAndReplaceAnnotationFromOtherParent",
    "events.partitions.PartitionAdded",
    "events.partitions.PartitionDeleted",
    "events.ClassifierChanged",
    "events.CompositeEvent",
    "events.ErrorEvent",
    "events.NoOp",
    "events.children.ChildAdded",
    "events.children.ChildDeleted",
    "events.children.ChildMovedInSameContainment",
    "events.children.ChildMovedFromOtherContainment",
    "events.children.ChildMovedFromOtherContainmentInSameParent",
    "events.children.ChildMovedAndReplacedInSameContainment",
    "events.children.ChildMovedAndReplacedFromOtherContainment",
    "events.children.ChildMovedAndReplacedFromOtherContainmentInSameParent",
    "events.children.ChildReplaced",
    "events.properties.PropertyAdded",
    "events.properties.PropertyChanged",
    "events.properties.PropertyDeleted",
    "events.references.ReferenceAdded",
    "events.references.ReferenceChanged",
    "events.references.ReferenceDeleted",
    "events.annotations.AnnotationAdded",
    "events.annotations.AnnotationDeleted",
    "events.annotations.AnnotationReplaced",
    "events.annotations.AnnotationMovedInSameParent",
    "events.annotations.AnnotationMovedFromOtherParent",
    "events.annotations.AnnotationMovedAndReplacedInSameParent",
    "events.annotations.AnnotationMovedAndReplacedFromOtherParent",
    "queries.participations.SignOnRequest",
    "queries.participations.SignOnResponse",
    "queries.participations.SignOffRequest",
    "queries.participations.SignOffResponse",
    "queries.participations.ReconnectRequest",
    "queries.participations.ReconnectResponse",
    "queries.ListPartitionsRequest",
    "queries.ListPartitionsResponse",
    "queries.ListAndSubscribePartitionsRequest",
    "queries.ListAndSubscribePartitionsResponse",
    "queries.GetAvailableIdsRequest",
    "queries.GetAvailableIdsResponse",
    "queries.ErrorResponse",
    "queries.InformAboutChangingPartitionsRequest",
    "queries.InformAboutChangingPartitionsResponse",
    "queries.subscriptions.SubscribeToPartitionContentsRequest",
    "queries.subscriptions.SubscribeToPartitionContentsResponse",
    "queries.subscriptions.SubscribeToChangingPartitionsRequest",
    "queries.subscriptions.SubscribeToChangingPartitionsResponse",
    "queries.subscriptions.UnsubscribeFromPartitionContentsRequest",
    "queries.subscriptions.UnsubscribeFromPartitionContentsResponse",
  };

  private static final Map<String, Class<?>> KIND_TO_CLASS = new HashMap<>();

  /** Reverse map from Java class to messageKind string, used when serializing. */
  private static final Map<Class<?>, String> CLASS_TO_KIND = new HashMap<>();

  static {
    for (String name : CLASS_NAMES) {
      try {
        Class<?> clazz = Class.forName(PKG + name);
        String kind = clazz.getSimpleName();
        KIND_TO_CLASS.put(kind, clazz);
        CLASS_TO_KIND.put(clazz, kind);
      } catch (ClassNotFoundException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
    // NoOp is the one class whose simple name differs from its messageKind
    Class<?> noOpClass = KIND_TO_CLASS.remove("NoOp");
    KIND_TO_CLASS.put("NoOpEvent", noOpClass);
    CLASS_TO_KIND.put(noOpClass, "NoOpEvent");
  }

  private final Gson gson;

  public DeltaMessageSerialization() {
    this.gson =
        new GsonBuilder()
            // Required so that "parent": null in nodes survives the toJsonTree() → write() path.
            // Optional fields that must be absent when null (split, distribute) are handled
            // explicitly: split is filtered in createMessageKindAdapter; distribute by
            // AdditionalInfoAdapter.
            .serializeNulls()
            .registerTypeAdapterFactory(new DeltaMessageTypeAdapterFactory())
            .create();
  }

  /**
   * Deserializes a delta message from the given JSON string.
   *
   * @return the deserialized object, or null if the messageKind is unknown/unimplemented
   */
  @Nullable
  public Object deserialize(@NotNull String json) {
    return deserialize(JsonParser.parseString(json));
  }

  /**
   * Deserializes a delta message from the given Reader.
   *
   * @return the deserialized object, or null if the messageKind is unknown/unimplemented
   */
  @Nullable
  public Object deserialize(@NotNull Reader reader) {
    return deserialize(JsonParser.parseReader(reader));
  }

  /**
   * Deserializes a delta message from the given File.
   *
   * @return the deserialized object, or null if the messageKind is unknown/unimplemented
   */
  @Nullable
  public Object deserialize(@NotNull File file) throws IOException {
    try (FileReader reader = new FileReader(file)) {
      return deserialize(reader);
    }
  }

  /** Serializes a delta message object back to a JSON string. */
  public String serialize(Object message) {
    return gson.toJson(message);
  }

  /** Returns true if the given messageKind has a registered Java class. */
  public boolean isKnownKind(String messageKind) {
    return KIND_TO_CLASS.containsKey(messageKind);
  }

  /** Returns the Java class registered for a given messageKind, or null if unknown. */
  @Nullable
  public Class<?> getClassForKind(String messageKind) {
    return KIND_TO_CLASS.get(messageKind);
  }

  /** Returns true if the target class is a DeltaCommand subclass. */
  public static boolean isCommandClass(Class<?> clazz) {
    return DeltaCommand.class.isAssignableFrom(clazz);
  }

  /** Returns true if the target class is a DeltaEvent subclass. */
  public static boolean isEventClass(Class<?> clazz) {
    return DeltaEvent.class.isAssignableFrom(clazz);
  }

  /** Returns true if the target class is a DeltaQuery or DeltaQueryResponse subclass. */
  public static boolean isQueryClass(Class<?> clazz) {
    return DeltaQuery.class.isAssignableFrom(clazz)
        || DeltaQueryResponse.class.isAssignableFrom(clazz);
  }

  @Nullable
  private Object deserialize(@NotNull JsonElement element) {
    if (!element.isJsonObject()) {
      throw new JsonParseException("Delta message must be a JSON object");
    }
    JsonObject obj = element.getAsJsonObject();
    JsonElement kindElement = obj.get("messageKind");
    if (kindElement == null || kindElement.isJsonNull()) {
      throw new JsonParseException("Delta message missing 'messageKind' field");
    }
    String kind = kindElement.getAsString();
    Class<?> targetClass = KIND_TO_CLASS.get(kind);
    if (targetClass == null) {
      return null;
    }
    return gson.fromJson(element, targetClass);
  }

  // ===================================================================
  // TypeAdapterFactory that handles all delta-specific serialization
  // ===================================================================

  private static final class DeltaMessageTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      Class<?> raw = type.getRawType();

      // DeltaProtocolVersion — serialize as wire string (e.g. "2026.1") not enum name
      if (DeltaProtocolVersion.class == raw) {
        return (TypeAdapter<T>) new DeltaProtocolVersionAdapter();
      }

      // Polymorphic abstract types — used for CompositeCommand.parts and CompositeEvent.parts
      if (DeltaCommand.class == raw) {
        return (TypeAdapter<T>) createPolymorphicAdapter(gson, DeltaCommand.class);
      }
      if (DeltaEvent.class == raw || BaseDeltaEvent.class == raw) {
        return (TypeAdapter<T>) createPolymorphicAdapter(gson, DeltaEvent.class);
      }

      // AdditionalInfo — distribute field is optional: omit when null, include when false/true
      if (AdditionalInfo.class == raw) {
        return (TypeAdapter<T>) new AdditionalInfoAdapter();
      }

      // MetaPointer — uses interning factory, must go through custom adapter
      if (MetaPointer.class == raw) {
        return (TypeAdapter<T>) new MetaPointerAdapter();
      }

      // SerializationChunk — delta format omits serializationFormatVersion and languages
      if (SerializationChunk.class == raw) {
        return (TypeAdapter<T>) new SerializationChunkAdapter(gson);
      }

      // SerializedClassifierInstance — needs custom write for "parent": null on root nodes;
      // read delegates to Gson reflection (works because @SerializedName("parent") is on the field)
      if (SerializedClassifierInstance.class == raw) {
        TypeAdapter<SerializedClassifierInstance> delegate =
            gson.getDelegateAdapter(this, TypeToken.get(SerializedClassifierInstance.class));
        return (TypeAdapter<T>) new SerializedClassifierInstanceAdapter(gson, delegate);
      }

      // Concrete delta classes — inject messageKind field on serialization
      String kind = CLASS_TO_KIND.get(raw);
      if (kind != null) {
        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
        TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
        return createMessageKindAdapter(delegate, elementAdapter, kind);
      }

      return null;
    }

    /** Creates an adapter that reads {@code messageKind} and dispatches to the concrete class. */
    private static <T> TypeAdapter<T> createPolymorphicAdapter(Gson gson, Class<T> baseType) {
      return new TypeAdapter<T>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          if (value == null) {
            out.nullValue();
            return;
          }
          // The concrete class adapter already injects messageKind
          @SuppressWarnings("unchecked")
          TypeAdapter<T> concreteAdapter = (TypeAdapter<T>) gson.getAdapter(value.getClass());
          concreteAdapter.write(out, value);
        }

        @Override
        public T read(JsonReader in) throws IOException {
          JsonElement elem = JsonParser.parseReader(in);
          if (elem.isJsonNull()) return null;
          JsonObject obj = elem.getAsJsonObject();
          JsonElement kindElem = obj.get("messageKind");
          if (kindElem == null || kindElem.isJsonNull()) return null;
          String kind = kindElem.getAsString();
          Class<?> targetClass = KIND_TO_CLASS.get(kind);
          if (targetClass == null || !baseType.isAssignableFrom(targetClass)) return null;
          @SuppressWarnings("unchecked")
          T result = (T) gson.fromJson(obj, targetClass);
          return result;
        }
      };
    }

    /**
     * Wraps a delegate adapter to prepend the {@code messageKind} field when serializing, so
     * round-trip JSON includes the discriminator. Null-valued {@code split} entries are omitted
     * because {@code split} is optional and absent-when-false by protocol convention.
     */
    private static <T> TypeAdapter<T> createMessageKindAdapter(
        TypeAdapter<T> delegate, TypeAdapter<JsonElement> elementAdapter, String kind) {
      return new TypeAdapter<T>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          if (value == null) {
            out.nullValue();
            return;
          }
          JsonElement tree = delegate.toJsonTree(value);
          JsonObject src = tree.getAsJsonObject();
          // Build a new object with messageKind as the first entry
          JsonObject result = new JsonObject();
          result.addProperty("messageKind", kind);
          for (Map.Entry<String, JsonElement> entry : src.entrySet()) {
            // split is optional — omit it when false
            if ("split".equals(entry.getKey())
                && entry.getValue().isJsonPrimitive()
                && !entry.getValue().getAsBoolean()) {
              continue;
            }
            result.add(entry.getKey(), entry.getValue());
          }
          elementAdapter.write(out, result);
        }

        @Override
        public T read(JsonReader in) throws IOException {
          return delegate.read(in);
        }
      };
    }
  }

  // ===================================================================
  // TypeAdapter for DeltaProtocolVersion
  // ===================================================================

  private static final class DeltaProtocolVersionAdapter extends TypeAdapter<DeltaProtocolVersion> {
    @Override
    public void write(JsonWriter out, DeltaProtocolVersion value) throws IOException {
      if (value == null) {
        out.nullValue();
      } else {
        out.value(value.toWireString());
      }
    }

    @Override
    public DeltaProtocolVersion read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      return DeltaProtocolVersion.fromWireString(in.nextString());
    }
  }

  // ===================================================================
  // TypeAdapter for MetaPointer
  // ===================================================================

  /** Reads and writes a MetaPointer as {@code {"language": ..., "version": ..., "key": ...}}. */
  private static final class MetaPointerAdapter extends TypeAdapter<MetaPointer> {
    @Override
    public void write(JsonWriter out, MetaPointer mp) throws IOException {
      if (mp == null) {
        out.nullValue();
        return;
      }
      out.beginObject();
      out.name("language").value(mp.getLanguage());
      out.name("version").value(mp.getVersion());
      out.name("key").value(mp.getKey());
      out.endObject();
    }

    @Override
    public MetaPointer read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
      return MetaPointer.get(str(o, "language"), str(o, "version"), str(o, "key"));
    }
  }

  // ===================================================================
  // TypeAdapter for SerializationChunk
  // ===================================================================

  /**
   * Reads and writes a SerializationChunk as {@code {"nodes": [...]}}. The delta protocol format
   * omits the {@code serializationFormatVersion} and {@code languages} fields present in the full
   * LionWeb JSON serialization format.
   */
  private static final class SerializationChunkAdapter extends TypeAdapter<SerializationChunk> {
    private final Gson gson;

    SerializationChunkAdapter(Gson gson) {
      this.gson = gson;
    }

    @Override
    public void write(JsonWriter out, SerializationChunk chunk) throws IOException {
      if (chunk == null) {
        out.nullValue();
        return;
      }
      TypeAdapter<SerializedClassifierInstance> nodeAdapter =
          gson.getAdapter(SerializedClassifierInstance.class);
      out.beginObject();
      out.name("nodes");
      out.beginArray();
      for (SerializedClassifierInstance node : chunk.getClassifierInstances()) {
        nodeAdapter.write(out, node);
      }
      out.endArray();
      out.endObject();
    }

    @Override
    public SerializationChunk read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      TypeAdapter<SerializedClassifierInstance> nodeAdapter =
          gson.getAdapter(SerializedClassifierInstance.class);
      JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
      SerializationChunk chunk = new SerializationChunk();
      JsonElement nodesEl = o.get("nodes");
      if (nodesEl != null && nodesEl.isJsonArray()) {
        for (JsonElement e : nodesEl.getAsJsonArray()) {
          SerializedClassifierInstance node = nodeAdapter.fromJsonTree(e);
          if (node != null) chunk.addClassifierInstance(node);
        }
      }
      return chunk;
    }
  }

  // ===================================================================
  // TypeAdapter for SerializedClassifierInstance
  // ===================================================================

  /**
   * Custom write for SerializedClassifierInstance: null collections are written as empty arrays,
   * and {@code parentNodeID} (mapped to JSON key {@code "parent"} via {@code @SerializedName}) is
   * always written — including as explicit {@code null} for partition roots (safe because
   * serializeNulls() is enabled globally). Read delegates to Gson's reflection adapter, which
   * correctly maps {@code "parent"} via the annotation.
   */
  private static final class SerializedClassifierInstanceAdapter
      extends TypeAdapter<SerializedClassifierInstance> {
    private final TypeAdapter<SerializedClassifierInstance> delegate;
    private final TypeAdapter<MetaPointer> mpAdapter;
    private final TypeAdapter<SerializedPropertyValue> propAdapter;
    private final TypeAdapter<SerializedContainmentValue> containmentAdapter;
    private final TypeAdapter<SerializedReferenceValue> referenceAdapter;

    SerializedClassifierInstanceAdapter(
        Gson gson, TypeAdapter<SerializedClassifierInstance> delegate) {
      this.delegate = delegate;
      this.mpAdapter = gson.getAdapter(MetaPointer.class);
      this.propAdapter = gson.getAdapter(SerializedPropertyValue.class);
      this.containmentAdapter = gson.getAdapter(SerializedContainmentValue.class);
      this.referenceAdapter = gson.getAdapter(SerializedReferenceValue.class);
    }

    @Override
    public void write(JsonWriter out, SerializedClassifierInstance node) throws IOException {
      if (node == null) {
        out.nullValue();
        return;
      }
      out.beginObject();
      out.name("id").value(node.getID());
      out.name("classifier");
      mpAdapter.write(out, node.getClassifier());
      writeArray(out, "properties", node.getProperties(), propAdapter);
      writeArray(out, "containments", node.getContainments(), containmentAdapter);
      writeArray(out, "references", node.getReferences(), referenceAdapter);
      out.name("annotations");
      out.beginArray();
      for (String ann : node.getAnnotations()) out.value(ann);
      out.endArray();
      // serializeNulls() is enabled globally, so value(null) writes "parent": null for roots
      out.name("parent").value(node.getParentNodeID());
      out.endObject();
    }

    @Override
    public SerializedClassifierInstance read(JsonReader in) throws IOException {
      return delegate.read(in);
    }

    private static <T> void writeArray(
        JsonWriter out, String name, Iterable<T> items, TypeAdapter<T> adapter) throws IOException {
      out.name(name);
      out.beginArray();
      for (T item : items) adapter.write(out, item);
      out.endArray();
    }
  }

  // ===================================================================
  // TypeAdapter for AdditionalInfo
  // ===================================================================

  /**
   * Reads and writes an {@link AdditionalInfo} entry. The {@code distribute} field is omitted
   * entirely when {@code null} — it must only appear in the JSON when explicitly set to {@code
   * true} or {@code false}, because the global {@code serializeNulls()} setting would otherwise
   * emit {@code "distribute": null}.
   */
  private static final class AdditionalInfoAdapter extends TypeAdapter<AdditionalInfo> {

    @Override
    public void write(JsonWriter out, AdditionalInfo info) throws IOException {
      if (info == null) {
        out.nullValue();
        return;
      }
      out.beginObject();
      out.name("kind").value(info.kind);
      if (info.distribute) {
        out.name("distribute").value(info.distribute);
      }
      out.name("message").value(info.message);
      if (info.data != null) {
        out.name("data");
        new Gson().toJson(info.data, out);
      }
      out.endObject();
    }

    @Override
    public AdditionalInfo read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
      String kind = str(o, "kind");
      boolean distribute = false;
      JsonElement distributeEl = o.get("distribute");
      if (distributeEl != null && !distributeEl.isJsonNull()) {
        distribute = distributeEl.getAsBoolean();
      }
      String message = str(o, "message");
      JsonElement data = o.get("data");
      return new AdditionalInfo(kind, distribute, message, data);
    }
  }

  // ===================================================================
  // Helpers
  // ===================================================================

  /** Returns the string value of a JSON field, or null if absent or null. */
  private static String str(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e == null || e.isJsonNull() ? null : e.getAsString();
  }
}
