import java.net.*; import java.sql.*; import java.util.Properties;
public class Isolated { public static void main(String[] a) throws Exception {
  URL[] u = {new java.io.File("out/memgraph-jdbc.jar").toURI().toURL(), new java.io.File("libs/neo4j-jdbc-full-bundle-6.15.1.jar").toURI().toURL()};
  try (URLClassLoader cl = new URLClassLoader(u, ClassLoader.getPlatformClassLoader())) {
    Driver d = (Driver) cl.loadClass("dev.memgraph.jdbc.MemgraphDriver").getDeclaredConstructor().newInstance();
    try (Connection c = d.connect("jdbc:memgraph://localhost:7687", new Properties());
         ResultSet r = c.getMetaData().getTables(null,null,"%",null)) { int n=0; while(r.next()) n++; System.out.println("tables="+n+" product="+c.getMetaData().getDatabaseProductName()); }
  } } }
