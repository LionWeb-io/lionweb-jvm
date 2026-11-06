package io.lionweb.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lionweb.LionWebVersion;
import io.lionweb.language.Language;
import io.lionweb.language.LionCoreBuiltins;
import io.lionweb.lioncore.LionCore;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class ImplicitDependenciesTest {

  @Test
  public void implicitDependenciesAreLionCoreAndBuiltins() {
    for (LionWebVersion version : LionWebVersion.values()) {
      assertEquals(
          Arrays.asList(LionCore.getInstance(version), LionCoreBuiltins.getInstance(version)),
          ImplicitDependencies.implicitDependencies(version));
    }
  }

  @Test
  public void isImplicitDependencyByLanguage() {
    LionWebVersion version = LionWebVersion.v2023_1;
    assertTrue(ImplicitDependencies.isImplicitDependency(version, LionCore.getInstance(version)));
    assertTrue(
        ImplicitDependencies.isImplicitDependency(version, LionCoreBuiltins.getInstance(version)));
    assertFalse(
        ImplicitDependencies.isImplicitDependency(
            version, new Language("MyLanguage", "my-language", "my-language", "1")));
  }

  @Test
  public void isImplicitDependencyByID() {
    for (LionWebVersion version : LionWebVersion.values()) {
      assertTrue(
          ImplicitDependencies.isImplicitDependency(
              version, LionCore.getInstance(version).getID()));
      assertTrue(
          ImplicitDependencies.isImplicitDependency(
              version, LionCoreBuiltins.getInstance(version).getID()));
      assertFalse(ImplicitDependencies.isImplicitDependency(version, "my-language"));
    }
  }

  @Test
  public void idsAreSpecificToTheLionWebVersion() {
    String m3v2023 = LionCore.getInstance(LionWebVersion.v2023_1).getID();
    String m3v2024 = LionCore.getInstance(LionWebVersion.v2024_1).getID();
    if (!m3v2023.equals(m3v2024)) {
      assertFalse(ImplicitDependencies.isImplicitDependency(LionWebVersion.v2024_1, m3v2023));
    }
  }
}
