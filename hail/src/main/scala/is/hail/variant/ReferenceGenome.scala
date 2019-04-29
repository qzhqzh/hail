package is.hail.variant

import java.io.InputStream

import htsjdk.samtools.reference.FastaSequenceIndex
import is.hail.HailContext
import is.hail.asm4s.Code
import is.hail.check.Gen
import is.hail.expr.types._
import is.hail.expr.{JSONExtractContig, JSONExtractIntervalLocus, JSONExtractReferenceGenome, Parser}
import is.hail.io.reference.FASTAReader
import is.hail.utils._
import org.json4s._
import org.json4s.jackson.{JsonMethods, Serialization}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.implicitConversions
import is.hail.expr.Parser._
import is.hail.expr.ir.EmitFunctionBuilder
import is.hail.expr.ir.functions.{IRFunctionRegistry, LiftoverFunctions, ReferenceGenomeFunctions}
import is.hail.expr.types.virtual.{TInt64, TInterval, TLocus, Type}
import is.hail.io.reference.LiftOver
import org.apache.hadoop.conf.Configuration
import org.apache.spark.TaskContext
import org.apache.spark.broadcast.Broadcast

abstract class RGBase extends Serializable {
  def locusType: TLocus

  def name: String

  def locusOrdering: Ordering[Locus]

  def contigParser: Parser[String]

  def isValidContig(contig: String): Boolean

  def checkLocus(l: Locus): Unit

  def checkLocus(contig: String, pos: Int): Unit

  def checkLocusInterval(i: Interval): Unit

  def contigLength(contig: String): Int

  def contigLength(contigIdx: Int): Int

  def inX(contigIdx: Int): Boolean

  def inX(contig: String): Boolean

  def inY(contigIdx: Int): Boolean

  def inY(contig: String): Boolean

  def isMitochondrial(contigIdx: Int): Boolean

  def isMitochondrial(contig: String): Boolean

  def inXPar(locus: Locus): Boolean

  def inYPar(locus: Locus): Boolean

  def compare(c1: String, c2: String): Int

  def unify(concrete: RGBase): Boolean

  def isBound: Boolean

  def clear(): Unit

  def subst(): RGBase

  @transient lazy val broadcastRGBase: BroadcastRGBase = new BroadcastRGBase(this)
}

class BroadcastRGBase(rgParam: RGBase) extends Serializable {
  @transient private[this] val rg: RGBase = rgParam

  private[this] val rgBc: Broadcast[ReferenceGenome] = {
    if (TaskContext.get != null)
      null
    else
      rgParam match {
        case rg: ReferenceGenome => rg.broadcast
        case _ => null
      }
  }

  def value: RGBase = {
    val t = if (rg != null)
      rg
    else
      rgBc.value
    t
  }
}

