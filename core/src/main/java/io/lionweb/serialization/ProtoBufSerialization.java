package io.lionweb.serialization;

import io.lionweb.LionWebVersion;
import io.lionweb.model.ClassifierInstance;
import io.lionweb.model.impl.ProxyNode;
import io.lionweb.protobuf.*;
import io.lionweb.serialization.data.*;
import io.lionweb.serialization.data.MetaPointer;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

public class ProtoBufSerialization extends AbstractSerialization {

  public ProtoBufSerialization() {
    super();
  }

  public ProtoBufSerialization(@Nonnull LionWebVersion lionWebVersion) {
    super(lionWebVersion);
  }

  public List<io.lionweb.model.Node> deserializeToNodes(byte[] bytes) throws IOException {
    return deserializeToNodes(new ByteArrayInputStream(bytes));
  }

  public SerializationChunk deserializeToChunk(byte[] bytes) throws IOException {
    PBChunk pbChunk = PBChunk.parseFrom(new ByteArrayInputStream(bytes));
    return deserializeSerializationChunk(pbChunk);
  }

  public List<io.lionweb.model.Node> deserializeToNodes(File file) throws IOException {
    return deserializeToNodes(new FileInputStream(file));
  }

  public List<io.lionweb.model.Node> deserializeToNodes(InputStream inputStream)
      throws IOException {
    return deserializeToNodes(PBChunk.parseFrom(inputStream));
  }

  public List<io.lionweb.model.Node> deserializeToNodes(PBChunk chunk) {
    return deserializeToClassifierInstances(chunk).stream()
        .filter(ci -> ci instanceof io.lionweb.model.Node)
        .map(ci -> (io.lionweb.model.Node) ci)
        .collect(Collectors.toList());
  }

  public List<ClassifierInstance<?>> deserializeToClassifierInstances(PBChunk chunk) {
    SerializationChunk serializationBlock = deserializeSerializationChunk(chunk);
    validateSerializationBlock(serializationBlock);
    return deserializeSerializationChunk(serializationBlock);
  }

  private SerializationChunk deserializeSerializationChunk(PBChunk chunk) {
    // Pre-size arrays for better performance
    int stringCount = chunk.getInternedStringsCount();
    int languageCount = chunk.getInternedLanguagesCount();
    int metaPointerCount = chunk.getInternedMetaPointersCount();

    String[] stringsArray = new String[stringCount + 1];
    stringsArray[0] = null;
    for (int i = 0; i < chunk.getInternedStringsCount(); i++) {
      stringsArray[i + 1] = chunk.getInternedStrings(i);
    }
    LanguageVersion[] languagesArray = new LanguageVersion[languageCount + 1];
    languagesArray[0] = null;
    for (int i = 0; i < chunk.getInternedLanguagesCount(); i++) {
      PBLanguage l = chunk.getInternedLanguages(i);
      String key = stringsArray[l.getSiKey()];
      String version = stringsArray[l.getSiVersion()];
      LanguageVersion lv = LanguageVersion.of(key, version);
      languagesArray[i + 1] = lv;
    }
    MetaPointer[] metapointersArray = new MetaPointer[metaPointerCount];
    for (int i = 0; i < chunk.getInternedMetaPointersCount(); i++) {
      PBMetaPointer mp = chunk.getInternedMetaPointers(i);

      if (mp.getLiLanguage() >= languagesArray.length) {
        throw new DeserializationException(
            "Unable to deserialize meta pointer with language " + mp.getLiLanguage());
      }
      LanguageVersion languageVersion = languagesArray[mp.getLiLanguage()];
      MetaPointer metaPointer =
          MetaPointer.get(
              languageVersion.getKey(), languageVersion.getVersion(), stringsArray[mp.getSiKey()]);
      metapointersArray[i] = metaPointer;
    }

    SerializationChunk serializationChunk = new SerializationChunk();
    serializationChunk.setSerializationFormatVersion(chunk.getSerializationFormatVersion());
    for (LanguageVersion languageVersion : languagesArray) {
      if (languageVersion != null) {
        serializationChunk.addLanguage(languageVersion);
      }
    }

    chunk
        .getNodesList()
        .forEach(
            n -> {
              SerializedClassifierInstance sci = new SerializedClassifierInstance();
              sci.setID(stringsArray[n.getSiId()]);
              sci.setParentNodeID(stringsArray[n.getSiParent()]);
              sci.setClassifier(metapointersArray[n.getMpiClassifier()]);
              n.getPropertiesList()
                  .forEach(
                      p -> {
                        SerializedPropertyValue spv =
                            SerializedPropertyValue.get(
                                metapointersArray[p.getMpiMetaPointer()],
                                stringsArray[p.getSiValue()]);
                        sci.unsafeAppendPropertyValue(spv);
                      });
              n.getContainmentsList()
                  .forEach(
                      c -> {
                        List<String> children = new ArrayList<>(c.getSiChildrenList().size());
                        for (int childIndex : c.getSiChildrenList()) {
                          if (childIndex == 0) {
                            throw new DeserializationException(
                                "Unable to deserialize child identified by Null ID");
                          }
                          children.add(stringsArray[childIndex]);
                        }
                        if (serializeEmptyFeatures || !children.isEmpty()) {
                          SerializedContainmentValue scv =
                              new SerializedContainmentValue(
                                  metapointersArray[c.getMpiMetaPointer()], children);
                          sci.unsafeAppendContainmentValue(scv);
                        }
                      });
              n.getReferencesList()
                  .forEach(
                      r -> {
                        SerializedReferenceValue srv =
                            new SerializedReferenceValue(metapointersArray[r.getMpiMetaPointer()]);
                        r.getValuesList()
                            .forEach(
                                rv -> {
                                  SerializedReferenceValue.Entry entry =
                                      new SerializedReferenceValue.Entry();
                                  entry.setReference(stringsArray[rv.getSiReferred()]);
                                  entry.setResolveInfo(stringsArray[rv.getSiResolveInfo()]);
                                  srv.addValue(entry);
                                });
                        if (serializeEmptyFeatures || !srv.getValue().isEmpty()) {
                          sci.unsafeAppendReferenceValue(srv);
                        }
                      });
              n.getSiAnnotationsList().forEach(a -> sci.addAnnotation(stringsArray[a]));
              serializationChunk.addClassifierInstance(sci);
            });
    return serializationChunk;
  }

