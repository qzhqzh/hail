package is.hail.variant

import is.hail.annotations._
import is.hail.check.Gen
import is.hail.expr.ir
import is.hail.expr.ir._
import is.hail.expr.types._
import is.hail.expr.types.physical.PStruct
import is.hail.expr.types.virtual._
import is.hail.rvd._
import is.hail.sparkextras.ContextRDD
import is.hail.table.{AbstractTableSpec, Table, TableSpec}
import is.hail.utils._
import is.hail.{HailContext, utils}
import org.apache.hadoop
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization

import scala.language.{existentials, implicitConversions}

abstract class ComponentSpec

object RelationalSpec {
  implicit val formats: Formats = new DefaultFormats() {
    override val typeHints = ShortTypeHints(List(
      classOf[ComponentSpec], classOf[RVDComponentSpec], classOf[PartitionCountsComponentSpec],
      classOf[RelationalSpec], classOf[MatrixTableSpec], classOf[TableSpec]))
    override val typeHintFieldName = "name"
  } +
    new TableTypeSerializer +
    new MatrixTypeSerializer

  def read(hc: HailContext, path: String): RelationalSpec = {
    if (!hc.hadoopConf.isDir(path))
      fatal(s"MatrixTable and Table files are directories; path '$path' is not a directory")
    val metadataFile = path + "/metadata.json.gz"
    val jv = hc.hadoopConf.readFile(metadataFile) { in => parse(in) }

    val fileVersion = jv \ "file_version" match {
      case JInt(rep) => SemanticVersion(rep.toInt)
      case _ =>
        fatal(
          s"""cannot read file: metadata does not contain file version: $metadataFile
             |  Common causes:
             |    - File is an 0.1 VariantDataset or KeyTable (0.1 and 0.2 native formats are not compatible!)""".stripMargin)
    }

    if (!FileFormat.version.supports(fileVersion))
      fatal(s"incompatible file format when reading: $path\n  supported version: ${ FileFormat.version }, found $fileVersion")

    // FIXME this violates the abstraction of the serialization boundary
    val referencesRelPath = (jv \ "references_rel_path": @unchecked) match {
      case JString(p) => p
    }
    ReferenceGenome.importReferences(hc.hadoopConf, path + "/" + referencesRelPath)

    jv.extract[RelationalSpec]
  }
}

abstract class RelationalSpec {
  def file_version: Int

  def hail_version: String

  def components: Map[String, ComponentSpec]

  def getComponent[T <: ComponentSpec](name: String): T = components(name).asInstanceOf[T]

  def globalsComponent: RVDComponentSpec = getComponent[RVDComponentSpec]("globals")

  def partitionCounts: Array[Long] = getComponent[PartitionCountsComponentSpec]("partition_counts").counts.toArray

  def write(hadoopConf: org.apache.hadoop.conf.Configuration, path: String) {
    hadoopConf.writeTextFile(path + "/metadata.json.gz") { out =>
      Serialization.write(this, out)(RelationalSpec.formats)
    }
  }

  def rvdType(path: String): RVDType
}

case class RVDComponentSpec(rel_path: String) extends ComponentSpec {
  def rvdSpec(hadoopConf: org.apache.hadoop.conf.Configuration, path: String): AbstractRVDSpec =
    AbstractRVDSpec.read(hadoopConf, path + "/" + rel_path)

  def read(hc: HailContext, path: String, requestedType: PStruct): RVD = {
    val rvdPath = path + "/" + rel_path
    rvdSpec(hc.hadoopConf, path)
      .read(hc, rvdPath, requestedType)
  }

  def readLocal(hc: HailContext, path: String, requestedType: PStruct): IndexedSeq[Row] = {
    val rvdPath = path + "/" + rel_path
    rvdSpec(hc.hadoopConf, path)
      .readLocal(hc, rvdPath, requestedType)
  }
}

case class PartitionCountsComponentSpec(counts: Seq[Long]) extends ComponentSpec

abstract class AbstractMatrixTableSpec extends RelationalSpec {
  def matrix_type: MatrixType

  def references_rel_path: String

  def colsComponent: RVDComponentSpec = getComponent[RVDComponentSpec]("cols")

  def rowsComponent: RVDComponentSpec = getComponent[RVDComponentSpec]("rows")

  def entriesComponent: RVDComponentSpec = getComponent[RVDComponentSpec]("entries")

