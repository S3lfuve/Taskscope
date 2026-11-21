package local.taskscope.manager;

import java.util.HashMap;
import java.util.Map;

final class SampleHistory {
    static final int CAPACITY = 60;

    static final class Series {
        private final float[] values = new float[CAPACITY + 1];
        private final long[] times = new long[values.length];
        private int size, next;

        void add(float value, long time) {
            values[next] = value;
            times[next] = time;
            next = (next + 1) % values.length;
            size = Math.min(size + 1, values.length);
        }

        float previous(int ago) {
            return ago >= size ? Float.NaN : values[index(ago)];
        }

        long time(int ago) { return ago >= size ? 0 : times[index(ago)]; }

        private int index(int ago) { return (next - 1 - ago + values.length) % values.length; }

    }

    private final Map<String, Series> series = new HashMap<>();

    Series get(String key) { return series.get(key); }

    void append(Telemetry sample, long time, boolean gap) {
        Map<String, Float> values = new HashMap<>();
        values.put("cpu", load(sample.cpuLoad()));
        values.put("ram", load(sample.memoryLoad()));
        values.put("gpu", load(sample.gpuLoad));
        values.put("tempCpu", (float)sample.cpuTemperature());
        values.put("tempGpu", (float)sample.gpuTemperature());
        values.put("tempBattery", (float)sample.batteryTemperature());
        for (int i = 0; i < Telemetry.EXTRA_SENSOR_KEYS.length; i++) {
            values.put(Telemetry.EXTRA_SENSOR_KEYS[i], (float)sample.extraTemperature(i));
        }
        for (Map.Entry<String, Telemetry.Cpu> core : sample.cpus.entrySet()) {
            if (!core.getKey().equals("cpu")) values.put(core.getKey(), load(core.getValue().percent));
        }
        for (String key : values.keySet()) series.computeIfAbsent(key, unused -> new Series());
        for (Map.Entry<String, Series> line : series.entrySet()) {
            if (gap) line.getValue().add(Float.NaN, 0);
            line.getValue().add(values.getOrDefault(line.getKey(), Float.NaN), time);
        }
    }

    private static float load(double value) { return value >= 0 ? (float)value : Float.NaN; }
}
