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

package org.apache.flink.api.java.operators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.flink.api.common.InvalidProgramException;
import org.apache.flink.api.common.io.FileOutputFormat;
import org.apache.flink.api.common.io.FileOutputFormat.IterationWriteMode;
import org.apache.flink.api.common.io.OutputFormat;
import org.apache.flink.api.common.operators.AbstractUdfOperator;
import org.apache.flink.api.common.operators.BinaryOperatorInformation;
import org.apache.flink.api.common.operators.GenericDataSinkBase;
import org.apache.flink.api.common.operators.Operator;
import org.apache.flink.api.common.operators.UnaryOperatorInformation;
import org.apache.flink.api.common.operators.base.BulkIterationBase;
import org.apache.flink.api.common.operators.base.DeltaIterationBase;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.io.CsvOutputFormat;
import org.apache.flink.api.java.operators.translation.JavaPlan;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.util.RecoveryUtil;

public class OperatorTranslation {
	
	/** The already translated operations */
	private Map<DataSet<?>, Operator<?>> translated = new HashMap<DataSet<?>, Operator<?>>();
	
	private Map<Object, ArrayList<DataSink<?>>> iterationSinks;
	
	public JavaPlan translateToPlan(List<DataSink<?>> sinks, Map<Object, ArrayList<DataSink<?>>> iterationSinks, String jobName) {
		List<GenericDataSinkBase<?>> planSinks = new ArrayList<GenericDataSinkBase<?>>();
		
		this.iterationSinks = iterationSinks;
		
		for (DataSink<?> sink : sinks) {
			planSinks.add(translate(sink));
		}
		
		JavaPlan p = new JavaPlan(planSinks); 
		p.setJobName(jobName);
		return p;
	}
	
	
	private <T> GenericDataSinkBase<T> translate(DataSink<T> sink) {
		
		// translate the input recursively
		Operator<T> input = translate(sink.getDataSet());
		
		// translate the sink itself and connect it to the input
		GenericDataSinkBase<T> translatedSink = sink.translateToDataFlow(input);
				
		return translatedSink;
	}
	
	
	private <T> Operator<T> translate(DataSet<T> dataSet) {
		
		// check if we have already translated that data set (operation or source)
		Operator<?> previous = (Operator<?>) this.translated.get(dataSet);
		if (previous != null) {
			@SuppressWarnings("unchecked")
			Operator<T> typedPrevious = (Operator<T>) previous;
			return typedPrevious;
		}
		
		Operator<T> dataFlowOp;
		
		if (dataSet instanceof DataSource) {
			dataFlowOp = ((DataSource<T>) dataSet).translateToDataFlow();
		}
		else if (dataSet instanceof SingleInputOperator) {
			dataFlowOp = translateSingleInputOperator((SingleInputOperator<?, ?, ?>) dataSet);
		}
		else if (dataSet instanceof TwoInputOperator) {
			dataFlowOp = translateTwoInputOperator((TwoInputOperator<?, ?, ?, ?>) dataSet);
		}
		else if (dataSet instanceof BulkIterationResultSet) {
			dataFlowOp = translateBulkIteration((BulkIterationResultSet<?>) dataSet);
		}
		else if (dataSet instanceof DeltaIterationResultSet) {
			dataFlowOp = translateDeltaIteration((DeltaIterationResultSet<?, ?>) dataSet);
		}
		else if (dataSet instanceof DeltaIteration.SolutionSetPlaceHolder || dataSet instanceof DeltaIteration.WorksetPlaceHolder) {
			throw new InvalidProgramException("A data set that is part of a delta iteration was used as a sink or action."
				+ " Did you forget to close the iteration?");
		}
		else {
			throw new RuntimeException("Error while creating the data flow plan for the program: Unknown operator or data set type: " + dataSet);
		}
		
		this.translated.put(dataSet, dataFlowOp);
		
		// take care of broadcast variables
		translateBcVariables(dataSet, dataFlowOp);
		
		return dataFlowOp;
	}
	
	
	private <I, O> org.apache.flink.api.common.operators.Operator<O> translateSingleInputOperator(SingleInputOperator<?, ?, ?> op) {
		
		@SuppressWarnings("unchecked")
		SingleInputOperator<I, O, ?> typedOp = (SingleInputOperator<I, O, ?>) op;
		
		@SuppressWarnings("unchecked")
		DataSet<I> typedInput = (DataSet<I>) op.getInput();
		
		Operator<I> input = translate(typedInput);
		
		org.apache.flink.api.common.operators.Operator<O> dataFlowOp = typedOp.translateToDataFlow(input);
		
		if (op instanceof UdfOperator<?>) {
			@SuppressWarnings("unchecked")
			SingleInputUdfOperator<I, O, ?> udfOp = (SingleInputUdfOperator<I, O, ?>) op;

			// set configuration parameters
			Configuration opParams = udfOp.getParameters();
			if (opParams != null) {
				dataFlowOp.getParameters().addAll(opParams);
			}

			if (dataFlowOp instanceof org.apache.flink.api.common.operators.SingleInputOperator) {
				org.apache.flink.api.common.operators.SingleInputOperator<?, O, ?> unaryOp =
						(org.apache.flink.api.common.operators.SingleInputOperator<?, O, ?>) dataFlowOp;
				// set the semantic properties
				unaryOp.setSemanticProperties(udfOp.getSemanticProperties());
			}
		}
		
		return dataFlowOp;
	}
	
