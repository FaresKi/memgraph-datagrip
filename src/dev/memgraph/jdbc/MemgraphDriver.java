package dev.memgraph.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC driver for Memgraph. URL: jdbc:memgraph://host[:port][/database]
 * Delegates Bolt transport and Cypher execution to the Neo4j JDBC driver (without SQL translation)
 * and synthesizes tables (node labels) and columns (properties) for DatabaseMetaData.
 */
public class MemgraphDriver implements Driver {
    static final String PREFIX = "jdbc:memgraph:";
    private static final Pattern URL = Pattern.compile("^jdbc:memgraph://([^/:?]+)(?::(\\d+))?(?:/([^?]*))?(?:\\?(.*))?$");

    static {
        try {
            DriverManager.registerDriver(new MemgraphDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        Matcher m = URL.matcher(url);
        if (!m.matches()) throw new SQLException("Invalid URL, expected jdbc:memgraph://host[:port][/db]: " + url);
        String port = m.group(2) == null ? "7687" : m.group(2);
        String db = m.group(3) == null || m.group(3).isEmpty() ? "memgraph" : m.group(3);
        StringBuilder target = new StringBuilder("jdbc:neo4j://").append(m.group(1)).append(':').append(port)
                .append('/').append(db).append("?enableSQLTranslation=false");
        if (m.group(4) != null) target.append('&').append(m.group(4));
        Driver neo4j;
        try {
            neo4j = (Driver) Class.forName("org.neo4j.jdbc.Neo4jDriver", true, MemgraphDriver.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Neo4j JDBC bundle (neo4j-jdbc-full-bundle) not found on the driver classpath", e);
        }
        Properties props = new Properties();
        if (info != null) props.putAll(info);
        Connection delegate = neo4j.connect(target.toString(), props);
        return new MemgraphConnection(delegate, db, url).proxy();
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(PREFIX); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 0; }
    @Override public int getMinorVersion() { return 1; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }

    static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(MemgraphDriver.class.getClassLoader(), new Class<?>[]{type}, h);
    }
}
