package io.lionweb.utils;

import io.lionweb.LionWebVersion;
import io.lionweb.language.*;
import io.lionweb.language.Enumeration;
import io.lionweb.model.Node;
import io.lionweb.model.impl.M3Node;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LanguageValidator is responsible for validating objects of type {@code Language}. It ensures that
 * a given language conforms to a comprehensive set of validation rules encompassing structural,
 * naming, dependency, and logical consistency checks.
 *
 * <p>This class provides functionalities for detecting issues such as circular references, invalid
 * language dependencies, inconsistent versions, non-unique names or keys, and improper ancestor or
 * interface hierarchy.
 */
public class LanguageValidator extends Validator<Language> {

  public static void ensureIsValid(Language language) {
    ValidationResult vr = new LanguageValidator().validate(language);
    if (!vr.isSuccessful()) {
      throw new RuntimeException("Invalid language: " + vr.getIssues());
    }
  }

  private void validateEnumeration(ValidationResult result, Enumeration enumeration) {
    validateNamesAreUnique(enumeration.getLiterals(), result);
  }

  private void validateClassifier(ValidationResult result, Classifier<?> classifier) {
    for (Feature<?> feature : classifier.getFeatures()) {
      result
          .addErrorIf(feature.getContainer() == null, "Container not set", feature)
          .addErrorIf(
              feature.getContainer() != null
                  && ((Node) feature.getContainer()).getID() != null
                  && !((Node) feature.getContainer()).getID().equals(classifier.getID()),
              "Features container not set correctly: set to "
                  + feature.getContainer()
                  + " when "
                  + classifier
                  + " was expected",
              feature)
          .addErrorIf(
              feature instanceof Containment
                  && ((Containment) feature).getType() instanceof Annotation,
              "A containment cannot hold elements of type annotation",
              feature);
    }
    validateNamesAreUnique(classifier.getFeatures(), result);
    validateFeatureNamesDoNotClashWithInherited(result, classifier);
  }

  /**
   * Own features must not reuse the name of an inherited feature. Clashes among inherited features
   * are allowed by the specification (see the name-clash example in the constraints of Feature), so
   * they are not reported.
   *
   * @see <a
   *     href="https://lionweb.io/specification/2024.1/metametamodel/metametamodel.html#Feature">Feature
   *     constraints</a>
   */
  private void validateFeatureNamesDoNotClashWithInherited(
      ValidationResult result, Classifier<?> classifier) {
    if (classifier.getFeatures().isEmpty()) {
      return;
    }
    Map<String, Feature<?>> ownFeaturesByName = new HashMap<>();
    for (Feature<?> feature : classifier.getFeatures()) {
      if (feature.getName() != null) {
        ownFeaturesByName.put(feature.getName(), feature);
      }
    }
    for (Classifier<?> ancestor : classifier.allAncestors()) {
      // A classifier among its own ancestors means a cyclic hierarchy, reported elsewhere
      if (ancestor == classifier) {
        continue;
      }
      for (Feature<?> inherited : ancestor.getFeatures()) {
        Feature<?> clashing = ownFeaturesByName.get(inherited.getName());
        if (clashing != null) {
          result.addError(
              "Feature name " + clashing.getName() + " clashes with an inherited feature",
              clashing);
        }
      }
    }
  }

  private void validateConcept(ValidationResult result, Concept concept) {
    checkAncestors(concept, result);
    result.addErrorIf(
        concept.getImplemented().size() != concept.getImplemented().stream().distinct().count(),
        "The same interface has been implemented multiple times",
        concept);
  }

  /** It checks if there is any circularity (direct or indirect) in the given StructuredDataType. */
  public static boolean isCircular(StructuredDataType structuredDataType) {
    return isCircular(structuredDataType, new HashSet<>(), new HashSet<>());
  }

  /**
   * Depth-first search: a cycle exists only if we reach an SDT that is on the current path.
   * Reaching an already fully explored SDT through another path is not a cycle.
   */
  private static boolean isCircular(
      StructuredDataType sdt, Set<StructuredDataType> onPath, Set<StructuredDataType> explored) {
    if (onPath.contains(sdt)) {
      return true;
    }
    if (!explored.add(sdt)) {
      return false;
    }
    onPath.add(sdt);
    for (Field field : sdt.getFields()) {
      if (field.getType() instanceof StructuredDataType
          && isCircular((StructuredDataType) field.getType(), onPath, explored)) {
        return true;
      }
    }
    onPath.remove(sdt);
    return false;
  }

  private void validateStructuralDataType(
      ValidationResult result, StructuredDataType structuredDataType) {
    if (isCircular(structuredDataType)) {
      result.addError(
          "Circular references are forbidden in StructuralDataFields", structuredDataType);
    }
    validateNamesAreUnique(structuredDataType.getFields(), result);
  }

