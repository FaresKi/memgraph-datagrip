# Memgraph for DataGrip

A small JDBC driver (`jdbc:memgraph://host[:port][/db]`) that makes Memgraph browsable in DataGrip.

- Node labels appear as **tables**; node properties (sampled via `schema.node_type_properties()`) as **columns**; `__id` is the internal node id (primary key).
- `SELECT * FROM label [WHERE ...] [ORDER BY ...] [LIMIT n] [OFFSET m]` and `count(*)` are rewritten to Cypher. Any other statement (e.g. raw `MATCH ...`) is passed through unchanged.
- Bolt transport is delegated to the Neo4j JDBC driver (`libs/neo4j-jdbc-full-bundle-*.jar`, SQL translation disabled).

## Install
1. `./build.sh` (needs a JDK 11+, or DataGrip's bundled JBR; downloads the Neo4j JDBC bundle into `libs/`).
2. Quit DataGrip, then merge `examples/databaseDrivers.xml` into your DataGrip config
   (macOS: `~/Library/Application Support/JetBrains/DataGrip<version>/options/databaseDrivers.xml`) and fix the jar paths.
   Alternatively add a driver manually: Data Sources > Drivers > + , add both jars, class `dev.memgraph.jdbc.MemgraphDriver`,
   URL template `jdbc:memgraph://{host}:{port}`.
3. New data source > **Memgraph** (`jdbc:memgraph://localhost:7687`). See `examples/dataSources.xml`.

## Test
Needs a Memgraph on localhost:7687 (`test/Smoke.java` prints results, `test/Isolated.java` checks isolated-classloader loading):
```
JDK=/Applications/DataGrip.app/Contents/jbr/Contents/Home/bin
$JDK/javac -d out/test test/*.java
$JDK/java -cp out/test:out/memgraph-jdbc.jar:libs/neo4j-jdbc-full-bundle-6.15.1.jar Smoke
```

Limitations: read-oriented; WHERE supports simple comparisons only (no LIKE/joins). Licensed MIT; the Neo4j JDBC bundle (Apache-2.0) is downloaded, not redistributed.

## Nodes and edges as tables
- Schema `nodes`: one table per node label (`__id` + properties).
- Schema `edges`: one table per relationship type (`__id`, `__source`, `__target` + properties).
- `select * from edges.leads_to`; unqualified names resolve to nodes first, then edges (`public.` is an alias for `nodes.`).
