#!/bin/sh
# Builds out/memgraph-jdbc.jar and downloads the Neo4j JDBC bundle (Bolt transport) into libs/.
set -e
cd "$(dirname "$0")"
NEO4J_JDBC=6.15.1
JDK=${JAVA_HOME:-/Applications/DataGrip.app/Contents/jbr/Contents/Home}
JAVAC=$JDK/bin/javac; [ -x "$JAVAC" ] || JAVAC=javac
rm -rf out && mkdir -p out/classes libs
"$JAVAC" -d out/classes $(find src -name '*.java')
cp -r src/META-INF out/classes/
(cd out/classes && zip -qr ../memgraph-jdbc.jar .)
JAR=libs/neo4j-jdbc-full-bundle-$NEO4J_JDBC.jar
[ -f "$JAR" ] || curl -fsSL -o "$JAR" \
  "https://repo1.maven.org/maven2/org/neo4j/neo4j-jdbc-full-bundle/$NEO4J_JDBC/neo4j-jdbc-full-bundle-$NEO4J_JDBC.jar"
echo "built out/memgraph-jdbc.jar (+ $JAR)"