  def rvdType(path: String): RVDType = {
    val rows = AbstractRVDSpec.read(HailContext.get, path + "/" + rowsComponent.rel_path)
    val entries = AbstractRVDSpec.read(HailContext.get, path + "/" + entriesComponent.rel_path)
    RVDType(rows.encodedType.appendKey(MatrixType.entriesIdentifier, entries.encodedType), rows.key)
  }

  def rowsTableSpec(path: String): AbstractTableSpec = RelationalSpec.read(HailContext.get, path).asInstanceOf[AbstractTableSpec]
  def colsTableSpec(path: String): AbstractTableSpec = RelationalSpec.read(HailContext.get, path).asInstanceOf[AbstractTableSpec]
  def entriesTableSpec(path: String): AbstractTableSpec = RelationalSpec.read(HailContext.get, path).asInstanceOf[AbstractTableSpec]
}

case class MatrixTableSpec(
  file_version: Int,
  hail_version: String,
  references_rel_path: String,
  matrix_type: MatrixType,
  components: Map[String, ComponentSpec]) extends AbstractMatrixTableSpec {

  // some legacy files written as MatrixTableSpec wrote the wrong type to the entries table metadata
  override def entriesTableSpec(path: String): AbstractTableSpec = {
    val writtenETS = super.entriesTableSpec(path).asInstanceOf[TableSpec]
    writtenETS.copy(table_type = TableType(matrix_type.entriesRVType, FastIndexedSeq(), matrix_type.globalType))
  }
}

object FileFormat {
  val version: SemanticVersion = SemanticVersion(1, 0, 0)
}

object MatrixTable {
  def read(hc: HailContext, path: String, dropCols: Boolean = false, dropRows: Boolean = false): MatrixTable =
    new MatrixTable(hc, MatrixIR.read(hc, path, dropCols, dropRows, None))

  def fromLegacy[T](hc: HailContext,
    matrixType: MatrixType,
    globals: Annotation,
    colValues: IndexedSeq[Annotation],
    rdd: RDD[(Annotation, Iterable[T])]): MatrixTable = {

    val localGType = matrixType.entryType
    val localRVRowType = matrixType.rvRowType

    val localNCols = colValues.length

    val ds = new MatrixTable(hc, matrixType,
      BroadcastRow(globals.asInstanceOf[Row], matrixType.globalType, hc.sc),
      BroadcastIndexedSeq(colValues, TArray(matrixType.colType), hc.sc),
      RVD.coerce(matrixType.canonicalRVDType,
        ContextRDD.weaken[RVDContext](rdd).cmapPartitions { (ctx, it) =>
          val region = ctx.region
          val rvb = new RegionValueBuilder(region)
          val rv = RegionValue(region)


          it.map { case (va, gs) =>
            val vaRow = va.asInstanceOf[Row]
            assert(matrixType.rowType.typeCheck(vaRow), s"${ matrixType.rowType }, $vaRow")

            rvb.start(localRVRowType.physicalType)
            rvb.startStruct()
            var i = 0
            while (i < vaRow.length) {
              rvb.addAnnotation(localRVRowType.types(i), vaRow.get(i))
              i += 1
            }
            rvb.startArray(localNCols) // gs
            gs.foreach { g => rvb.addAnnotation(localGType, g) }
            rvb.endArray() // gs
            rvb.endStruct()
            rv.setOffset(rvb.end())

            rv
          }
        }))
    ds.typecheck()
    ds
  }

  def range(hc: HailContext, nRows: Int, nCols: Int, nPartitions: Option[Int]): MatrixTable =
    if (nRows == 0) {
      new MatrixTable(hc, MatrixIR.range(hc, nRows, nCols, nPartitions, dropRows=true))
    } else
      new MatrixTable(hc, MatrixIR.range(hc, nRows, nCols, nPartitions))

  def gen(hc: HailContext, gen: VSMSubgen): Gen[MatrixTable] =
    gen.gen(hc)