case class ReferenceGenome(name: String, contigs: Array[String], lengths: Map[String, Int],
  xContigs: Set[String] = Set.empty[String], yContigs: Set[String] = Set.empty[String],
  mtContigs: Set[String] = Set.empty[String], parInput: Array[(Locus, Locus)] = Array.empty[(Locus, Locus)]) extends RGBase {

  val nContigs = contigs.length

  if (nContigs <= 0)
    fatal("Must have at least one contig in the reference genome.")

  if (!contigs.areDistinct())
    fatal("Repeated contig names are not allowed.")

  val missingLengths = contigs.toSet.diff(lengths.keySet)
  val extraLengths = lengths.keySet.diff(contigs.toSet)

  if (missingLengths.nonEmpty)
    fatal(s"No lengths given for the following contigs: ${ missingLengths.mkString(", ") }")

  if (extraLengths.nonEmpty)
    fatal(s"Contigs found in 'lengths' that are not present in 'contigs': ${ extraLengths.mkString(", ") }")

  if (xContigs.intersect(yContigs).nonEmpty)
    fatal(s"Found the contigs '${ xContigs.intersect(yContigs).mkString(", ") }' in both X and Y contigs.")

  if (xContigs.intersect(mtContigs).nonEmpty)
    fatal(s"Found the contigs '${ xContigs.intersect(mtContigs).mkString(", ") }' in both X and MT contigs.")

  if (yContigs.intersect(mtContigs).nonEmpty)
    fatal(s"Found the contigs '${ yContigs.intersect(mtContigs).mkString(", ") }' in both Y and MT contigs.")

  val contigsIndex: Map[String, Int] = contigs.zipWithIndex.toMap
  val contigsSet: Set[String] = contigs.toSet
  val lengthsByIndex: Array[Int] = contigs.map(lengths)

  lengths.foreach { case (n, l) =>
    if (l <= 0)
      fatal(s"Contig length must be positive. Contig '$n' has length equal to $l.")
  }

  val xNotInRef = xContigs.diff(contigsSet)
  val yNotInRef = yContigs.diff(contigsSet)
  val mtNotInRef = mtContigs.diff(contigsSet)

  if (xNotInRef.nonEmpty)
    fatal(s"The following X contig names are absent from the reference: '${ xNotInRef.mkString(", ") }'.")

  if (yNotInRef.nonEmpty)
    fatal(s"The following Y contig names are absent from the reference: '${ yNotInRef.mkString(", ") }'.")

  if (mtNotInRef.nonEmpty)
    fatal(s"The following mitochondrial contig names are absent from the reference: '${ mtNotInRef.mkString(", ") }'.")

  val xContigIndices = xContigs.map(contigsIndex)
  val yContigIndices = yContigs.map(contigsIndex)
  val mtContigIndices = mtContigs.map(contigsIndex)

  val locusOrdering = {
    val localContigsIndex = contigsIndex
    new Ordering[Locus] {
      def compare(x: Locus, y: Locus): Int = ReferenceGenome.compare(localContigsIndex, x, y)
    }
  }

  // must be constructed after orderings
  @transient @volatile var _locusType: TLocus = _

  def locusType: TLocus = {
    if (_locusType == null) {
      synchronized {
        if (_locusType == null)
          _locusType = TLocus(this)
      }
    }
    _locusType
  }

  val par = parInput.map { case (start, end) =>
    if (start.contig != end.contig)
      fatal(s"The contigs for the 'start' and 'end' of a PAR interval must be the same. Found '$start-$end'.")

    if ((!xContigs.contains(start.contig) && !yContigs.contains(start.contig)) ||
      (!xContigs.contains(end.contig) && !yContigs.contains(end.contig)))
      fatal(s"The contig name for PAR interval '$start-$end' was not found in xContigs '${ xContigs.mkString(",") }' or in yContigs '${ yContigs.mkString(",") }'.")

    Interval(start, end, includesStart = true, includesEnd = false)
  }

  private var fastaReader: FASTAReader = _

  def contigParser = Parser.oneOfLiteral(contigs)

  val globalPosContigStarts = {
    var pos = 0L
    contigs.map { c =>
      val x = (c, pos)
      pos += contigLength(c)
      x
    }.toMap
  }

  val nBases = lengths.map(_._2.toLong).sum

  private val globalPosOrd = TInt64().ordering

  @transient private var globalContigEnds: Array[Long] = _

  def getGlobalContigEnds: Array[Long] = contigs.map(contigLength(_).toLong).scan(0L)(_ + _).tail

  def locusToGlobalPos(contig: String, pos: Int): Long =
    globalPosContigStarts(contig) + (pos - 1)

  def locusToGlobalPos(l: Locus): Long = locusToGlobalPos(l.contig, l.position)

  def globalPosToContig(idx: Long): String = {
    if (globalContigEnds == null)
      globalContigEnds = getGlobalContigEnds
    contigs(globalContigEnds.view.partitionPoint(_ > idx))
  }

  def globalPosToLocus(idx: Long): Locus = {
    val contig = globalPosToContig(idx)
    Locus(contig, (idx - globalPosContigStarts(contig) + 1).toInt)
  }

  def contigLength(contig: String): Int = lengths.get(contig) match {
    case Some(l) => l
    case None => fatal(s"Invalid contig name: '$contig'.")
  }

  def contigLength(contigIdx: Int): Int = {
    require(contigIdx >= 0 && contigIdx < nContigs)
    lengthsByIndex(contigIdx)
  }

  def isValidContig(contig: String): Boolean =
    contigsSet.contains(contig)

  def isValidLocus(contig: String, pos: Int): Boolean = isValidContig(contig) && pos > 0 && pos <= contigLength(contig)

  def isValidLocus(l: Locus): Boolean = isValidLocus(l.contig, l.position)

  def checkContig(contig: String): Unit = {
    if (!isValidContig(contig))
      fatal(s"Contig '$contig' is not in the reference genome '$name'.")
  }

  def checkLocus(l: Locus): Unit = checkLocus(l.contig, l.position)

  def checkLocus(contig: String, pos: Int): Unit = {
    if (!isValidLocus(contig, pos)) {
      if (!isValidContig(contig))
        fatal(s"Invalid locus '$contig:$pos' found. Contig '$contig' is not in the reference genome '$name'.")
      else
        fatal(s"Invalid locus '$contig:$pos' found. Position '$pos' is not within the range [1-${ contigLength(contig) }] for reference genome '$name'.")
    }
  }

  def checkLocusInterval(i: Interval): Unit = {
    val start = i.start.asInstanceOf[Locus]
    val end = i.end.asInstanceOf[Locus]
    val includesStart = i.includesStart
    val includesEnd = i.includesEnd

    if (!isValidLocus(start.contig, if (includesStart) start.position else start.position + 1)) {
      if (!isValidContig(start.contig))
        fatal(s"Invalid interval '$i' found. Contig '${ start.contig }' is not in the reference genome '$name'.")
      else
        fatal(s"Invalid interval '$i' found. Start '$start' is not within the range [1-${ contigLength(start.contig) }] for reference genome '$name'.")
    }

    if (!isValidLocus(end.contig, if (includesEnd) end.position else end.position - 1)) {
      if (!isValidContig(end.contig))
        fatal(s"Invalid interval '$i' found. Contig '${ end.contig }' is not in the reference genome '$name'.")
      else
        fatal(s"Invalid interval '$i' found. End '$end' is not within the range [1-${ contigLength(end.contig) }] for reference genome '$name'.")
    }

    if (!Interval.isValid(locusType.ordering, start, end, includesStart, includesEnd))
      if (start == end && ((includesStart && !includesEnd) || (!includesStart && includesStart)))
        fatal(s"Invalid interval '$i' found. Start and end cannot be equal if one endpoint is inclusive and the other endpoint is exclusive.")
      else
        fatal(s"Invalid interval '$i' found. ")
  }

  def normalizeLocusInterval(i: Interval): Interval = {
    var start = i.start.asInstanceOf[Locus]
    var end = i.end.asInstanceOf[Locus]
    var includesStart = i.includesStart
    var includesEnd = i.includesEnd

    if (!includesStart && start.position == 0) {
      start = start.copyChecked(this, position = 1)
      includesStart = true
    }
    if (!includesEnd && end.position == contigLength(end.contig) + 1) {
      end = end.copyChecked(this, position = contigLength(end.contig))
      includesEnd = true
    }

    Interval(start, end, includesStart, includesEnd)
  }

  def inX(contigIdx: Int): Boolean = xContigIndices.contains(contigIdx)

  def inX(contig: String): Boolean = xContigs.contains(contig)

  def inY(contigIdx: Int): Boolean = yContigIndices.contains(contigIdx)

  def inY(contig: String): Boolean = yContigs.contains(contig)

  def isMitochondrial(contigIdx: Int): Boolean = mtContigIndices.contains(contigIdx)

  def isMitochondrial(contig: String): Boolean = mtContigs.contains(contig)

  def inXPar(l: Locus): Boolean = inX(l.contig) && par.exists(_.contains(locusType.ordering, l))

  def inYPar(l: Locus): Boolean = inY(l.contig) && par.exists(_.contains(locusType.ordering, l))

  def compare(contig1: String, contig2: String): Int = ReferenceGenome.compare(contigsIndex, contig1, contig2)

  def validateContigRemap(contigMapping: Map[String, String]) {
    val badContigs = mutable.Set[(String, String)]()

    contigMapping.foreach { case (oldName, newName) =>
      if (!contigsSet.contains(newName))
        badContigs += ((oldName, newName))
    }

    if (badContigs.nonEmpty)
      fatal(s"Found ${ badContigs.size } ${ plural(badContigs.size, "contig mapping that does", "contigs mapping that do") }" +
        s" not have remapped contigs in reference genome '$name':\n  " +
        s"@1", badContigs.truncatable("\n  "))
  }

  def hasSequence: Boolean = fastaReader != null

  def addSequence(hc: HailContext, fastaFile: String, indexFile: String) {
    if (hasSequence)
      fatal(s"FASTA sequence has already been loaded for reference genome '$name'.")

    val hConf = hc.hadoopConf
    if (!hConf.exists(fastaFile))
      fatal(s"FASTA file '$fastaFile' does not exist.")
    if (!hConf.exists(indexFile))
      fatal(s"FASTA index file '$indexFile' does not exist.")

    val localIndexFile = FASTAReader.getUriLocalIndexFile(hConf, indexFile)
    val index = new FastaSequenceIndex(new java.io.File(localIndexFile))

    val missingContigs = contigs.filterNot(index.hasIndexEntry)
    if (missingContigs.nonEmpty)
      fatal(s"Contigs missing in FASTA '$fastaFile' that are present in reference genome '$name':\n  " +
        s"@1", missingContigs.truncatable("\n  "))

    val invalidLengths = lengths.flatMap { case (c, l) =>
      val fastaLength = index.getIndexEntry(c).getSize
      if (fastaLength != l)
        Some((c, l, fastaLength))
      else
        None
    }.map { case (c, e, f) => s"$c\texpected:$e\tfound:$f"}

    if (invalidLengths.nonEmpty)
      fatal(s"Contig sizes in FASTA '$fastaFile' do not match expected sizes for reference genome '$name':\n  " +
        s"@1", invalidLengths.truncatable("\n  "))

    val fastaPath = hConf.fileStatus(fastaFile).getPath.toString
    val indexPath = hConf.fileStatus(indexFile).getPath.toString
    fastaReader = FASTAReader(hc, this, fastaPath, indexPath)
  }

  def addSequenceFromReader(hConf: SerializableHadoopConfiguration, fastaFile: String, indexFile: String, blockSize: Int, capacity: Int): ReferenceGenome = {
    fastaReader = new FASTAReader(hConf, this, fastaFile, indexFile, blockSize, capacity)
    this
  }

  def getSequence(contig: String, position: Int, before: Int = 0, after: Int = 0): String = {
    if (!hasSequence)
      fatal(s"FASTA file has not been loaded for reference genome '$name'.")
    fastaReader.lookup(contig, position, before, after)
  }

  def getSequence(l: Locus, before: Int, after: Int): String =
    getSequence(l.contig, l.position, before, after)

  def getSequence(i: Interval): String = {
    if (!hasSequence)
      fatal(s"FASTA file has not been loaded for reference genome '$name'.")
    fastaReader.lookup(i)
  }

  def removeSequence(): Unit = {
    if (!hasSequence)
      fatal(s"Reference genome '$name' does not have sequence loaded.")
    fastaReader = null
  }

  private[this] var liftoverMaps: Map[String, LiftOver] = Map.empty[String, LiftOver]

  private[this] var liftoverFunctions: Map[String, Set[String]] = Map.empty[String, Set[String]]

  def hasLiftover(destRGName: String): Boolean = liftoverMaps.contains(destRGName)

  def addLiftover(hc: HailContext, chainFile: String, destRGName: String): Unit = {
    if (name == destRGName)
      fatal(s"Destination reference genome cannot have the same name as this reference '$name'")
    if (hasLiftover(destRGName))
      fatal(s"Chain file already exists for source reference '$name' and destination reference '$destRGName'.")
    val hConf = hc.hadoopConf
    if (!hConf.exists(chainFile))
      fatal(s"Chain file '$chainFile' does not exist.")

    val chainFilePath = hConf.fileStatus(chainFile).getPath.toString
    val lo = LiftOver(hc, chainFilePath)

    val destRG = ReferenceGenome.getReference(destRGName)
    lo.checkChainFile(this, destRG)

    liftoverMaps += destRGName -> lo
    val irFunctions = new LiftoverFunctions(this, destRG)
    irFunctions.registerAll()
    liftoverFunctions += destRGName -> irFunctions.registered
  }

  def addLiftoverFromHConf(hConf: SerializableHadoopConfiguration, chainFilePath: String, destRGName: String): ReferenceGenome = {
    val lo = new LiftOver(hConf, chainFilePath)
    liftoverMaps += destRGName -> lo
    this
  }

  def getLiftover(destRGName: String): LiftOver = {
    if (!hasLiftover(destRGName))
      fatal(s"Chain file has not been loaded for source reference '$name' and destination reference '$destRGName'.")
    liftoverMaps(destRGName)
  }

  def removeLiftover(destRGName: String): Unit = {
    if (!hasLiftover(destRGName))
      fatal(s"liftover does not exist from reference genome '$name' to '$destRGName'.")
    liftoverMaps -= destRGName
    liftoverFunctions(destRGName).foreach(IRFunctionRegistry.removeIRFunction)
    liftoverFunctions -= destRGName
  }

  def liftoverLocus(destRGName: String, l: Locus, minMatch: Double): (Locus, Boolean) = {
    val lo = getLiftover(destRGName)
    lo.queryLocus(l, minMatch)
  }

  def liftoverLocusInterval(destRGName: String, interval: Interval, minMatch: Double): (Interval, Boolean) = {
    val lo = getLiftover(destRGName)
    lo.queryInterval(interval, minMatch)
  }

  @transient lazy val broadcast: Broadcast[ReferenceGenome] = HailContext.sc.broadcast(this)

  override def hashCode: Int = {
    import org.apache.commons.lang.builder.HashCodeBuilder

    val b = new HashCodeBuilder()
      .append(name)
      .append(contigs)
      .append(lengths)
      .append(xContigs)
      .append(yContigs)
      .append(mtContigs)
      .append(par)
    b.toHashCode
  }

  override def equals(other: Any): Boolean = {
    other match {
      case rg: ReferenceGenome =>
        name == rg.name &&
          contigs.sameElements(rg.contigs) &&
          lengths == rg.lengths &&
          xContigs == rg.xContigs &&
          yContigs == rg.yContigs &&
          mtContigs == rg.mtContigs &&
          par.sameElements(rg.par)
      case _ => false
    }
  }

  def unify(concrete: RGBase): Boolean = this == concrete

  def isBound: Boolean = true

  def clear() {}

  def subst(): ReferenceGenome = this

  override def toString: String = name

  def write(hadoopConf: org.apache.hadoop.conf.Configuration, file: String): Unit =
    hadoopConf.writeTextFile(file) { out =>
      val jrg = JSONExtractReferenceGenome(name,
        contigs.map(contig => JSONExtractContig(contig, contigLength(contig))),
        xContigs, yContigs, mtContigs,
        par.map(i => JSONExtractIntervalLocus(i.start.asInstanceOf[Locus], i.end.asInstanceOf[Locus])))
      implicit val formats: Formats = defaultJSONFormats
      Serialization.write(jrg, out)
    }

  def toJSONString: String = {
    val jrg = JSONExtractReferenceGenome(name,
      contigs.map(contig => JSONExtractContig(contig, contigLength(contig))),
      xContigs, yContigs, mtContigs,
      par.map(i => JSONExtractIntervalLocus(i.start.asInstanceOf[Locus], i.end.asInstanceOf[Locus])))
    implicit val formats: Formats = defaultJSONFormats
    Serialization.write(jrg)
  }

  def codeSetup(fb: EmitFunctionBuilder[_]): Code[ReferenceGenome] = {
    val json = toJSONString
    val chunkSize = (1 << 16) - 1
    val nChunks = (json.length() - 1) / chunkSize + 1
    assert(nChunks > 0)

    val chunks = Array.tabulate(nChunks){ i => json.slice(i * chunkSize, (i + 1) * chunkSize) }
    val stringAssembler =
      chunks.tail.foldLeft[Code[String]](chunks.head) { (c, s) => c.invoke[String, String]("concat", s) }

    var rg = Code.invokeScalaObject[String, ReferenceGenome](ReferenceGenome.getClass, "parse", stringAssembler)
    if (fastaReader != null) {
      fb.addHadoopConfiguration(HailContext.sHadoopConf)
      rg = rg.invoke[SerializableHadoopConfiguration, String, String, Int, Int, ReferenceGenome](
        "addSequenceFromReader",
        fb.getHadoopConfiguration,
        fastaReader.fastaFile,
        fastaReader.indexFile,
        fastaReader.blockSize,
        fastaReader.capacity)
    }

    for ((destRG, lo) <- liftoverMaps) {
      fb.addHadoopConfiguration(HailContext.sHadoopConf)
      rg = rg.invoke[SerializableHadoopConfiguration, String, String, ReferenceGenome](
        "addLiftoverFromHConf",
        fb.getHadoopConfiguration,
        lo.chainFile,
        destRG)
    }
    rg
  }

  private[this] var registeredFunctions: Set[String] = Set.empty[String]
  def wrapFunctionName(fname: String): String = s"$fname($name)"
  def addIRFunctions(): Unit = {
    val irFunctions = new ReferenceGenomeFunctions(this)
    irFunctions.registerAll()
    registeredFunctions ++= irFunctions.registered
  }
  def removeIRFunctions(): Unit = {
    registeredFunctions.foreach(IRFunctionRegistry.removeIRFunction)
    liftoverFunctions.foreach(_._2.foreach(IRFunctionRegistry.removeIRFunction))
    registeredFunctions = Set.empty[String]
  }
}

