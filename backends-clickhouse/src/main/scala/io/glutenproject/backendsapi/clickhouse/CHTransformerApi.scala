/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.glutenproject.backendsapi.clickhouse

import io.glutenproject.GlutenConfig
import io.glutenproject.backendsapi.{BackendsApiManager, TransformerApi}
import io.glutenproject.execution.CHHashAggregateExecTransformer
import io.glutenproject.expression.{ConverterUtils, ExpressionConverter}
import io.glutenproject.substrait.SubstraitContext
import io.glutenproject.substrait.expression.{BooleanLiteralNode, CastNode, ExpressionBuilder, ExpressionNode, SelectionNode}
import io.glutenproject.utils.{CHInputPartitionsUtil, ExpressionDocUtil}

import org.apache.spark.internal.Logging
import org.apache.spark.shuffle.utils.RangePartitionerBoundsGenerator
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, RangePartitioning}
import org.apache.spark.sql.connector.read.InputPartition
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, PartitionDirectory}
import org.apache.spark.sql.execution.datasources.v1.ClickHouseFileIndex
import org.apache.spark.sql.types._
import org.apache.spark.util.collection.BitSet

import com.google.common.collect.Lists

import java.util

class CHTransformerApi extends TransformerApi with Logging {

  /**
   * Do validate for ColumnarShuffleExchangeExec. For ClickHouse backend, it will return true
   * directly.
   *
   * @return
   */
  override def validateColumnarShuffleExchangeExec(
      outputPartitioning: Partitioning,
      child: SparkPlan): Boolean = {
    val outputAttributes = child.output
    // check repartition expression
    val substraitContext = new SubstraitContext
    outputPartitioning match {
      case HashPartitioning(exprs, _) =>
        !(exprs
          .map(
            expr => {
              val node = ExpressionConverter
                .replaceWithExpressionTransformer(expr, outputAttributes)
                .doTransform(substraitContext.registeredFunction)
              if (!node.isInstanceOf[SelectionNode] && !node.isInstanceOf[CastNode]) {
                // This is should not happen.
                logDebug("Expressions are not supported in HashPartitioning.")
                false
              } else {
                true
              }
            })
          .exists(_ == false)) ||
        BackendsApiManager.getSettings.supportShuffleWithProject(outputPartitioning, child)
      case rangePartitoning: RangePartitioning =>
        GlutenConfig.getConf.enableColumnarSort &&
        RangePartitionerBoundsGenerator.supportedOrderings(rangePartitoning, child)
      case _ => true
    }
  }

  /** Generate Seq[InputPartition] for FileSourceScanExecTransformer. */
  def genInputPartitionSeq(
      relation: HadoopFsRelation,
      selectedPartitions: Array[PartitionDirectory],
      output: Seq[Attribute],
      optionalBucketSet: Option[BitSet],
      optionalNumCoalescedBuckets: Option[Int],
      disableBucketedScan: Boolean): Seq[InputPartition] = {
    if (relation.location.isInstanceOf[ClickHouseFileIndex]) {
      // Generate NativeMergeTreePartition for MergeTree
      relation.location.asInstanceOf[ClickHouseFileIndex].partsPartitions
    } else {
      // Generate FilePartition for Parquet
      CHInputPartitionsUtil(
        relation,
        selectedPartitions,
        output,
        optionalBucketSet,
        optionalNumCoalescedBuckets,
        disableBucketedScan).genInputPartitionSeq()
    }
  }

