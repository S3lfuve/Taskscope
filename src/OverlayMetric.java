package local.taskscope.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

enum OverlayMetric {
    FPS("FPS", Source.FRAME),
    CPU("ЦП", Source.CPU),
    GPU("GPU", Source.GPU),
    CPU_TEMP("Температура ЦП", Source.SENSOR),
    GPU_TEMP("Температура GPU", Source.SENSOR),
    RAM_USED("Занято", Source.PUBLIC),
    FRAME_TIME("Время кадра", Source.FRAME),

    CPU_CORES("Загрузка ядер", Source.CPU, true),
    CPU_FREQ("Частоты ЦП", Source.FREQUENCY, true),
    CPU_FAST("Мощные ядра", Source.CLUSTER),
    CPU_EFFICIENT("Экономные ядра", Source.CLUSTER),
    GPU_FREQ("Частота GPU", Source.GPU),
    RAM_TOTAL("Всего ОЗУ", Source.PUBLIC),
    RAM_PERCENT("Загрузка ОЗУ", Source.PUBLIC),
    BATTERY_TEMP("Темп. батареи", Source.PUBLIC),
    BATTERY_LEVEL("Заряд", Source.PUBLIC),
    SKIN_TEMP("Корпус", Source.SENSOR);

    enum Source { PUBLIC, CPU, GPU, CLUSTER, FREQUENCY, SENSOR, FRAME }

    static final String NA = "н/д";
    static final int BASE_COLOR = 0xfff0f0f0;
    final String label;
    final Source source;
    final boolean multiline;

    OverlayMetric(String label, Source source) { this(label, source, false); }

    OverlayMetric(String label, Source source, boolean multiline) {
        this.label = label;
        this.source = source;
        this.multiline = multiline;
    }

    static final class Values {
        Telemetry kernel, sensors;
        FrameSampler.Reading frame;
        long totalRam = -1, availableRam = -1;
        double batteryTemperature = Double.NaN, batteryLevel = Double.NaN;

        private boolean hasKernelMemory() {
            return kernel != null && kernel.memory > 0 && kernel.available >= 0 && kernel.available <= kernel.memory;
        }

        long memoryTotal() { return hasKernelMemory() ? kernel.memory * 1024 : totalRam; }

        long memoryUsed() {
            if (hasKernelMemory()) return (kernel.memory - kernel.available) * 1024;
            return totalRam > 0 && availableRam >= 0 && availableRam <= totalRam ? totalRam - availableRam : -1;
        }

        double memoryPercent() {
            long used = memoryUsed();
            return used < 0 ? Double.NaN : 100.0 * used / memoryTotal();
        }
    }

    String value(Values values) {
        Telemetry cpu = values.kernel, heat = values.sensors;
        switch (this) {
            case FPS: return values.frame == null ? NA : show(values.frame.fps, "%.1f", "");
            case FRAME_TIME: return values.frame == null ? NA : show(values.frame.frameMillis, "%.1f", " мс");

            case CPU: case GPU: case CPU_FAST: case CPU_EFFICIENT: return show(load(cpu), "%.0f", "%");
            case CPU_CORES: return cpu == null ? NA : cores(cpu);
            case CPU_FREQ: return cpu == null ? NA : frequencies(cpu);
            case GPU_FREQ: return cpu == null ? NA : frequency(cpu.gpuHz);
            case CPU_TEMP: return heat == null ? NA : temperature(heat.cpuTemperature());
            case GPU_TEMP: return heat == null ? NA : temperature(heat.gpuTemperature());
            case RAM_USED:
                long used = values.memoryUsed();
                return used < 0 ? NA : storage(used) + " / " + capacity(values.memoryTotal());
            case RAM_TOTAL: return storage(values.memoryTotal());
            case RAM_PERCENT: return show(values.memoryPercent(), "%.0f", "%");
            case BATTERY_TEMP: return temperature(values.batteryTemperature);
            case BATTERY_LEVEL: return show(values.batteryLevel, "%.0f", "%");
            case SKIN_TEMP: return heat == null ? NA : temperature(heat.extraTemperature(0));
            default: return NA;
        }
    }

    private double load(Telemetry sample) {
        if (sample == null) return Double.NaN;
        if (this == CPU) return sample.cpuLoad();
        if (this == GPU) return sample.gpuLoad;
        long total = 0;
        double sum = 0;
        boolean fast = false, efficient = false;
        for (Map.Entry<String, Telemetry.Cpu> core : sample.cpus.entrySet()) {
            if (!core.getKey().matches("cpu[0-9]+")) continue;
            boolean high = sample.highPerformanceCore(core.getKey());
            fast |= high;
            efficient |= !high;
            if ((this == CPU_FAST) == high && core.getValue().percent >= 0) {
                sum += core.getValue().percent;
                total++;
            }
        }
        return fast && efficient && total > 0 ? sum / total : Double.NaN;
    }