object ReferenceGenome {
  var references: Map[String, ReferenceGenome] = Map()
  var GRCh37: ReferenceGenome = _
  var GRCh38: ReferenceGenome = _
  var GRCm38: ReferenceGenome = _
  var hailReferences: Set[String] = _

  def addDefaultReferences() : Unit = {
    assert(references.isEmpty)
    GRCh37 = fromResource("reference/grch37.json")
    GRCh38 = fromResource("reference/grch38.json")
    GRCm38 = fromResource("reference/grcm38.json")
    hailReferences = references.keySet
  }

  def reset(): Unit = {
    references.foreach { case (name, rg) => rg.removeIRFunctions() }
    references = Map()
    GRCh37 = null
    GRCh38 = null
    GRCm38 = null
    hailReferences = null
  }

  def addReference(rg: ReferenceGenome) {
    references.get(rg.name) match {
      case Some(rg2) =>
        if (rg != rg2) {
          fatal(s"Cannot add reference genome '${ rg.name }', a different reference with that name already exists. Choose a reference name NOT in the following list:\n  " +
            s"@1", references.keys.truncatable("\n  "))
        }
      case None =>
        rg.addIRFunctions()
        references += (rg.name -> rg)
    }
  }

  def getReference(name: String): ReferenceGenome = {
    references.get(name) match {
      case Some(rg) => rg
      case None => fatal(s"Reference genome '$name' does not exist. Choose a reference name from the following list:\n  " +
        s"@1", references.keys.truncatable("\n  "))
    }
  }

