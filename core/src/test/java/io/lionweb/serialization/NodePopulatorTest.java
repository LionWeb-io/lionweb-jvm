package io.lionweb.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.lionweb.LionWebVersion;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.lioncore.LionCore;
import io.lionweb.model.ClassifierInstanceUtils;
import io.lionweb.model.impl.DynamicNode;
import io.lionweb.serialization.data.SerializationChunk;
import io.lionweb.serialization.data.SerializedClassifierInstance;
import java.util.Collections;
import org.junit.jupiter.api.Test;

public class NodePopulatorTest {

  @Test
  public void populateReferenceToBuiltinsValueWithCorrectID() {
    AbstractSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    DeserializationStatus deserializationStatus =
        new DeserializationStatus(
            Collections.emptyList(),
            serialization.getInstanceResolver(),
            serialization.dataTypesValuesSerialization);
    NodePopulator nodePopulator =
        new NodePopulator(
            serialization, serialization.getInstanceResolver(), deserializationStatus);

    SerializationChunk chunk =
        new LowLevelJsonSerialization()
            .deserializeSerializationBlock(
                "{\n"
                    + "  \"serializationFormatVersion\": \"2024.1\",\n"
                    + "  \"languages\": [{\"key\": \"LionCore-M3\", \"version\": \"2024.1\"}],\n"
                    + "  \"nodes\": [\n"
                    + "    {\n"
                    + "      \"id\": \"my-node\",\n"
                    + "      \"classifier\": {\n"
                    + "        \"language\": \"LionCore-M3\",\n"
                    + "        \"version\": \"2024.1\",\n"
                    + "        \"key\": \"Property\"\n"
                    + "      },\n"
                    + "      \"properties\": [],\n"
                    + "      \"containments\": [],\n"
                    + "      \"references\": [\n"
                    + "        {\n"
                    + "          \"reference\": {\n"
                    + "            \"language\": \"LionCore-M3\",\n"
                    + "            \"version\": \"2024.1\",\n"
                    + "            \"key\": \"Property-type\"\n"
                    + "          },\n"
                    + "          \"targets\": [\n"
                    + "            {\n"
                    + "              \"resolveInfo\": \"LionWeb.LionCore_builtins.Boolean\",\n"
                    + "              \"reference\": \"LionCore-builtins-Boolean-2024-1\"\n"
                    + "            }\n"
                    + "          ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"parent\": \"io-lionweb-Properties-BooleanValue\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}");
    SerializedClassifierInstance serializedNode = chunk.getClassifierInstances().get(0);

    DynamicNode node = new DynamicNode("my-node", LionCore.getProperty());
    nodePopulator.populateClassifierInstance(node, serializedNode);

    assertEquals(
        LionCoreBuiltins.getBoolean(),
        ClassifierInstanceUtils.getOnlyReferenceValueByReferenceName(node, "type").getReferred());
  }

  @Test
  public void populateReferenceToBuiltinsValueWithIncorrectID() {
    AbstractSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    DeserializationStatus deserializationStatus =
        new DeserializationStatus(
            Collections.emptyList(),
            serialization.getInstanceResolver(),
            serialization.dataTypesValuesSerialization);
    NodePopulator nodePopulator =
        new NodePopulator(
            serialization, serialization.getInstanceResolver(), deserializationStatus);

    SerializationChunk chunk =
        LowLevelJsonSerialization.tolerant()
            .deserializeSerializationBlock(
                "{\n"
                    + "  \"serializationFormatVersion\": \"2024.1\",\n"
                    + "  \"languages\": [{\"key\": \"LionCore-M3\", \"version\": \"2024.1\"}],\n"
                    + "  \"nodes\": [\n"
                    + "    {\n"
                    + "      \"id\": \"my-node\",\n"
                    + "      \"classifier\": {\n"
                    + "        \"language\": \"LionCore-M3\",\n"
                    + "        \"version\": \"2024.1\",\n"
                    + "        \"key\": \"Property\"\n"
                    + "      },\n"
                    + "      \"properties\": [],\n"
                    + "      \"containments\": [],\n"
                    + "      \"references\": [\n"
                    + "        {\n"
                    + "          \"reference\": {\n"
                    + "            \"language\": \"LionCore-M3\",\n"
                    + "            \"version\": \"2024.1\",\n"
                    + "            \"key\": \"Property-type\"\n"
                    + "          },\n"
                    + "          \"targets\": [\n"
                    + "            {\n"
                    + "              \"resolveInfo\": \"LionWeb.LionCore_builtins.Boolean\",\n"
                    + "              \"reference\": \""
                    + LionCoreBuiltins.getInstance(LionWebVersion.v2023_1)
                    + "\"\n"
                    + "            }\n"
                    + "          ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"parent\": \"io-lionweb-Properties-BooleanValue\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}");
    SerializedClassifierInstance serializedNode = chunk.getClassifierInstances().get(0);

    try {
      DynamicNode node = new DynamicNode("my-node", LionCore.getProperty());
      nodePopulator.populateClassifierInstance(node, serializedNode);
      fail("Exception was expected");
    } catch (DeserializationException t) {
      t.printStackTrace();
    }
  }

  @Test
  public void populateReferenceToBuiltinsValueWithNoID() {
    AbstractSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    DeserializationStatus deserializationStatus =
        new DeserializationStatus(
            Collections.emptyList(),
            serialization.getInstanceResolver(),
            serialization.dataTypesValuesSerialization);
    NodePopulator nodePopulator =
        new NodePopulator(
            serialization,
            serialization.getInstanceResolver(),
            deserializationStatus,
            LionWebVersion.v2024_1);

    SerializationChunk chunk =
        new LowLevelJsonSerialization()
            .deserializeSerializationBlock(
                "{\n"
                    + "  \"serializationFormatVersion\": \"2024.1\",\n"
                    + "  \"languages\": [{\"key\": \"LionCore-M3\", \"version\": \"2024.1\"}],\n"
                    + "  \"nodes\": [\n"
                    + "    {\n"
                    + "      \"id\": \"my-node\",\n"
                    + "      \"classifier\": {\n"
                    + "        \"language\": \"LionCore-M3\",\n"
                    + "        \"version\": \"2024.1\",\n"
                    + "        \"key\": \"Property\"\n"
                    + "      },\n"
                    + "      \"properties\": [],\n"
                    + "      \"containments\": [],\n"
                    + "      \"references\": [\n"
                    + "        {\n"
                    + "          \"reference\": {\n"
                    + "            \"language\": \"LionCore-M3\",\n"
                    + "            \"version\": \"2024.1\",\n"
                    + "            \"key\": \"Property-type\"\n"
                    + "          },\n"
                    + "          \"targets\": [\n"
                    + "            {\n"
                    + "              \"resolveInfo\": \"LionWeb.LionCore_builtins.Boolean\",\n"
                    + "              \"reference\": null\n"
                    + "            }\n"
                    + "          ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"parent\": \"io-lionweb-Properties-BooleanValue\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}");
    SerializedClassifierInstance serializedNode = chunk.getClassifierInstances().get(0);

    DynamicNode node = new DynamicNode("my-node", LionCore.getProperty());
    nodePopulator.populateClassifierInstance(node, serializedNode);

    assertEquals(
        LionCoreBuiltins.getBoolean(),
        ClassifierInstanceUtils.getOnlyReferenceValueByReferenceName(node, "type").getReferred());
  }
}
