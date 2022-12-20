package org.gbif.graph.clustering;

import org.apache.spark.api.java.function.ForeachPartitionFunction;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.Multiplicity;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.schema.JanusGraphManagement;
import scala.Tuple2;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Quick test to load an embedded JanusGraph backed by HBase.
 */
public class Spark {
  public static void main(String[] args) throws Exception {
    String warehouseLocation = new File("spark-warehouse").getAbsolutePath();
    SparkSession spark = SparkSession
            .builder()
            .appName("JanusGraph HBase Load")
            .config("spark.sql.warehouse.dir", warehouseLocation)
            .enableHiveSupport()
            .getOrCreate();

    // setup janus once in the driver to avoid race conditions in executors
    try (
        JanusGraph graph = (JanusGraph) GraphFactory.open(propertiesAsMap("/janus.properties"));
        GraphTraversalSource g = graph.traversal();
    ) {
      JanusGraphManagement mgmt = graph.openManagement();
      mgmt.makeVertexLabel("occurrence").make();
      mgmt.makeEdgeLabel("related").multiplicity(Multiplicity.SIMPLE).make();
      mgmt.makePropertyKey("gbifID").dataType(String.class).cardinality(Cardinality.SINGLE).make();
      mgmt.makePropertyKey("datasetKey").dataType(String.class).cardinality(Cardinality.SINGLE).make();
      mgmt.makePropertyKey("basisOfRecord").dataType(String.class).cardinality(Cardinality.SINGLE).make();
      mgmt.makePropertyKey("reason").dataType(String.class).cardinality(Cardinality.SINGLE).make();

      // HBase managed indexes
      PropertyKey gbifID = mgmt.getPropertyKey("gbifID");
      mgmt.buildIndex("byGbifID", Vertex.class).addKey(gbifID).buildCompositeIndex();
      //mgmt.buildIndex("byGbifID", Vertex.class).addKey(gbifID).unique().buildCompositeIndex();

      // ES managed indexes
      // TODO

      mgmt.commit();
      System.out.println("Finished initialised Janus");
    }

    Dataset<Row> df = spark.sql(
        "SELECT id, ds, o FROM" +
            "(SELECT id1 AS id, dataset1 as ds, o1 as o FROM uat.occurrence_relationships " +
            "UNION ALL " +
            "SELECT id2 AS id, dataset2 as ds, o2 as o FROM uat.occurrence_relationships) t " +
            "GROUP BY id, ds, o"
    );

    // create all the vertices for the connected occurrences, creating a map of the GBIF -> Janus IDs
    Dataset<Row> nodeIDs = df.repartition(100).mapPartitions((MapPartitionsFunction<Row, Tuple2<String,Long>>) it -> {
      List<Tuple2<String,Long>> mapping = new LinkedList<>();

      try (
          Graph graph = GraphFactory.open(propertiesAsMap("/janus.properties"));
          GraphTraversalSource g = graph.traversal();
      ) {
        while (it.hasNext()) {
          Row row = it.next();

          String id = row.getAs("id");
          String ds = row.getAs("ds");

          final Vertex occurrence = g.addV("occurrence").property("gbifID", id).property("datasetKey", ds).next();
          mapping.add(new Tuple2<>(id, (Long) occurrence.id())); // GBIF to JanusGraph ID
        }

        g.tx().commit();
        g.tx().close();
      }

      return mapping.iterator();
    }, Encoders.tuple(Encoders.STRING(),Encoders.LONG())).toDF("gbifID", "janusId");

    nodeIDs.write().saveAsTable("uat.node_ids"); // ease debugging at cost of a new read

    // create all the edges between the nodes we just created
    Dataset<Row> relations = spark.sql(
        "SELECT n1.janusId as jid1, n2.janusId as jid2, reasons, o.id1 as id1, o.id2 as id2 " +
            "FROM uat.occurrence_relationships o " +
            "JOIN uat.node_ids n1 ON o.id1 = n1.gbifID " +
            "JOIN uat.node_ids n2 ON o.id2 = n2.gbifID " +
            "WHERE o.id1<o.id2 " +
            "GROUP BY n1.janusId, n2.janusId, reasons, o.id1, o.id2"); // defensive

    relations.repartition(100).foreachPartition((ForeachPartitionFunction<Row>) iterator -> {
      try (
          Graph graph = GraphFactory.open(propertiesAsMap("/janus.properties"));
          GraphTraversalSource g = graph.traversal();
      ) {

        while (iterator.hasNext()) {
          Row row = iterator.next();

          Long source = row.getAs("jid1");
          Long target = row.getAs("jid2");
          String reasons = row.getAs("reasons");

          Vertex v1 = g.V(source).next();
          Vertex v2 = g.V(target).next();
          v1.addEdge("related", v2).properties("reasons", reasons);
        }
        g.tx().commit();
        g.tx().close();
      }

    });

  }

  private static Map<String,String> propertiesAsMap(String resource) throws Exception {
    Map<String, String> propertyMap = new HashMap();
    try (InputStream stream = Spark.class.getResourceAsStream(resource)) {
      Properties p = new Properties();
      p.load(stream);
      Enumeration keys = p.propertyNames();
      while (keys.hasMoreElements()) {
        String key = (String) keys.nextElement();
        propertyMap.put(key, p.getProperty(key));
      }
      return propertyMap;
    }
  }
}