  def fromRowsTable(kt: Table): MatrixTable = {
    val matrixType = MatrixType.fromParts(
      kt.globalSignature,
      Array.empty[String],
      TStruct.empty(),
      kt.key,
      kt.signature,
      TStruct.empty())

    val rvRowType = matrixType.rvRowType.physicalType
    val oldRowType = kt.signature.physicalType

    val rvd = kt.rvd.mapPartitions(matrixType.canonicalRVDType) { it =>
      val rvb = new RegionValueBuilder()
      val rv2 = RegionValue()

      it.map { rv =>
        rvb.set(rv.region)
        rvb.start(rvRowType)
        rvb.startStruct()
        rvb.addAllFields(oldRowType, rv)
        rvb.startArray(0) // gs
        rvb.endArray()
        rvb.endStruct()
        rv2.set(rv.region, rvb.end())
        rv2
      }
    }

    val colValues =
      BroadcastIndexedSeq(Array.empty[Annotation], TArray(matrixType.colType), kt.hc.sc)

    new MatrixTable(kt.hc, matrixType, kt.globals, colValues, rvd)
  }
}

case class VSMSubgen(
  sSigGen: Gen[Type],
  saSigGen: Gen[TStruct],
  vSigGen: Gen[Type],
  rowPartitionKeyGen: (Type) => Gen[Array[String]],
  vaSigGen: Gen[TStruct],
  globalSigGen: Gen[TStruct],
  tSigGen: Gen[TStruct],
  sGen: (Type) => Gen[Annotation],
  saGen: (TStruct) => Gen[Annotation],
  vaGen: (TStruct) => Gen[Annotation],
  globalGen: (TStruct) => Gen[Annotation],
  vGen: (Type) => Gen[Annotation],
  tGen: (TStruct, Annotation) => Gen[Annotation]) {

  def gen(hc: HailContext): Gen[MatrixTable] =
    for {
      size <- Gen.size
      (l, w) <- Gen.squareOfAreaAtMostSize.resize((size / 3 / 10) * 8)

      vSig <- vSigGen.resize(3)
      rowPartitionKey <- rowPartitionKeyGen(vSig)
      vaSig <- vaSigGen.map(t => t.deepOptional().asInstanceOf[TStruct]).resize(3)
      sSig <- sSigGen.resize(3)
      saSig <- saSigGen.map(t => t.deepOptional().asInstanceOf[TStruct]).resize(3)
      globalSig <- globalSigGen.resize(5)
      tSig <- tSigGen.map(t => t.structOptional().asInstanceOf[TStruct]).resize(3)
      global <- globalGen(globalSig).resize(25)
      nPartitions <- Gen.choose(1, 10)

      sampleIds <- Gen.buildableOfN[Array](w, sGen(sSig).resize(3))
        .map(ids => ids.distinct)
      nSamples = sampleIds.length
      saValues <- Gen.buildableOfN[Array](nSamples, saGen(saSig).resize(5))
      rows <- Gen.buildableOfN[Array](l,
        for {
          v <- vGen(vSig).resize(3)
          va <- vaGen(vaSig).resize(5)
          ts <- Gen.buildableOfN[Array](nSamples, tGen(tSig, v).resize(3))
        } yield (v, (va, ts: Iterable[Annotation])))
    } yield {
      assert(sampleIds.forall(_ != null))
      val (finalSASig, sIns) = saSig.structInsert(sSig, List("s"))

      val (finalVASig, vaIns, finalRowPartitionKey, rowKey) =
        vSig match {
          case vSig: TStruct =>
            val (finalVASig, vaIns) = vaSig.annotate(vSig)
            (finalVASig, vaIns, rowPartitionKey, vSig.fieldNames)
          case _ =>
            val (finalVASig, vaIns) = vaSig.structInsert(vSig, List("v"))
            (finalVASig, vaIns, Array("v"), Array("v"))
        }

      MatrixTable.fromLegacy(hc,
        MatrixType.fromParts(globalSig, Array("s"), finalSASig, rowKey, finalVASig, tSig),
        global,
        sampleIds.zip(saValues).map { case (id, sa) => sIns(sa, id) },
        hc.sc.parallelize(rows.map { case (v, (va, gs)) =>
          (vaIns(va, v), gs)
        }, nPartitions))
        .distinctByRow()
    }
}

