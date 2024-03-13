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
package io.glutenproject.extension.columnar

import io.glutenproject.GlutenConfig
import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.execution._
import io.glutenproject.expression.ExpressionConverter
import io.glutenproject.extension.{ColumnarToRowLike, GlutenPlan}
import io.glutenproject.sql.shims.SparkShimLoader
import io.glutenproject.utils.{LogLevelUtil, PlanUtil}

import org.apache.spark.api.python.EvalPythonExecTransformer
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight, BuildSide}
import org.apache.spark.sql.catalyst.plans.{LeftOuter, LeftSemi, RightOuter}
import org.apache.spark.sql.catalyst.rules.{PlanChangeLogger, Rule}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.adaptive.BroadcastQueryStageExec
import org.apache.spark.sql.execution.aggregate.{HashAggregateExec, ObjectHashAggregateExec, SortAggregateExec}
import org.apache.spark.sql.execution.datasources.WriteFilesExec
import org.apache.spark.sql.execution.datasources.v2.{BatchScanExec, FileScan}
import org.apache.spark.sql.execution.exchange.{BroadcastExchangeExec, BroadcastExchangeLike, ShuffleExchangeExec, ShuffleExchangeLike}
import org.apache.spark.sql.execution.joins._
import org.apache.spark.sql.execution.python.EvalPythonExec
import org.apache.spark.sql.execution.window.WindowExec
import org.apache.spark.sql.hive.HiveTableScanExecTransformer

