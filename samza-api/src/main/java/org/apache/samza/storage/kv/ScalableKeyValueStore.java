package org.apache.samza.storage.kv;

public interface ScalableKeyValueStore<K, V> extends KeyValueStore<K, V> {
    long memoryResize(long memoryMb);
}
