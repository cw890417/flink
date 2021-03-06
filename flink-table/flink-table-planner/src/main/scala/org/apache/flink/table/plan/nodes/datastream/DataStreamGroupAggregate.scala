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
package org.apache.flink.table.plan.nodes.datastream

import java.lang.{Byte => JByte}

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.{RelNode, RelWriter, SingleRel}
import org.apache.flink.api.java.functions.NullByteKeySelector
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.table.api.{StreamQueryConfig, StreamTableEnvImpl}
import org.apache.flink.table.codegen.AggregationCodeGenerator
import org.apache.flink.table.plan.nodes.CommonAggregate
import org.apache.flink.table.plan.rules.datastream.DataStreamRetractionRules
import org.apache.flink.table.plan.schema.RowSchema
import org.apache.flink.table.runtime.CRowKeySelector
import org.apache.flink.table.runtime.aggregate.AggregateUtil.CalcitePair
import org.apache.flink.table.runtime.aggregate._
import org.apache.flink.table.runtime.types.{CRow, CRowTypeInfo}
import org.apache.flink.table.util.Logging
import org.apache.flink.types.Row

/**
  *
  * Flink RelNode for data stream unbounded group aggregate
  *
  * @param cluster         Cluster of the RelNode, represent for an environment of related
  *                        relational expressions during the optimization of a query.
  * @param traitSet        Trait set of the RelNode
  * @param inputNode       The input RelNode of aggregation
  * @param namedAggregates List of calls to aggregate functions and their output field names
  * @param inputSchema     The type of the rows consumed by this RelNode
  * @param schema          The type of the rows emitted by this RelNode
  * @param groupings       The position (in the input Row) of the grouping keys
  */
class DataStreamGroupAggregate(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputNode: RelNode,
    namedAggregates: Seq[CalcitePair[AggregateCall, String]],
    schema: RowSchema,
    inputSchema: RowSchema,
    groupings: Array[Int])
  extends SingleRel(cluster, traitSet, inputNode)
    with CommonAggregate
    with DataStreamRel
    with Logging {

  override def deriveRowType() = schema.relDataType

  override def needsUpdatesAsRetraction = true

  override def producesUpdates = true

  override def consumesRetractions = true

  def getGroupings: Array[Int] = groupings

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new DataStreamGroupAggregate(
      cluster,
      traitSet,
      inputs.get(0),
      namedAggregates,
      schema,
      inputSchema,
      groupings)
  }

  override def toString: String = {
    s"Aggregate(${
      if (!groupings.isEmpty) {
        s"groupBy: (${groupingToString(inputSchema.relDataType, groupings)}), "
      } else {
        ""
      }
    }select:(${aggregationToString(
      inputSchema.relDataType, groupings, getRowType, namedAggregates, Nil)}))"
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    super.explainTerms(pw)
      .itemIf("groupBy", groupingToString(
        inputSchema.relDataType, groupings), !groupings.isEmpty)
      .item("select", aggregationToString(
        inputSchema.relDataType, groupings, getRowType, namedAggregates, Nil))
  }

  override def translateToPlan(
      tableEnv: StreamTableEnvImpl,
      queryConfig: StreamQueryConfig): DataStream[CRow] = {

    if (groupings.length > 0 && queryConfig.getMinIdleStateRetentionTime < 0) {
      LOG.warn(
        "No state retention interval configured for a query which accumulates state. " +
        "Please provide a query configuration with valid retention interval to prevent excessive " +
        "state size. You may specify a retention time of 0 to not clean up the state.")
    }

    val inputDS = input.asInstanceOf[DataStreamRel].translateToPlan(tableEnv, queryConfig)

    val outRowType = CRowTypeInfo(schema.typeInfo)

    val generator = new AggregationCodeGenerator(
      tableEnv.getConfig,
      false,
      inputSchema.typeInfo,
      None)

    val aggString = aggregationToString(
      inputSchema.relDataType,
      groupings,
      getRowType,
      namedAggregates,
      Nil)

    val keyedAggOpName = s"groupBy: (${groupingToString(inputSchema.relDataType, groupings)}), " +
      s"select: ($aggString)"
    val nonKeyedAggOpName = s"select: ($aggString)"

    def createKeyedProcessFunction[K]: KeyedProcessFunction[K, CRow, CRow] = {
      AggregateUtil.createGroupAggregateFunction[K](
        generator,
        namedAggregates,
        inputSchema.relDataType,
        inputSchema.fieldTypeInfos,
        groupings,
        queryConfig,
        tableEnv.getConfig,
        DataStreamRetractionRules.isAccRetract(this),
        DataStreamRetractionRules.isAccRetract(getInput))
    }

    val result: DataStream[CRow] =
    // grouped / keyed aggregation
      if (groupings.nonEmpty) {
        inputDS
        .keyBy(new CRowKeySelector(groupings, inputSchema.projectedTypeInfo(groupings)))
        .process(createKeyedProcessFunction[Row])
        .returns(outRowType)
        .name(keyedAggOpName)
        .asInstanceOf[DataStream[CRow]]
      }
      // global / non-keyed aggregation
      else {
        inputDS
        .keyBy(new NullByteKeySelector[CRow])
        .process(createKeyedProcessFunction[JByte])
        .setParallelism(1)
        .setMaxParallelism(1)
        .returns(outRowType)
        .name(nonKeyedAggOpName)
        .asInstanceOf[DataStream[CRow]]
      }
    result
  }
}