  public byte[] serializeTreesToByteArray(ClassifierInstance<?>... roots) {
    // Use LinkedHashSet with initial capacity to reduce resizing
    Set<String> nodesIDs = new HashSet<>(1024);
    List<ClassifierInstance<?>> allNodes = new ArrayList<>(1024);

    for (ClassifierInstance<?> root : roots) {
      Set<ClassifierInstance<?>> classifierInstances = new LinkedHashSet<>(512);
      ClassifierInstance.collectSelfAndDescendants(root, true, classifierInstances);

      // Process in batches to reduce memory allocation
      for (ClassifierInstance<?> n : classifierInstances) {
        if (n.getID() != null) {
          if (!nodesIDs.contains(n.getID())) {
            allNodes.add(n);
            nodesIDs.add(n.getID());
          }
        } else {
          allNodes.add(n);
        }
      }
    }

    // Filter out proxy nodes more efficiently
    List<ClassifierInstance<?>> filteredNodes = new ArrayList<>(allNodes.size());
    for (ClassifierInstance<?> node : allNodes) {
      if (!(node instanceof ProxyNode)) {
        filteredNodes.add(node);
      }
    }

    return serializeNodesToByteArray(filteredNodes);
  }

  public byte[] serializeNodesToByteArray(List<ClassifierInstance<?>> classifierInstances) {
    if (classifierInstances.stream().anyMatch(n -> n instanceof ProxyNode)) {
      throw new IllegalArgumentException("Proxy nodes cannot be serialized");
    }
    SerializationChunk serializationBlock = serializeNodesToSerializationChunk(classifierInstances);
    return serializeToByteArray(serializationBlock);
  }

  public byte[] serializeNodesToByteArray(ClassifierInstance<?>... classifierInstances) {
    return serializeNodesToByteArray(Arrays.asList(classifierInstances));
  }

  public byte[] serializeToByteArray(SerializationChunk serializationChunk) {
    return serialize(serializationChunk).toByteArray();
  }

  protected class SerializeHelper {
    // Replace HashMaps with ArrayList for better cache locality and faster access
    private final List<MetaPointer> metaPointers = new ArrayList<>();
    private final List<String> strings = new ArrayList<>();
    private final List<LanguageVersion> languages = new ArrayList<>();

    // Keep reverse lookup maps for indexing only
    private final Map<MetaPointer, Integer> metaPointerIndexMap = new HashMap<>();
    private final Map<String, Integer> stringIndexMap = new HashMap<>();
    private final Map<LanguageVersion, Integer> languageIndexMap = new HashMap<>();

    public List<MetaPointer> getMetaPointers() {
      return metaPointers;
    }

    public List<String> getStrings() {
      return strings;
    }

    public List<LanguageVersion> getLanguages() {
      return languages;
    }

    public SerializeHelper() {
      stringIndexMap.put(null, 0);
      languageIndexMap.put(null, 0);
      strings.add(null);
      languages.add(null);
    }

    public int stringIndexer(String string) {
      if (string == null) {
        return 0;
      }
      if (stringIndexMap.containsKey(string)) {
        return stringIndexMap.get(string);
      }
      int index = strings.size();
      strings.add(string);
      stringIndexMap.put(string, index);
      return index;
    }

    public int languageIndexer(LanguageVersion language) {
      if (language == null) {
        return 0;
      }
      if (languageIndexMap.containsKey(language)) {
        return languageIndexMap.get(language);
      }
      int index = languages.size();
      stringIndexer(language.getKey());
      stringIndexer(language.getVersion());
      languages.add(language);
      languageIndexMap.put(language, index);
      return index;
    }

    public int metaPointerIndexer(MetaPointer metaPointer) {
      if (metaPointerIndexMap.containsKey(metaPointer)) {
        return metaPointerIndexMap.get(metaPointer);
      }
      int index = metaPointers.size();
      languageIndexer(metaPointer.getLanguageVersion());
      stringIndexer(metaPointer.getKey());
      metaPointers.add(metaPointer);
      metaPointerIndexMap.put(metaPointer, index);
      return index;
    }

