# LionWeb JVM

![Maven Central Version](https://img.shields.io/maven-central/v/io.lionweb/lionweb-2024.1-core)
![Build](https://github.com/LionWeb-io/lionweb-java/actions/workflows/ci.yml/badge.svg)

LionWeb JVM is the JVM implementation of the [LionWeb](https://lionweb.io) specification, providing libraries for Java and Kotlin.

The published libraries require Java 11 or higher (the Gradle plugin requires Java 17 or higher).

Building the project requires Java 17 or higher. We support and test on Java 17, 21, and 25.

## Documentation

Take a look at the [Documentation](https://lionweb.io/lionweb-java).

## Status of the project

While new features are being added, and the project is evolving, the core features are solidly implemented. 
The library is mature and used in production.

The project is actively maintained. Feel free to open issues to ask questions or to request help 
or support.

For visibility on the future plans regarding this project refer to the [Roadmap](ROADMAP.md).

Currently the project is on par with the LionWeb specification.

In particular, this library supports both versions 2023.1 and 2024.1 of the specification.

## Using the library

```kotlin
dependencies {
    implementation("io.lionweb:lionweb-2024.1-core:$lionwebVersion")
}
```

## Development

Before opening in IDEA run:

```
./gradlew setup
```

This will generate necessary classes.

## Core
Contains LionCore (M2) and LionWeb (M1) implementations in Java, including (de-)serializers from/to JSON.

## Emf
Contains im/exporters to convert models between LionCore &harr; Ecore and LionWeb &harr; EMF.

## Emf-builtins
Some of LionCore's built-in elements have no direct representation in Ecore.
This sub-project is an Eclipse project that defines an _EPackage_ `builtins` to host the equivalent elements in Ecore.
The language's _nsURI_ is `http://lionweb.io/lionweb-java/emf/core/builtins/2023.1`.

## Client
Functionalities to connect to the [LionWeb Server](https://github.com/LionWeb-io/lionweb-server). It also
includes an in-memory server, paired with a Client exposing the same interface as the interface for connecting to 
the LionWeb Server.

## Client-testing
Facilities to write functional tests against the LionWeb Server.

## Extensions
Serialization-related extensions that go beyond the core LionWeb specification:

- **Protobuf serialization** — efficient binary encoding/decoding of LionWeb serialization chunks using Protocol Buffers
- **LionWeb Archive (`.lwa`)** — a ZIP-based packaging format that bundles partitions, language definitions, and metadata into a single versioned file

## Kotlin-core
Kotlin-idiomatic bindings on top of `core`. Provides `BaseNode` and `BaseAnnotation` open classes, a `ContainmentList` that keeps parent/child links in sync, and extension functions for serialization.

```kotlin
dependencies {
    implementation("io.lionweb:lionweb-2024.1-kotlin-core:$lionwebVersion")
}
```

## Kotlin-client
Kotlin-idiomatic bindings on top of `client` for connecting to a LionWeb Server. Provides a high-level `LionWebClient` that works with typed `BaseNode` subclasses, and a low-level `LowLevelRepoClient` for raw chunk access.

```kotlin
dependencies {
    implementation("io.lionweb:lionweb-2024.1-kotlin-client:$lionwebVersion")
}
```

## Gradle Plugin
A Gradle plugin (`io.lionweb`) that generates Java source code from LionWeb language definition files and packages language JSON files into your JAR. Apply it in your build:

```kotlin
plugins {
    id("io.lionweb") version "$lionwebVersion"
}
```

The plugin exposes tasks `generateLWLanguages` and `generateLWNodeClasses`, and a `lionweb { }` extension block for configuring input/output directories, package names, primitive type mappings, and more.

## Server
A standalone LionWeb repository server backed by an in-memory store. It exposes two protocols simultaneously:

- **HTTP bulk API** (default port 9239) — standard LionWeb bulk endpoints (`/bulk/store`, `/bulk/retrieve`, `/bulk/listPartitions`, etc.) plus inspection endpoints (`/inspection/nodesByClassifier`, `/inspection/nodesByLanguage`). Accepts GZIP-compressed request bodies.
- **WebSocket delta protocol** (default port 9240) — real-time change propagation between clients.

An optional Svelte-based **web UI** (default port 9241) can be enabled for inspecting the repository in a browser.

Run via Gradle:

```
./gradlew :server:run                  # HTTP bulk + WebSocket delta
./gradlew :server:runServerWeb         # also enable the web UI
```

Or build a fat JAR and run directly:

```
./gradlew :server:shadowJar
java -jar server/build/libs/server-*-all.jar [options]
```

Key CLI options:

| Option | Default | Description |
|---|---|---|
| `--http-port` | 9239 | HTTP bulk API port |
| `--ws-port` | 9240 | WebSocket delta protocol port |
| `--web-port` | 9241 | Web UI port (requires `--web-ui`) |
| `--web-ui` | false | Enable the browser-based dashboard |
| `--repository` | `repo` | Name of the repository created on startup |

## Changelog

The Changelog is [here](CHANGELOG.md).

## Testing

To ensure proper testing coverage we configured Jacoco.
You can run `./gradlew jacocoTestReport` and then find the report for each module, under `build/reports/jacoco`.

## Benchmarks

The project includes JMH (Java Microbenchmark Harness) benchmarks to measure and optimize serialization/deserialization performance:

- **DeserializeFromChunkBenchmark** - Benchmarks `deserializeSerializationChunk` performance, measuring both the core deserialization logic (excluding JSON parsing) and end-to-end deserialization from JSON
- **SerializeNodesToChunkBenchmark** - Benchmarks `serializeNodesToSerializationChunk` performance, comparing direct serialization of flat node collections versus tree-based serialization with traversal
- **ProtoBufBytesBenchmark** - Benchmarks Protobuf binary serialization/deserialization via `DirectProtoBufSerializer` / `DirectProtoBufDeserializer`, measuring the cost of converting a `SerializationChunk` to bytes and back

These benchmarks use GC profiling to track memory allocations and identify optimization opportunities. They are located in `core/src/performanceTest/java` and can be run using:

```
./gradlew :core:performanceTest
```

Or run directly from your IDE by executing the `main` method in each benchmark class.

## Contributors

This project is part of the [LionWeb](https://lionweb.io) initiative.

The project is currently maintained by [Federico Tomassetti](https://tomassetti.me) with contributions from:
* Ulyana Tikhonova
* Meinte Boersma
* Niko Stotz
* Tiago Baptista
* Alessio Stalla

Contributions include support in the design, code contributions, code reviews, 
and bug reports.
