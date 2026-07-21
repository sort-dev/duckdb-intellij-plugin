plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.sort.duckdb"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    // TESTS ONLY: an in-process DuckDB proves the direct-metadata path (duckdb_functions(),
    // duckdb_keywords(), PREPARE-as-validator) headlessly. The PLUGIN does not bundle the engine —
    // at runtime those queries go through the user's configured data source. (duckdb_jdbc with
    // natives is ~50 MB; bundling it would dwarf the plugin for something the driver provides.)
    testImplementation("org.duckdb:duckdb_jdbc:1.2.1")

    // TESTS ONLY: the GizmoSQL quack driver (brikk build) — QuackDriverFactsTest extracts the
    // driver class and URL scheme from the jar itself (acceptsURL needs no server), keeping
    // config/duckdb-brikk-drivers.xml honest. The plugin does not bundle or link this jar.
    testImplementation("dev.brikk.duckdb:quack-jdbc:0.3.0")

    // TESTS ONLY (for now): the sqllogictest format layer + expander — powers the upstream
    // duckdb test/sql census harvest (SltHarvestSmokeTest exercises the API contract).
    testImplementation("dev.brikk.ducklake:slt-format:0.2.0")

    intellijPlatform {
        // Same platform window as doris-intellij: compiled against DataGrip 2026.1 (build 261),
        // one artifact serving 261+262. Remote SDK so any clone/CI can build without an IDE.
        datagrip("2026.1.3")
        bundledPlugin("com.intellij.database")
        // Test-runtime transitive requirement of com.intellij.database (see doris-intellij notes).
        bundledPlugin("com.intellij.modules.json")
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
        ides {
            ide("DB", "2026.1.3")
            ide("IU", "262.8665.81")
        }
    }
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

tasks {
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
        // Load our plugin (and the database plugin it depends on) in the light test fixture.
        systemProperty("idea.load.plugins.id", "com.intellij.database,dev.sort.duckdb-intellij-plugin")
        // DuckDB syntax corpus for the substrate scoreboard (DuckdbSyntaxProbeTest).
        systemProperty("corpus.dir", layout.projectDirectory.dir("src/test/resources/corpus").asFile.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// --- census harvest tooling (manual task; output is committed corpus) ---

sourceSets {
    create("tools")
}

dependencies {
    "toolsImplementation"("dev.brikk.ducklake:slt-format:0.2.0")
}

// Usage: ./gradlew harvestCensus [-PduckdbSrc=/path/to/duckdb-checkout]
// Reads <duckdbSrc>/test/sql/**.test, samples per family via slt-format, writes
// src/test/resources/corpus/census/ + census-negatives.jsonl. Commit the output.
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
