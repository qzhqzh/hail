package is.hail.annotations.aggregators

import is.hail.annotations._

trait RegionValueAggregator extends Serializable {

  def combOp(agg2: RegionValueAggregator): Unit

  def result(rvb: RegionValueBuilder): Unit

  def newInstance(): RegionValueAggregator

  def copy(): RegionValueAggregator

  def clear(): Unit
}
