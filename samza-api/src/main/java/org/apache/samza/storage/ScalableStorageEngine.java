package org.apache.samza.storage;

public interface ScalableStorageEngine extends StorageEngine {
    long memoryResize(long memoryMb);
}
