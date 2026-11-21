package local.taskscope.manager;

import android.content.Context;
import android.content.SharedPreferences;

final class MonitorSettings {
    enum MemoryMethod { RSS, PSS, USS }

    static final int[] BACKGROUND_MINUTES = {1, 3, 5, 10};
    static final String[] SPEED_LABELS = {"Медленнее", "По умолчанию", "Быстро"};
    static final String[] MEMORY_LABELS = {"RSS", "PSS", "USS"};
    private static final MemoryMethod[] MEMORY_METHODS = MemoryMethod.values();
    private static final double[] SPEEDS = {0.65, 1.0, 1.35};
    private final SharedPreferences values;

    MonitorSettings(Context context) {
        values = context.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE);
    }

    int backgroundIndex() { return bounded("background", 1, BACKGROUND_MINUTES.length); }
    int speedIndex() { return bounded("speed", 1, SPEEDS.length); }
    int memoryIndex() { return bounded("memory_method", MemoryMethod.PSS.ordinal(), MEMORY_METHODS.length); }
    MemoryMethod memoryMethod() { return MEMORY_METHODS[memoryIndex()]; }
    long backgroundMillis() { return BACKGROUND_MINUTES[backgroundIndex()] * 60_000L; }
    long intervalMillis() { return Math.round(1000 / SPEEDS[speedIndex()]); }
    boolean roundUp() { return values.getBoolean("round_up", false); }
    void setBackground(int index) { values.edit().putInt("background", index).apply(); }
    void setSpeed(int index) { values.edit().putInt("speed", index).apply(); }
    void setMemoryMethod(int index) { values.edit().putInt("memory_method", index).apply(); }
    void setRoundUp(boolean enabled) { values.edit().putBoolean("round_up", enabled).apply(); }

    private int bounded(String key, int fallback, int count) {
        int index = values.getInt(key, fallback);
        return index >= 0 && index < count ? index : fallback;
    }
}
