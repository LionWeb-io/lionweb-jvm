package io.lionweb.utils;

import io.lionweb.LionWebVersion;
import io.lionweb.language.Language;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.lioncore.LionCore;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * The languages every language implicitly depends on: LionCore M3 and LionCore builtins.
 *
 * <p>These languages are always available, so they don't have to be provided together with the
 * languages that depend on them, and a language can use them without listing them among its
 * dependencies.
 */
public final class ImplicitDependencies {

  private static final Map<LionWebVersion, Set<Language>> LANGUAGES =
      new EnumMap<>(LionWebVersion.class);
  private static final Map<LionWebVersion, Set<String>> IDS = new EnumMap<>(LionWebVersion.class);

  static {
    for (LionWebVersion lionWebVersion : LionWebVersion.values()) {
      Set<Language> languages =
          Set.of(
              LionCore.getInstance(lionWebVersion), LionCoreBuiltins.getInstance(lionWebVersion));
      LANGUAGES.put(lionWebVersion, languages);
      IDS.put(
          lionWebVersion,
          languages.stream().map(Language::getID).collect(Collectors.toUnmodifiableSet()));
    }
  }

  private ImplicitDependencies() {}

  /** Returns the implicit dependencies for the given LionWeb version. */
  public static @Nonnull Set<Language> implicitDependencies(
      @Nonnull LionWebVersion lionWebVersion) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    return LANGUAGES.get(lionWebVersion);
  }

  /** Tells if the given language is an implicit dependency for the given LionWeb version. */
  public static boolean isImplicitDependency(
      @Nonnull LionWebVersion lionWebVersion, @Nonnull Language language) {
    Objects.requireNonNull(language, "language should not be null");
    return implicitDependencies(lionWebVersion).contains(language);
  }

  /**
   * Tells if the language with the given ID is an implicit dependency for the given LionWeb
   * version.
   */
  public static boolean isImplicitDependency(
      @Nonnull LionWebVersion lionWebVersion, @Nonnull String languageID) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    Objects.requireNonNull(languageID, "languageID should not be null");
    return IDS.get(lionWebVersion).contains(languageID);
  }
}