    public PBNode serializeNode(SerializedClassifierInstance n) {
      PBNode.Builder nodeBuilder = PBNode.newBuilder();
      // If it is zero we should not set the field at all, so the present bit will not be set
      if (n.getID() != null) {
        nodeBuilder.setSiId(this.stringIndexer(n.getID()));
      }
      if (n.getParentNodeID() != null) {
        nodeBuilder.setSiParent(this.stringIndexer(n.getParentNodeID()));
      }
      nodeBuilder.setMpiClassifier(this.metaPointerIndexer((n.getClassifier())));
      n.getProperties()
          .forEach(
              p -> {
                PBProperty.Builder b = PBProperty.newBuilder();
                if (p.getValue() != null) {
                  b.setSiValue(this.stringIndexer(p.getValue()));
                }
                b.setMpiMetaPointer(this.metaPointerIndexer(p.getMetaPointer()));
                nodeBuilder.addProperties(b.build());
              });
      n.getContainments()
          .forEach(
              p ->
                  nodeBuilder.addContainments(
                      PBContainment.newBuilder()
                          .addAllSiChildren(
                              p.getChildrenIds().stream()
                                  .map(this::stringIndexer)
                                  .collect(Collectors.toList()))
                          .setMpiMetaPointer(this.metaPointerIndexer(p.getMetaPointer()))
                          .build()));
      n.getReferences()
          .forEach(
              p ->
                  nodeBuilder.addReferences(
                      PBReference.newBuilder()
                          .addAllValues(
                              p.getValue().stream()
                                  .map(
                                      rf -> {
                                        PBReferenceValue.Builder b = PBReferenceValue.newBuilder();
                                        if (rf.getReference() != null) {
                                          b.setSiReferred(this.stringIndexer(rf.getReference()));
                                        }
                                        if (rf.getResolveInfo() != null) {
                                          b.setSiResolveInfo(
                                              this.stringIndexer(rf.getResolveInfo()));
                                        }
                                        return b.build();
                                      })
                                  .collect(Collectors.toList()))
                          .setMpiMetaPointer(this.metaPointerIndexer(p.getMetaPointer()))
                          .build()));
      n.getAnnotations().forEach(a -> nodeBuilder.addSiAnnotations(this.stringIndexer(a)));
      return nodeBuilder.build();
    }
  }

  public PBChunk serializeTree(ClassifierInstance<?> classifierInstance) {
    if (classifierInstance instanceof ProxyNode) {
      throw new IllegalArgumentException("Proxy nodes cannot be serialized");
    }
    Set<ClassifierInstance<?>> classifierInstances = new LinkedHashSet<>();
    ClassifierInstance.collectSelfAndDescendants(classifierInstance, true, classifierInstances);

    SerializationChunk serializationChunk =
        serializeNodesToSerializationChunk(
            classifierInstances.stream()
                .filter(n -> !(n instanceof ProxyNode))
                .collect(Collectors.toList()));
    return serialize(serializationChunk);
  }

  public PBChunk serialize(SerializationChunk serializationChunk) {
    PBChunk.Builder chunkBuilder = PBChunk.newBuilder();
    chunkBuilder.setSerializationFormatVersion(serializationChunk.getSerializationFormatVersion());
    SerializeHelper serializeHelper = new SerializeHelper();

    // Process all nodes first to build indices
    List<SerializedClassifierInstance> instances = serializationChunk.getClassifierInstances();
    for (SerializedClassifierInstance instance : instances) {
      chunkBuilder.addNodes(serializeHelper.serializeNode(instance));
    }

    // We need to process languages before strings, otherwise we might end up with null pointers
    for (LanguageVersion languageVersion : serializeHelper.languages) {
      if (languageVersion != null) {
        PBLanguage.Builder languageBuilder = PBLanguage.newBuilder();
        if (languageVersion.getKey() != null) {
          languageBuilder.setSiKey(serializeHelper.stringIndexer(languageVersion.getKey()));
        }
        if (languageVersion.getVersion() != null) {
          languageBuilder.setSiVersion(serializeHelper.stringIndexer(languageVersion.getVersion()));
        }
        chunkBuilder.addInternedLanguages(languageBuilder.build());
      }
    }

    for (String string : serializeHelper.strings) {
      if (string != null) {
        chunkBuilder.addInternedStrings(string);
      }
    }
    for (MetaPointer metaPointer : serializeHelper.metaPointers) {
      PBMetaPointer.Builder metaPointerBuilder =
          PBMetaPointer.newBuilder()
              .setLiLanguage(serializeHelper.languageIndexer(metaPointer.getLanguageVersion()));
      if (metaPointer.getKey() != null) {
        metaPointerBuilder.setSiKey(serializeHelper.stringIndexer(metaPointer.getKey()));
      }
      chunkBuilder.addInternedMetaPointers(metaPointerBuilder.build());
    }

    return chunkBuilder.build();
  }
}
