-- from keywords/escaped_quotes_expressions.test:5
PRAGMA enable_verification;

-- from keywords/escaped_quotes_expressions.test:8
CREATE SCHEMA "SCH""EMA";

-- from keywords/escaped_quotes_expressions.test:11
CREATE TYPE "EN""UM" AS ENUM('ALL');

-- from keywords/escaped_quotes_expressions.test:14
CREATE TABLE "SCH""EMA"."TA""BLE"("COL""UMN" "EN""UM");
