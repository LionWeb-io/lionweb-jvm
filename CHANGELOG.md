# Changelog

### Version 1.3.2
* Serializes empty features by default ([#306](https://github.com/LionWeb-io/lionweb-jvm/issues/306))

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