  def hasReference(name: String): Boolean = references.contains(name)

  def read(is: InputStream): ReferenceGenome = {
    implicit val formats = defaultJSONFormats
    JsonMethods.parse(is).extract[JSONExtractReferenceGenome].toReferenceGenome
  }

  def parse(str: String): ReferenceGenome = {
    implicit val formats = defaultJSONFormats
    JsonMethods.parse(str).extract[JSONExtractReferenceGenome].toReferenceGenome
  }

  def fromResource(file: String): ReferenceGenome = {
    val rg = loadFromResource[ReferenceGenome](file)(read)
    addReference(rg)
    rg
  }

  def fromFile(hc: HailContext, file: String): ReferenceGenome = {
    val rg = hc.hadoopConf.readFile(file)(read)
    addReference(rg)
    rg
  }

  def fromJSON(config: String): ReferenceGenome = {
    val rg = parse(config)
    addReference(rg)
    rg
  }

  def fromFASTAFile(hc: HailContext, name: String, fastaFile: String, indexFile: String,
    xContigs: java.util.List[String], yContigs: java.util.List[String],
    mtContigs: java.util.List[String], parInput: java.util.List[String]): ReferenceGenome =
    fromFASTAFile(hc, name, fastaFile, indexFile, xContigs.asScala.toArray, yContigs.asScala.toArray,
      mtContigs.asScala.toArray, parInput.asScala.toArray)

