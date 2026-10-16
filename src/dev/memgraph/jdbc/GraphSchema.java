package dev.memgraph.jdbc;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Label -> (property -> SQL type name) introspected from Memgraph. */
final class GraphSchema {
    private static final Pattern LABEL = Pattern.compile("`([^`]+)`");
    static final String NODES = "nodes";
    static final String EDGES = "edges";
    final Map<String, LinkedHashMap<String, String>> labels = new TreeMap<>();
    final Map<String, LinkedHashMap<String, String>> edges = new TreeMap<>();

    static GraphSchema load(Connection c) throws SQLException {
        GraphSchema s = new GraphSchema();
        try {
            s.loadFromSchemaModule(c);
        } catch (SQLException e) {
            s.labels.clear();
            s.loadByScan(c);
        }
        try {
            s.loadEdges(c);
        } catch (SQLException e) {
            s.edges.clear();
        }
        return s;
    }

    private void loadEdges(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet r = st.executeQuery("CALL schema.rel_type_properties() YIELD relType, propertyName, propertyTypes "
                     + "RETURN relType, propertyName, propertyTypes")) {
            while (r.next()) {
                String relType = r.getString(1);
                String prop = r.getString(2);
                String type = sqlType(String.valueOf(r.getObject(3)));
                Matcher m = LABEL.matcher(relType == null ? "" : relType);
                while (m.find()) {
                    LinkedHashMap<String, String> cols = edges.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>());
                    if (prop != null && !prop.isEmpty()) cols.merge(prop, type, (a, b) -> a.equals(b) ? a : "VARCHAR");
                }
            }
        }
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("MATCH ()-[r]->() RETURN DISTINCT type(r)")) {
            while (r.next()) edges.computeIfAbsent(r.getString(1), k -> new LinkedHashMap<>());
        }
    }

    private void loadFromSchemaModule(Connection c) throws SQLException {
        String q = "CALL schema.node_type_properties() YIELD nodeType, propertyName, propertyTypes "
                + "RETURN nodeType, propertyName, propertyTypes";
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(q)) {
            while (r.next()) {
                String nodeType = r.getString(1);
                String prop = r.getString(2);
                String type = sqlType(String.valueOf(r.getObject(3)));
                Matcher m = LABEL.matcher(nodeType == null ? "" : nodeType);
                while (m.find()) {
                    LinkedHashMap<String, String> cols = labels.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>());
                    if (prop != null && !prop.isEmpty()) cols.merge(prop, type, (a, b) -> a.equals(b) ? a : "VARCHAR");
                }
            }
        }
    }

    private void loadByScan(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet r = st.executeQuery("MATCH (n) UNWIND labels(n) AS l RETURN DISTINCT l")) {
            while (r.next()) labels.computeIfAbsent(r.getString(1), k -> new LinkedHashMap<>());
        }
        try (Statement st = c.createStatement();
             ResultSet r = st.executeQuery("MATCH (n) UNWIND labels(n) AS l UNWIND keys(n) AS k RETURN DISTINCT l, k")) {
            while (r.next()) labels.computeIfAbsent(r.getString(1), x -> new LinkedHashMap<>()).put(r.getString(2), "VARCHAR");
        }
    }

    /** Maps Memgraph property type lists such as "[Int]" to a SQL type name. */
    static String sqlType(String propertyTypes) {
        String t = propertyTypes.replaceAll("[\\[\\]\\s\"]", "");
        if (t.contains(",")) return "VARCHAR";
        switch (t) {
            case "Int": return "BIGINT";
            case "Float": return "DOUBLE";
            case "Bool": return "BOOLEAN";
            default: return "VARCHAR";
        }
    }

    static int jdbcType(String sqlType) {
        switch (sqlType) {
            case "BIGINT": return Types.BIGINT;
            case "DOUBLE": return Types.DOUBLE;
            case "BOOLEAN": return Types.BOOLEAN;
            default: return Types.VARCHAR;
        }
    }
}
