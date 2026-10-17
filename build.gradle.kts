plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.duckdb"
version = "0.4.1"

sqlDialectPlugin {
    embeddedLibraries = with(libs.versions) {
        setOf(
            "brikk-sql-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-metadata-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-engine-kotlin-intellij-support-${brikk.engine.kotlin.intellij.support.get()}.jar",
            "brikk-sql-intellij-support-${brikk.sql.intellij.support.get()}.jar",
            "brikk-sql-verify-api-${brikk.sql.core.get()}.jar",
            "brikk-sql-verify-duckdb-${brikk.sql.core.get()}.jar",
        )
    }
    requiredNotices = with(libs.versions) {
        listOf(
            "brikk-sql-jvm:${brikk.sql.core.get()}",
            "brikk-engine-kotlin-intellij-support:${brikk.engine.kotlin.intellij.support.get()}",
            "brikk-sql-intellij-support:${brikk.sql.intellij.support.get()}",
            "brikk-sql-verify-api:${brikk.sql.core.get()}",
            "brikk-sql-verify-duckdb:${brikk.sql.core.get()}",
        )
    }
}

dependencies {
    implementation(libs.brikk.sql.intellij.support)
    implementation(libs.brikk.engine.kotlin.intellij.support)
    implementation(libs.brikk.sql.jvm)
    implementation("dev.brikk.house:brikk-sql-verify-duckdb:${libs.versions.brikk.sql.core.get()}") {
        exclude(group = "org.duckdb")
    }
    testImplementation(libs.brikk.sql.intellij.test.support)
    testImplementation(libs.duckdb.jdbc)
    testImplementation("dev.brikk.duckdb:quack-jdbc:0.8.0")
    testImplementation("dev.brikk.ducklake:slt-format:0.3.0")
}

tasks.withType<Test>().configureEach {
    val testTmp = layout.buildDirectory.dir("test-tmp").get().asFile
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
    doFirst { check(testTmp.isDirectory || testTmp.mkdirs()) }
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
