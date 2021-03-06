/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2015 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
package spade.sketch;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocket;
import spade.core.*;

public class Sketch extends AbstractSketch {

    private static final double falsePositiveProbability = 0.1;
    private static final int expectedSize = 20;
    private static final Logger logger = Logger.getLogger(Sketch.class.getName());
    private static final boolean USE_CACHE = false;

    public Sketch() {
        matrixFilter = new MatrixFilter(falsePositiveProbability, expectedSize);
        objects = new HashMap<>();
    }

    @Override
    public void putVertex(AbstractVertex incomingVertex) {
    }

    @Override
    public void putEdge(AbstractEdge incomingEdge) {
        try {
            if (incomingEdge.type().equalsIgnoreCase("Used")
                    && incomingEdge.getDestinationVertex().getAnnotation("network").equalsIgnoreCase("true")) {
                // Connection was created to this host
                AbstractVertex networkVertex = incomingEdge.getDestinationVertex();
                String remoteHost = networkVertex.getAnnotation("destination host");
                String localHost = networkVertex.getAnnotation("source host");
                if (!USE_CACHE || (USE_CACHE && !Kernel.remoteSketches.containsKey(remoteHost))) {
                    logger.log(Level.INFO, "concreteSketch - Attempting to receive sketches from {0}", remoteHost);
                    int port = Integer.parseInt(Settings.getProperty("remote_sketch_port"));
                    SSLSocket remoteSocket = (SSLSocket) Kernel.sslSocketFactory.createSocket(remoteHost, port);

                    OutputStream outStream = remoteSocket.getOutputStream();
                    InputStream inStream = remoteSocket.getInputStream();
                    ObjectOutputStream clientObjectOutputStream = new ObjectOutputStream(outStream);
                    ObjectInputStream clientObjectInputStream = new ObjectInputStream(inStream);

                    clientObjectOutputStream.writeObject("giveSketch");
                    clientObjectOutputStream.flush();
                    AbstractSketch tmpSketch = (AbstractSketch) clientObjectInputStream.readObject();
                    Map<String, AbstractSketch> receivedSketches = (Map<String, AbstractSketch>) clientObjectInputStream.readObject();
                    Kernel.remoteSketches.put(remoteHost, tmpSketch);
                    receivedSketches.remove(localHost);
                    Kernel.remoteSketches.putAll(receivedSketches);

                    logger.log(Level.INFO, "concreteSketch - Received sketches from {0}", remoteHost);
                    clientObjectOutputStream.writeObject("close");
                    clientObjectOutputStream.flush();
                    clientObjectOutputStream.close();
                    clientObjectInputStream.close();
                    outStream.close();
                    inStream.close();
                    remoteSocket.close();
                }
                // Update sketch bloom filters
                BloomFilter newAncestors = Kernel.remoteSketches.get(remoteHost).matrixFilter.get(networkVertex);
                if (newAncestors != null) {
                    logger.log(Level.INFO, "concreteSketch - Found bloomfilter for networkVertex");
                    Runnable update = new updateMatrixThread(this, networkVertex, incomingEdge.type());
                    new Thread(update).start();
                }
            } else if (incomingEdge.type().equalsIgnoreCase("WasGeneratedBy")
                    && incomingEdge.getSourceVertex().getAnnotation("network").equalsIgnoreCase("true")) {
                AbstractVertex networkVertex = incomingEdge.getSourceVertex();
                Runnable update = new updateMatrixThread(this, networkVertex, incomingEdge.type());
                new Thread(update).start();
            }
        } catch (NumberFormatException | IOException | ClassNotFoundException exception) {
            Logger.getLogger(Sketch.class.getName()).log(Level.SEVERE, null, exception);
        }
    }
}

class updateMatrixThread implements Runnable {

    private final AbstractSketch sketch;
    private final AbstractVertex vertex;
    private final String type;
    private static final Logger logger = Logger.getLogger(updateMatrixThread.class.getName());
    private static final String ID_STRING = Settings.getProperty("storage_identifier");

    public updateMatrixThread(AbstractSketch workingSketch, AbstractVertex networkVertex, String edgeType) {
        sketch = workingSketch;
        vertex = networkVertex;
        type = edgeType;
    }

    @Override
    public void run() {
        String storageId = getStorageId(vertex);
        if (type.equalsIgnoreCase("Used")) {
            logger.log(Level.INFO, "concreteSketch - Updating matrixfilter for USED edge for storageId: {0}", storageId);
            String remoteHost = vertex.getAnnotation("destination host");
            BloomFilter newAncestors = Kernel.remoteSketches.get(remoteHost).matrixFilter.get(vertex);
            Graph descendants = Query.executeQuery("query Neo4j lineage " + storageId + " 20 d null", false);
            for (AbstractVertex currentVertex : descendants.vertexSet()) {
                if (currentVertex.getAnnotation("network").equalsIgnoreCase("true")) {
                    sketch.matrixFilter.updateAncestors(currentVertex, newAncestors);
                }
            }
            logger.log(Level.INFO, "concreteSketch - Updated bloomfilters for USED edge - storageId: {0}", storageId);
        } else if (type.equalsIgnoreCase("WasGeneratedBy")) {
            logger.log(Level.INFO, "concreteSketch - Updating matrixfilter for WGB edge for storageId: {0}", storageId);
            Graph ancestors = Query.executeQuery("query Neo4j lineage " + storageId + " 20 a null", false);
            for (AbstractVertex currentVertex : ancestors.vertexSet()) {
                if (currentVertex.getAnnotation("network").equalsIgnoreCase("true")) {
                    sketch.matrixFilter.add(vertex, currentVertex);
                }
            }
            logger.log(Level.INFO, "concreteSketch - Updated bloomfilters for WGB edge - storageId: {0}", storageId);
        }
    }

    private String getStorageId(AbstractVertex networkVertex) {
        try {
            logger.log(Level.INFO, "concreteSketch - Getting storageId of networkVertex");
            String vertexQueryExpression = "query Neo4j vertices";
            vertexQueryExpression += " source\\ host:" + networkVertex.getAnnotation("source host");
            vertexQueryExpression += " AND source\\ port:" + networkVertex.getAnnotation("source port");
            vertexQueryExpression += " AND destination\\ host:" + networkVertex.getAnnotation("destination host");
            vertexQueryExpression += " AND destination\\ port:" + networkVertex.getAnnotation("destination port");
            Graph result = Query.executeQuery(vertexQueryExpression, false);
            AbstractVertex resultVertex = result.vertexSet().iterator().next();
            logger.log(Level.INFO, "concreteSketch - Returning storageId: {0}", resultVertex.getAnnotation(ID_STRING));
            return resultVertex.getAnnotation(ID_STRING);
        } catch (Exception exception) {
            Logger.getLogger(Sketch.class.getName()).log(Level.SEVERE, null, exception);
            return null;
        }
    }
}