object MiscColumnarRules {
  // This rule will conduct the conversion from Spark plan to the plan transformer.
  case class TransformPreOverrides() extends Rule[SparkPlan] with LogLevelUtil {
    val columnarConf: GlutenConfig = GlutenConfig.getConf
    @transient private val planChangeLogger = new PlanChangeLogger[SparkPlan]()

    /**
     * Generate a plan for hash aggregation.
     * @param plan:
     *   the original Spark plan.
     * @return
     *   the actually used plan for execution.
     */
    private def genHashAggregateExec(plan: HashAggregateExec): SparkPlan = {
      val newChild = replaceWithTransformerPlan(plan.child)
      def transformHashAggregate(): GlutenPlan = {
        BackendsApiManager.getSparkPlanExecApiInstance
          .genHashAggregateExecTransformer(
            plan.requiredChildDistributionExpressions,
            plan.groupingExpressions,
            plan.aggregateExpressions,
            plan.aggregateAttributes,
            plan.initialInputBufferOffset,
            plan.resultExpressions,
            newChild
          )
      }

      // If child's output is empty, fallback or offload both the child and aggregation.
      if (
        plan.child.output.isEmpty && BackendsApiManager.getSettings.fallbackAggregateWithChild()
      ) {
        newChild match {
          case _: TransformSupport =>
            // If the child is transformable, transform aggregation as well.
            logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
            transformHashAggregate()
          case p: SparkPlan if PlanUtil.isGlutenTableCache(p) =>
            transformHashAggregate()
          case _ =>
            // If the child is not transformable, transform the grandchildren only.
            TransformHints.tagNotTransformable(plan, "child output schema is empty")
            val grandChildren = plan.child.children.map(child => replaceWithTransformerPlan(child))
            plan.withNewChildren(Seq(plan.child.withNewChildren(grandChildren)))
        }
      } else {
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        transformHashAggregate()
      }
    }

    /**
     * Generate a plan for filter.
     * @param plan:
     *   the original Spark plan.
     * @return
     *   the actually used plan for execution.
     */
    private def genFilterExec(plan: FilterExec): SparkPlan = {
      // FIXME: Filter push-down should be better done by Vanilla Spark's planner or by
      //  a individual rule.
      val scan = plan.child
      // Push down the left conditions in Filter into FileSourceScan.
      val newChild: SparkPlan = scan match {
        case _: FileSourceScanExec | _: BatchScanExec =>
          if (TransformHints.isTransformable(scan)) {
            val newScan = FilterHandler.applyFilterPushdownToScan(plan)
            newScan match {
              case ts: TransformSupport if ts.doValidate().isValid => ts
              case _ => replaceWithTransformerPlan(scan)
            }
          } else {
            replaceWithTransformerPlan(scan)
          }
        case _ => replaceWithTransformerPlan(plan.child)
      }
      logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
      BackendsApiManager.getSparkPlanExecApiInstance
        .genFilterExecTransformer(plan.condition, newChild)
    }

    def applyScanNotTransformable(plan: SparkPlan): SparkPlan = plan match {
      case plan: FileSourceScanExec =>
        val newPartitionFilters =
          ExpressionConverter.transformDynamicPruningExpr(plan.partitionFilters)
        val newSource = plan.copy(partitionFilters = newPartitionFilters)
        if (plan.logicalLink.nonEmpty) {
          newSource.setLogicalLink(plan.logicalLink.get)
        }
        TransformHints.tag(newSource, TransformHints.getHint(plan))
        newSource
      case plan: BatchScanExec =>
        val newPartitionFilters: Seq[Expression] = plan.scan match {
          case scan: FileScan =>
            ExpressionConverter.transformDynamicPruningExpr(scan.partitionFilters)
          case _ =>
            ExpressionConverter.transformDynamicPruningExpr(plan.runtimeFilters)
        }
        val newSource = plan.copy(runtimeFilters = newPartitionFilters)
        if (plan.logicalLink.nonEmpty) {
          newSource.setLogicalLink(plan.logicalLink.get)
        }
        TransformHints.tag(newSource, TransformHints.getHint(plan))
        newSource
      case plan if HiveTableScanExecTransformer.isHiveTableScan(plan) =>
        val newPartitionFilters: Seq[Expression] = ExpressionConverter.transformDynamicPruningExpr(
          HiveTableScanExecTransformer.getPartitionFilters(plan))
        val newSource = HiveTableScanExecTransformer.copyWith(plan, newPartitionFilters)
        if (plan.logicalLink.nonEmpty) {
          newSource.setLogicalLink(plan.logicalLink.get)
        }
        TransformHints.tag(newSource, TransformHints.getHint(plan))
        newSource
      case other =>
        throw new UnsupportedOperationException(s"${other.getClass.toString} is not supported.")
    }

    def replaceWithTransformerPlan(plan: SparkPlan): SparkPlan = {
      if (TransformHints.isNotTransformable(plan)) {
        logDebug(s"Columnar Processing for ${plan.getClass} is under row guard.")
        plan match {
          case shj: ShuffledHashJoinExec =>
            if (BackendsApiManager.getSettings.recreateJoinExecOnFallback()) {
              // Because we manually removed the build side limitation for LeftOuter, LeftSemi and
              // RightOuter, need to change the build side back if this join fallback into vanilla
              // Spark for execution.
              return ShuffledHashJoinExec(
                shj.leftKeys,
                shj.rightKeys,
                shj.joinType,
                getSparkSupportedBuildSide(shj),
                shj.condition,
                replaceWithTransformerPlan(shj.left),
                replaceWithTransformerPlan(shj.right),
                shj.isSkewJoin
              )
            } else {
              return shj.withNewChildren(shj.children.map(replaceWithTransformerPlan))
            }
          case plan: BatchScanExec =>
            return applyScanNotTransformable(plan)
          case plan: FileSourceScanExec =>
            return applyScanNotTransformable(plan)
          case plan if HiveTableScanExecTransformer.isHiveTableScan(plan) =>
            return applyScanNotTransformable(plan)
          case p =>
            return p.withNewChildren(p.children.map(replaceWithTransformerPlan))
        }
      }
      plan match {
        case plan: BatchScanExec =>
          applyScanTransformer(plan)
        case plan: FileSourceScanExec =>
          applyScanTransformer(plan)
        case plan if HiveTableScanExecTransformer.isHiveTableScan(plan) =>
          applyScanTransformer(plan)
        case plan: CoalesceExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          CoalesceExecTransformer(plan.numPartitions, replaceWithTransformerPlan(plan.child))
        case plan: ProjectExec =>
          val columnarChild = replaceWithTransformerPlan(plan.child)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          ProjectExecTransformer(plan.projectList, columnarChild)
        case plan: FilterExec =>
          genFilterExec(plan)
        case plan: HashAggregateExec =>
          genHashAggregateExec(plan)
        case plan: SortAggregateExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          BackendsApiManager.getSparkPlanExecApiInstance
            .genHashAggregateExecTransformer(
              plan.requiredChildDistributionExpressions,
              plan.groupingExpressions,
              plan.aggregateExpressions,
              plan.aggregateAttributes,
              plan.initialInputBufferOffset,
              plan.resultExpressions,
              plan.child match {
                case sort: SortExecTransformer if !sort.global =>
                  replaceWithTransformerPlan(sort.child)
                case sort: SortExec if !sort.global =>
                  replaceWithTransformerPlan(sort.child)
                case _ => replaceWithTransformerPlan(plan.child)
              }
            )
        case plan: ObjectHashAggregateExec =>
          val child = replaceWithTransformerPlan(plan.child)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          BackendsApiManager.getSparkPlanExecApiInstance
            .genHashAggregateExecTransformer(
              plan.requiredChildDistributionExpressions,
              plan.groupingExpressions,
              plan.aggregateExpressions,
              plan.aggregateAttributes,
              plan.initialInputBufferOffset,
              plan.resultExpressions,
              child
            )
        case plan: UnionExec =>
          val children = plan.children.map(replaceWithTransformerPlan)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          ColumnarUnionExec(children)
        case plan: ExpandExec =>
          val child = replaceWithTransformerPlan(plan.child)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          ExpandExecTransformer(plan.projections, plan.output, child)
        case plan: WriteFilesExec =>
          val child = replaceWithTransformerPlan(plan.child)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val writeTransformer = WriteFilesExecTransformer(
            child,
            plan.fileFormat,
            plan.partitionColumns,
            plan.bucketSpec,
            plan.options,
            plan.staticPartitions)
          BackendsApiManager.getSparkPlanExecApiInstance.createColumnarWriteFilesExec(
            writeTransformer,
            plan.fileFormat,
            plan.partitionColumns,
            plan.bucketSpec,
            plan.options,
            plan.staticPartitions
          )
        case plan: SortExec =>
          val child = replaceWithTransformerPlan(plan.child)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          SortExecTransformer(plan.sortOrder, plan.global, child, plan.testSpillFrequency)
        case plan: TakeOrderedAndProjectExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val child = replaceWithTransformerPlan(plan.child)
          val (limit, offset) = SparkShimLoader.getSparkShims.getLimitAndOffsetFromTopK(plan)
          TakeOrderedAndProjectExecTransformer(
            limit,
            plan.sortOrder,
            plan.projectList,
            child,
            offset)
        case plan: ShuffleExchangeExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val child = replaceWithTransformerPlan(plan.child)
          if (
            (child.supportsColumnar || columnarConf.enablePreferColumnar) &&
            BackendsApiManager.getSettings.supportColumnarShuffleExec()
          ) {
            BackendsApiManager.getSparkPlanExecApiInstance.genColumnarShuffleExchange(plan, child)
          } else {
            plan.withNewChildren(Seq(child))
          }
        case plan: ShuffledHashJoinExec =>
          val left = replaceWithTransformerPlan(plan.left)
          val right = replaceWithTransformerPlan(plan.right)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          BackendsApiManager.getSparkPlanExecApiInstance
            .genShuffledHashJoinExecTransformer(
              plan.leftKeys,
              plan.rightKeys,
              plan.joinType,
              plan.buildSide,
              plan.condition,
              left,
              right,
              plan.isSkewJoin)
        case plan: SortMergeJoinExec =>
          val left = replaceWithTransformerPlan(plan.left)
          val right = replaceWithTransformerPlan(plan.right)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          SortMergeJoinExecTransformer(
            plan.leftKeys,
            plan.rightKeys,
            plan.joinType,
            plan.condition,
            left,
            right,
            plan.isSkewJoin)
        case plan: BroadcastExchangeExec =>
          val child = replaceWithTransformerPlan(plan.child)
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          ColumnarBroadcastExchangeExec(plan.mode, child)
        case plan: BroadcastHashJoinExec =>
          val left = replaceWithTransformerPlan(plan.left)
          val right = replaceWithTransformerPlan(plan.right)
          BackendsApiManager.getSparkPlanExecApiInstance
            .genBroadcastHashJoinExecTransformer(
              plan.leftKeys,
              plan.rightKeys,
              plan.joinType,
              plan.buildSide,
              plan.condition,
              left,
              right,
              isNullAwareAntiJoin = plan.isNullAwareAntiJoin)
        case plan: CartesianProductExec =>
          val left = replaceWithTransformerPlan(plan.left)
          val right = replaceWithTransformerPlan(plan.right)
          BackendsApiManager.getSparkPlanExecApiInstance
            .genCartesianProductExecTransformer(left, right, plan.condition)
        case plan: BroadcastNestedLoopJoinExec =>
          val left = replaceWithTransformerPlan(plan.left)
          val right = replaceWithTransformerPlan(plan.right)
          BackendsApiManager.getSparkPlanExecApiInstance
            .genBroadcastNestedLoopJoinExecTransformer(
              left,
              right,
              plan.buildSide,
              plan.joinType,
              plan.condition)
        case plan: WindowExec =>
          WindowExecTransformer(
            plan.windowExpression,
            plan.partitionSpec,
            plan.orderSpec,
            replaceWithTransformerPlan(plan.child))
        case plan: GlobalLimitExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val child = replaceWithTransformerPlan(plan.child)
          val (limit, offset) = SparkShimLoader.getSparkShims.getLimitAndOffsetFromGlobalLimit(plan)
          LimitTransformer(child, offset, limit)
        case plan: LocalLimitExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val child = replaceWithTransformerPlan(plan.child)
          LimitTransformer(child, 0L, plan.limit)
        case plan: GenerateExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val child = replaceWithTransformerPlan(plan.child)
          BackendsApiManager.getSparkPlanExecApiInstance.genGenerateTransformer(
            plan.generator,
            plan.requiredChildOutput,
            plan.outer,
            plan.generatorOutput,
            child)
        case plan: EvalPythonExec =>
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          val child = replaceWithTransformerPlan(plan.child)
          EvalPythonExecTransformer(plan.udfs, plan.resultAttrs, child)
        case p =>
          logDebug(s"Transformation for ${p.getClass} is currently not supported.")
          val children = plan.children.map(replaceWithTransformerPlan)
          p.withNewChildren(children)
      }
    }

    /**
     * Get the build side supported by the execution of vanilla Spark.
     *
     * @param plan:
     *   shuffled hash join plan
     * @return
     *   the supported build side
     */
    private def getSparkSupportedBuildSide(plan: ShuffledHashJoinExec): BuildSide = {
      plan.joinType match {
        case LeftOuter | LeftSemi => BuildRight
        case RightOuter => BuildLeft
        case _ => plan.buildSide
      }
    }

    /**
     * Apply scan transformer for file source and batch source,
     *   1. create new filter and scan transformer, 2. validate, tag new scan as unsupported if
     *      failed, 3. return new source.
     */
    def applyScanTransformer(plan: SparkPlan): SparkPlan = plan match {
      case plan: FileSourceScanExec =>
        val transformer = ScanTransformerFactory.createFileSourceScanTransformer(plan)
        val validationResult = transformer.doValidate()
        if (validationResult.isValid) {
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          transformer
        } else {
          logDebug(s"Columnar Processing for ${plan.getClass} is currently unsupported.")
          val newSource = plan.copy(partitionFilters = transformer.partitionFilters)
          TransformHints.tagNotTransformable(newSource, validationResult.reason.get)
          newSource
        }
      case plan: BatchScanExec =>
        ScanTransformerFactory.createBatchScanTransformer(plan)

      case plan if HiveTableScanExecTransformer.isHiveTableScan(plan) =>
        // TODO: Add DynamicPartitionPruningHiveScanSuite.scala
        val newPartitionFilters: Seq[Expression] = ExpressionConverter.transformDynamicPruningExpr(
          HiveTableScanExecTransformer.getPartitionFilters(plan))
        val hiveTableScanExecTransformer =
          BackendsApiManager.getSparkPlanExecApiInstance.genHiveTableScanExecTransformer(plan)
        val validateResult = hiveTableScanExecTransformer.doValidate()
        if (validateResult.isValid) {
          logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
          return hiveTableScanExecTransformer
        }
        logDebug(s"Columnar Processing for ${plan.getClass} is currently unsupported.")
        val newSource = HiveTableScanExecTransformer.copyWith(plan, newPartitionFilters)
        TransformHints.tagNotTransformable(newSource, validateResult.reason.get)
        newSource
      case other =>
        throw new UnsupportedOperationException(s"${other.getClass.toString} is not supported.")
    }

    def apply(plan: SparkPlan): SparkPlan = {
      val newPlan = replaceWithTransformerPlan(plan)

      planChangeLogger.logRule(ruleName, plan, newPlan)
      newPlan
    }
  }

