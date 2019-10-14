package org.apache.spark.ml.feature

import java.util

import org.apache.spark.ml.data.{SCol, SDFrame, SRow, UDF}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.transformer.ServingTrans
import org.apache.spark.ml.util.SchemaUtils
import org.apache.spark.sql.types.{ArrayType, StringType, StructField, StructType}

class StopWordsRemoverServing(stage: StopWordsRemover) extends ServingTrans{

  override def transform(dataset: SDFrame): SDFrame = {
    val outputSchema = transformSchema(dataset.schema)
    val tUDF = if (stage.getCaseSensitive) {
      val stopWordsSet = stage.getStopWords.toSet
      UDF.make[Seq[String], Seq[String]](terms => terms.filter(s => !stopWordsSet.contains(s)), false)
    } else {
      // TODO: support user locale (SPARK-15064)
      val toLower = (s: String) => if (s != null) s.toLowerCase else s
      val lowerStopWords = stage.getStopWords.map(toLower(_)).toSet
      UDF.make[Seq[String], Seq[String]](terms => terms.filter(s => !lowerStopWords.contains(toLower(s))), false)
    }
    val metadata = outputSchema(stage.getOutputCol).metadata
    dataset.select(SCol(), tUDF(stage.getOutputCol, SCol(stage.getInputCol)).setSchema(stage.getOutputCol, metadata))
  }

  override def copy(extra: ParamMap): StopWordsRemoverServing = {
    new StopWordsRemoverServing(stage.copy(extra))
  }

  override def transformSchema(schema: StructType): StructType = {
    val inputType = schema(stage.getInputCol).dataType
    require(inputType.sameType(ArrayType(StringType)),
      s"Input type must be ArrayType(StringType) but got $inputType.")
    SchemaUtils.appendColumn(schema, stage.getOutputCol, inputType, schema(stage.getInputCol).nullable)
  }

  override val uid: String = stage.uid

  override def prepareData(rows: Array[SRow]): SDFrame = {
    if (stage.isDefined(stage.inputCol)) {
      val schema = new StructType().add(new StructField(stage.getInputCol, ArrayType(StringType), true))
      new SDFrame(rows)(schema)
    } else {
      throw new Exception (s"inputCol or inputCols of ${stage} is not defined!")
    }
  }

  override def prepareData(feature: util.Map[String, _]): SDFrame = {
    if (stage.isDefined(stage.inputCol)) {
      val featureName = feature.keySet.toArray
      if (!featureName.contains(stage.getInputCol)) {
        throw new Exception (s"the ${stage.getInputCol} is not included in the input col(s)")
      } else if (!feature.get(stage.getInputCol).isInstanceOf[Seq[String]]) {
        throw new Exception (s"the type of col ${stage.getInputCol} is not Seq[String]")
      } else {
        val schema = new StructType().add(new StructField(stage.getInputCol, ArrayType(StringType), true))
        val rows =  Array(new SRow(Array(feature.get(stage.getInputCol))))
        new SDFrame(rows)(schema)
      }
    } else {
      throw new Exception (s"inputCol or inputCols of ${stage} is not defined!")
    }
  }
}

object StopWordsRemoverServing {
  def apply(stage: StopWordsRemover): StopWordsRemoverServing = new StopWordsRemoverServing(stage)
}