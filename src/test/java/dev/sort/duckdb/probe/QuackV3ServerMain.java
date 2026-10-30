package dev.sort.duckdb.probe;

import java.sql.DriverManager;

/** Test-owned native server process. The parent can kill it if a cancellation regression hangs. */
public final class QuackV3ServerMain {
    public static void main(String[] args) throws Exception {
        String uri = "quack:127.0.0.1:" + Integer.parseInt(args[0]);
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
             var statement = connection.createStatement()) {
            statement.execute("SET autoinstall_known_extensions=false");
            statement.execute("SET autoload_known_extensions=false");
            statement.execute("SET allow_persistent_secrets=false");
            statement.execute("SET threads=2");
            statement.execute("SET memory_limit='256MB'");
            statement.execute("SET extension_directory=" + literal(args[4]));
            statement.execute("LOAD " + literal(args[2]));
            statement.execute("LOAD " + literal(args[3]));
            statement.execute("CALL quack_serve(" + literal(uri) + ", disable_ssl:=true, token:="
                    + literal(args[1]) + ", create_secret_if_not_exists:=false)");
            try {
                System.out.println("READY");
                System.out.flush();
                System.in.read();
            } finally {
                statement.execute("CALL quack_stop(" + literal(uri) + ")");
            }
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