    private static String cores(Telemetry sample) {
        List<String> names = new ArrayList<>();
        List<String> loads = new ArrayList<>();
        for (Map.Entry<String, Telemetry.Cpu> core : sample.cpus.entrySet()) {
            if (!core.getKey().matches("cpu[0-9]+")) continue;
            names.add(core.getKey().substring(3));
            loads.add(show(core.getValue().percent, "%.0f", "%"));
        }
        if (names.isEmpty()) return NA;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < Math.min(8, names.size()); i++) {
            if (i > 0) line.append(i % 4 == 0 ? "\n" : "  ");
            line.append(names.get(i)).append(" ").append(loads.get(i));
        }
        if (names.size() > 8) line.append("  +").append(names.size() - 8);
        return line.toString();
    }

    private static String frequencies(Telemetry sample) {
        if (sample.frequencies.isEmpty()) return NA;
        StringBuilder result = new StringBuilder();
        for (Map.Entry<Integer, Telemetry.Frequency> entry : sample.frequencies.entrySet()) {
            if (result.length() > 0) result.append("\n");
            Telemetry.Frequency value = entry.getValue();
            String cores = value.cores.trim().replaceAll("\\s+", ",");
            String[] parts = cores.split(",");
            if (parts.length > 2) cores = parts[0] + "–" + parts[parts.length - 1];
            result.append(cores.isEmpty() ? "ЦП " + entry.getKey() : "Ядра " + cores)
                .append("  ").append(value.hardware ? frequency(value.hz) : NA);
        }
        return result.toString();
    }

    int color(Values values) {
        if (this == RAM_USED || this == RAM_PERCENT) return loadColor(values.memoryPercent());
        if (this == CPU || this == GPU || this == CPU_FAST || this == CPU_EFFICIENT) {
            return loadColor(load(values.kernel));
        }
        if (this == BATTERY_TEMP) return heatColor(values.batteryTemperature, 39, 50);
        if (values.sensors != null) {
            if (this == CPU_TEMP) return heatColor(values.sensors.cpuTemperature(), 66, 91);
            if (this == GPU_TEMP) return heatColor(values.sensors.gpuTemperature(), 62, 87);
            if (this == SKIN_TEMP) return heatColor(values.sensors.extraTemperature(0), 37, 48);

        }
        return BASE_COLOR;
    }

    private static int loadColor(double percent) {
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) return BASE_COLOR;
        if (percent <= 50) return 0xff80d2a1;
        if (percent <= 65) return mix(0xff80d2a1, 0xffeed480, (percent - 50) / 15);
        if (percent <= 80) return mix(0xffeed480, 0xffffa65f, (percent - 65) / 15);
        return mix(0xffffa65f, 0xffff7777, (percent - 80) / 20);
    }

    private static int heatColor(double degrees, double warm, double hot) {
        return !validTemperature(degrees) || degrees <= warm ? BASE_COLOR
            : mix(0xffffd495, 0xffff847a, Math.min(1, (degrees - warm) / (hot - warm)));
    }

    private static int mix(int one, int two, double fraction) {
        int r = channel(one >> 16, two >> 16, fraction);
        int g = channel(one >> 8, two >> 8, fraction);
        int b = channel(one, two, fraction);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int channel(int one, int two, double fraction) {
        return (int)Math.round((one & 255) + ((two & 255) - (one & 255)) * fraction);
    }

    private static boolean validTemperature(double degrees) { return degrees >= -40 && degrees <= 150; }

    private static String temperature(double degrees) {
        return validTemperature(degrees) ? String.format(Locale.ROOT, "%.0f °C", degrees) : NA;
    }

    private static String frequency(long hz) {
        return hz > 0 ? show(hz / 1_000_000.0, "%.0f", " МГц") : NA;
    }

    private static String storage(long bytes) {
        return bytes >= 0 ? show(bytes / 1073741824.0, "%.1f", " ГиБ") : NA;
    }

    private static String capacity(long bytes) {
        String number = String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return number + " ГиБ";
    }

    private static String show(double value, String format, String suffix) {
        return Double.isFinite(value) && value >= 0 ? String.format(Locale.ROOT, format, value) + suffix : NA;
    }
}