	private <I1, I2, O> org.apache.flink.api.common.operators.Operator<O> translateTwoInputOperator(TwoInputOperator<?, ?, ?, ?> op) {
		
		@SuppressWarnings("unchecked")
		TwoInputOperator<I1, I2, O, ?> typedOp = (TwoInputOperator<I1, I2, O, ?>) op;
		
		@SuppressWarnings("unchecked")
		DataSet<I1> typedInput1 = (DataSet<I1>) op.getInput1();
		@SuppressWarnings("unchecked")
		DataSet<I2> typedInput2 = (DataSet<I2>) op.getInput2();
		
		Operator<I1> input1 = translate(typedInput1);
		Operator<I2> input2 = translate(typedInput2);
		
		org.apache.flink.api.common.operators.Operator<O> dataFlowOp = typedOp.translateToDataFlow(input1, input2);
		
		if (op instanceof UdfOperator<?> ) {
			@SuppressWarnings("unchecked")
			TwoInputUdfOperator<I1, I2, O, ?> udfOp = (TwoInputUdfOperator<I1, I2, O, ?>) op;
			
			// set configuration parameters
			Configuration opParams = udfOp.getParameters();
			if (opParams != null) {
				dataFlowOp.getParameters().addAll(opParams);
			}

			if (dataFlowOp instanceof org.apache.flink.api.common.operators.DualInputOperator) {
				org.apache.flink.api.common.operators.DualInputOperator<?, ?,  O, ?> binaryOp =
						(org.apache.flink.api.common.operators.DualInputOperator<?, ?, O, ?>) dataFlowOp;
				// set the semantic properties
				binaryOp.setSemanticProperties(udfOp.getSemanticProperties());
			}
		}
		
		return dataFlowOp;
	}
	
	
	private <T> BulkIterationBase<T> translateBulkIteration(BulkIterationResultSet<?> untypedIterationEnd) {
		@SuppressWarnings("unchecked")
		BulkIterationResultSet<T> iterationEnd = (BulkIterationResultSet<T>) untypedIterationEnd;
		
		BulkIterationBase<T> iterationOperator =
				new BulkIterationBase<T>(new UnaryOperatorInformation<T, T>(iterationEnd.getType(), iterationEnd.getType()), "Bulk Iteration");
		IterativeDataSet<T> iterationHead = iterationEnd.getIterationHead();

		translated.put(iterationHead, iterationOperator.getPartialSolution());

		Operator<T> translatedBody = translate(iterationEnd.getNextPartialSolution());
		iterationOperator.setNextPartialSolution(translatedBody);
		iterationOperator.setMaximumNumberOfIterations(iterationHead.getMaxIterations());
		iterationOperator.setInput(translate(iterationHead.getInput()));
		
		if(iterationEnd.getTerminationCriterion() != null) {
			iterationOperator.setTerminationCriterion(translate(iterationEnd.getTerminationCriterion()));
		}
		if(iterationEnd.getConvergenceCriterion() != null) {
			if(iterationEnd.getTerminationCriterion() != null) {
				throw new RuntimeException("Error while creating the data flow plan for the program: You can either use a termination criterion or an convergence criterion in bulk iterations, not both.");
			}
			if(iterationEnd.getConvergenceCriterionAccumulatorName() == null) {
				throw new RuntimeException("Error while creating the data flow plan for the program: You have to specify the convergence criterion AND the name of the accumulator that the criterion belongs to.");
			}
			iterationOperator.registerConvergenceCriterion(iterationEnd.getConvergenceCriterionAccumulatorName(), iterationEnd.getConvergenceCriterion());
		}
		
		ArrayList<DataSink<?>> sinks = this.iterationSinks.get(untypedIterationEnd.getIterationHead());
		if(sinks != null) {
			for(DataSink<?> s : sinks) {
				iterationOperator.addIterationSink(translate(s));
			}
		}
		
		if(iterationHead.getCheckpointInterval() > 0) {
			
			boolean anyRegularInput = false;
			
			if(iterationOperator.getNextPartialSolution() instanceof org.apache.flink.api.common.operators.SingleInputOperator) {
				if(((org.apache.flink.api.common.operators.SingleInputOperator) iterationOperator.getNextPartialSolution() ).getInput()
				.equals(iterationOperator.getPartialSolution())) {
					anyRegularInput = true;
				}
			}
			
			if(iterationOperator.getNextPartialSolution() instanceof org.apache.flink.api.common.operators.DualInputOperator) {
				org.apache.flink.api.common.operators.DualInputOperator dio = 
						(org.apache.flink.api.common.operators.DualInputOperator)  iterationOperator.getNextPartialSolution();
				if(dio.getFirstInput().equals(iterationOperator.getPartialSolution())
						|| dio.getSecondInput().equals(iterationOperator.getPartialSolution())) {
					anyRegularInput = true;
				}
			}
			
			if(anyRegularInput) {
				String checkpointPath = RecoveryUtil.getCheckpointPath()+"checkpoint"; //+iterationHead.getName().trim();
				System.out.println(checkpointPath);
				FileOutputFormat<?> outputFormat = new CsvOutputFormat(new Path(checkpointPath), CsvOutputFormat.DEFAULT_LINE_DELIMITER, CsvOutputFormat.DEFAULT_FIELD_DELIMITER);
				outputFormat.setIterationWriteMode(new IterationWriteMode(2, iterationHead.getCheckpointInterval()));
				
				iterationOperator.addIterationSink(
						translate(iterationHead.output((OutputFormat<T>) outputFormat)));
			}
		}
		
		return iterationOperator;
	}
	
