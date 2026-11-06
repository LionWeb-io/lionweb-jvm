package io.lionweb.utils;

import io.lionweb.LionWebVersion;
import io.lionweb.language.Language;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.lioncore.LionCore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * The languages every language implicitly depends on: LionCore M3 and LionCore builtins.
 *
 * <p>These languages are always available, so they don't have to be provided together with the
 * languages that depend on them, and a language can use them without listing them among its
 * dependencies.
 */
public final class ImplicitDependencies {

  private ImplicitDependencies() {}

  /** Returns the implicit dependencies for the given LionWeb version. */
  public static @Nonnull List<Language> implicitDependencies(
      @Nonnull LionWebVersion lionWebVersion) {
    Objects.requireNonNull(lionWebVersion, "lionWebVersion should not be null");
    return Collections.unmodifiableList(
        Arrays.asList(
            LionCore.getInstance(lionWebVersion), LionCoreBuiltins.getInstance(lionWebVersion)));
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
    Objects.requireNonNull(languageID, "languageID should not be null");
    return implicitDependencies(lionWebVersion).stream()
        .anyMatch(language -> languageID.equals(language.getID()));
  }
}