object VSMSubgen {
  val random = VSMSubgen(
    sSigGen = Gen.const(TString()),
    saSigGen = Type.genInsertable,
    vSigGen = ReferenceGenome.gen.map(rg =>
      TStruct(
        "locus" -> TLocus(rg),
        "alleles" -> TArray(TString()))),
    rowPartitionKeyGen = (t: Type) => Gen.const(Array("locus")),
    vaSigGen = Type.genInsertable,
    globalSigGen = Type.genInsertable,
    tSigGen = Gen.const(Genotype.htsGenotypeType),
    sGen = (t: Type) => Gen.identifier.map(s => s: Annotation),
    saGen = (t: Type) => t.genValue,
    vaGen = (t: Type) => t.genValue,
    globalGen = (t: Type) => t.genNonmissingValue,
    vGen = (t: Type) => {
      val rg = t.asInstanceOf[TStruct]
        .field("locus")
        .typ
        .asInstanceOf[TLocus]
        .rg.asInstanceOf[ReferenceGenome]
      VariantSubgen.random(rg).genLocusAlleles
    },
    tGen = (t: Type, v: Annotation) => Genotype.genExtreme(
      v.asInstanceOf[Row]
        .getAs[IndexedSeq[String]](1)
        .length))

  val plinkSafeBiallelic: VSMSubgen = random.copy(
    vSigGen = Gen.const(TStruct(
      "locus" -> TLocus(ReferenceGenome.GRCh37),
      "alleles" -> TArray(TString()))),
    sGen = (t: Type) => Gen.plinkSafeIdentifier,
    vGen = (t: Type) => VariantSubgen.plinkCompatibleBiallelic(ReferenceGenome.GRCh37).genLocusAlleles)

  val callAndProbabilities = VSMSubgen(
    sSigGen = Gen.const(TString()),
    saSigGen = Type.genInsertable,
    vSigGen = Gen.const(
      TStruct(
        "locus" -> TLocus(ReferenceGenome.GRCh37),
        "alleles" -> TArray(TString()))),
    rowPartitionKeyGen = (t: Type) => Gen.const(Array("locus")),
    vaSigGen = Type.genInsertable,
    globalSigGen = Type.genInsertable,
    tSigGen = Gen.const(TStruct(
      "GT" -> TCall(),
      "GP" -> TArray(TFloat64()))),
    sGen = (t: Type) => Gen.identifier.map(s => s: Annotation),
    saGen = (t: Type) => t.genValue,
    vaGen = (t: Type) => t.genValue,
    globalGen = (t: Type) => t.genValue,
    vGen = (t: Type) => VariantSubgen.random(ReferenceGenome.GRCh37).genLocusAlleles,
    tGen = (t: Type, v: Annotation) => Genotype.genGenericCallAndProbabilitiesGenotype(
      v.asInstanceOf[Row]
        .getAs[IndexedSeq[String]](1)
        .length))

  val realistic = random.copy(
    tGen = (t: Type, v: Annotation) => Genotype.genRealistic(
      v.asInstanceOf[Row]
        .getAs[IndexedSeq[String]](1)
        .length))
}

class MatrixTable(val hc: HailContext, val ast: MatrixIR) {

  def this(hc: HailContext,
    matrixType: MatrixType,
    globals: BroadcastRow,
    colValues: BroadcastIndexedSeq,
    rvd: RVD) =
    this(hc, MatrixLiteral(MatrixValue(matrixType, globals, colValues, rvd)))

  def referenceGenome: ReferenceGenome = matrixType.referenceGenome

  val matrixType: MatrixType = ast.typ

  val colType: TStruct = matrixType.colType
  val rowType: TStruct = matrixType.rowType
  val entryType: TStruct = matrixType.entryType
  val globalType: TStruct = matrixType.globalType

  val rvRowType: TStruct = matrixType.rvRowType
  val rowKey: IndexedSeq[String] = matrixType.rowKey
  val entriesIndex: Int = matrixType.entriesIdx

  val colKey: IndexedSeq[String] = matrixType.colKey

  def colKeyTypes: Array[Type] = colKey
    .map(s => matrixType.colType.types(matrixType.colType.fieldIdx(s)))
    .toArray

  val rowKeyTypes: Array[Type] = rowKey
    .map(s => matrixType.rowType.types(matrixType.rowType.fieldIdx(s)))
    .toArray

  val rowKeyStruct: TStruct = TStruct(rowKey.zip(rowKeyTypes): _*)

  lazy val value@MatrixValue(_, globals, colValues, rvd) = Interpret(ast, optimize = true)

  def partitionCounts(): Array[Long] = {
    ast.partitionCounts match {
      case Some(counts) => counts.toArray
      case None => rvd.countPerPartition()
    }
  }

  // length nPartitions + 1, first element 0, last element rvd count
  def partitionStarts(): Array[Long] = partitionCounts().scanLeft(0L)(_ + _)

