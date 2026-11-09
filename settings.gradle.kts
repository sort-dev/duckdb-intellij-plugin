pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            version("brikk-sql-core", "1.0.1")
            version("brikk-sql-verify", "1.0.1")
            version("brikk-sql-intellij-support", "1.0.1")
            version("duckdb-jdbc", "2.0.0.0")
            version("intellij-runtime-263", "263.7307.44")
            version("intellij-verifier-ides", "DB-2026.1.3 IU-262.10968.63 DB-2026.2.5 IU-263.4732.28 IU-263.7307.44")
        }
    }
}

rootProject.name = "duckdb-intellij-plugin"
