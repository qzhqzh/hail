package is.hail

import java.io.{File, InputStream}
import java.util.Properties

import is.hail.annotations._
import is.hail.expr.ir.functions.IRFunctionRegistry
import is.hail.expr.ir.{BaseIR, IRParser, MatrixIR, TextTableReader}
import is.hail.expr.types.physical.PStruct
import is.hail.expr.types.virtual._
import is.hail.io.bgen.IndexBgen
import is.hail.io.vcf._
import is.hail.io.{CodecSpec, Decoder, LoadMatrix}
import is.hail.rvd.RVDContext
import is.hail.sparkextras.ContextRDD
import is.hail.table.Table
import is.hail.utils.{log, _}
import is.hail.variant.{MatrixTable, ReferenceGenome}
import org.apache.commons.io.FileUtils
import org.apache.hadoop
import org.apache.log4j.{ConsoleAppender, LogManager, PatternLayout, PropertyConfigurator}
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.InputMetrics
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

case class FilePartition(index: Int, file: String) extends Partition

object HailContext {
  val tera: Long = 1024L * 1024L * 1024L * 1024L

  val logFormat: String = "%d{yyyy-MM-dd HH:mm:ss} %c{1}: %p: %m%n"

  private val contextLock = new Object()

  private var theContext: HailContext = _

  def isInitialized: Boolean = contextLock.synchronized {
    theContext != null
  }

  def get: HailContext = contextLock.synchronized {
    assert(TaskContext.get() == null, "HailContext not available on worker")
    assert(theContext != null, "HailContext not initialized")
    theContext
  }

  def sc: SparkContext = get.sc

  def hadoopConf: hadoop.conf.Configuration = get.hadoopConf

  def sHadoopConf: SerializableHadoopConfiguration = get.sHadoopConf

  def hadoopConfBc: Broadcast[SerializableHadoopConfiguration] = get.hadoopConfBc

  def checkSparkCompatibility(jarVersion: String, sparkVersion: String): Unit = {
    def majorMinor(version: String): String = version.split("\\.", 3).take(2).mkString(".")

    if (majorMinor(jarVersion) != majorMinor(sparkVersion))
      fatal(s"This Hail JAR was compiled for Spark $jarVersion, cannot run with Spark $sparkVersion.\n" +
        s"  The major and minor versions must agree, though the patch version can differ.")
    else if (jarVersion != sparkVersion)
      warn(s"This Hail JAR was compiled for Spark $jarVersion, running with Spark $sparkVersion.\n" +
        s"  Compatibility is not guaranteed.")
  }

  def createSparkConf(appName: String, master: Option[String],
    local: String, blockSize: Long): SparkConf = {
    require(blockSize >= 0)
    checkSparkCompatibility(is.hail.HAIL_SPARK_VERSION, org.apache.spark.SPARK_VERSION)

    val conf = new SparkConf().setAppName(appName)

    master match {
      case Some(m) =>
        conf.setMaster(m)
      case None =>
        if (!conf.contains("spark.master"))
          conf.setMaster(local)
    }

    conf.set("spark.logConf", "true")
    conf.set("spark.ui.showConsoleProgress", "false")

    conf.set(
      "spark.hadoop.io.compression.codecs",
      "org.apache.hadoop.io.compress.DefaultCodec," +
        "is.hail.io.compress.BGzipCodec," +
        "is.hail.io.compress.BGzipCodecTbi," +
        "org.apache.hadoop.io.compress.GzipCodec")

    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrator", "is.hail.kryo.HailKryoRegistrator")

    conf.set("spark.hadoop.mapreduce.input.fileinputformat.split.minsize", (blockSize * 1024L * 1024L).toString)

    // load additional Spark properties from HAIL_SPARK_PROPERTIES
    val hailSparkProperties = System.getenv("HAIL_SPARK_PROPERTIES")
    if (hailSparkProperties != null) {
      hailSparkProperties
        .split(",")
        .foreach { p =>
          p.split("=") match {
            case Array(k, v) =>
              log.info(s"set Spark property from HAIL_SPARK_PROPERTIES: $k=$v")
              conf.set(k, v)
            case _ =>
              warn(s"invalid key-value property pair in HAIL_SPARK_PROPERTIES: $p")
          }
        }
    }
    conf
  }

