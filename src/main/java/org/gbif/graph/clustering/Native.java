package org.gbif.graph.clustering;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;

/**
 * Quick test to load an embedded JanusGraph backed by HBase.
 */
public class Native {
  public static void main(String[] args) throws Exception {
    try (
        Graph graph = GraphFactory.open("src/main/resources/janus.properties");
        GraphTraversalSource g = graph.traversal();
    ) {

          Long source = Long.valueOf(84287648);
          Long target = Long.valueOf(656126200);
          String reasons = "FAKE";

          Vertex v1 = g.V(source).next();
          Vertex v2 = g.V(target).next();
          v1.addEdge("related", v2)
                  .properties("reasons", reasons);
          g.tx().commit();
    }
  }
}