  override def postProcessNativeConfig(
      nativeConfMap: util.Map[String, String],
      backendPrefix: String): Unit = {
    val settingPrefix = backendPrefix + ".runtime_settings."
    if (nativeConfMap.getOrDefault("spark.memory.offHeap.enabled", "false").toBoolean) {
      val offHeapSize =
        nativeConfMap.getOrDefault("spark.gluten.memory.offHeap.size.in.bytes", "0").toLong
      if (offHeapSize > 0) {
        // Only set default max_bytes_before_external_sort for CH when it is not set explicitly.
        val sortSpillKey = settingPrefix + "max_bytes_before_external_sort";
        if (!nativeConfMap.containsKey(sortSpillKey)) {
          val sortSpillValue = offHeapSize * 0.5
          nativeConfMap.put(sortSpillKey, sortSpillValue.toLong.toString)
        }

        // Only set default max_bytes_before_external_group_by for CH when it is not set explicitly.
        val groupBySpillKey = settingPrefix + "max_bytes_before_external_group_by";
        if (!nativeConfMap.containsKey(groupBySpillKey)) {
          val groupBySpillValue = offHeapSize * 0.5
          nativeConfMap.put(groupBySpillKey, groupBySpillValue.toLong.toString)
        }

        // Only set default max_bytes_before_external_join for CH when join_algorithm is grace_hash
        val joinAlgorithmKey = settingPrefix + "join_algorithm";
        if (
          nativeConfMap.containsKey(joinAlgorithmKey) &&
          nativeConfMap.get(joinAlgorithmKey) == "grace_hash"
        ) {
          val joinSpillKey = settingPrefix + "max_bytes_in_join";
          if (!nativeConfMap.containsKey(joinSpillKey)) {
            val joinSpillValue = offHeapSize * 0.7
            nativeConfMap.put(joinSpillKey, joinSpillValue.toLong.toString)
          }
        }
      }
    }

    val injectConfig: (String, String) => Unit = (srcKey, dstKey) => {
      if (nativeConfMap.containsKey(srcKey) && !nativeConfMap.containsKey(dstKey)) {
        nativeConfMap.put(dstKey, nativeConfMap.get(srcKey))
      }
    }

    val hdfsConfigPrefix = backendPrefix + ".runtime_config.hdfs."
    injectConfig("spark.hadoop.input.connect.timeout", hdfsConfigPrefix + "input_connect_timeout")
    injectConfig("spark.hadoop.input.read.timeout", hdfsConfigPrefix + "input_read_timeout")
    injectConfig("spark.hadoop.input.write.timeout", hdfsConfigPrefix + "input_write_timeout")
    injectConfig(
      "spark.hadoop.dfs.client.log.severity",
      hdfsConfigPrefix + "dfs_client_log_severity")

    // TODO: set default to true when metrics could be collected
    // while ch query plan optimization is enabled.
    val planOptKey = settingPrefix + "query_plan_enable_optimizations"
    if (!nativeConfMap.containsKey(planOptKey)) {
      nativeConfMap.put(planOptKey, "false")
    }
  }

  override def getSupportExpressionClassName: util.Set[String] = {
    ExpressionDocUtil.supportExpression()
  }

  override def getPlanOutput(plan: SparkPlan): Seq[Attribute] = {
    plan match {
      case hash: HashAggregateExec =>
        // when grouping expression has alias,
        // output name will be different from grouping expressions,
        // so using output attribute instead of grouping expression
        val groupingExpressions = hash.output.splitAt(hash.groupingExpressions.size)._1
        val aggResultAttributes = CHHashAggregateExecTransformer.getAggregateResultAttributes(
          groupingExpressions,
          hash.aggregateExpressions
        )
        if (aggResultAttributes.size == hash.output.size) {
          aggResultAttributes
        } else {
          var output = Seq.empty[Attribute]
          for (i <- hash.output.indices) {
            if (i < groupingExpressions.size) {
              output = output :+ aggResultAttributes(i)
            } else {
              output = output :+ hash.output(i)
            }
          }
          output
        }
      case _ =>
        plan.output
    }

  }

  override def createDateDiffParamList(
      start: ExpressionNode,
      end: ExpressionNode): Iterable[ExpressionNode] = {
    List(ExpressionBuilder.makeStringLiteral("day"), start, end)
  }

  override def createLikeParamList(
      left: ExpressionNode,
      right: ExpressionNode,
      escapeChar: ExpressionNode): Iterable[ExpressionNode] =
    List(left, right)

  override def createCheckOverflowExprNode(
      args: java.lang.Object,
      substraitExprName: String,
      childNode: ExpressionNode,
      dataType: DecimalType,
      nullable: Boolean,
      nullOnOverflow: Boolean): ExpressionNode = {
    val functionMap = args.asInstanceOf[java.util.HashMap[String, java.lang.Long]]
    val functionId = ExpressionBuilder.newScalarFunction(
      functionMap,
      ConverterUtils.makeFuncName(
        substraitExprName,
        Seq(dataType, BooleanType),
        ConverterUtils.FunctionConfig.OPT))

    // Just make a fake toType value, because native engine cannot accept datatype itself.
    val toTypeNodes =
      ExpressionBuilder.makeDecimalLiteral(new Decimal().set(0, dataType.precision, dataType.scale))
    val expressionNodes =
      Lists.newArrayList(childNode, new BooleanLiteralNode(nullOnOverflow), toTypeNodes)
    val typeNode = ConverterUtils.getTypeNode(dataType, nullable)
    ExpressionBuilder.makeScalarFunction(functionId, expressionNodes, typeNode)
  }
}
