package is.hail.utils.richUtils

import java.io._

import is.hail.HailContext
import is.hail.rvd.RVDContext
import org.apache.spark.TaskContext
import is.hail.utils._
import is.hail.sparkextras._
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

class RichContextRDD[T: ClassTag](crdd: ContextRDD[RVDContext, T]) {
  // Only use on CRDD's whose T is not dependent on the context
  def clearingRun: RDD[T] =
    crdd.cmap { (ctx, v) =>
      ctx.region.clear()
      v
    }.run

  def writePartitions(path: String,
    stageLocally: Boolean,
    write: (RVDContext, Iterator[T], OutputStream) => Long): (Array[String], Array[Long]) = {
    val sc = crdd.sparkContext
    val hadoopConf = sc.hadoopConfiguration

    hadoopConf.mkDir(path + "/parts")

    val sHadoopConfBc = HailContext.hadoopConfBc

    val nPartitions = crdd.getNumPartitions

    val d = digitsNeeded(nPartitions)

    val (partFiles, partitionCounts) = crdd.cmapPartitionsWithIndex { (i, ctx, it) =>
      val hConf = sHadoopConfBc.value.value
      val f = partFile(d, i, TaskContext.get)
      val finalFilename = path + "/parts/" + f
      val filename =
        if (stageLocally) {
          val context = TaskContext.get
          val partPath = hConf.getTemporaryFile("file:///tmp")
          context.addTaskCompletionListener { (context: TaskContext) =>
            hConf.delete(partPath, recursive = false)
          }
          partPath
        } else
          finalFilename
      val os = hConf.unsafeWriter(filename)
      val count = write(ctx, it, os)
      if (stageLocally)
        hConf.copy(filename, finalFilename)
      ctx.region.clear()
      Iterator.single(f -> count)
    }
      .collect()
      .unzip

    val itemCount = partitionCounts.sum
    assert(nPartitions == partitionCounts.length)

    (partFiles, partitionCounts)
  }
}