  def colKeys: IndexedSeq[Annotation] = {
    val queriers = colKey.map(colType.query(_))
    colValues.safeValue.map(a => Row.fromSeq(queriers.map(q => q(a)))).toArray[Annotation]
  }

  def rowKeysF: (Row) => Row = {
    val localRowType = rowType
    val queriers = rowKey.map(localRowType.query(_)).toArray
    (r: Row) => Row.fromSeq(queriers.map(_ (r)))
  }

  def stringSampleIds: IndexedSeq[String] = {
    assert(colKeyTypes.length == 1 && colKeyTypes(0).isInstanceOf[TString], colKeyTypes.toSeq)
    val querier = colType.query(colKey(0))
    colValues.value.map(querier(_).asInstanceOf[String])
  }

  def stringSampleIdSet: Set[String] = stringSampleIds.toSet

  def count(): (Long, Long) = (countRows(), countCols())

  def countRows(): Long = Interpret(TableCount(MatrixRowsTable(ast)))

  def countCols(): Long = ast.columnCount.map(_.toLong).getOrElse(Interpret[Long](TableCount(MatrixColsTable(ast))))

  def distinctByRow(): MatrixTable =
    copyAST(ast = MatrixDistinctByRow(ast))

  def dropCols(): MatrixTable =
    copyAST(ast = MatrixFilterCols(ast, ir.False()))

  def dropRows(): MatrixTable = copyAST(MatrixFilterRows(ast, ir.False()))

  def sparkContext: SparkContext = hc.sc

  def hadoopConf: hadoop.conf.Configuration = hc.hadoopConf

  def head(n: Long): MatrixTable = {
    if (n < 0)
      fatal(s"n must be non-negative! Found '$n'.")
    copy2(rvd = rvd.head(n, None))
  }

  def same(that: MatrixTable, tolerance: Double = utils.defaultTolerance, absolute: Boolean = false): Boolean = {
    var metadataSame = true
    if (rowType.deepOptional() != that.rowType.deepOptional()) {
      metadataSame = false
      println(
        s"""different row signature:
           |  left:  ${ rowType.toString }
           |  right: ${ that.rowType.toString }""".stripMargin)
    }
    if (colType.deepOptional() != that.colType.deepOptional()) {
      metadataSame = false
      println(
        s"""different column signature:
           |  left:  ${ colType.toString }
           |  right: ${ that.colType.toString }""".stripMargin)
    }
    if (globalType.deepOptional() != that.globalType.deepOptional()) {
      metadataSame = false
      println(
        s"""different global signature:
           |  left:  ${ globalType.toString }
           |  right: ${ that.globalType.toString }""".stripMargin)
    }
    if (entryType.deepOptional() != that.entryType.deepOptional()) {
      metadataSame = false
      println(
        s"""different entry signature:
           |  left:  ${ entryType.toString }
           |  right: ${ that.entryType.toString }""".stripMargin)
    }
    if (!colValuesSimilar(that, tolerance, absolute)) {
      metadataSame = false
      println(
        s"""different sample annotations:
           |  left:  $colValues
           |  right: ${ that.colValues }""".stripMargin)
    }
    if (!globalType.valuesSimilar(globals.value, that.globals.value, tolerance, absolute)) {
      metadataSame = false
      println(
        s"""different global annotation:
           |  left:  ${ globals.value }
           |  right: ${ that.globals.value }""".stripMargin)
    }
    if (rowKey != that.rowKey || colKey != that.colKey) {
      metadataSame = false
      println(
        s"""
           |different keys:
           |  left:  rk $rowKey, ck $colKey
           |  right: rk ${ that.rowKey }, ck ${ that.colKey }""".stripMargin)
    }
    if (!metadataSame)
      println("metadata were not the same")

    val leftRVType = rvRowType
    val rightRVType = that.rvRowType
    val localRowType = rowType
    val localLeftEntriesIndex = entriesIndex
    val localRightEntriesIndex = that.entriesIndex
    val localEntryType = entryType
    val localRKF = rowKeysF
    val localColKeys = colKeys

    val (_, jcrdd) = this.rvd.orderedZipJoin(that.rvd)

    metadataSame &&
      jcrdd.mapPartitions { it =>
        val fullRow1 = new UnsafeRow(leftRVType.physicalType)
        val fullRow2 = new UnsafeRow(rightRVType.physicalType)

        it.map { case Muple(rv1, rv2) =>
          if (rv2 == null) {
            fullRow1.set(rv1)
            val row1 = fullRow1.deleteField(localRightEntriesIndex)
            println(s"row ${ localRKF(row1) } present in left but not right")
            false
          } else if (rv1 == null) {
            fullRow2.set(rv2)
            val row2 = fullRow2.deleteField(localRightEntriesIndex)
            println(s"row ${ localRKF(row2) } present in right but not left")
            false
          }
          else {
            var partSame = true

            fullRow1.set(rv1)
            fullRow2.set(rv2)
            val row1 = fullRow1.deleteField(localLeftEntriesIndex)
            val row2 = fullRow2.deleteField(localRightEntriesIndex)

            if (!localRowType.valuesSimilar(row1, row2, tolerance, absolute)) {
              println(
                s"""row fields not the same:
                   |  $row1
                   |  $row2""".stripMargin)
              partSame = false
            }

            val gs1 = fullRow1.getAs[IndexedSeq[Annotation]](localLeftEntriesIndex)
            val gs2 = fullRow2.getAs[IndexedSeq[Annotation]](localRightEntriesIndex)

            var i = 0
            while (partSame && i < gs1.length) {
              if (!localEntryType.valuesSimilar(gs1(i), gs2(i), tolerance, absolute)) {
                partSame = false
                println(
                  s"""different entry at row ${ localRKF(row1) }, col ${ localColKeys(i) }
                     |  ${ gs1(i) }
                     |  ${ gs2(i) }""".stripMargin)
              }
              i += 1
            }
            partSame
          }
        }
      }.clearingRun.forall(t => t)
  }

