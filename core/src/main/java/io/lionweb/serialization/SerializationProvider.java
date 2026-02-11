package io.lionweb.serialization;

import io.lionweb.LionWebVersion;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.lioncore.LionCore;
import java.util.Objects;
import javax.annotation.Nonnull;

public class SerializationProvider {

  /** This has specific support for LionCore or LionCoreBuiltins. */
  public static JsonSerialization getStandardJsonSerialization() {
    return getStandardJsonSerialization(LionWebVersion.currentVersion);
  }

  /** This has specific support for LionCore or LionCoreBuiltins. */
  public static JsonSerialization getStandardJsonSerialization(
      @Nonnull LionWebVersion lionWebVersion) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    JsonSerialization serialization = new JsonSerialization(lionWebVersion);
    standardInitialization(serialization);
    return serialization;
  }

  /**
   * Same as {@link #getStandardJsonSerialization(LionWebVersion)}, but {@link
   * AbstractSerialization#serializeEmptyFeatures omits empty features}.
   */
  public static JsonSerialization getEfficientJsonSerialization(
      @Nonnull LionWebVersion lionWebVersion) {
    JsonSerialization serialization = getStandardJsonSerialization(lionWebVersion);
    serialization.setSerializeEmptyFeatures(false);
    return serialization;
  }

  /** This has no specific support for LionCore or LionCoreBuiltins. */
  public static JsonSerialization getBasicJsonSerialization() {
    return new JsonSerialization();
  }

  public static JsonSerialization getBasicJsonSerialization(
      @Nonnull LionWebVersion lionWebVersion) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    return new JsonSerialization(lionWebVersion);
  }

  /** This has specific support for LionCore or LionCoreBuiltins. */
  public static ProtoBufSerialization getStandardProtoBufSerialization(
      @Nonnull LionWebVersion lionWebVersion) {
    ProtoBufSerialization serialization = new ProtoBufSerialization(lionWebVersion);
    standardInitialization(serialization);
    return serialization;
  }

  /** This has specific support for LionCore or LionCoreBuiltins. */
  public static ProtoBufSerialization getStandardProtoBufSerialization() {
    return getStandardProtoBufSerialization(LionWebVersion.currentVersion);
  }

  /**
   * Same as {@link #getStandardProtoBufSerialization(LionWebVersion)}, but {@link
   * AbstractSerialization#serializeEmptyFeatures omits empty features}.
   */
  public static ProtoBufSerialization getEfficientProtoBufSerialization(
      @Nonnull LionWebVersion lionWebVersion) {
    ProtoBufSerialization serialization = getStandardProtoBufSerialization(lionWebVersion);
    serialization.setSerializeEmptyFeatures(false);
    return serialization;
  }

  /** This has no specific support for LionCore or LionCoreBuiltins. */
  public static ProtoBufSerialization getBasicProtoBufSerialization(
      @Nonnull LionWebVersion lionWebVersion) {
    return new ProtoBufSerialization(lionWebVersion);
  }

  /** This has no specific support for LionCore or LionCoreBuiltins. */
  public static ProtoBufSerialization getBasicProtoBufSerialization() {
    return new ProtoBufSerialization();
  }

  /**
   * In most cases you may want not to call this method directly but call
   * getStandardJsonSerialization instead.
   */
  public static void standardInitialization(AbstractSerialization serialization) {
    standardInitialization(serialization, serialization.getLionWebVersion());
  }

  /**
   * In most cases you may want not to call this method directly but call
   * getStandardJsonSerialization instead.
   *
   * <p>This method allows to consider core languages from a different LionWeb Version than the one
   * used for serialization. This may be useful in the context of upgrading or downgrading the
   * LionWeb Version.
   */
  public static void standardInitialization(
      AbstractSerialization serialization, LionWebVersion coreLanguagesVersion) {
    serialization.classifierResolver.registerLanguage(LionCore.getInstance(coreLanguagesVersion));
    serialization.instantiator.registerLionCoreCustomDeserializers(coreLanguagesVersion);
    serialization.dataTypesValuesSerialization
        .registerLionBuiltinsPrimitiveSerializersAndDeserializers(coreLanguagesVersion);
    serialization.instanceResolver.addAll(
        LionCore.getInstance(coreLanguagesVersion).thisAndAllDescendants());
    serialization.instanceResolver.addAll(
        LionCoreBuiltins.getInstance(coreLanguagesVersion).thisAndAllDescendants());
  }
}
