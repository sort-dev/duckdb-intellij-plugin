package dev.sort.duckdb.brikk

import dev.brikk.house.sql.intellij.host.HostSqlChannel
import dev.brikk.house.sql.intellij.host.HostSqlPlaceholderErrorFilter

object DuckdbHostSql : HostSqlChannel("dev.sort.duckdb")

class DuckdbPlaceholderErrorFilter : HostSqlPlaceholderErrorFilter(DuckdbHostSql)
