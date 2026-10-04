import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.sort.duckdb"
version = "0.3.0"

val brikkSqlVersion = "0.18.0"
val companionInstalled = providers.gradleProperty("test.sqlTranspiler").orNull == "installed"
val dorisZip = providers.gradleProperty("test.dorisPluginZip").map(::File).orNull

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Translator only. Native DuckDB and brikk-sql-verify are never bundled.
    implementation("dev.brikk.house:brikk-sql-jvm:$brikkSqlVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    // TESTS ONLY: an in-process DuckDB proves the direct-metadata path (duckdb_functions(),
    // duckdb_keywords(), PREPARE-as-validator) headlessly. The PLUGIN does not bundle the engine —
    // at runtime those queries go through the user's configured data source. (duckdb_jdbc with
    // natives is ~50 MB; bundling it would dwarf the plugin for something the driver provides.)
    testImplementation("org.duckdb:duckdb_jdbc:1.5.5.0")

    // TESTS ONLY: the GizmoSQL quack driver (brikk build) — QuackDriverFactsTest extracts the
    // driver class and URL scheme from the jar itself (acceptsURL needs no server), keeping
    // config/duckdb-brikk-drivers.xml honest. The plugin does not bundle or link this jar.
    testImplementation("dev.brikk.duckdb:quack-jdbc:0.5.0")

    // TESTS ONLY (for now): the sqllogictest format layer + expander — powers the upstream
    // duckdb test/sql census harvest (SltHarvestSmokeTest exercises the API contract).
    testImplementation("dev.brikk.ducklake:slt-format:0.3.0")

    intellijPlatform {
        // Same platform window as doris-intellij: compiled against DataGrip 2026.1 (build 261),
        // one artifact serving 261+262. Remote SDK so any clone/CI can build without an IDE.
        val localIde = providers.gradleProperty("duckdb.localIde")
        if (localIde.isPresent) local(localIde) else datagrip("2026.1.3")
        bundledPlugin("com.intellij.database")
        // Test-runtime transitive requirement of com.intellij.database (see doris-intellij notes).
        bundledPlugin("com.intellij.modules.json")
        if (companionInstalled) plugin("dev.sort.sql-transpiler-intellij-plugin:0.3.0")
        if (dorisZip != null) {
            require(dorisZip.isFile) { "Missing Doris plugin ZIP: $dorisZip" }
            localPlugin(dorisZip)
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        failureLevel.set(listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.COMPATIBILITY_WARNINGS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
        ))
        ides {
            create("DB", "2026.1.3") {}
            create("IU", "262.8665.81") {}
            create("DB", "2026.2.5") {}
            // Exact forward-compatibility targets used by the Doris release verifier.
            create("IU", "263.4732.28") {}
            create("IU", "263.5701.42") {}
        }
    }
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

tasks {
    processResources {
        from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    }
    // Stable artifact name (no version suffix) so install-from-disk always points at the same file.
    buildPlugin {
        archiveVersion = ""
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    named<Test>("test") {
        useJUnit()
        systemProperty("java.awt.headless", "true")
        // Load our plugin (and the database plugin it depends on) in the light test fixture.
        systemProperty("idea.load.plugins.id", buildList {
            add("com.intellij.database")
            add("dev.sort.duckdb-intellij-plugin")
            if (companionInstalled) add("dev.sort.sql-transpiler-intellij-plugin")
            if (dorisZip != null) add("dev.sort.doris-intellij-plugin")
        }.joinToString(","))
        // DuckDB syntax corpus for the substrate scoreboard (DuckdbSyntaxProbeTest).
        systemProperty("corpus.dir", layout.projectDirectory.dir("src/test/resources/corpus").asFile.absolutePath)
// Live quack-wire suites (QuackLiveTruthTest, DuckdbLiveHarvestOverQuackTest) are gated on
        // this property; forward it from the Gradle invocation (`-Dquack.live.url=...` or
        // `-Pquack.live.url=...`) into the forked test JVM. Absent => those tests JUnit-Assume
        // out, keeping the committed run offline-deterministic.
        val quackLiveUrl = providers.systemProperty("quack.live.url")
            .orElse(providers.gradleProperty("quack.live.url"))
            .orNull
        if (quackLiveUrl != null) systemProperty("quack.live.url", quackLiveUrl)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val verifyEmbeddedPipes by tasks.registering {
    val engineVersion = brikkSqlVersion
    group = "verification"
    description = "Checks bundled PIPE libraries, Java 21 bytecode, notices, and independence."
    val archive = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val ownJar = tasks.named<org.gradle.jvm.tasks.Jar>("composedJar").flatMap { it.archiveFileName }
    dependsOn("buildPlugin")
    inputs.file(archive)
    doLast {
        ZipFile(archive.get().asFile).use { zip ->
            val jars = zip.entries().asSequence().filter { it.name.endsWith(".jar") }.toList()
            val expected = setOf(ownJar.get(), "brikk-sql-jvmMain-$engineVersion.jar",
                "brikk-sql-metadata-jvmMain-$engineVersion.jar")
            check(jars.size == expected.size && jars.map { it.name.substringAfterLast('/') }.toSet() == expected) {
                "Unexpected bundled libraries: ${jars.map { it.name }}"
            }
            for (library in jars) JarInputStream(zip.getInputStream(library)).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (!entry.name.endsWith(".class")) continue
                    val header = jar.readNBytes(8)
                    check(header.size == 8)
                    val major = ((header[6].toInt() and 255) shl 8) or (header[7].toInt() and 255)
                    check(major <= 65) { "Java 21 incompatible: ${library.name}/${entry.name} ($major)" }
                }
            }
            val resources = mutableMapOf<String, String>()
            JarInputStream(zip.getInputStream(jars.single { it.name.substringAfterLast('/') == ownJar.get() })).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (entry.name in setOf("META-INF/plugin.xml", "META-INF/THIRD_PARTY_NOTICES.md", "META-INF/LICENSE"))
                        resources[entry.name] = jar.readBytes().toString(Charsets.UTF_8)
                }
            }
            check(resources.size == 3) { "Missing descriptor/notices" }
            check("brikk-sql-jvm:$engineVersion" in resources.getValue("META-INF/THIRD_PARTY_NOTICES.md"))
            check(!resources.getValue("META-INF/plugin.xml").contains("<depends>dev.sort.sql-transpiler"))
        }
    }
}
tasks.named("check") { dependsOn(verifyEmbeddedPipes) }