  def fromFASTAFile(hc: HailContext, name: String, fastaFile: String, indexFile: String,
    xContigs: Array[String] = Array.empty[String], yContigs: Array[String] = Array.empty[String],
    mtContigs: Array[String] = Array.empty[String], parInput: Array[String] = Array.empty[String]): ReferenceGenome = {
    val hConf = hc.hadoopConf
    if (!hConf.exists(fastaFile))
      fatal(s"FASTA file '$fastaFile' does not exist.")
    if (!hConf.exists(indexFile))
      fatal(s"FASTA index file '$indexFile' does not exist.")

    val localIndexFile = FASTAReader.getUriLocalIndexFile(hConf, indexFile)
    val index = new FastaSequenceIndex(new java.io.File(localIndexFile))

    val contigs = new ArrayBuilder[String]
    val lengths = new ArrayBuilder[(String, Int)]

    index.iterator().asScala.foreach { entry =>
      val contig = entry.getContig
      val length = entry.getSize
      contigs += contig
      lengths += (contig -> length.toInt)
    }

    val rg = ReferenceGenome(name, contigs.result(), lengths.result().toMap, xContigs, yContigs, mtContigs, parInput)
    rg.fastaReader = FASTAReader(hc, rg, fastaFile, indexFile)
    rg
  }