  private void validateLWVersionConsistency(Language language, ValidationResult result) {
    Set<LionWebVersion> lwVersionsUsed =
        language.thisAndAllDescendants().stream()
            .filter(n -> n instanceof M3Node<?>)
            .map(n -> ((M3Node<?>) n).getLionWebVersion())
            .collect(Collectors.toSet());
    if (lwVersionsUsed.size() > 1) {
      result.addError("Inconsistent LionWeb Versions used", language);
    }
  }

  @Override
  public @Nonnull ValidationResult validate(@Nonnull Language language) {
    // Given languages are also valid node trees, we check against errors for node trees
    ValidationResult result = new NodeTreeValidator().validate(language);

    result.addErrorIf(
        language.getVersion() == null || language.getVersion().isEmpty(),
        "Version not set",
        language);

    validateNamesAreUnique(language.getElements(), result);
    validateNamesAndKeysAreValid(language, result);
    validateKeysAreUnique(language, result);
    validateLWVersionConsistency(language, result);
    validateLanguageDependencies(language, result);

    language
        .getElements()
        .forEach(
            (LanguageEntity el) -> {
              result
                  .addErrorIf(el.getLanguage() == null, "Language not set", el)
                  .addErrorIf(
                      el.getLanguage() != null && el.getLanguage() != language,
                      "Language not set correctly",
                      el);

              if (el instanceof io.lionweb.language.Enumeration) {
                validateEnumeration(result, (Enumeration) el);
              }
              if (el instanceof Classifier) {
                validateClassifier(result, (Classifier) el);
              }
              if (el instanceof Concept) {
                validateConcept(result, (Concept) el);
              }
              if (el instanceof Interface) {
                checkInterfacesCycles((Interface) el, result);
              }
              if (el instanceof Annotation) {
                checkAnnotates((Annotation) el, result);
              }
              if (el instanceof StructuredDataType) {
                validateStructuralDataType(result, (StructuredDataType) el);
              }
            });

    return result;
  }

  private void validateNamesAreUnique(
      List<? extends NamespacedEntity> elements, ValidationResult result) {
    Map<String, List<NamespacedEntity>> elementsByName =
        elements.stream()
            .filter(namespacedEntity -> namespacedEntity.getName() != null)
            .collect(Collectors.groupingBy((NamespacedEntity::getName)));
    elementsByName
        .entrySet()
        .forEach(
            (Map.Entry<String, List<NamespacedEntity>> entry) -> {
              if (entry.getValue().size() > 1) {
                entry
                    .getValue()
                    .forEach(
                        (NamespacedEntity el) ->
                            result.addError("Duplicate name " + el.getName(), (Node) el));
              }
            });
  }

  private void validateNamesAndKeysAreValid(Language language, ValidationResult result) {
    for (Node n : language.thisAndAllDescendants()) {
      if (n instanceof IKeyed<?>) {
        IKeyed<?> keyed = (IKeyed<?>) n;
        String key = keyed.getKey();
        if (key == null) {
          result.addError("Key should not be null", n);
        } else if (!IdUtils.isValidID(key)) {
          result.addError("Invalid key '" + key + "'", n);
        }
        // The language name can be a qualified name
        String name = keyed.getName();
        if (name == null) {
          result.addError(n == language ? "Qualified name not set" : "Simple name not set", n);
        } else if (!(n == language
            ? Naming.isValidQualifiedName(name)
            : Naming.isValidName(name))) {
          result.addError("Invalid name '" + name + "'", n);
        }
      }
    }
  }

  private void validateLanguageDependencies(Language language, ValidationResult result) {
    Set<Language> usedLanguages = new HashSet<>();
    for (LanguageEntity<?> element : language.getElements()) {
      if (element instanceof Classifier<?>) {
        for (Feature<?> feature : ((Classifier<?>) element).getFeatures()) {
          if (feature instanceof Property) {
            addLanguageOf(usedLanguages, ((Property) feature).getType());
          } else if (feature instanceof Link<?>) {
            addLanguageOf(usedLanguages, ((Link<?>) feature).getType());
          }
        }
      }
      if (element instanceof Concept) {
        Concept concept = (Concept) element;
        addLanguageOf(usedLanguages, concept.getExtendedConcept());
        for (Interface interfaze : concept.getImplemented()) {
          addLanguageOf(usedLanguages, interfaze);
        }
      } else if (element instanceof Interface) {
        for (Interface extendedInterface : ((Interface) element).getExtendedInterfaces()) {
          addLanguageOf(usedLanguages, extendedInterface);
        }
      } else if (element instanceof Annotation) {
        Annotation annotation = (Annotation) element;
        addLanguageOf(usedLanguages, annotation.getAnnotates());
        addLanguageOf(usedLanguages, annotation.getExtendedAnnotation());
        for (Interface interfaze : annotation.getImplemented()) {
          addLanguageOf(usedLanguages, interfaze);
        }
      } else if (element instanceof StructuredDataType) {
        for (Field field : ((StructuredDataType) element).getFields()) {
          addLanguageOf(usedLanguages, field.getType());
        }
      }
    }
    usedLanguages.remove(language);
    if (usedLanguages.isEmpty()) {
      return;
    }
    // Elements of transitive dependencies can be used too
    Set<Language> allDependencies = new HashSet<>();
    Deque<Language> toExplore = new ArrayDeque<>(language.dependsOn());
    while (!toExplore.isEmpty()) {
      Language dependency = toExplore.pop();
      if (allDependencies.add(dependency)) {
        toExplore.addAll(dependency.dependsOn());
      }
    }
    for (Language ul : usedLanguages) {
      if (!allDependencies.contains(ul)
          && !ImplicitDependencies.isImplicitDependency(language.getLionWebVersion(), ul)) {
        result.addError(
            "Language "
                + ul.getKey()
                + " version "
                + ul.getVersion()
                + " is not listed among dependencies",
            language);
      }
    }
  }

