pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            // Shared wiring baseline: integration 64c35c0; runtime b8cf969.
            version("brikk-sql-core", "0.20.0-SNAPSHOT")
            version("brikk-sql-intellij-support", "0.2.0-SNAPSHOT")
            version("brikk-engine-kotlin-intellij-support", "0.3.0-SNAPSHOT")
            version("duckdb-jdbc", "1.5.5.0")
        }
    }
}

rootProject.name = "duckdb-intellij-plugin"