  // This rule will try to convert the row-to-columnar and columnar-to-row
  // into native implementations.
  case class TransformPostOverrides() extends Rule[SparkPlan] {
    val columnarConf: GlutenConfig = GlutenConfig.getConf
    @transient private val planChangeLogger = new PlanChangeLogger[SparkPlan]()

    def replaceWithTransformerPlan(plan: SparkPlan): SparkPlan = plan.transformDown {
      case RowToColumnarExec(child) =>
        logDebug(s"ColumnarPostOverrides RowToColumnarExec(${child.getClass})")
        BackendsApiManager.getSparkPlanExecApiInstance.genRowToColumnarExec(child)
      case c2r @ ColumnarToRowExec(child) if PlanUtil.outputNativeColumnarData(child) =>
        logDebug(s"ColumnarPostOverrides ColumnarToRowExec(${child.getClass})")
        val nativeC2r = BackendsApiManager.getSparkPlanExecApiInstance.genColumnarToRowExec(child)
        if (nativeC2r.doValidate().isValid) {
          nativeC2r
        } else {
          c2r
        }
    }

    // apply for the physical not final plan
    def apply(plan: SparkPlan): SparkPlan = {
      val newPlan = replaceWithTransformerPlan(plan)
      planChangeLogger.logRule(ruleName, plan, newPlan)
      newPlan
    }
  }

