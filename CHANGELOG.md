# Changelog

### Version 1.4.6

* Remove the unneeded `RawReferenceValue` class (issue #432)
* Fix typos in public names: rename package `io.lionweb.client.delta.messages.queries.partitcipations` to `participations`, and `LionWebArchive.LW_VERION_KEY` to `LW_VERSION_KEY`
* README: clarify Java requirements (published libraries require Java 11+, the Gradle plugin and building the project require Java 17+), fix the Maven coordinates in the usage example, and add a short project description
* Bump dependencies: kotest 6.2.3, javapoet 0.18.0, Spotless 8.9.0, ben-manes versions plugin 0.58.0, `actions/setup-java` v6, `gradle/actions` 6.2.0

### Version 1.4.5

* Fix how some references are saved in M3
* Make some tests more resilient to transient network errors

### Version 1.4.4

* Fix serialization of references to `LionCore-builtins` and M3 elements in LionWeb 2024.1: the target reference id is now omitted (serialized as `null`), relying on the qualified `resolveInfo` (e.g. `LionWeb.LionCore_builtins.String`) as mandated by specification section 3.4. For LionWeb 2023.1 the reference id is still kept (issue #411)
* Fix `resolveInfo` of `StructuredDataType` `Field` type references, which was serialized as the plain name (e.g. `String`) instead of the qualified autoresolve name (e.g. `LionWeb.LionCore_builtins.String`) (issue #411)

### Version 1.4.3

* Add explicit messages to all bare `Objects.requireNonNull` calls across the codebase
* Centralise Gradle build configuration: introduce `lionweb-java-conventions`, `lionweb-kotlin-conventions`, `lionweb-functional-test-conventions` convention plugins in `buildSrc` covering JVM source/target compatibility, toolchain, `mavenCentral()` repository, JUnit Platform test runner, test logging, `sourcesJar`, and the `functionalTest` suite skeleton; remove the resulting duplication from all submodule build files
* Bump Kotlin to 2.4.0
* Add `IdUtils.encodeToValidId` and `IdUtils.decodeFromValidId` for encoding/decoding valid LionWeb IDs

### Version 1.4.2

* Reorganize server package structure; introduce `HTTPBulkServer` with full RESTful bulk and inspection API endpoints
* Add integration tests for `HTTPBulkServer` bulk and inspection APIs
* Rename `LionWebClient` to `LionWebBulkClient`; remove deprecated classes and interfaces
* Remove unused `AdditionalInfoData` class
* Add `@NotNull`/`@Nullable` annotations across client APIs (`BulkRequestFailureException`, `DeltaClient`, and others) for improved null safety
* Refactor `DeltaClient` with stricter participation checks
* Bump dependencies: Kotlin 2.3.21, kotest 6.1.11, javapoet 0.16.0, OkHttp 5.3.2
* Add class-level Javadoc to all public classes and interfaces across `core`, `client`, `client-testing`, `emf`, `extensions`, and `gradle-plugin` modules
* Add `@see` links to the [LionWeb Delta API specification](https://lionweb.io/specification/delta/delta-api.html) on all delta protocol message classes
* Revised nullability annotations in the entire codebase
* Modernize Java 9+ collection factory methods: replace `Collections.emptyList/singletonList/emptyMap/emptySet` and `Arrays.asList` (for constant data) with `List.of`, `Map.of`, and `Set.of` across production sources
* Remove deprecated `lowLevelRepoClient` from `LionWebClient` (Kotlin); migrate `isNodeExisting`, `getParentId`, and `nodesByClassifier` to use `jRepoClient` directly; delete now-unused `LowLevelRepoClient`, and Kotlin-specific `ClassifierKey`/`ClassifierResult` duplicates in favour of the canonical `io.lionweb.client.api` types

### Version 1.4.1

* Align all delta messages to match the integration test expectations
* Merged CommonChecks in IdUtils
* Add support for common delta commands in DeltaClient
* Add demo utilities to validate the behavior of the delta server

### Version 1.4.0

**Breaking change:** JDK 8 support is dropped. The minimum supported runtime is now Java 11.
Tested and supported Java versions: 11, 17, 21, and 25.

* Drop JDK 8 support; minimum runtime is now Java 11; add Java 25 to the CI matrix
* Bump dependencies: protobuf 4.35.0, Guava 33.6.0-jre, Jetbrains annotations 26.1.0, testcontainers 1.21.4, emfjson 2.3.0, javapoet 0.15.0, protobuf Gradle plugin 0.10.0, vanniktech publish plugin 0.36.0
* Explicitly declare `jsr305` as `compileOnly`/`testCompileOnly` across all modules (fixes compilation after Guava removed its transitive JSR-305 dependency)
* Remove all generated protobuf message classes (`PBChunk`, `PBNode`, `PBBulkImport`, etc.) — `ProtoBufSerialization` now delegates entirely to `DirectProtoBufSerializer` / `DirectProtoBufDeserializer`; proto files and the protobuf codegen Gradle plugin are removed from `core` and `extensions`
* Add `DirectBulkImportSerializer` for bulk-import protobuf serialization without generated classes; `ExtraProtoBufSerialization.serializeBulkImportToBytes` now uses it
* Improve `DirectProtoBufDeserializer` to throw `DeserializationException` when a metapointer references an out-of-bounds language index
* Remove deprecated `ReferenceValue.setReferred()`/`setResolveInfo()` mutators (use `withReferred()`/`withResolveInfo()` builder methods instead) and `AbstractSerialization.DEFAULT_SERIALIZATION_FORMAT` constant

### Version 1.3.17

* Add end-to-end integration test for Gradle plugin code generation pipeline (db2sql language)
* Fix language topological sort ordering for cross-platform file system compatibility
* Consolidate GitHub CI workflows into a unified `ci.yml` with Java 17, 21, and 25 matrix
* Upgrade Gradle wrapper to 9.5.1
* Upgrade Spotless to 8.5.1 for Java 25 compatibility

### Version 1.3.16

* Fix issue with conflicting variables in classes generation

### Version 1.3.15

* Enable dynamic nodes in JSON serialization to support annotations
* Enhance `GenerationContext` with concept mappings and support for boolean fields
* Improve exception messages in code generation for unsupported concepts and interfaces
* Include instance ID in `Instantiator` exceptions for better diagnostics
* Introduce `InconsistentDataHandler` interface for customizable handling of inconsistent data during serialization/deserialization
* Extend `InconsistentDataHandler` to handle missing classifiers, in addition to missing properties
* Improve null and duplicate ID checks during deserialization

### Version 1.3.14

* Enable dynamic nodes in JSON serialization to support annotations
* Add `DirectProtoBufSerializer` and `DirectProtoBufDeserializer` for efficient binary serialization/deserialization
* Optimize `DirectProtoBufSerializer` with single-pass indexing and cached indexing for improved performance
* Introduce flat serialization plan in ProtoBuf serialization for improved cache locality and memory efficiency
* Extend subchunk logic to include annotations
* Extract ProtoBuf field numbers into a dedicated constants class for improved readability

### Version 1.3.13

* Refactor `Classifier`: replace stream-based feature lookups with enhanced for-loops for improved performance

### Version 1.3.12

* Optimize equality checks for `ClassifierInstances` and `ReferenceValues`
* Optimize `addAnnotation` method for improved readability and performance
* Bump org.jetbrains.dokka to 2.2.0

### Version 1.3.11

* Fix foojay toolchain resolver plugin compatibility with Gradle 9 (update to 1.0.0)
* Pre-size `SerializedClassifierInstance` feature lists from protobuf node counts to reduce allocations
* Make classifier index materialization optional in `RepositoryData` and `InMemoryServer` (default: enabled)
* Add `nodesByClassifier(limit, classifierKey)` for targeted single-classifier lookup
* Optimize `nodesByClassifier` limit slicing via `subList` instead of element-by-element iteration

### Version 1.3.10

* Upgrade Gradle wrapper to 9.4.1
* Fix encoding issues in file I/O by using explicit UTF-8 encoding instead of platform default charset
* Add support for custom charsets in JSON serialization/deserialization methods
* Fix Gradle plugin task validation errors for Gradle 9 compatibility (`@CacheableTask` on generation tasks)
* Fix code generation task to run on Java 11+ (MWE2 launcher requirement)
* Upgrade Kotlin to 2.3.20
* Upgrade org.eclipse.emf.ecore.xmi to 2.40.0
* Upgrade protobuf-java to 4.34.1
* Upgrade com.github.gmazzo.buildconfig to 6.0.9

### Version 1.3.9

* Fix null check for `registerPartitionObserver` parameter
* Fix integration test paths after lionweb-integration-testing restructure
* Performance optimization for `LanguageVersion.of` with fast path cache lookup
* Simplify `Containment` and `Property` construction by including ID in constructor
* Remove SpotBugs integration

### Version 1.3.8

* Further serialization and deserialization performance improvements
* Add classifier indexing to `RepositoryData` for efficient node retrieval
* Optimize `hashCode` caching in `ClassifierKey`
* Add O(1) `placeAt` method for efficient node sorting during deserialization

### Version 1.3.7

* Introducing benchmarks
* Tuning serialization and deserialization performance

### Version 1.3.6

* Improve performance by computing expensive `requireNonNull` exception message lazily

### Version 1.3.5

* Improvements to InMemoryServer concurrency

### Version 1.3.4

* Performance improvements to `InMemoryServer`
* Performance improvements to language model
* Performance improvements to `DynamicClassidierInstance`

### Version 1.3.3

* Performance improvements because of features caching

### Version 1.3.2
* Serializes empty features by default ([#306](https://github.com/LionWeb-io/lionweb-jvm/issues/306))
* Omit unset properties same as other unset features

### Version 1.3.1

* Fix issues in generation of classes related to conflicts with Java keywords
* Make gradle plugin configure dependencies and source sets automatically

### Version 1.3.0

* Combine LW Java and LW Kotlin to create LW JVM
* Move to Junit 5, consistently

### Version 1.2.7

* Complete classes generation from the Gradle Plugin
* Initial support for delta protocol

### Version 1.2.6

* Improve classes generation from the Gradle Plugin

### Version 1.2.5

* In generated language class, add getters for primitive types and enumerations
* Rename PrimitiveValuesSerialization to DataTypeValuesSerialization and the internal interfaces as-well: 
  PrimitiveSerializer, PrimitiveDeserializer, and PrimitiveValueSerializerAndDeserializer

### Version 1.2.4

* Refactor standardInitialization to support custom LionWeb versions

### Version 1.2.3

* Fixing and completing LanguageJavaCodeGenerator

### Version 1.2.2

* Do not register types introduced in LW 2024.1 when using LW 2023
* When comparing Chunks, check also used languages

### Version 1.2.1

* Improve generation of Language Classes from the Gradle Plugin
* Adding generation of AST Classes from the Gradle Plugin

### Version 1.2.0

* Preliminary work to support delta protocol
* Added Gradle Plugin
* Added support for LionWeb Archive

### Version 1.1.4

* Improved protobuf serialization and removed FlatBuffers support
* Introduced concurrency tests and thread-safe caching
* Extended Classifier and Annotation APIs with chaining and multiplicity validation
* Enhanced validation in ChunkValidator and RepositoryData
* Improved test coverage and documentation

### Version 1.1.3

* Added consistency checks in RepositoryData and InMemoryServer
* Enhanced error reporting and node movement handling
* Improved validation of annotations and parent relationships

### Version 1.1.2

* Refactored ChunkValidator for duplicate metapointer detection and streamlined checks
* Introduced setPropertyValue and clarified unsafe serialization operations
* Added tests for ChunkValidator and serialization logic

### Version 1.1.1

* Updated LionWeb Server dependency
* Improved validation and consistency logic across repository components
* Refined domain model handling for libraries and missing nodes

### Version 1.1.0

* Renamed SerializedChunk into SerializationChunk
* Added support for improved protobuf serialization
* Removed support for FlatBuffers serialization

### Version 1.0.7

* Add support for partition observers

### Version 1.0.6

* Considering annotation in InMemoryStorage

### Version 1.0.5

* Ensure that RepositoryData do not propose IDs of existing nodes

### Version 1.0.4

* Fixed bug in RepositoryData.ids

### Version 1.0.3

* Fixed bug in SerializedChunk.equals

### Version 1.0.2

* Internaling MetaPointers and most of SerializedPropertyValues

### Version 1.0.1

* DynamicNode: uniformly use getID() to access node IDs

### Version 1.0.0

* Reorganization of all packages
* Added In-Memory Server
* LanguageValidator now verifies consistency of LionWeb Version usages
* Made creation of languages more convenient
* Added AbstractSerialization.setAllUnavailabilityPolicies
* Added Language.getAnnotationByName
* Revised how we calculate languages to list as used languages
* Reduction of memory footprint for storing nodes

### Version 0.4.7

* Supporting limit parameter in inspection APIs
* Add possibility to remove feature from concept
* Improve support for multiple LionWeb versions in ProtobufSerialization and FlatBuffersSerialization

### Version 0.4.6

* Adding support for serializing and deserializing entire repositories

### Version 0.4.5

* Implement support for history APIs
* Adding documentation

### Version 0.4.4

* Bug fix for ClientForBulkAPIs
* Added retrieve(Node) method in LionWebRepoClient
* Introducing documentation

### Version 0.4.3

* Bug fix for ClientForBulkAPIs.retrieve

### Version 0.4.2

* Performance improvements for serialization

### Version 0.4.1

* Add support for more APIs in the repo client: DB Admin APIs and Inspection APIs

### Version 0.4.0

* Introduction of the `repo-client` and `repo-client-testing` modules

### Version 0.3.5

* Minor bug fixing

### Version 0.3.4

* Permit to load languages depending on other languages

### Version 0.3.3

* EMF conversion: various improvements
* Improvements to DynamicNode.equals

### Version 0.3.2

* Allow annotations to have children
* Remove Partition and Experimental
* EMF conversion: handle abstract flag
* EMF conversion: handle attributes with high multiplicity

## lionweb-java-2024.1-* - Version 0.3.0

Introducing support for LionWeb 2024.1

### Version 0.2.18

* Introduce support for serialization based on ProtoBuffer and FlatBuffers
* Introduce SerializationProvider

### Version 0.2.16

* Support presence of multiple references to the same proxied node during serialization

### Version 0.2.15

* Revise support for homogeneous APIs in Node, especially for references
* Introduced ClassifierInstanceUtils

### Version 0.2.14

* Improve support for annotations, in particular for M3 Nodes

### Version 0.2.13

* Minor improvements to DynamicNode
* Fixes in serialization around null parents

### Version 0.2.12

* Introducing HasSettableParent

### Version 0.2.11

* Correct serialization of enums

### Version 0.2.10

* Introducing Proxy Nodes

### Version 0.2.5

* Model is renamed in Partition
* Node.getModel is renamed in Node.getPartition

### Version 0.2.2

* Convergence of Serialized{Annotation&Node}Instance into SerializedClassifierInstance

### Version 0.2.1

* The JSON serialization is changed so that the meta-pointer under `"classifier"` appears directly after the `"id"` key-value pair.
* The internal representation of serialized nodes was simplified.

## lionweb-java-2023.1-* - Version 0.2.0

This is the first release based on the new artifact ids (which now include the specifications version), which indicate support for the specification of LionWeb released under version 2023.1.

At this stage support for the M1 and M2 APIs is relatively complete. Support for annotations may need refinements. Some constraints may not yet been verified.
Import and export from and to EMF is a work in progress.