  def addSequence(name: String, fastaFile: String, indexFile: String): Unit = {
    references(name).addSequence(HailContext.get, fastaFile, indexFile)
  }

  def removeSequence(name: String): Unit = {
    references(name).removeSequence()
  }

  def referenceAddLiftover(name: String, chainFile: String, destRGName: String): Unit = {
    references(name).addLiftover(HailContext.get, chainFile, destRGName)
  }

  def referenceRemoveLiftover(name: String, destRGName: String): Unit = {
    references(name).removeLiftover(destRGName)
  }

  def importReferences(hConf: Configuration, path: String) {
    if (hConf.exists(path)) {
      val refs = hConf.listStatus(path)
      refs.foreach { fs =>
        val rgPath = fs.getPath.toString
        val rg = hConf.readFile(rgPath)(read)
        val name = rg.name
        if (!ReferenceGenome.hasReference(name))
          addReference(rg)
        else {
          if (ReferenceGenome.getReference(name) != rg)
            fatal(s"'$name' already exists and is not identical to the imported reference from '$rgPath'.")
        }
      }
    }
  }

  private def writeReference(hadoopConf: org.apache.hadoop.conf.Configuration, path: String, rg: RGBase) {
    val rgPath = path + "/" + rg.name + ".json.gz"
    if (!hailReferences.contains(rg.name) && !hadoopConf.exists(rgPath))
      rg.asInstanceOf[ReferenceGenome].write(hadoopConf, rgPath)
  }

