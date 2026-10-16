package dev.memgraph.jdbc;

import java.sql.*;
import java.util.*;

import static dev.memgraph.jdbc.MemgraphDriver.proxy;

/** Minimal in-memory ResultSet used for synthesized DatabaseMetaData results. */
final class MemResultSet {
    private final List<String> names;
    private final List<Object[]> rows;
    private int pos = -1;
    private boolean wasNull;
    private boolean closed;

    private MemResultSet(List<String> names, List<Object[]> rows) {
        this.names = names;
        this.rows = rows;
    }

    static ResultSet of(List<String> names, List<Object[]> rows) {
        MemResultSet rs = new MemResultSet(names, rows);
        return proxy(ResultSet.class, (p, m, a) -> rs.invoke(m.getName(), a, p));
    }

    private int index(Object col) throws SQLException {
        if (col instanceof Integer) return (Integer) col - 1;
        int i = names.indexOf(String.valueOf(col).toUpperCase(Locale.ROOT));
        if (i < 0) throw new SQLException("No column " + col);
        return i;
    }

    private Object value(Object col) throws SQLException {
        if (pos < 0 || pos >= rows.size()) throw new SQLException("No current row");
        Object v = rows.get(pos)[index(col)];
        wasNull = v == null;
        return v;
    }

    private Object invoke(String name, Object[] a, Object self) throws SQLException {
        switch (name) {
            case "next": return ++pos < rows.size();
            case "close": closed = true; return null;
            case "isClosed": return closed;
            case "wasNull": return wasNull;
            case "findColumn": return index(a[0]) + 1;
            case "getObject": case "getString": {
                Object v = value(a[0]);
                return name.equals("getString") && v != null ? v.toString() : v;
            }
            case "getInt": case "getShort": case "getLong": case "getBoolean": case "getDouble": {
                Object v = value(a[0]);
                if (v == null) return name.equals("getBoolean") ? (Object) false : zero(name);
                if (name.equals("getBoolean")) return v instanceof Boolean ? v : Boolean.parseBoolean(v.toString());
                Number n = v instanceof Number ? (Number) v : Double.valueOf(v.toString());
                switch (name) {
                    case "getInt": return n.intValue();
                    case "getShort": return n.shortValue();
                    case "getLong": return n.longValue();
                    default: return n.doubleValue();
                }
            }
            case "getMetaData": return metaData();
            case "getType": return ResultSet.TYPE_FORWARD_ONLY;
            case "getConcurrency": return ResultSet.CONCUR_READ_ONLY;
            case "getFetchSize": return 0;
            case "setFetchSize": return null;
            case "getStatement": return null;
            case "getWarnings": return null;
            case "clearWarnings": return null;
            case "isWrapperFor": return false;
            case "hashCode": return System.identityHashCode(self);
            case "equals": return self == a[0];
            case "toString": return "MemResultSet" + names;
            default: throw new SQLFeatureNotSupportedException(name);
        }
    }

    private static Object zero(String name) {
        switch (name) {
            case "getInt": return 0;
            case "getShort": return (short) 0;
            case "getLong": return 0L;
            default: return 0.0;
        }
    }

    private ResultSetMetaData metaData() {
        return proxy(ResultSetMetaData.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getColumnCount": return names.size();
                case "getColumnName": case "getColumnLabel": return names.get((Integer) a[0] - 1);
                case "getColumnType": return rows.isEmpty() || rows.get(0)[(Integer) a[0] - 1] instanceof Number ? Types.INTEGER : Types.VARCHAR;
                case "getColumnTypeName": return "VARCHAR";
                case "getColumnClassName": return "java.lang.Object";
                case "getTableName": case "getSchemaName": case "getCatalogName": return "";
                case "isNullable": return ResultSetMetaData.columnNullable;
                case "isWrapperFor": return false;
                default: return null;
            }
        });
    }
}