// Fixtures flatten loaders; normal companion tests must exercise our pinned engine, not theirs.
tasks.named<Test>("test") {
    if (companionInstalled) classpath = classpath.filter {
        !(it.path.contains("/sql-transpiler-intellij-plugin/") && it.name.startsWith("brikk-sql-"))
    }
    if (dorisZip != null) classpath = classpath.filter {
        !(it.path.contains("/doris-intellij-plugin/") && it.name.startsWith("brikk-sql-"))
    }
}
if (companionInstalled || dorisZip != null) tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
    sandboxSuffix.set("-test-pipes-peers")
}
if (providers.gradleProperty("test.pluginIsolation").orNull == "true") {
    val mainOutputs = sourceSets.main.get().output.files.map { it.absoluteFile }.toSet() +
        layout.buildDirectory.dir("instrumented/instrumentCode").get().asFile.absoluteFile
    val testResources = sourceSets.test.get().output.resourcesDir?.absoluteFile
    tasks.named<Test>("test") {
        include("**/DuckdbPipesIsolationTest.class")
        systemProperty("test.pluginIsolation", "true")
        classpath = classpath.filter {
            it.absoluteFile !in mainOutputs && it.absoluteFile != testResources &&
                !it.path.contains("/sql-transpiler-intellij-plugin/") && !it.path.contains("/doris-intellij-plugin/") &&
                !it.name.startsWith("duckdb-intellij-plugin-") && !it.name.startsWith("brikk-sql-")
        }
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Didea.force.use.core.classloader=true", "-Didea.use.core.classloader.for.plugin.path=false")
        })
    }
}

// --- census harvest tooling (manual task; output is committed corpus) ---

sourceSets {
    create("tools")
}

dependencies {
    "toolsImplementation"("dev.brikk.ducklake:slt-format:0.3.0")
    "toolsImplementation"("org.duckdb:duckdb_jdbc:1.5.5.0")
}

// Usage: ./gradlew harvestCensus [-PduckdbSrc=/path/to/duckdb-checkout]
// Reads <duckdbSrc>/test/sql/**.test, samples per family via slt-format, writes
// src/test/resources/corpus/census/ + census-negatives.jsonl. Commit the output.
// Usage: ./gradlew harvestFunctionCatalog — regenerates the bundled function/keyword catalog
// resources from the pinned duckdb_jdbc (self-updating with the dependency bump). Commit output.
val harvestFunctionCatalog by tasks.registering(JavaExec::class) {
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass = "dev.sort.duckdb.tools.FunctionCatalogHarvestKt"
    args(layout.projectDirectory.dir("src/main/resources/duckdb").asFile.absolutePath)
}

// Usage: ./gradlew harvestExtensionCatalog — regenerates extension-functions.tsv by diffing
// duckdb_functions() across INSTALL+LOAD for each curated extension (network required — INSTALL
// pulls from the DuckDB extension repo). Depends on functions.tsv (the base catalog) for its
// no-overlap assertion. Commit output.
val harvestExtensionCatalog by tasks.registering(JavaExec::class) {
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass = "dev.sort.duckdb.tools.ExtensionCatalogHarvestKt"
    args(layout.projectDirectory.dir("src/main/resources/duckdb").asFile.absolutePath)
}

val harvestCensus by tasks.registering(JavaExec::class) {
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass = "dev.sort.duckdb.tools.SltCensusHarvestKt"
    val src = providers.gradleProperty("duckdbSrc")
        .getOrElse(layout.projectDirectory.dir("../references/duckdb-upstream").asFile.absolutePath)
    args(
        src,
        layout.projectDirectory.dir("src/test/resources/corpus/census").asFile.absolutePath,
        layout.projectDirectory.file("src/test/resources/corpus/census-negatives.jsonl").asFile.absolutePath,
    )
}