  def configureAndCreateSparkContext(appName: String, master: Option[String],
    local: String, blockSize: Long): SparkContext = {
    val sc = new SparkContext(createSparkConf(appName, master, local, blockSize))
    sc
  }

  def checkSparkConfiguration(sc: SparkContext) {
    val conf = sc.getConf

    val problems = new ArrayBuffer[String]

    val serializer = conf.getOption("spark.serializer")
    val kryoSerializer = "org.apache.spark.serializer.KryoSerializer"
    if (!serializer.contains(kryoSerializer))
      problems += s"Invalid configuration property spark.serializer: required $kryoSerializer.  " +
        s"Found: ${ serializer.getOrElse("empty parameter") }."

    if (!conf.getOption("spark.kryo.registrator").exists(_.split(",").contains("is.hail.kryo.HailKryoRegistrator")))
      problems += s"Invalid config parameter: spark.kryo.registrator must include is.hail.kryo.HailKryoRegistrator." +
        s"Found ${ conf.getOption("spark.kryo.registrator").getOrElse("empty parameter.") }"

    if (problems.nonEmpty)
      fatal(
        s"""Found problems with SparkContext configuration:
           |  ${ problems.mkString("\n  ") }""".stripMargin)
  }

  def configureLogging(logFile: String, quiet: Boolean, append: Boolean) {
    val logProps = new Properties()

    logProps.put("log4j.rootLogger", "INFO, logfile")
    logProps.put("log4j.appender.logfile", "org.apache.log4j.FileAppender")
    logProps.put("log4j.appender.logfile.append", append.toString)
    logProps.put("log4j.appender.logfile.file", logFile)
    logProps.put("log4j.appender.logfile.threshold", "INFO")
    logProps.put("log4j.appender.logfile.layout", "org.apache.log4j.PatternLayout")
    logProps.put("log4j.appender.logfile.layout.ConversionPattern", HailContext.logFormat)

    LogManager.resetConfiguration()
    PropertyConfigurator.configure(logProps)

    if (!quiet)
      consoleLog.addAppender(new ConsoleAppender(new PatternLayout(HailContext.logFormat), "System.err"))
  }

  /**
    * If a HailContext has already been initialized, this function returns it regardless of the
    * parameters with which it was initialized.
    *
    * Otherwise, it initializes and returns a new HailContext.
    */
  def getOrCreate(sc: SparkContext = null,
    appName: String = "Hail",
    master: Option[String] = None,
    local: String = "local[*]",
    logFile: String = "hail.log",
    quiet: Boolean = false,
    append: Boolean = false,
    minBlockSize: Long = 1L,
    branchingFactor: Int = 50,
    tmpDir: String = "/tmp",
    optimizerIterations: Int = 3): HailContext = contextLock.synchronized {

    if (theContext != null) {
      val hc = theContext
      if (sc == null) {
        warn("Requested that Hail be initialized with a new SparkContext, but Hail " +
          "has already been initialized. Different configuration settings will be ignored.")
      }
      val paramsDiff = (Map(
        "tmpDir" -> Seq(tmpDir, hc.tmpDir),
        "branchingFactor" -> Seq(branchingFactor, hc.branchingFactor),
        "minBlockSize" -> Seq(minBlockSize, hc.sc.getConf.getLong("spark.hadoop.mapreduce.input.fileinputformat.split.minsize", 0L) / 1024L / 1024L)
      ) ++ master.map(m => "master" -> Seq(m, hc.sc.master))).filter(_._2.areDistinct())
      val paramsDiffStr = paramsDiff.map { case (name, Seq(provided, existing)) =>
        s"Param: $name, Provided value: $provided, Existing value: $existing"
      }.mkString("\n")
      if (paramsDiff.nonEmpty) {
        warn("Found differences between requested and initialized parameters. Ignoring requested " +
          s"parameters.\n$paramsDiffStr")
      }

      hc
    } else {
      apply(sc, appName, master, local, logFile, quiet, append, minBlockSize, branchingFactor,
        tmpDir, optimizerIterations)
    }
  }

