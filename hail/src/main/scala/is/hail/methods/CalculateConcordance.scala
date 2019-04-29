package is.hail.methods

import is.hail.annotations.UnsafeRow
import is.hail.expr.ir.{Interpret, Literal, MatrixIR, MatrixValue, TableIR, TableKeyBy, TableLiteral, TableParallelize, TableValue}
import is.hail.expr.types.virtual.{TArray, TInt64, TStruct}
import is.hail.utils._
import is.hail.variant._
import org.apache.spark.sql.Row

object ConcordanceCombiner {
  val schema = TArray(TArray(TInt64()))
}

class ConcordanceCombiner extends Serializable {
  // 5x5 square matrix indexed by [NoData, NoCall, HomRef, Het, HomVar] on each axis
  val mapping = MultiArray2.fill(5, 5)(0L)

  def merge(left: Int, right: Int) {
    mapping(left, right) += 1
  }

  def merge(other: ConcordanceCombiner): ConcordanceCombiner = {
    mapping.addElementWise(other.mapping)
    this
  }

  def reset() {
    val a = mapping.array
    var i = 0
    while (i < 25) {
      a(i) = 0L
      i += 1
    }
  }

  def nDiscordant: Long = {
    var n = 0L
    for (i <- 2 to 4)
      for (j <- 2 to 4)
        if (i != j)
          n += mapping(i, j)
    n
  }

  def report() {
    val innerTotal = (1 until 5).map(i => (1 until 5).map(j => mapping(i, j)).sum).sum
    val innerDiagonal = (1 until 5).map(i => mapping(i, i)).sum
    val total = mapping.sum
    info(
      s"""Summary of inner join concordance:
         |  Total observations: $innerTotal
         |  Total concordant observations: $innerDiagonal
         |  Total concordance: ${ (innerDiagonal.toDouble / innerTotal * 100).formatted("%.2f") }%""".stripMargin)
  }

  def toAnnotation: IndexedSeq[IndexedSeq[Long]] =
    (0 until 5).map(i => (0 until 5).map(j => mapping(i, j)).toArray: IndexedSeq[Long]).toArray[IndexedSeq[Long]]
}

object CalculateConcordance {

