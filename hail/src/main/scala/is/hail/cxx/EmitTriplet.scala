package is.hail.cxx

import is.hail.expr.types.physical._

object EmitTriplet {
  def apply(pType: PType, setup: Code, m: Code, v: Code, region: EmitRegion): EmitTriplet =
    new EmitTriplet(pType, setup, s"($m)", s"($v)", region)
}

class EmitTriplet private(val pType: PType, val setup: Code, val m: Code, val v: Code, val region: EmitRegion) {
  def needsRegion: Boolean = !pType.isPrimitive || region == null
  def memoize(fb: FunctionBuilder): EmitTriplet = {
    val mv = fb.variable("memm", "bool", m)
    val vv = fb.variable("memv", typeToCXXType(pType))

    EmitTriplet(pType,
      s"""
         |$setup
         |${ mv.define }
         |${ vv.define }
         |if (!$mv)
         |  $vv = $v;
         |""".stripMargin,
      mv.toString, vv.toString, region)
  }
}

/*
 * A new EmitRegion should get created at the start of every stream (e.g.
 * ArrayRange, for the body of ArrayFlatMap). The rest of the stream is emitted,
 * and if the generated code used to process every element of the stream uses
 * the provided region in the EmitRegion, then the code generation for the node
 * that creates the EmitRegion is responsible for initializing it and acquiring
 * a new region for every element, as well as for adding references from the
 * base region (outside the stream) to the necessary regions within the stream.
 *
 * There is an option in `emitArray` to use the same region throughout the
 * deforested array code if e.g. the array needs to be instantiated, as happens
 * when the array is not ultimately passed to an ArrayFold.
 *
 * The EmitTriplet also carries the region that was used to create the value
 * that it holds; this is used for cases like InsertFields inside the body of an
 * ArrayFold in order to unify the field regions, which could be different.
 */

object EmitRegion {
  def from(parentRegion: EmitRegion, sameRegion: Boolean): EmitRegion =
    if (sameRegion) parentRegion else parentRegion.newRegion()

  def apply(fb: FunctionBuilder, region: Code): EmitRegion = {
    new EmitRegion(fb, region, null)
  }
}

class EmitRegion private (val fb: FunctionBuilder, val baseRegion: Code, _region: Variable) {
  assert(_region == null || _region.typ == "RegionPtr")

  val region: Code = if (_region == null) baseRegion else _region.toString
  override def toString: String = region.toString

  private[this] var isUsed: Boolean = false
  def use(): Unit = { isUsed = true }
  def used: Boolean = isUsed

  def defineIfUsed(sameRegion: Boolean): Code = {
    if (isUsed && !sameRegion && _region != null) _region.define else ""
  }

  def addReference(other: EmitRegion): Code =
    if (other.used && this != other) s"$region->add_reference_to($other);" else ""

  def structBuilder(fb: FunctionBuilder, pType: PBaseStruct): StagedBaseStructTripletBuilder = {
    use(); new StagedBaseStructTripletBuilder(this, fb, pType)
  }

  def arrayBuilder(fb: FunctionBuilder, pType: PContainer): StagedContainerBuilder = {
    use(); new StagedContainerBuilder(fb, region, pType)
  }

  def newRegion(): EmitRegion = new EmitRegion(fb, baseRegion, fb.variable("region", "RegionPtr", s"$baseRegion->get_region()"))
}

object SparkFunctionContext {
  def apply(fb: FunctionBuilder, spark_context: Variable): SparkFunctionContext =
    SparkFunctionContext(s"$spark_context.spark_env_", s"$spark_context.hadoop_conf_",
      EmitRegion(fb, s"$spark_context.region_"))

  def apply(fb: FunctionBuilder): SparkFunctionContext = apply(fb, fb.getArg(0))
}

case class SparkFunctionContext(sparkEnv: Code, hadoopConfig: Code, region: EmitRegion)

abstract class ArrayEmitter(val setup: Code, val m: Code, val setupLen: Code, val length: Option[Code], val arrayRegion: EmitRegion) {
  def emit(f: (Code, Code) => Code): Code
}

object NDArrayLoopEmitter {
  def linearizeIndices(fb: FunctionBuilder, idxs: Seq[Variable], strides: Code, shape: Code): Code = {
    val result = fb.variable("result", "int", "0")
    val nDims = idxs.length
    val buildIndex = idxs.zipWithIndex.map { case (idx, dim) =>
        s"""
           | if ($idx < 0 || $idx >= $shape[$dim]) {
           |   throw new FatalError("Invalid index");
           | }
           |
           | $result += $idx * $strides[$dim];
         """.stripMargin
    }.mkString("\n")

    s"""
       |({
       | if ($strides.size() != $nDims) {
       |   throw new FatalError("Number of indices must match number of dimensions.");
       | }
       |
       | ${ result.define }
       | $buildIndex
       | $result;
       |})
     """.stripMargin
  }
}

abstract class NDArrayLoopEmitter(
  fb: FunctionBuilder,
  resultRegion: EmitRegion,
  resultElemType: PType,
  resultShape: Variable,
  outputIndices: Seq[Int]) {

  fb.translationUnitBuilder().include("hail/ArrayBuilder.h")
  private[this] val container = PArray(resultElemType)
  private[this] val builder = fb.variable("builder", StagedContainerBuilder.builderType(container))

  // Always stores the result as row-major
  private[this] val strides = fb.variable("strides", "std::vector<long>", s"make_strides(true, $resultShape)")

  def outputElement(idxVars: Seq[Variable]): Code

  def emit(): Code = {
    val data = fb.variable("data", "const char *")
    s"""
      |({
      | ${ data.define }
      | ${ resultShape.define }
      | ${ strides.define }
      |
      | ${ builder.defineWith(s"{ (int) n_elements($resultShape), $resultRegion }") }
      | $builder.clear_missing_bits();
      |
      | ${ emitLoops() }
      |
      | $data = ${container.cxxImpl}::elements_address($builder.offset());
      | make_ndarray(0, 0, ${resultElemType.byteSize}, $resultShape, $strides, $data);
      |})
    """.stripMargin
  }

  private def emitLoops(): Code = {
    val idxVars = outputIndices.map{ i => fb.variable(s"dim${i}_", "int") }
    val outIndex = NDArrayLoopEmitter.linearizeIndices(fb, idxVars, strides.toString, resultShape.toString)
    val body = s"$builder.set_element($outIndex, ${ outputElement(idxVars) });"

    idxVars.zipWithIndex.foldRight(body){ case ((dimVar, dimIdx), innerLoops) =>
      s"""
         |${ dimVar.define }
         |for ($dimVar = 0; $dimVar < $resultShape[$dimIdx]; ++$dimVar) {
         |  $innerLoops
         |}
         |""".stripMargin
    }
  }
}