  def apply(sc: SparkContext = null,
    appName: String = "Hail",
    master: Option[String] = None,
    local: String = "local[*]",
    logFile: String = "hail.log",
    quiet: Boolean = false,
    append: Boolean = false,
    minBlockSize: Long = 1L,
    branchingFactor: Int = 50,
    tmpDir: String = "/tmp",
    optimizerIterations: Int = 3): HailContext = contextLock.synchronized {
    require(theContext == null)

    val javaVersion = raw"(\d+)\.(\d+)\.(\d+).*".r
    val versionString = System.getProperty("java.version")
    versionString match {
      // old-style version: 1.MAJOR.MINOR
      // new-style version: MAJOR.MINOR.SECURITY (started in JRE 9)
      // see: https://docs.oracle.com/javase/9/migrate/toc.htm#JSMIG-GUID-3A71ECEF-5FC5-46FE-9BA9-88CBFCE828CB
      case javaVersion("1", major, minor) =>
        if (major.toInt < 8)
          fatal(s"Hail requires Java 1.8, found $versionString")
      case javaVersion(major, minor, security) =>
        if (major.toInt > 8)
          fatal(s"Hail requires Java 8, found $versionString")
      case _ =>
        fatal(s"Unknown JVM version string: $versionString")
    }

    {
      import breeze.linalg._
      import breeze.linalg.operators.{BinaryRegistry, OpMulMatrix}

      implicitly[BinaryRegistry[DenseMatrix[Double], Vector[Double], OpMulMatrix.type, DenseVector[Double]]].register(
        DenseMatrix.implOpMulMatrix_DMD_DVD_eq_DVD)
    }

    configureLogging(logFile, quiet, append)

    val sparkContext = if (sc == null)
      configureAndCreateSparkContext(appName, master, local, minBlockSize)
    else {
      checkSparkConfiguration(sc)
      sc
    }

    sparkContext.hadoopConfiguration.set("io.compression.codecs",
      "org.apache.hadoop.io.compress.DefaultCodec," +
        "is.hail.io.compress.BGzipCodec," +
        "is.hail.io.compress.BGzipCodecTbi," +
        "org.apache.hadoop.io.compress.GzipCodec"
    )

    if (!quiet)
      ProgressBarBuilder.build(sparkContext)

    val hailTempDir = TempDir.createTempDir(tmpDir, sparkContext.hadoopConfiguration)
    info(s"Hail temporary directory: $hailTempDir")
    val hc = new HailContext(sparkContext, logFile, hailTempDir, branchingFactor, optimizerIterations)
    sparkContext.uiWebUrl.foreach(ui => info(s"SparkUI: $ui"))

    var uploadEmail = System.getenv("HAIL_UPLOAD_EMAIL")
    if (uploadEmail == null)
      uploadEmail = sparkContext.getConf.get("hail.uploadEmail", null)
    if (uploadEmail != null)
      hc.setUploadEmail(uploadEmail)

    var enableUploadStr = System.getenv("HAIL_ENABLE_PIPELINE_UPLOAD")
    if (enableUploadStr == null)
      enableUploadStr = sparkContext.getConf.get("hail.enablePipelineUpload", null)
    if (enableUploadStr != null && enableUploadStr == "true")
      hc.enablePipelineUpload()

    info(s"Running Hail version ${ hc.version }")
    theContext = hc

    // needs to be after `theContext` is set, since this creates broadcasts
    ReferenceGenome.addDefaultReferences()

    hc
  }

  def clear() {
    ReferenceGenome.reset()
    IRFunctionRegistry.clearUserFunctions()
    theContext = null
  }

