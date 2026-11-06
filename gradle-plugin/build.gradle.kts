plugins {
    `java-gradle-plugin`
    alias(libs.plugins.gradle.publish)
    alias(libs.plugins.vt.publish)
    alias(libs.plugins.build.config)
}

project.group = "io.lionweb"

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

gradlePlugin {
    website.set("https://github.com/LionWeb-io/lionweb-jvm")
    vcsUrl.set("https://github.com/LionWeb-io/lionweb-jvm.git")
    plugins {
        create("lwGradlePlugin") {
            id = "io.lionweb"
            displayName = "LionWeb Gradle Plugin"
            description = "Generate Java classes for a given LionWeb Language definition"
            tags.set(listOf("lionweb", "language-engineering", "MDE"))
            implementationClass = "io.lionweb.gradleplugin.LionWebPlugin"
        }
    }
    // Registers the integrationTest source set so java-gradle-plugin automatically wires
    // plugin-under-test-metadata.properties onto its classpath (needed for withPluginClasspath()).
    testSourceSets(sourceSets["test"], integrationTest)
}

repositories {
    mavenCentral()
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(project(":core"))
    compileOnly(libs.jsr305)
    implementation(libs.gson)
    implementation(libs.protobuf)
    implementation(libs.javapoet)
    implementation(libs.annotations)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    "integrationTestImplementation"(gradleTestKit())
    "integrationTestImplementation"(libs.junit.api)
    "integrationTestImplementation"(libs.junit.params)
    "integrationTestRuntimeOnly"(libs.junit.engine)
    "integrationTestCompileOnly"(libs.jsr305)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against the full plugin pipeline."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    mustRunAfter(tasks.test)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// In order to use JavaPoet, we cannot stick to Java 8
java {
    sourceCompatibility = JavaVersion.toVersion("17")
    targetCompatibility = JavaVersion.toVersion("17")
}

val specsVersion = extra["specsVersion"] as String

buildConfig {
    sourceSets.getByName("main") {
        packageName("io.lionweb.gradleplugin")
        buildConfigField("String", "VERSION", "\"${version}\"")
        useJavaOutput()
    }
}
