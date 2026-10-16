package dev.memgraph.jdbc;

import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

import static dev.memgraph.jdbc.MemgraphDriver.call;

/** Connection wrapper: rewrites SQL, and serves table/column metadata from the graph schema. */
final class MemgraphConnection {
    private static final String SCHEMA = GraphSchema.NODES;

    private final Connection delegate;
    private final String catalog;
    private final String url;
    private GraphSchema schema;
    private Connection self;

    MemgraphConnection(Connection delegate, String catalog, String url) {
        this.delegate = delegate;
        this.catalog = catalog;
        this.url = url;
    }

    Connection proxy() {
        self = MemgraphDriver.proxy(Connection.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getMetaData": return metaData();
                case "getCatalog": return catalog;
                case "getSchema": return SCHEMA;
                case "setSchema": case "setCatalog": return null;
                case "createStatement": case "prepareStatement": case "prepareCall": {
                    if (a != null && a.length > 0 && a[0] instanceof String) {
                        a = a.clone();
                        a[0] = rewrite((String) a[0]);
                    }
                    Object st = call(delegate, m, a);
                    return m.getName().equals("createStatement") ? wrapStatement((Statement) st, Statement.class)
                            : wrapStatement(st, m.getReturnType());
                }
                case "nativeSQL": return rewrite((String) a[0]);
                default: return call(delegate, m, a);
            }
        });
        return self;
    }

    private String rewrite(String sql) throws SQLException {
        if (sql == null || !sql.trim().regionMatches(true, 0, "select", 0, 6)) return sql;
        return SqlRewriter.rewrite(sql, schema());
    }

    private synchronized GraphSchema schema() throws SQLException {
        if (schema == null) schema = GraphSchema.load(delegate);
        return schema;
    }

    private synchronized GraphSchema refreshSchema() throws SQLException {
        schema = GraphSchema.load(delegate);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private <T> T wrapStatement(Object st, Class<?> type) {
        return (T) MemgraphDriver.proxy((Class<Object>) type, (p, m, a) -> {
            String n = m.getName();
            if (a != null && a.length > 0 && a[0] instanceof String
                    && (n.startsWith("execute") || n.equals("addBatch"))) {
                a = a.clone();
                a[0] = rewrite((String) a[0]);
            }
            if (n.equals("getConnection")) return self;
            return call(st, m, a);
        });
    }

    private DatabaseMetaData metaData() throws SQLException {
        DatabaseMetaData real = delegate.getMetaData();
        return MemgraphDriver.proxy(DatabaseMetaData.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getConnection": return self;
                case "getURL": return url;
                case "getDatabaseProductName": return "Memgraph";
                case "getCatalogs": return rs(List.of("TABLE_CAT"), List.<Object[]>of(new Object[]{catalog}));
                case "getSchemas": return rs(List.of("TABLE_SCHEM", "TABLE_CATALOG"),
                        List.<Object[]>of(new Object[]{GraphSchema.NODES, catalog}, new Object[]{GraphSchema.EDGES, catalog}));
                case "getTableTypes": return rs(List.of("TABLE_TYPE"), List.<Object[]>of(new Object[]{"TABLE"}));
                case "getTables": return tables(a);
                case "getColumns": return columns(a);
                case "getPrimaryKeys": return primaryKeys(a);
                case "getImportedKeys": case "getExportedKeys": case "getCrossReference":
                    return rs(List.of("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT",
                            "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE",
                            "FK_NAME", "PK_NAME", "DEFERRABILITY"), List.of());
                case "getIndexInfo":
                    return rs(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
                            "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY",
                            "PAGES", "FILTER_CONDITION"), List.of());
                case "getProcedures": case "getFunctions":
                    return rs(List.of("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "REMARKS", "PROCEDURE_TYPE",
                            "SPECIFIC_NAME"), List.of());
                case "getProcedureColumns": case "getFunctionColumns": case "getPseudoColumns":
                case "getVersionColumns": case "getBestRowIdentifier": case "getAttributes": case "getSuperTypes":
                case "getClientInfoProperties":
                    return rs(List.of("COLUMN_NAME"), List.of());
                case "getSuperTables": case "getUDTs": case "getTablePrivileges": case "getColumnPrivileges":
                    return rs(List.of("TABLE_CAT"), List.of());
                default: return call(real, m, a);
            }
        });
    }

    private static ResultSet rs(List<String> cols, List<Object[]> rows) {
        return MemResultSet.of(cols, rows);
    }

    private static boolean like(String pattern, String value) {
        if (pattern == null) return true;
        StringBuilder re = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            if (ch == '%') re.append(".*");
            else if (ch == '_') re.append('.');
            else re.append(Pattern.quote(String.valueOf(ch)));
        }
        return Pattern.compile(re.toString(), Pattern.DOTALL).matcher(value).matches();
    }

    private boolean catMatches(Object cat) {
        return cat == null || catalog.equals(cat);
    }

    private static List<String> schemaNames() {
        return List.of(GraphSchema.NODES, GraphSchema.EDGES);
    }

    private static Map<String, LinkedHashMap<String, String>> tablesOf(GraphSchema s, String schema) {
        return schema.equals(GraphSchema.NODES) ? s.labels : s.edges;
    }

    private ResultSet tables(Object[] a) throws SQLException {
        GraphSchema s = refreshSchema();
        List<Object[]> rows = new ArrayList<>();
        List<Object> types = a[3] == null ? null : Arrays.asList((Object[]) a[3]);
        if (catMatches(a[0]) && (types == null || types.contains("TABLE"))) {
            for (String sch : schemaNames()) {
                if (!like((String) a[1], sch)) continue;
                for (String name : tablesOf(s, sch).keySet())
                    if (like((String) a[2], name))
                        rows.add(new Object[]{catalog, sch, name, "TABLE",
                                sch.equals(GraphSchema.NODES) ? "Node label" : "Relationship type",
                                null, null, null, null, null});
            }
        }
        return rs(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT", "TYPE_SCHEM",
                "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"), rows);
    }

    private ResultSet columns(Object[] a) throws SQLException {
        GraphSchema s = schema();
        List<Object[]> rows = new ArrayList<>();
        if (catMatches(a[0])) {
            for (String sch : schemaNames()) {
                if (!like((String) a[1], sch)) continue;
                boolean edge = sch.equals(GraphSchema.EDGES);
                for (Map.Entry<String, LinkedHashMap<String, String>> e : tablesOf(s, sch).entrySet()) {
                    if (!like((String) a[2], e.getKey())) continue;
                    LinkedHashMap<String, String> cols = new LinkedHashMap<>();
                    cols.put(SqlRewriter.ID, "BIGINT");
                    if (edge) {
                        cols.put(SqlRewriter.SOURCE, "BIGINT");
                        cols.put(SqlRewriter.TARGET, "BIGINT");
                    }
                    cols.putAll(e.getValue());
                    int pos = 1;
                    for (Map.Entry<String, String> c : cols.entrySet()) {
                        if (!like((String) a[3], c.getKey())) { pos++; continue; }
                        boolean id = c.getKey().equals(SqlRewriter.ID);
                        boolean internal = c.getKey().startsWith("__") && cols.containsKey(c.getKey()) && !e.getValue().containsKey(c.getKey());
                        rows.add(new Object[]{catalog, sch, e.getKey(), c.getKey(), GraphSchema.jdbcType(c.getValue()),
                                c.getValue(), 65535, null, null, 10, id ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable,
                                internal ? "Internal id" : null, null, null, null, 65535, pos++, id ? "NO" : "YES",
                                null, null, null, null, "NO", "NO"});
                    }
                }
            }
        }
        return rs(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
                "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG",
                "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"), rows);
    }

    private ResultSet primaryKeys(Object[] a) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        if (a[2] != null) {
            GraphSchema s = schema();
            for (String sch : schemaNames())
                if ((a[1] == null || sch.equals(a[1])) && tablesOf(s, sch).containsKey((String) a[2]))
                    rows.add(new Object[]{catalog, sch, a[2], SqlRewriter.ID, (short) 1, "id"});
        }
        return rs(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"), rows);
    }
}