  def colValuesSimilar(that: MatrixTable, tolerance: Double = utils.defaultTolerance, absolute: Boolean = false): Boolean = {
    require(colType == that.colType, s"\n${ colType }\n${ that.colType }")
    colValues.value.zip(that.colValues.value)
      .forall { case (s1, s2) => colType.valuesSimilar(s1, s2, tolerance, absolute)
      }
  }

  def copy2(rvd: RVD = rvd,
    colValues: BroadcastIndexedSeq = colValues,
    colKey: IndexedSeq[String] = colKey,
    globals: BroadcastRow = globals,
    colType: TStruct = colType,
    rvRowType: TStruct = rvRowType,
    rowKey: IndexedSeq[String] = rowKey,
    globalType: TStruct = globalType,
    entryType: TStruct = entryType): MatrixTable = {
    val newMatrixType = matrixType.copy(
      globalType = globalType,
      colKey = colKey,
      colType = colType,
      rowKey = rowKey,
      rvRowType = rvRowType)
    new MatrixTable(hc,
      newMatrixType,
      globals, colValues, rvd)
  }

  def copyAST(ast: MatrixIR = ast): MatrixTable =
    new MatrixTable(hc, ast)

  def storageLevel: String = rvd.storageLevel.toReadableString()

  def numCols: Int = colValues.value.length

  def typecheck() {
    var foundError = false
    if (!globalType.typeCheck(globals.value)) {
      foundError = true
      warn(
        s"""found violation in global annotation
           |Schema: $globalType
           |Annotation: ${ Annotation.printAnnotation(globals.value) }""".stripMargin)
    }

    colValues.value.zipWithIndex.find { case (sa, i) => !colType.typeCheck(sa) }
      .foreach { case (sa, i) =>
        foundError = true
        warn(
          s"""found violation in sample annotations for col $i
             |Schema: $colType
             |Annotation: ${ Annotation.printAnnotation(sa) }""".stripMargin)
      }

    val localRVRowType = rvRowType

    val predicate = { (rv: RegionValue) =>
      val ur = new UnsafeRow(localRVRowType.physicalType, rv)
      !localRVRowType.typeCheck(ur)
    }

    Region.scoped { region =>
      rvd.find(region)(predicate).foreach { rv =>
        val ur = new UnsafeRow(localRVRowType.physicalType, rv)
        foundError = true
        warn(
          s"""found violation in row
             |Schema: $localRVRowType
             |Annotation: ${ Annotation.printAnnotation(ur) }""".stripMargin)
      }
    }

    if (foundError)
      fatal("found one or more type check errors")
  }

  def write(path: String, overwrite: Boolean = false, stageLocally: Boolean = false, codecSpecJSONStr: String = null) {
    ir.Interpret(ir.MatrixWrite(ast, MatrixNativeWriter(path, overwrite, stageLocally, codecSpecJSONStr)))
  }
}
