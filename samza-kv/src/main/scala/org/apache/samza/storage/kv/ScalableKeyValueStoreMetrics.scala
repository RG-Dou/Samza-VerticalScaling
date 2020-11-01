package org.apache.samza.storage.kv

import org.apache.samza.metrics.{MetricsRegistry, MetricsRegistryMap}

class ScalableKeyValueStoreMetrics (
                                    override val storeName: String = "unknown",
                                    override val registry: MetricsRegistry = new MetricsRegistryMap)
  extends KeyValueStoreMetrics (storeName = storeName, registry = registry) {

  def setDirtyCacheSize(getValue: () => Long) {
    newGauge("used-cache-size", getValue)
  }

  val totalCacheBytes = newGauge[Long]("total-cache-size", 0L)

}
