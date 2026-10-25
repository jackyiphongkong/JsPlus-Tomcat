/*******************************************************************************
The MIT License (MIT)

Copyright (c) 2024 KILLCODING.COM

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*****************************************************************************/
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