  def startProgressBar(sc: SparkContext) {
    ProgressBarBuilder.build(sc)
  }

  def readRowsPartition(
    makeDec: (InputStream) => Decoder
  )(ctx: RVDContext,
    in: InputStream,
    metrics: InputMetrics = null
  ): Iterator[RegionValue] =
    new Iterator[RegionValue] {
      private val region = ctx.region
      private val rv = RegionValue(region)

      private val trackedIn = new ByteTrackingInputStream(in)
      private val dec =
        try {
          makeDec(trackedIn)
        } catch {
          case e: Exception =>
            in.close()
            throw e
        }

      private var cont: Byte = dec.readByte()
      if (cont == 0)
        dec.close()

      // can't throw
      def hasNext: Boolean = cont != 0

      def next(): RegionValue = {
        // !hasNext => cont == 0 => dec has been closed
        if (!hasNext)
          throw new NoSuchElementException("next on empty iterator")

        try {
          rv.setOffset(dec.readRegionValue(region))
          cont = dec.readByte()
          if (metrics != null) {
            ExposedMetrics.incrementRecord(metrics)
            ExposedMetrics.incrementBytes(metrics, trackedIn.bytesReadAndClear())
          }

          if (cont == 0)
            dec.close()

          rv
        } catch {
          case e: Exception =>
            dec.close()
            throw e
        }
      }

      override def finalize(): Unit = {
        dec.close()
      }
    }

  private[this] val codecsKey = "io.compression.codecs"
  private[this] val hadoopGzipCodec = "org.apache.hadoop.io.compress.GzipCodec"
  private[this] val hailGzipAsBGZipCodec = "is.hail.io.compress.BGzipCodecGZ"

  def maybeGZipAsBGZip[T](force: Boolean)(body: => T): T = {
    val hadoopConf = HailContext.get.hadoopConf
    if (!force)
      body
    else {
      val defaultCodecs = hadoopConf.get(codecsKey)
      hadoopConf.set(codecsKey, defaultCodecs.replaceAllLiterally(hadoopGzipCodec, hailGzipAsBGZipCodec))
      try {
        body
      } finally {
        hadoopConf.set(codecsKey, defaultCodecs)
      }
    }
  }

  def pyRemoveIrVector(id: Int) {
    get.irVectors.remove(id)
  }
}

