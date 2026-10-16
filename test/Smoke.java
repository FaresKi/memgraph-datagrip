import java.sql.*;
public class Smoke {
  static void dump(ResultSet r, int max) throws Exception {
    ResultSetMetaData m=r.getMetaData(); int n=0;
    for(int i=1;i<=m.getColumnCount();i++) System.out.print(m.getColumnLabel(i)+" | "); System.out.println();
    while(r.next()&&n++<max){ for(int i=1;i<=m.getColumnCount();i++){Object o=r.getObject(i); String s=String.valueOf(o); System.out.print((s.length()>40?s.substring(0,40):s)+" | ");} System.out.println(); }
  }
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:memgraph://localhost:7687")) {
      DatabaseMetaData md = c.getMetaData();
      System.out.println("== tables"); dump(md.getTables(null,null,"%",new String[]{"TABLE"}), 50);
      System.out.println("== columns of title"); try(ResultSet r=md.getColumns(null,null,"title","%")){dump(r,50);}
      String[] qs = {"select * from title limit 3","SELECT count(*) FROM \"title\"","select t.__id, t.graph_id from public.title t order by t.__id desc limit 2 offset 1","MATCH (n:policy) RETURN n.id LIMIT 2","select 1","select * from edges.leads_to limit 3","select count(*) from edges.leads_to","select __source, __target from leads_to order by __id limit 2"};
      for (String q: qs){ System.out.println("== "+q); try(Statement s=c.createStatement(); ResultSet r=s.executeQuery(q)){dump(r,5);} catch(Exception e){System.out.println("ERR "+e.getMessage());} }
    }
  }
}