  def getReferences(t: Type): Set[ReferenceGenome] = {
    var rgs = Set[ReferenceGenome]()
    MapTypes.foreach {
      case tl: TLocus =>
        rgs += tl.rg.asInstanceOf[ReferenceGenome]
      case _ =>
    }(t)
    rgs
  }

  def exportReferences(hadoopConf: org.apache.hadoop.conf.Configuration, path: String, t: Type) {
    val rgs = getReferences(t)
    rgs.foreach(writeReference(hadoopConf, path, _))
  }

  def compare(contigsIndex: Map[String, Int], c1: String, c2: String): Int = {
    (contigsIndex.get(c1), contigsIndex.get(c2)) match {
      case (Some(i), Some(j)) => i.compare(j)
      case (Some(_), None) => -1
      case (None, Some(_)) => 1
      case (None, None) => c1.compare(c2)
    }
  }

  def compare(contigsIndex: Map[String, Int], l1: Locus, l2: Locus): Int = {
    val c = compare(contigsIndex, l1.contig, l2.contig)
    if (c != 0)
      return c

    Integer.compare(l1.position, l2.position)
  }

  def gen: Gen[ReferenceGenome] = for {
    name <- Gen.identifier.filter(!ReferenceGenome.hasReference(_))
    nContigs <- Gen.choose(3, 10)
    contigs <- Gen.distinctBuildableOfN[Array](nContigs, Gen.identifier)
    lengths <- Gen.buildableOfN[Array](nContigs, Gen.choose(1000000, 500000000))
    contigsIndex = contigs.zip(lengths).toMap
    xContig <- Gen.oneOfSeq(contigs)
    parXA <- Gen.choose(0, contigsIndex(xContig))
    parXB <- Gen.choose(0, contigsIndex(xContig))
    yContig <- Gen.oneOfSeq(contigs) if yContig != xContig
    parYA <- Gen.choose(0, contigsIndex(yContig))
    parYB <- Gen.choose(0, contigsIndex(yContig))
    mtContig <- Gen.oneOfSeq(contigs) if mtContig != xContig && mtContig != yContig
  } yield ReferenceGenome(name, contigs, contigs.zip(lengths).toMap, Set(xContig), Set(yContig), Set(mtContig),
    Array(
      (Locus(xContig, math.min(parXA, parXB)),
        Locus(xContig, math.max(parXA, parXB))),
      (Locus(yContig, math.min(parYA, parYB)),
        Locus(yContig, math.max(parYA, parYB)))))