class HailContext private(val sc: SparkContext,
  val logFile: String,
  val tmpDir: String,
  val branchingFactor: Int,
  val optimizerIterations: Int) {
  val hadoopConf: hadoop.conf.Configuration = sc.hadoopConfiguration
  val sHadoopConf: SerializableHadoopConfiguration = new SerializableHadoopConfiguration(hadoopConf)
  val hadoopConfBc: Broadcast[SerializableHadoopConfiguration] = sc.broadcast(sHadoopConf)
  val sparkSession = SparkSession.builder().config(sc.getConf).getOrCreate()

  val flags: HailFeatureFlags = new HailFeatureFlags()

  var checkRVDKeys: Boolean = false

  private var nextVectorId: Int = 0
  val irVectors: mutable.Map[Int, Array[_ <: BaseIR]] = mutable.Map.empty[Int, Array[_ <: BaseIR]]

  def addIrVector(irArray: Array[_ <: BaseIR]): Int = {
    val typ = irArray.head.typ
    irArray.foreach { ir =>
      if (ir.typ != typ)
        fatal("all ir vector items must have the same type")
    }
    irVectors(nextVectorId) = irArray
    nextVectorId += 1
    nextVectorId - 1
  }

  def version: String = is.hail.HAIL_PRETTY_VERSION

  def grep(regex: String, files: Seq[String], maxLines: Int = 100) {
    val regexp = regex.r
    sc.textFilesLines(hadoopConf.globAll(files))
      .filter(line => regexp.findFirstIn(line.value).isDefined)
      .take(maxLines)
      .groupBy(_.source.asInstanceOf[Context].file)
      .foreach { case (file, lines) =>
        info(s"$file: ${ lines.length } ${ plural(lines.length, "match", "matches") }:")
        lines.map(_.value).foreach { line =>
          val (screen, logged) = line.truncatable().strings
          log.info("\t" + logged)
          println(s"\t$screen")
        }
      }
  }

  def getTemporaryFile(nChar: Int = 10, prefix: Option[String] = None, suffix: Option[String] = None): String =
    sc.hadoopConfiguration.getTemporaryFile(tmpDir, nChar, prefix, suffix)

  def indexBgen(files: java.util.List[String],
    indexFileMap: java.util.Map[String, String],
    rg: Option[String],
    contigRecoding: java.util.Map[String, String],
    skipInvalidLoci: Boolean) {
    indexBgen(files.asScala, indexFileMap.asScala.toMap, rg, contigRecoding.asScala.toMap, skipInvalidLoci)
  }

  def indexBgen(files: Seq[String],
    indexFileMap: Map[String, String] = null,
    rg: Option[String] = None,
    contigRecoding: Map[String, String] = Map.empty[String, String],
    skipInvalidLoci: Boolean = false) {
    IndexBgen(this, files.toArray, indexFileMap, rg, contigRecoding, skipInvalidLoci)
    info(s"Number of BGEN files indexed: ${ files.length }")
  }

  def importTable(input: String,
    keyNames: Option[IndexedSeq[String]] = None,
    nPartitions: Option[Int] = None,
    types: Map[String, Type] = Map.empty[String, Type],
    comment: Array[String] = Array.empty[String],
    separator: String = "\t",
    missing: String = "NA",
    noHeader: Boolean = false,
    impute: Boolean = false,
    quote: java.lang.Character = null,
    skipBlankLines: Boolean = false,
    forceBGZ: Boolean = false
  ): Table = importTables(List(input), keyNames, nPartitions, types, comment,
    separator, missing, noHeader, impute, quote, skipBlankLines, forceBGZ)

  def importTables(inputs: Seq[String],
    keyNames: Option[IndexedSeq[String]] = None,
    nPartitions: Option[Int] = None,
    types: Map[String, Type] = Map.empty[String, Type],
    comment: Array[String] = Array.empty[String],
    separator: String = "\t",
    missing: String = "NA",
    noHeader: Boolean = false,
    impute: Boolean = false,
    quote: java.lang.Character = null,
    skipBlankLines: Boolean = false,
    forceBGZ: Boolean = false): Table = {
    require(nPartitions.forall(_ > 0), "nPartitions argument must be positive")

    val files = hadoopConf.globAll(inputs)
    if (files.isEmpty)
      fatal(s"Arguments referred to no files: '${ inputs.mkString(",") }'")

    HailContext.maybeGZipAsBGZip(forceBGZ) {
      TextTableReader.read(this)(files, types, comment, separator, missing,
        noHeader, impute, nPartitions.getOrElse(sc.defaultMinPartitions), quote,
        skipBlankLines).keyBy(keyNames)
    }
  }

  def read(file: String, dropCols: Boolean = false, dropRows: Boolean = false): MatrixTable = {
    MatrixTable.read(this, file, dropCols = dropCols, dropRows = dropRows)
  }

  def readVDS(file: String, dropSamples: Boolean = false, dropVariants: Boolean = false): MatrixTable =
    read(file, dropSamples, dropVariants)

  def readPartitions[T: ClassTag](
    path: String,
    partFiles: Array[String],
    read: (Int, InputStream, InputMetrics) => Iterator[T],
    optPartitioner: Option[Partitioner] = None): RDD[T] = {
    val nPartitions = partFiles.length

    val localHadoopConfBc = hadoopConfBc

    new RDD[T](sc, Nil) {
      def getPartitions: Array[Partition] =
        Array.tabulate(nPartitions)(i => FilePartition(i, partFiles(i)))

      override def compute(split: Partition, context: TaskContext): Iterator[T] = {
        val p = split.asInstanceOf[FilePartition]
        val filename = path + "/parts/" + p.file
        val in = localHadoopConfBc.value.value.unsafeReader(filename)
        read(p.index, in, context.taskMetrics().inputMetrics)
      }

      @transient override val partitioner: Option[Partitioner] = optPartitioner
    }
  }

  def readRows(
    path: String,
    t: PStruct,
    codecSpec: CodecSpec,
    partFiles: Array[String],
    requestedType: PStruct
  ): ContextRDD[RVDContext, RegionValue] = {
    val makeDec = codecSpec.buildDecoder(t, requestedType)
    ContextRDD.weaken[RVDContext](readPartitions(path, partFiles, (_, is, m) => Iterator.single(is -> m)))
      .cmapPartitions { (ctx, it) =>
        assert(it.hasNext)
        val (is, m) = it.next
        assert(!it.hasNext)
        HailContext.readRowsPartition(makeDec)(ctx, is, m)
      }
  }

  def parseVCFMetadata(file: String): Map[String, Map[String, Map[String, String]]] = {
    LoadVCF.parseHeaderMetadata(this, Set.empty, TFloat64(), file)
  }

  def pyParseVCFMetadataJSON(file: String): String = {
    val metadata = LoadVCF.parseHeaderMetadata(this, Set.empty, TFloat64(), file)
    implicit val formats = defaultJSONFormats
    JsonMethods.compact(Extraction.decompose(metadata))
  }

  def importMatrix(files: java.util.List[String],
    rowFields: java.util.Map[String, String],
    keyNames: java.util.List[String],
    cellType: String,
    missingVal: String,
    minPartitions: Option[Int],
    noHeader: Boolean,
    forceBGZ: Boolean,
    sep: String = "\t"): MatrixIR =
    importMatrices(files.asScala, rowFields.asScala.toMap.mapValues(IRParser.parseType), keyNames.asScala.toArray,
      IRParser.parseType(cellType), missingVal, minPartitions, noHeader, forceBGZ, sep)

  def importMatrices(files: Seq[String],
    rowFields: Map[String, Type],
    keyNames: Array[String],
    cellType: Type,
    missingVal: String = "NA",
    nPartitions: Option[Int],
    noHeader: Boolean,
    forceBGZ: Boolean,
    sep: String = "\t"): MatrixIR = {
    assert(sep.length == 1)

    val inputs = hadoopConf.globAll(files)

    HailContext.maybeGZipAsBGZip(forceBGZ) {
      LoadMatrix(this, inputs, rowFields, keyNames, cellType = TStruct("x" -> cellType), missingVal, nPartitions, noHeader, sep(0))
    }
  }

  def setUploadURL(url: String) {
    Uploader.url = url
  }

  def setUploadEmail(email: String) {
    Uploader.email = email
    if (email != null)
      warn(s"set upload email: $email")
    else
      warn("reset upload email, subsequent uploads will be anonymous")
  }

  def getUploadEmail: String = {
    Uploader.email
  }

  def enablePipelineUpload() {
    Uploader.uploadEnabled = true
    warn("pipeline upload enabled")
  }

  def disablePipelineUpload() {
    Uploader.uploadEnabled = false
    warn("pipeline upload disabled")
  }

  def uploadLog() {
    warn(s"uploading $logFile")
    Uploader.upload("log", FileUtils.readFileToString(new File(logFile)))
  }
}

class HailFeatureFlags {
  private[this] val flags: mutable.Map[String, String] =
    mutable.Map[String, String](
      "cpp" -> null
    )

  val available: java.util.ArrayList[String] =
    new java.util.ArrayList[String](java.util.Arrays.asList[String](flags.keys.toSeq: _*))

  def set(flag: String, value: String): Unit = {
    flags.update(flag, value)
  }

  def get(flag: String): String = flags(flag)

  def exists(flag: String): Boolean = flags.contains(flag)
}