	private <D, W> DeltaIterationBase<D, W> translateDeltaIteration(DeltaIterationResultSet<?, ?> untypedIterationEnd) {
		@SuppressWarnings("unchecked")
		DeltaIterationResultSet<D, W> iterationEnd = (DeltaIterationResultSet<D, W>) untypedIterationEnd;
		DeltaIteration<D, W> iterationHead = iterationEnd.getIterationHead();
		
		String name = iterationHead.getName() == null ? "Unnamed Delta Iteration" : iterationHead.getName();
		
		DeltaIterationBase<D, W> iterationOperator = new DeltaIterationBase<D, W>(new BinaryOperatorInformation<D, W, D>(iterationEnd.getType(), iterationEnd.getWorksetType(), iterationEnd.getType()),
				iterationEnd.getKeyPositions(), name);
		
		iterationOperator.setMaximumNumberOfIterations(iterationEnd.getMaxIterations());
		
		if (iterationHead.getParallelism() > 0) {
			iterationOperator.setParallelism(iterationHead.getParallelism());
		}

		DeltaIteration.SolutionSetPlaceHolder<D> solutionSetPlaceHolder = iterationHead.getSolutionSet();
		DeltaIteration.WorksetPlaceHolder<W> worksetPlaceHolder = iterationHead.getWorkset();

		translated.put(solutionSetPlaceHolder, iterationOperator.getSolutionSet());
		translated.put(worksetPlaceHolder, iterationOperator.getWorkset());

		Operator<D> translatedSolutionSet = translate(iterationEnd.getNextSolutionSet());
		Operator<W> translatedWorkset = translate(iterationEnd.getNextWorkset());
		
		iterationOperator.setNextWorkset(translatedWorkset);
		iterationOperator.setSolutionSetDelta(translatedSolutionSet);

		iterationOperator.setInitialSolutionSet(translate(iterationHead.getInitialSolutionSet()));
		iterationOperator.setInitialWorkset(translate(iterationHead.getInitialWorkset()));
		
		iterationOperator.setSolutionSetUnManaged(iterationHead.isSolutionSetUnManaged());
		
		ArrayList<DataSink<?>> sinks = this.iterationSinks.get(iterationHead.getSolutionSet());
		ArrayList<DataSink<?>> sinksTmp = this.iterationSinks.get(iterationHead.getWorkset());
		if(sinks != null && sinksTmp != null) {
			sinks.addAll(sinksTmp);
		}
		if(sinks == null && sinksTmp != null) {
			sinks = sinksTmp;
		}
		if(sinks != null) {
			for(DataSink<?> s : sinks) {
				iterationOperator.addIterationSink(translate(s));
			}
		}
		
		if(iterationHead.getCheckpointInterval() > 0) {
			
			String checkpointPath = RecoveryUtil.getCheckpointPath()+"checkpoint"; //+iterationHead.getName().trim();
			System.out.println(checkpointPath);
			FileOutputFormat<?> outputFormat = new CsvOutputFormat(new Path(checkpointPath), CsvOutputFormat.DEFAULT_LINE_DELIMITER, CsvOutputFormat.DEFAULT_FIELD_DELIMITER);
			outputFormat.setIterationWriteMode(new IterationWriteMode(2, iterationHead.getCheckpointInterval()));
			
			iterationOperator.addIterationSink(
					translate(iterationHead.getWorkset().output((OutputFormat<W>) outputFormat)));
		}
		
		return iterationOperator;
	}
	
	private void translateBcVariables(DataSet<?> setOrOp, Operator<?> dataFlowOp) {
		// check if this is actually an operator that could have broadcast variables
		if (setOrOp instanceof UdfOperator) {
			if (!(dataFlowOp instanceof AbstractUdfOperator<?, ?>)) {
				throw new RuntimeException("Error while creating the data flow plan for the program: A UDF operation was not translated to a UDF operator.");
			}
			
			UdfOperator<?> udfOp = (UdfOperator<?>) setOrOp;
			AbstractUdfOperator<?, ?> udfDataFlowOp = (AbstractUdfOperator<?, ?>) dataFlowOp;
		
			for (Map.Entry<String, DataSet<?>> bcVariable : udfOp.getBroadcastSets().entrySet()) {
				Operator<?> bcInput = translate(bcVariable.getValue());
				udfDataFlowOp.setBroadcastVariable(bcVariable.getKey(), bcInput);
			}
		}
	}
}
