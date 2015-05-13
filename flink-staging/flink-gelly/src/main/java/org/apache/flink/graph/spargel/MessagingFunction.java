/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.graph.spargel;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;

import org.apache.flink.api.common.accumulators.Accumulator;
import org.apache.flink.api.common.functions.IterationRuntimeContext;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.graph.Edge;
import org.apache.flink.util.Collector;

/**
 * The base class for functions that produce messages between vertices as a part of a {@link VertexCentricIteration}.
 * 
 * @param <VertexKey> The type of the vertex key (the vertex identifier).
 * @param <VertexValue> The type of the vertex value (the state of the vertex).
 * @param <Message> The type of the message sent between vertices along the edges.
 * @param <EdgeValue> The type of the values that are associated with the edges.
 */
public abstract class MessagingFunction<VertexKey extends Comparable<VertexKey> & Serializable, 
	VertexValue extends Serializable, Message, EdgeValue extends Serializable> implements Serializable {

	private static final long serialVersionUID = 1L;
	
	// --------------------------------------------------------------------------------------------
	//  Public API Methods
	// --------------------------------------------------------------------------------------------
	
	/**
	 * This method is invoked once per superstep for each vertex that was changed in that superstep.
	 * It needs to produce the messages that will be received by vertices in the next superstep.
	 * 
	 * @param vertexKey The key of the vertex that was changed.
	 * @param vertexValue The value (state) of the vertex that was changed.
	 * 
	 * @throws Exception The computation may throw exceptions, which causes the superstep to fail.
	 */
	public abstract void sendMessages(VertexKey vertexKey, VertexValue vertexValue) throws Exception;
	
	/**
	 * This method is executed one per superstep before the vertex update function is invoked for each vertex.
	 * 
	 * @throws Exception Exceptions in the pre-superstep phase cause the superstep to fail.
	 */
	public void preSuperstep() throws Exception {}
	
	/**
	 * This method is executed one per superstep after the vertex update function has been invoked for each vertex.
	 * 
	 * @throws Exception Exceptions in the post-superstep phase cause the superstep to fail.
	 */
	public void postSuperstep() throws Exception {}
	
	
	/**
	 * Gets an {@link java.lang.Iterable} with all outgoing edges. This method is mutually exclusive with
	 * {@link #sendMessageToAllNeighbors(Object)} and may be called only once.
	 * 
	 * @return An iterator with all outgoing edges.
	 */
	@SuppressWarnings("unchecked")
	public Iterable<Edge<VertexKey, EdgeValue>> getOutgoingEdges() {
		if (edgesUsed) {
			throw new IllegalStateException("Can use either 'getOutgoingEdges()' or 'sendMessageToAllTargets()' exactly once.");
		}
		edgesUsed = true;
		this.edgeIterator.set((Iterator<Edge<VertexKey, EdgeValue>>) edges);
		return this.edgeIterator;
	}
	
	/**
	 * Sends the given message to all vertices that are targets of an outgoing edge of the changed vertex.
	 * This method is mutually exclusive to the method {@link #getOutgoingEdges()} and may be called only once.
	 * 
	 * @param m The message to send.
	 */
	public void sendMessageToAllNeighbors(Message m) {
		if (edgesUsed) {
			throw new IllegalStateException("Can use either 'getOutgoingEdges()' or 'sendMessageToAllTargets()' exactly once.");
		}
		
		edgesUsed = true;
		
		outValue.f1 = m;
		
		while (edges.hasNext()) {
			Tuple next = (Tuple) edges.next();
			VertexKey k = next.getField(1);
			outValue.f0 = k;
			out.collect(outValue);
		}
	}
	
	/**
	 * Sends the given message to the vertex identified by the given key. If the target vertex does not exist,
	 * the next superstep will cause an exception due to a non-deliverable message.
	 * 
	 * @param target The key (id) of the target vertex to message.
	 * @param m The message.
	 */
	public void sendMessageTo(VertexKey target, Message m) {
		outValue.f0 = target;
		outValue.f1 = m;
		out.collect(outValue);
	}

	// --------------------------------------------------------------------------------------------
	
	/**
	 * Gets the number of the superstep, starting at <tt>1</tt>.
	 * 
	 * @return The number of the current superstep.
	 */
	public int getSuperstepNumber() {
		return this.runtimeContext.getSuperstepNumber();
	}
	
	/**
	 * Adds an accumulator to this iteration. The accumulator is reset after each superstep.
	 * 
	 * @param name
	 * @param accumulator
	 */
	public <V, A extends Serializable> void addIterationAccumulator(String name, Accumulator<V, A> accumulator) {
		this.runtimeContext.addIterationAccumulator(name, accumulator);
	}
	
	/**
	 * Returns the accumulated value of the last iteration
	 * 
	 * @param name
	 * @return
	 */
	public <T extends Accumulator<?, ?>> T getPreviousIterationAccumulator(String name) {
		return this.runtimeContext.<T>getPreviousIterationAccumulator(name);
	}
	
	/**
	 * Gets the broadcast data set registered under the given name. Broadcast data sets
	 * are available on all parallel instances of a function. They can be registered via
	 * {@link VertexCentricIteration#addBroadcastSetForMessagingFunction(String, org.apache.flink.api.java.DataSet)}.
	 * 
	 * @param name The name under which the broadcast set is registered.
	 * @return The broadcast data set.
	 */
	public <T> Collection<T> getBroadcastSet(String name) {
		return this.runtimeContext.<T>getBroadcastVariable(name);
	}

	// --------------------------------------------------------------------------------------------
	//  internal methods and state
	// --------------------------------------------------------------------------------------------
	
	private Tuple2<VertexKey, Message> outValue;
	
	private IterationRuntimeContext runtimeContext;
	
	private Iterator<?> edges;
	
	private Collector<Tuple2<VertexKey, Message>> out;
	
	private EdgesIterator<VertexKey, EdgeValue> edgeIterator;
	
	private boolean edgesUsed;
	
	private boolean initialized = false;
	
	
	void init(IterationRuntimeContext context) {
		this.runtimeContext = context;
		this.outValue = new Tuple2<VertexKey, Message>();
		this.edgeIterator = new EdgesIterator<VertexKey, EdgeValue>();
		this.initialized = true;
	}
	
	void set(Iterator<?> edges, Collector<Tuple2<VertexKey, Message>> out) {
		this.edges = edges;
		this.out = out;
		this.edgesUsed = false;
	}
	
	public boolean isInitialized() {
		return initialized;
	}

	public void setInitialized(boolean initialized) {
		this.initialized = initialized;
	}

	private static final class EdgesIterator<VertexKey extends Comparable<VertexKey> & Serializable, 
		EdgeValue extends Serializable> 
		implements Iterator<Edge<VertexKey, EdgeValue>>, Iterable<Edge<VertexKey, EdgeValue>>
	{
		private Iterator<Edge<VertexKey, EdgeValue>> input;
		
		private Edge<VertexKey, EdgeValue> edge = new Edge<VertexKey, EdgeValue>();
		
		void set(Iterator<Edge<VertexKey, EdgeValue>> input) {
			this.input = input;
		}
		
		@Override
		public boolean hasNext() {
			return input.hasNext();
		}

		@Override
		public Edge<VertexKey, EdgeValue> next() {
			Edge<VertexKey, EdgeValue> next = input.next();
			edge.setTarget(next.f1);
			edge.setValue(next.f2);
			return edge;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
		@Override
		public Iterator<Edge<VertexKey, EdgeValue>> iterator() {
			return this;
		}
	}
}