  private static void addLanguageOf(
      Set<Language> usedLanguages, @Nullable LanguageEntity<?> entity) {
    if (entity != null && entity.getLanguage() != null) {
      usedLanguages.add(entity.getLanguage());
    }
  }

  private void validateKeysAreUnique(Language language, ValidationResult result) {
    Map<String, String> uniqueKeys = new HashMap<>();
    language
        .thisAndAllDescendants()
        .forEach(
            n -> {
              if (n instanceof IKeyed<?>) {
                IKeyed<?> IKeyed = (IKeyed<?>) n;
                String key = IKeyed.getKey();
                if (key != null) {
                  if (uniqueKeys.containsKey(key)) {
                    result.addError(
                        "Key '" + key + "' is duplicate. It is also used by " + uniqueKeys.get(key),
                        n);
                  } else {
                    uniqueKeys.put(key, n.getID());
                  }
                }
              }
            });
  }

  public boolean isLanguageValid(Language language) {
    return validate(language).isSuccessful();
  }

  private void checkAncestors(Concept concept, ValidationResult validationResult) {
    checkAncestorsHelper(new HashSet<>(), concept, validationResult, true);
  }

  private void checkAncestors(Interface iface, ValidationResult validationResult) {
    checkAncestorsHelper(new HashSet<>(), iface, validationResult, false);
  }

  private void checkAnnotates(Annotation annotation, ValidationResult validationResult) {
    validationResult.addErrorIf(
        annotation.getEffectivelyAnnotated() == null,
        "An annotation should specify annotates or inherit it",
        annotation);
    validationResult.addErrorIf(
        annotation.getExtendedAnnotation() != null
            && annotation.getAnnotates() != null
            && annotation.getAnnotates() != annotation.getExtendedAnnotation().getAnnotates(),
        "When a sub annotation specify a value for annotates it must be the same value the super annotation specifies",
        annotation);
  }

  private void checkAncestorsHelper(
      Set<Classifier> alreadyExplored,
      Concept concept,
      ValidationResult validationResult,
      boolean examiningConcept) {
    if (alreadyExplored.contains(concept)) {
      validationResult.addError("Cyclic hierarchy found", concept);
    } else {
      alreadyExplored.add(concept);
      if (concept.getExtendedConcept() != null) {
        checkAncestorsHelper(
            alreadyExplored, concept.getExtendedConcept(), validationResult, examiningConcept);
      }
      concept
          .getImplemented()
          .forEach(
              interf ->
                  checkAncestorsHelper(
                      alreadyExplored, interf, validationResult, examiningConcept));
    }
  }

  private void checkAncestorsHelper(
      Set<Classifier> alreadyExplored,
      Interface iface,
      ValidationResult validationResult,
      boolean examiningConcept) {
    if (alreadyExplored.contains(iface)) {
      // It is ok to indirectly implement multiple time the same interface for a Concept.
      // It is instead an issue in case we are looking into interfaces.
      //
      // For example, this is fine:
      // class A extends B, implements I
      // class B implements I
      //
      // This is not fine:
      // interface I1 extends I2
      // interface I2 extends I1
      if (!examiningConcept) {
        validationResult.addError("Cyclic hierarchy found", iface);
      }
    } else {
      alreadyExplored.add(iface);
      iface
          .getExtendedInterfaces()
          .forEach(
              interf ->
                  checkAncestorsHelper(
                      alreadyExplored, interf, validationResult, examiningConcept));
    }
  }

  private void checkInterfacesCycles(Interface iface, ValidationResult validationResult) {
    if (iface.allExtendedInterfaces().contains(iface)) {
      validationResult.addError("Cyclic hierarchy found: the interface extends itself", iface);
    }
  }
}
