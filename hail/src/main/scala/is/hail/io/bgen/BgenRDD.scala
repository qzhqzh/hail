package is.hail.io.bgen

import is.hail.annotations._
import is.hail.asm4s.AsmFunction4
import is.hail.expr.types._
import is.hail.expr.types.physical.PStruct
import is.hail.expr.types.virtual.{TStruct, Type}
import is.hail.io.HadoopFSDataBinaryReader
import is.hail.io.index.{IndexReader, IndexReaderBuilder, LeafChild}
import is.hail.rvd._
import is.hail.sparkextras._
import is.hail.variant.ReferenceGenome
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.{OneToOneDependency, Partition, SparkContext, TaskContext}

import scala.language.reflectiveCalls

sealed trait EntriesSetting
case object NoEntries extends EntriesSetting
final case class EntriesWithFields (
  gt: Boolean,
  gp: Boolean,
  dosage: Boolean
) extends EntriesSetting

sealed case class RowFields (
  varid: Boolean,
  rsid: Boolean,
  offset: Boolean,
  fileIdx: Boolean
)

case class BgenSettings(
  nSamples: Int,
  entries: EntriesSetting,
  dropCols: Boolean,
  rowFields: RowFields,
  rgBc: Option[Broadcast[ReferenceGenome]],
  indexAnnotationType: Type
) {
  val (includeGT, includeGP, includeDosage) = entries match {
    case NoEntries => (false, false, false)
    case EntriesWithFields(gt, gp, dosage) => (gt, gp, dosage)
  }

  val matrixType: MatrixType = MatrixBGENReader.getMatrixType(
    rgBc.map(_.value),
    rowFields.rsid,
    rowFields.varid,
    rowFields.offset,
    rowFields.fileIdx,
    includeGT,
    includeGP,
    includeDosage
  )

  val typ: TStruct = entries match {
    case NoEntries =>
      matrixType.rowType
    case _: EntriesWithFields =>
      matrixType.rvRowType
  }

  def pType: PStruct = typ.physicalType

  def rg: Option[ReferenceGenome] = rgBc.map(_.value)
}

object BgenRDD {
  def apply(
    sc: SparkContext,
    partitions: Array[Partition],
    settings: BgenSettings,
    keys: RDD[Row]
  ): ContextRDD[RVDContext, RegionValue] = {
    ContextRDD(new BgenRDD(sc, partitions, settings, keys))
  }

  private[bgen] def decompress(
    input: Array[Byte],
    uncompressedSize: Int
  ): Array[Byte] = is.hail.utils.decompress(input, uncompressedSize)
}

private class BgenRDD(
  sc: SparkContext,
  parts: Array[Partition],
  settings: BgenSettings,
  keys: RDD[Row]
) extends RDD[RVDContext => Iterator[RegionValue]](sc, if (keys == null) Nil else Seq(new OneToOneDependency(keys))) {
  private[this] val f = CompileDecoder(settings)
  private[this] val indexBuilder = IndexReaderBuilder(settings)

  protected def getPartitions: Array[Partition] = parts

  def compute(split: Partition, context: TaskContext): Iterator[RVDContext => Iterator[RegionValue]] =
    Iterator.single { (ctx: RVDContext) =>
      split match {
        case p: IndexBgenPartition =>
          assert(keys == null)
          new IndexBgenRecordIterator(ctx, p, settings, f()).flatten
        case p: LoadBgenPartition =>
          val index: IndexReader = indexBuilder(p.sHadoopConfBc.value.value, p.indexPath, 8)
          context.addTaskCompletionListener { (context: TaskContext) =>
            index.close()
          }
          if (keys == null)
            new BgenRecordIteratorWithoutFilter(ctx, p, settings, f(), index).flatten
          else {
            val keyIterator = keys.iterator(p.filterPartition, context)
            new BgenRecordIteratorWithFilter(ctx, p, settings, f(), index, keyIterator).flatten
          }
      }
    }
}

private class IndexBgenRecordIterator(
  ctx: RVDContext,
  p: IndexBgenPartition,
  settings: BgenSettings,
  f: AsmFunction4[Region, BgenPartition, HadoopFSDataBinaryReader, BgenSettings, Long]
) extends Iterator[Option[RegionValue]] {
  private[this] val bfis = p.makeInputStream
  bfis.seek(p.startByteOffset)

  private[this] val rv = RegionValue(ctx.region)

  def next(): Option[RegionValue] = {
    val maybeOffset = f(ctx.region, p, bfis, settings)
    if (maybeOffset == -1) {
      None
    } else {
      rv.setOffset(maybeOffset)
      Some(rv)
    }
  }

  def hasNext: Boolean =
    bfis.getPosition < p.endByteOffset
}

private class BgenRecordIteratorWithoutFilter(
  ctx: RVDContext,
  p: LoadBgenPartition,
  settings: BgenSettings,
  f: AsmFunction4[Region, BgenPartition, HadoopFSDataBinaryReader, BgenSettings, Long],
  index: IndexReader
) extends Iterator[Option[RegionValue]] {
  private[this] val bfis = p.makeInputStream
  private[this] val it = index.iterator(p.startIndex, p.endIndex)
  private[this] val rv = RegionValue(ctx.region)

  def next(): Option[RegionValue] = {
    val recordOffset = it.next().recordOffset
    if (recordOffset != bfis.getPosition)
      bfis.seek(recordOffset)

    val maybeOffset = f(ctx.region, p, bfis, settings)
    if (maybeOffset == -1) {
      None
    } else {
      rv.setOffset(maybeOffset)
      Some(rv)
    }
  }

  def hasNext: Boolean =
    it.hasNext
}

private class BgenRecordIteratorWithFilter(
  ctx: RVDContext,
  p: LoadBgenPartition,
  settings: BgenSettings,
  f: AsmFunction4[Region, BgenPartition, HadoopFSDataBinaryReader, BgenSettings, Long],
  index: IndexReader,
  keys: Iterator[Annotation]
) extends Iterator[Option[RegionValue]] {
  private[this] val bfis = p.makeInputStream
  private[this] val rv = RegionValue(ctx.region)
  private[this] val it = index.iterator(p.startIndex, p.endIndex)
  private[this] var isEnd = false
  private[this] var current: LeafChild = _
  private[this] var key: Annotation = _
  private[this] val ordering = index.keyType.ordering

  def next(): Option[RegionValue] = {
    val recordOffset = current.recordOffset
    if (recordOffset != bfis.getPosition)
      bfis.seek(recordOffset)

    val maybeOffset = f(ctx.region, p, bfis, settings)
    val result = if (maybeOffset == -1) {
      None
    } else {
      rv.setOffset(maybeOffset)
      Some(rv)
    }
    current = null
    result
  }

  def hasNext: Boolean = {
    if (isEnd)
      return false

    if ((current == null && !it.hasNext) || (key == null && !keys.hasNext)) {
      isEnd = true
      return false
    }

    if (key == null)
      key = keys.next()

    if (current == null)
      current = it.next()

    while (current != null && key != null && !ordering.equiv(current.key, key)) {
      if (ordering.lt(key, current.key))
        key = if (keys.hasNext) keys.next() else null
      else {
        it.seek(key)
        current = if (it.hasNext) it.next() else null
      }
    }

    current != null && key != null
  }
}
