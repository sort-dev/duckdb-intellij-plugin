plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.duckdb"
version = "0.3.5"

sqlDialectPlugin {
    embeddedLibraries = with(libs.versions) {
        setOf(
            "brikk-sql-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-metadata-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-engine-kotlin-intellij-support-${brikk.engine.kotlin.intellij.support.get()}.jar",
            "brikk-sql-intellij-support-${brikk.sql.intellij.support.get()}.jar",
        )
    }
    requiredNotices = with(libs.versions) {
        listOf(
            "brikk-sql-jvm:${brikk.sql.core.get()}",
            "brikk-engine-kotlin-intellij-support:${brikk.engine.kotlin.intellij.support.get()}",
            "brikk-sql-intellij-support:${brikk.sql.intellij.support.get()}",
        )
    }
}

dependencies {
    implementation(libs.brikk.sql.intellij.support)
    implementation(libs.brikk.engine.kotlin.intellij.support)
    implementation(libs.brikk.sql.jvm)
    testImplementation(libs.brikk.sql.intellij.test.support)
    testImplementation(libs.duckdb.jdbc)
    testImplementation("dev.brikk.duckdb:quack-jdbc:0.5.0")
    testImplementation("dev.brikk.ducklake:slt-format:0.3.0")
}

tasks.withType<Test>().configureEach {
    systemProperty("test.brikkSqlVersion", libs.versions.brikk.sql.core.get())
    systemProperty("corpus.dir", layout.projectDirectory.dir("src/test/resources/corpus").asFile.absolutePath)
    if (name == "test") {
        providers.gradleProperty("test.brikkCompilerPlugin").orNull?.let {
            require(file(it).isFile) { "Missing compiler fixture JAR: $it" }
            systemProperty("test.brikkCompilerPlugin", file(it).absolutePath)
        } ?: exclude("**/BrikkCompilerRelationTest.class")
        providers.systemProperty("quack.live.url")
            .orElse(providers.gradleProperty("quack.live.url"))
            .orNull?.let { systemProperty("quack.live.url", it) }
    } else {
        exclude("**/BrikkCompilerRelationTest.class")
    }
}

sourceSets {
    create("tools")
}

dependencies {
    "toolsImplementation"("dev.brikk.ducklake:slt-format:0.3.0")
    "toolsImplementation"(libs.duckdb.jdbc)
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
