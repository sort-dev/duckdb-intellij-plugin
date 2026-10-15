pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            // Shared editor-popup baseline: 74af818ed072d2a0fcfa8120e477867de0e2375d.
            version("brikk-sql-core", "0.20.0-SNAPSHOT")
            version("brikk-sql-intellij-support", "0.2.0-SNAPSHOT")
            version("brikk-engine-kotlin-intellij-support", "0.3.0-SNAPSHOT")
            version("duckdb-jdbc", "1.5.5.0")
        }
    }
}

rootProject.name = "duckdb-intellij-plugin"