  def apply(name: String, contigs: Array[String], lengths: Map[String, Int], xContigs: Array[String], yContigs: Array[String],
    mtContigs: Array[String], parInput: Array[String]): ReferenceGenome = {
    val parRegex = """(\w+):(\d+)-(\d+)""".r

    val par = parInput.map {
      case parRegex(contig, start, end) => (Locus(contig.toString, start.toInt), Locus(contig.toString, end.toInt))
      case _ => fatal("expected PAR input of form contig:start-end")
    }

    val rg = ReferenceGenome(name, contigs, lengths, xContigs.toSet, yContigs.toSet, mtContigs.toSet, par)
    addReference(rg)
    rg
  }

  def apply(name: java.lang.String, contigs: java.util.List[String], lengths: java.util.Map[String, Int],
    xContigs: java.util.List[String], yContigs: java.util.List[String],
    mtContigs: java.util.List[String], parInput: java.util.List[String]): ReferenceGenome =
    ReferenceGenome(name, contigs.asScala.toArray, lengths.asScala.toMap, xContigs.asScala.toArray, yContigs.asScala.toArray,
      mtContigs.asScala.toArray, parInput.asScala.toArray)
}

case class RGVariable(var rg: RGBase = null) extends RGBase {
  val locusType: TLocus = TLocus(this)

  override def toString = "?RG"

  def unify(concrete: RGBase): Boolean = {
    if (rg == null) {
      rg = concrete
      true
    } else
      rg == concrete
  }

  def isBound: Boolean = rg != null

  def clear() {
    rg = null
  }

  def subst(): RGBase = {
    assert(rg != null)
    rg
  }

  def name: String = ???

  def locusOrdering: Ordering[Locus] =
    new Ordering[Locus] {
      def compare(x: Locus, y: Locus): Int = throw new UnsupportedOperationException("RGVariable.locusOrdering unimplemented")
    }

  def contigParser: Parser[String] = ???

  def isValidContig(contig: String): Boolean = ???

  def checkLocus(l: Locus): Unit = ???

  def checkLocus(contig: String, pos: Int): Unit = ???

  def checkLocusInterval(i: Interval): Unit = ???

  def contigLength(contig: String): Int = ???

  def contigLength(contigIdx: Int): Int = ???

  def inX(contigIdx: Int): Boolean = ???

  def inX(contig: String): Boolean = ???

  def inY(contigIdx: Int): Boolean = ???

  def inY(contig: String): Boolean = ???

  def isMitochondrial(contigIdx: Int): Boolean = ???

  def isMitochondrial(contig: String): Boolean = ???

  def inXPar(locus: Locus): Boolean = ???

  def inYPar(locus: Locus): Boolean = ???

  def compare(c1: String, c2: String): Int = ???
}