  // Remove topmost columnar-to-row otherwise AQE throws error.
  // See: org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec#newQueryStage
  //
  // The rule is basically a workaround because of the limited compatibility between Spark's AQE
  // and columnar API.
  case class RemoveTopmostColumnarToRow(session: SparkSession, isAdaptiveContext: Boolean)
    extends Rule[SparkPlan] {
    override def apply(plan: SparkPlan): SparkPlan = {
      if (!isAdaptiveContext) {
        // The rule only applies in AQE. If AQE is off the topmost C2R will be strictly required
        // by Spark.
        return plan
      }
      plan match {
        // See: org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec#newQueryStage
        case ColumnarToRowLike(child: ShuffleExchangeLike) => child
        case ColumnarToRowLike(child: BroadcastExchangeLike) => child
        // See: org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec#getFinalPhysicalPlan
        //  BroadQueryStageExec could be inside a C2R which may cause check failures. E.g.,
        //  org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec#doExecuteBroadcast
        // Note: To avoid the similar issue with AQE=off, we don't remove the C2R on
        //  ShuffleQueryStageExec. Also there is not check like the one for BroadcastQueryStageExec
        //  so it's safe to keep it.
        case ColumnarToRowLike(child: BroadcastQueryStageExec) => child
        case other => other
      }
    }
  }

  // `InMemoryTableScanExec` internally supports ColumnarToRow.
  case class RemoveGlutenTableCacheColumnarToRow(session: SparkSession) extends Rule[SparkPlan] {
    override def apply(plan: SparkPlan): SparkPlan = plan.transformDown {
      case ColumnarToRowLike(child) if PlanUtil.isGlutenTableCache(child) =>
        child
    }
  }
}
