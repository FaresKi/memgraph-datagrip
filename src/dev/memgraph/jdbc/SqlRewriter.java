package dev.memgraph.jdbc;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the simple SQL that DataGrip issues against "tables" into Cypher.
 * Anything it does not recognise is passed through unchanged (so raw Cypher keeps working).
 */
final class SqlRewriter {
    static final String ID = "__id";
    static final String SOURCE = "__source";
    static final String TARGET = "__target";
    private static final String IDENT = "(?:\"[^\"]+\"|`[^`]+`|\\w+)";
    private static final Pattern SELECT = Pattern.compile(
            "^\\s*select\\s+(?<proj>.+?)\\s+from\\s+(?<tbl>" + IDENT + "(?:\\." + IDENT + ")*)"
                    + "(?:\\s+(?:as\\s+)?(?!(?:where|order|limit|offset|fetch)\\b)(?<alias>\\w+))?"
                    + "(?<tail>\\s+.*)?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NO_FROM = Pattern.compile("^\\s*select\\s+(?<e>[^;]*?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LIMIT = Pattern.compile("\\s+limit\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFSET = Pattern.compile("\\s+offset\\s+(\\d+)(?:\\s+rows?)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER = Pattern.compile("(?:^|\\s)order\\s+by\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WHERE = Pattern.compile("^\\s*where\\s+(.+?)(?=\\s+order\\s+by\\s|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("'(?:[^'\\\\]|''|\\\\.)*'|\"[^\"]*\"|\\w+|\\s+|.", Pattern.DOTALL);

    private SqlRewriter() {}

    static String rewrite(String sql, GraphSchema schema) {
        if (sql == null) return null;
        String trimmed = sql.trim();
        if (!trimmed.regionMatches(true, 0, "select", 0, 6)) return sql;
        Matcher m = SELECT.matcher(trimmed);
        if (!m.matches()) {
            Matcher nf = NO_FROM.matcher(trimmed);
            return nf.matches() && !nf.group("e").toLowerCase(Locale.ROOT).contains(" from ") ? "RETURN " + nf.group("e") : sql;
        }
        String[] parts = m.group("tbl").split("\\.(?=(?:[^\"`]*[\"`][^\"`]*[\"`])*[^\"`]*$)");
        String label = unquote(parts[parts.length - 1]);
        String qualifier = parts.length > 1 ? unquote(parts[parts.length - 2]) : null;
        boolean edge;
        String realLabel;
        Map<String, String> columns;
        String nodeName = qualifier == null || qualifier.equalsIgnoreCase(GraphSchema.NODES) || qualifier.equalsIgnoreCase("public") ? labelName(schema.labels, label) : null;
        String edgeName = qualifier == null || qualifier.equalsIgnoreCase(GraphSchema.EDGES) ? labelName(schema.edges, label) : null;
        if (nodeName != null) { edge = false; realLabel = nodeName; columns = schema.labels.get(nodeName); }
        else if (edgeName != null) { edge = true; realLabel = edgeName; columns = schema.edges.get(edgeName); }
        else return sql;
        String alias = m.group("alias");
        List<String> colNames = new ArrayList<>(columns.keySet());

        String tail = m.group("tail") == null ? "" : m.group("tail");
        String limit = null, offset = null;
        Matcher lm = LIMIT.matcher(tail);
        if (lm.find()) { limit = lm.group(1); tail = tail.substring(0, lm.start()); }
        Matcher om = OFFSET.matcher(tail);
        if (om.find()) { offset = om.group(1); tail = tail.substring(0, om.start()); }
        lm = LIMIT.matcher(tail); // "OFFSET x LIMIT y" ordering
        if (limit == null && lm.find()) { limit = lm.group(1); tail = tail.substring(0, lm.start()); }

        String where = null, order = null;
        Matcher om2 = ORDER.matcher(tail);
        String beforeOrder = tail;
        if (om2.find()) { order = om2.group(1).trim(); beforeOrder = tail.substring(0, om2.start()); }
        Matcher wm = WHERE.matcher(beforeOrder);
        if (wm.find()) where = wm.group(1).trim();
        else if (!beforeOrder.trim().isEmpty()) return sql;

        String rl = realLabel.replace("`", "``");
        StringBuilder q = new StringBuilder(edge ? "MATCH (s)-[n:`" + rl + "`]->(t)" : "MATCH (n:`" + rl + "`)");
        try {
            if (where != null) q.append(" WHERE ").append(translateExpr(where, colNames, alias, edge));
            String proj = m.group("proj").trim();
            if (proj.equalsIgnoreCase("count(*)") || proj.equalsIgnoreCase("count(1)")) {
                q.append(" RETURN count(n) AS count");
                if (limit != null || offset != null || order != null) return sql;
                return q.toString();
            }
            q.append(" RETURN ").append(projection(proj, colNames, alias, edge));
            if (order != null) q.append(" ORDER BY ").append(translateExpr(order, colNames, alias, edge));
        } catch (IllegalArgumentException e) {
            return sql;
        }
        if (offset != null) q.append(" SKIP ").append(offset);
        if (limit != null) q.append(" LIMIT ").append(limit);
        return q.toString();
    }

    private static String projection(String proj, List<String> cols, String alias, boolean edge) {
        if (proj.equals("*") || (alias != null && proj.equalsIgnoreCase(alias + ".*"))) {
            StringBuilder b = new StringBuilder("id(n) AS `" + ID + "`");
            if (edge) b.append(", id(s) AS `").append(SOURCE).append("`, id(t) AS `").append(TARGET).append('`');
            for (String c : cols) b.append(", n.`").append(c.replace("`", "``")).append("` AS `").append(c.replace("`", "``")).append('`');
            return b.toString();
        }
        List<String> out = new ArrayList<>();
        for (String item : proj.split(",(?=(?:[^\"`]*[\"`][^\"`]*[\"`])*[^\"`]*$)")) {
            Matcher a = Pattern.compile("^(.+?)(?:\\s+as\\s+(" + IDENT + "))?$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(item.trim());
            if (!a.matches()) throw new IllegalArgumentException();
            String expr = translateExpr(a.group(1), cols, alias, edge);
            String name = a.group(2) != null ? unquote(a.group(2)) : unquote(a.group(1).trim().replaceFirst("^\\w+\\.", ""));
            out.add(expr + " AS `" + name.replace("`", "``") + "`");
        }
        return String.join(", ", out);
    }

    /** Replaces column references with property accesses; unknown identifiers are rejected. */
    private static String translateExpr(String expr, List<String> cols, String alias, boolean edge) {
        if (alias != null) expr = expr.replaceAll("(?i)\\b" + Pattern.quote(alias) + "\\.", "");
        Matcher t = TOKEN.matcher(expr);
        StringBuilder out = new StringBuilder();
        Set<String> keywords = Set.of("and", "or", "not", "is", "null", "true", "false", "asc", "desc", "in", "like", "count");
        while (t.find()) {
            String tok = t.group();
            if (tok.startsWith("'")) {
                out.append("'").append(tok.substring(1, tok.length() - 1).replace("''", "\\'")).append("'");
            } else if (tok.startsWith("\"") || tok.matches("\\w+")) {
                boolean quoted = tok.startsWith("\"");
                String name = quoted ? tok.substring(1, tok.length() - 1) : tok;
                if (!quoted && (keywords.contains(name.toLowerCase(Locale.ROOT)) || name.matches("\\d+"))) out.append(name);
                else out.append(columnRef(name, cols, edge));
            } else {
                out.append(tok);
            }
        }
        return out.toString();
    }

    private static String columnRef(String name, List<String> cols, boolean edge) {
        if (name.equals(ID)) return "id(n)";
        if (edge && name.equals(SOURCE)) return "id(s)";
        if (edge && name.equals(TARGET)) return "id(t)";
        for (String c : cols) if (c.equals(name)) return "n.`" + c.replace("`", "``") + "`";
        for (String c : cols) if (c.equalsIgnoreCase(name)) return "n.`" + c.replace("`", "``") + "`";
        throw new IllegalArgumentException("Unknown column " + name);
    }

    private static String labelName(Map<String, ?> m, String label) {
        if (m.containsKey(label)) return label;
        for (String l : m.keySet()) if (l.equalsIgnoreCase(label)) return l;
        return null;
    }

    static String unquote(String id) {
        id = id.trim();
        if (id.length() >= 2 && (id.startsWith("\"") || id.startsWith("`"))) return id.substring(1, id.length() - 1);
        return id;
    }
}
