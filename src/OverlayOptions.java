package local.taskscope.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class OverlayOptions {
    static final int[] INTERVALS = {250, 500, 1000, 2000};
    static final String[] INTERVAL_NAMES = {"250 мс", "500 мс", "1000 мс", "2000 мс"};
    private static final EnumSet<OverlayMetric> DEFAULT = EnumSet.of(OverlayMetric.FPS, OverlayMetric.CPU,
        OverlayMetric.GPU, OverlayMetric.CPU_TEMP, OverlayMetric.GPU_TEMP, OverlayMetric.RAM_USED);
    private final SharedPreferences store;

    OverlayOptions(Context context) {
        store = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE);
    }

    int intervalIndex() {
        int index = store.getInt("interval", 2);
        return index >= 0 && index < INTERVALS.length ? index : 2;
    }

    int interval() { return INTERVALS[intervalIndex()]; }

    void setInterval(int index) { store.edit().putInt("interval", index).apply(); }

    List<OverlayMetric> order() {
        Set<OverlayMetric> order = new LinkedHashSet<>();
        for (String name : store.getString("order", "").split(",")) {
            try { order.add(OverlayMetric.valueOf(name)); }
            catch (IllegalArgumentException ignored) { }
        }
        Collections.addAll(order, OverlayMetric.values());
        return new ArrayList<>(order);
    }

    List<OverlayMetric> enabled() {
        EnumSet<OverlayMetric> selected = selection();
        List<OverlayMetric> result = order();
        result.retainAll(selected);
        return result;
    }

    boolean enabled(OverlayMetric metric) { return selection().contains(metric); }

    void setEnabled(OverlayMetric metric, boolean enabled) {
        EnumSet<OverlayMetric> selected = selection();
        if (enabled) selected.add(metric);
        else selected.remove(metric);
        store.edit().putString("selected", encode(selected)).apply();
    }

    void move(OverlayMetric metric, int offset) {
        List<OverlayMetric> order = order();
        int from = order.indexOf(metric), to = from + offset;
        if (to < 0 || to >= order.size()) return;
        Collections.swap(order, from, to);
        store.edit().putString("order", encode(order)).apply();
    }

    private EnumSet<OverlayMetric> selection() {
        if (!store.contains("selected")) return EnumSet.copyOf(DEFAULT);
        EnumSet<OverlayMetric> selected = EnumSet.noneOf(OverlayMetric.class);
        for (String name : store.getString("selected", "").split(",")) {
            try { selected.add(OverlayMetric.valueOf(name)); }
            catch (IllegalArgumentException ignored) { }
        }
        return selected;
    }

    private static String encode(Iterable<OverlayMetric> metrics) {
        StringBuilder saved = new StringBuilder();
        for (OverlayMetric metric : metrics) {
            if (saved.length() > 0) saved.append(',');
            saved.append(metric.name());
        }
        return saved.toString();
    }

    SharedPreferences store() { return store; }
}
