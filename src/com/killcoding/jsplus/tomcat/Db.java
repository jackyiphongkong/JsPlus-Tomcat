package com.killcoding.jsplus.tomcat;

import java.sql.Connection;

import com.killcoding.datasource.CacheDriverExecutor;

/**
 * Minimal JDBC helpers shared by the management APIs.
 * <p>
 * Every {@code CacheDriverExecutor} owns the {@code Connection} it was created
 * with, so closing the executor closes the connection too. These helpers keep
 * that contract in one place instead of repeating the
 * {@code getDataSource().getConnection()} / close dance in every API method.
 */
public final class Db {

    private Db() {
    }

    /** Open a connection from the master script console's data source. */
    public static Connection getConnection() throws Exception {
        return TomcatScriptConsole.getMasterConsole().getDataSource().getConnection();
    }

    /** Close a {@link CacheDriverExecutor} (and its connection) quietly. */
    public static void closeQuietly(CacheDriverExecutor cde) {
        if (cde != null) {
            try {
                cde.close();
            } catch (Exception e) {
                // Closing is best-effort; nothing to do if it fails.
            }
        }
    }
}
