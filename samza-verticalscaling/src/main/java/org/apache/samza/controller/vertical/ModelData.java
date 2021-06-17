package org.apache.samza.controller.vertical;

public class ModelData {
    final private long index;
    private long count = 0;
    private double key = 0.0;
    private double value = 0.0;

    public ModelData(long index) {
        this.index = index;
    }

    public void addData(double key, double value){
        this.key = (this.key * count + key) / (count + 1);
        this.value = (this.value * count + value) / (count + 1);
        count += 1;
    }

    public double getKey(){
        return key;
    }

    public double getValue(){
        return value;
    }

    public long getIndex(){
        return index;
    }

    public String toString(){
        return "index: " + index + ", key: " + key + ", value: " + value;
    }
}