  def pyApply(leftIR: MatrixIR, rightIR: MatrixIR): (IndexedSeq[IndexedSeq[Long]], TableIR, TableIR) = {
    val left = Interpret(leftIR)
    val right = Interpret(rightIR)

    left.requireUniqueSamples("concordance")
    right.requireUniqueSamples("concordance")

    val overlap = left.stringSampleIds.toSet.intersect(right.stringSampleIds.toSet)
    if (overlap.isEmpty)
      fatal("No overlapping samples between datasets")

    if (!left.typ.rowKeyStruct.types.sameElements(right.typ.rowKeyStruct.types))
      fatal(s"""Cannot compute concordance for datasets with different key types:
              |  left: ${ left.typ.rowKeyStruct.types.map(_.toString).mkString(", ") }
              |  right: ${ right.typ.rowKeyStruct.types.map(_.toString).mkString(", ") }""")

    info(
      s"""Found ${ overlap.size } overlapping samples
         |  Left: ${ left.nCols } total samples
         |  Right: ${ right.nCols } total samples""".stripMargin)

    val leftPreIds = left.stringSampleIds
    val rightPreIds = right.stringSampleIds
    val leftFiltered: MatrixValue = left.filterCols { case (_, i) => overlap(leftPreIds(i)) }
    val rightFiltered: MatrixValue = right.filterCols { case (_, i) => overlap(rightPreIds(i)) }

    val sampleSchema = TStruct(
      left.typ.colKey.zip(left.typ.colKeyStruct.types) ++
      Array("n_discordant" -> TInt64(),
      "concordance" -> ConcordanceCombiner.schema): _*
    )

    val variantSchema = TStruct(
      left.typ.rowKey.zip(left.typ.rowKeyStruct.types) ++
        Array("n_discordant" -> TInt64(), "concordance" -> ConcordanceCombiner.schema): _*
    )

    val leftIds = leftFiltered.stringSampleIds
    val rightIds = rightFiltered.stringSampleIds

    assert(leftIds.toSet == overlap && rightIds.toSet == overlap)

    val rightIdIndex = rightIds.zipWithIndex.toMap
    val leftToRight = leftIds.map(rightIdIndex).toArray
    val leftToRightBc = left.sparkContext.broadcast(leftToRight)

    val (_, join) = leftFiltered.rvd.orderedZipJoin(rightFiltered.rvd)

    val leftRowType = leftFiltered.typ.rvRowType
    val leftRowPType = leftRowType.physicalType
    val rightRowType = rightFiltered.typ.rvRowType
    val rightRowPType = rightRowType.physicalType

    val nSamples = leftIds.length
    val sampleResults = join.mapPartitions { it =>
      val comb = Array.fill(nSamples)(new ConcordanceCombiner)

      val lview = HardCallView(leftRowPType)
      val rview = HardCallView(rightRowPType)

      it.foreach { jrv =>
        val lrv = jrv.rvLeft
        val rrv = jrv.rvRight

        if (lrv != null)
          lview.setRegion(lrv)
        if (rrv != null)
          rview.setRegion(rrv)

        var li = 0
        while (li < nSamples) {
          if (lrv != null)
            lview.setGenotype(li)
          if (rrv != null)
            rview.setGenotype(leftToRightBc.value(li))
          comb(li).merge(
            if (lrv != null) {
              if (lview.hasGT) {
                val gt = Call.unphasedDiploidGtIndex(lview.getGT)
                if (gt > 2)
                  fatal(s"'concordance' requires biallelic genotype calls. Found ${ Call.toString(lview.getGT) }.")
                gt + 2
              }
              else
                1
            } else
              0,
            if (rrv != null) {
              if (rview.hasGT) {
                val gt = Call.unphasedDiploidGtIndex(rview.getGT)
                if (gt > 2)
                  fatal(s"'concordance' requires biallelic genotype calls. Found ${ Call.toString(rview.getGT) }.")
                gt + 2
              }
              else
                1
            } else
              0)
          li += 1
        }
      }
      Iterator(comb)
    }.treeReduce { case (arr1, arr2) =>
      arr1.indices.foreach { i => arr1(i).merge(arr2(i)) }
      arr1
    }

    val leftRowKeysF = left.typ.extractRowKey
    val rightRowKeysF = right.typ.extractRowKey
    val variantCRDD = join.mapPartitions { it =>
      val comb = new ConcordanceCombiner

      val lur = new UnsafeRow(leftRowPType)
      val rur = new UnsafeRow(rightRowPType)
      val lview = HardCallView(leftRowPType)
      val rview = HardCallView(rightRowPType)

      it.map { jrv =>
        comb.reset()

        val lrv = jrv.rvLeft
        val rrv = jrv.rvRight

        val rowKeys: Row =
          if (lrv != null) {
            lur.set(lrv)
            leftRowKeysF(lur)
          } else {
            rur.set(rrv)
            rightRowKeysF(rur)
          }

        if (lrv != null)
          lview.setRegion(lrv)
        if (rrv != null)
          rview.setRegion(rrv)

        var li = 0
        while (li < nSamples) {
          if (lrv != null)
            lview.setGenotype(li)
          if (rrv != null)
            rview.setGenotype(leftToRightBc.value(li))
          comb.merge(
            if (lrv != null) {
              if (lview.hasGT)
                Call.unphasedDiploidGtIndex(lview.getGT) + 2
              else
                1
            } else
              0,
            if (rrv != null) {
              if (rview.hasGT)
                Call.unphasedDiploidGtIndex(rview.getGT) + 2
              else
                1
            } else
              0)
          li += 1
        }
        val r = Row.fromSeq(rowKeys.toSeq ++ Array(comb.nDiscordant, comb.toAnnotation))
        assert(variantSchema.typeCheck(r))
        r
      }
    }

    val global = new ConcordanceCombiner
    sampleResults.foreach(global.merge)

    global.report()

    val sample = TableKeyBy(
      TableParallelize(
        Literal(TStruct("rows" -> TArray(sampleSchema), "global" -> TStruct.empty()),
          Row(leftFiltered.stringSampleIds.zip(sampleResults)
            .map { case (id, comb) => Row(id, comb.nDiscordant, comb.toAnnotation) },
	  Row()))),
      left.typ.colKey)

    val variant = TableKeyBy(
      TableLiteral(TableValue(variantSchema, FastIndexedSeq(), variantCRDD.toRegionValues(variantSchema))),
      left.typ.rowKey,
      isSorted = false)

    (global.toAnnotation, sample, variant)
  }
}
