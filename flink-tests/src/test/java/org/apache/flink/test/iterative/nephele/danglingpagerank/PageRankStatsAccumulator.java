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


package org.apache.flink.test.iterative.nephele.danglingpagerank;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.apache.flink.api.common.accumulators.Accumulator;
import org.apache.flink.api.common.accumulators.SimpleAccumulator;

@SuppressWarnings("serial")
public class PageRankStatsAccumulator implements SimpleAccumulator<PageRankStats> {

	private double diff = 0;

	private double rank = 0;

	private double danglingRank = 0;

	private long numDanglingVertices = 0;

	private long numVertices = 0;

	private long edges = 0;

	private double summedRank = 0;

	private double finalDiff = 0;

	@Override
	public PageRankStats getLocalValue() {
		return new PageRankStats(diff, rank, danglingRank, numDanglingVertices, numVertices, edges, summedRank,
			finalDiff);
	}

	public void add(double diffDelta, double rankDelta, double danglingRankDelta, long danglingVerticesDelta,
			long verticesDelta, long edgesDelta, double summedRankDelta, double finalDiffDelta) {
		diff += diffDelta;
		rank += rankDelta;
		danglingRank += danglingRankDelta;
		numDanglingVertices += danglingVerticesDelta;
		numVertices += verticesDelta;
		edges += edgesDelta;
		summedRank += summedRankDelta;
		finalDiff += finalDiffDelta;
	}

	@Override
	public void add(PageRankStats pageRankStats) {
		diff += pageRankStats.diff();
		rank += pageRankStats.rank();
		danglingRank += pageRankStats.danglingRank();
		numDanglingVertices += pageRankStats.numDanglingVertices();
		numVertices += pageRankStats.numVertices();
		edges += pageRankStats.edges();
		summedRank += pageRankStats.summedRank();
		finalDiff += pageRankStats.finalDiff();
	}

	public void resetLocal() {
		diff = 0;
		rank = 0;
		danglingRank = 0;
		numDanglingVertices = 0;
		numVertices = 0;
		edges = 0;
		summedRank = 0;
		finalDiff = 0;
	}

	@Override
	public void merge(Accumulator<PageRankStats, PageRankStats> other) {
		this.add(other.getLocalValue());
	}

	@Override
	public void write(ObjectOutputStream out) throws IOException {
		out.writeDouble(diff);
		out.writeDouble(rank);
		out.writeDouble(danglingRank);
		out.writeLong(numDanglingVertices);
		out.writeLong(numVertices);
		out.writeLong(edges);
		out.writeDouble(summedRank);
		out.writeDouble(finalDiff);
	}

	@Override
	public void read(ObjectInputStream in) throws IOException {
		diff = in.readDouble();
		rank = in.readDouble();
		danglingRank = in.readDouble();
		numDanglingVertices = in.readLong();
		numVertices = in.readLong();
		edges = in.readLong();
		summedRank = in.readDouble();
		finalDiff = in.readDouble();
	}
	
	public PageRankStatsAccumulator clone() {
		PageRankStatsAccumulator clone = new PageRankStatsAccumulator();
		clone.add(this.getLocalValue());
		return clone;
	}
}