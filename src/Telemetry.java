package local.taskscope.manager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;

final class Telemetry {
    static final String[] EXTRA_SENSOR_KEYS = {"tempSkin", "tempMemory", "tempModem"};
    private static final String[][] EXTRA_SENSOR_NAMES = {
        {"shell_back", "skin-therm", "skin", "skin_therm", "skin-thermal"},
        {"ddr", "ddr-therm", "dram", "ddr_therm", "dram-therm"},
        {"mdmss-0", "modem", "modem-therm", "modem_therm", "modem-thermal"}
    };
    static final class Cpu {
        final long total, idle;
        double percent = -1;

        Cpu(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    static final class Task {
        final int pid, ppid, uid, threads, nice;
        final long rss, start, ticks, flags;
        final String state, name;
        double cpu = -1;

        Task(String[] f) {
            pid = Integer.parseInt(f[1]);
            ppid = Integer.parseInt(f[2]);
            uid = Integer.parseInt(f[3]);
            state = f[4];
            rss = Long.parseLong(f[5]);
            start = Long.parseLong(f[6]);
            ticks = Long.parseLong(f[7]);
            threads = Integer.parseInt(f[8]);
            nice = Integer.parseInt(f[9]);
            flags = Long.parseLong(f[10]);
            name = f[11];
        }

        boolean kernel() { return (flags & 0x00200000L) != 0; }

        int user() { return uid / 100_000; }

        String packageCandidate() {
            int suffix = name.indexOf(':');
            return suffix >= 0 ? name.substring(0, suffix) : name;
        }

        boolean isSame(Task other) { return other != null && pid == other.pid && start == other.start; }
    }

    static final class Sensor {
        final String name;
        final double celsius;

        Sensor(String name, double celsius) {
            this.name = name;
            this.celsius = celsius;
        }
    }

    static final class Frequency {
        final String cores;
        final long hz, limitHz, maximumHz;
        final boolean hardware;

        Frequency(String cores, long hz, long limitHz, long maximumHz, boolean hardware) {
            this.cores = cores;
            this.hz = hz;
            this.limitHz = limitHz;
            this.maximumHz = maximumHz;
            this.hardware = hardware;
        }

        boolean limited() { return limitHz > 0 && maximumHz > limitHz; }
    }

    final Map<Integer, Sensor> sensors = new TreeMap<>();
    final Map<String, Integer> cooling = new TreeMap<>();
    final Map<Integer, Frequency> frequencies = new TreeMap<>();
    final Map<String, Cpu> cpus = new LinkedHashMap<>();
    final Map<Integer, Task> tasks = new LinkedHashMap<>();
    long memory = -1, available = -1, swap = -1, swapFree = -1, gpuHz = -1;
    double gpuLoad = -1, batteryCelsius = Double.NaN;
    String gpu = "";
    long gpuLimitHz = -1, gpuMaximumHz = -1;
    int processCount, kernelCount;

    static Telemetry parse(String data, Telemetry previous, Telemetry previousTasks, boolean full) {
        Telemetry s = new Telemetry();
        boolean complete = false;
        for (String line : data.split("\n")) {
            String[] f = line.split("\t", -1);
            try {
                switch (f[0]) {
                    case "C":
                        s.cpus.put(f[1], new Cpu(Long.parseLong(f[2]), Long.parseLong(f[3])));
                        break;
                    case "M":
                        s.memory = Long.parseLong(f[1]);
                        s.available = Long.parseLong(f[2]);
                        s.swap = Long.parseLong(f[3]);
                        s.swapFree = Long.parseLong(f[4]);
                        break;
                    case "G":
                        s.gpuLoad = Double.parseDouble(f[1]);
                        s.gpuHz = Long.parseLong(f[2]);
                        s.gpu = f[3];
                        if (f.length > 5) {
                            s.gpuLimitHz = Long.parseLong(f[4]);
                            s.gpuMaximumHz = Long.parseLong(f[5]);
                        }
                        break;
                    case "T":
                        s.sensors.put(Integer.parseInt(f[1]), new Sensor(f[2], Long.parseLong(f[3]) / 1000.0));
                        break;
                    case "L":
                        int state = Integer.parseInt(f[2]);
                        if (state > 0) s.cooling.merge(f[1], state, Math::max);
                        break;
                    case "F":
                        s.frequencies.put(Integer.parseInt(f[1].substring("policy".length())),
                            new Frequency(f[2], Long.parseLong(f[3]) * 1000, Long.parseLong(f[4]) * 1000,
                                Long.parseLong(f[5]) * 1000, "1".equals(f[6])));
                        break;
                    case "P":
                        Task task = new Task(f);
                        if (s.tasks.put(task.pid, task) == null) {
                            if (task.kernel()) s.kernelCount++;
                            else s.processCount++;
                        }
                        break;
                    case "END":
                        complete = true;
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { }
        }
        if (!complete || !s.cpus.containsKey("cpu") || (full && s.tasks.isEmpty())) {
            throw new IllegalStateException("Не удалось прочитать счётчики ядра.");
        }
        if (previous != null) {
            for (Map.Entry<String, Cpu> entry : s.cpus.entrySet()) {
                Cpu now = entry.getValue(), before = previous.cpus.get(entry.getKey());
                if (before != null && now.total > before.total) {
                    now.percent = clamp(100 * (1 - (double)(now.idle - before.idle) / (now.total - before.total)));
                }
            }
        }
        if (previousTasks != null) {
            long delta = s.cpus.get("cpu").total - previousTasks.cpus.get("cpu").total;
            if (delta > 0) {
                for (Task now : s.tasks.values()) {
                    Task before = previousTasks.tasks.get(now.pid);
                    if (now.isSame(before) && now.ticks >= before.ticks) {
                        now.cpu = clamp(100.0 * (now.ticks - before.ticks) / delta);
                    }
                }
            }
        }
        return s;
    }

    double extraTemperature(int index) {
        for (String name : EXTRA_SENSOR_NAMES[index]) {
            for (Sensor sensor : sensors.values()) {
                if (sensor.name.equalsIgnoreCase(name)) return sensor.celsius;
            }
        }
        return Double.NaN;
    }

    boolean highPerformanceCore(String core) {
        long lowest = Long.MAX_VALUE, coreMax = 0;
        String number = core.substring(3);
        for (Frequency policy : frequencies.values()) {
            if (policy.maximumHz <= 0) continue;
            lowest = Math.min(lowest, policy.maximumHz);
            for (String id : policy.cores.split("\\s+")) {
                if (id.equals(number)) coreMax = policy.maximumHz;
            }
        }
        return coreMax > lowest;
    }

    double cpuTemperature() { return hottest(true); }

    double gpuTemperature() { return hottest(false); }

    double batteryTemperature() {
        if (Double.isFinite(batteryCelsius)) return batteryCelsius;
        for (String type : new String[]{"battery", "battery-therm", "battery_therm"}) {
            for (Sensor sensor : sensors.values()) {
                if (sensor.name.equalsIgnoreCase(type)) return sensor.celsius;
            }
        }
        return Double.NaN;
    }

    private double hottest(boolean cpu) {
        double max = Double.NEGATIVE_INFINITY;
        double fallback = Double.NEGATIVE_INFINITY;
        for (Sensor sensor : sensors.values()) {
            String name = sensor.name.toLowerCase(Locale.ROOT);
            boolean match = cpu
                ? name.startsWith("cpu") || name.startsWith("apss") || name.startsWith("cluster")
                : name.startsWith("gpu") || name.startsWith("kgsl");
            if (match) {
                max = Math.max(max, sensor.celsius);
            } else {
                String zone = name.replace('-', '_').replaceFirst("_thermal$", "");
                boolean alternate = cpu ? zone.equals("big") || zone.equals("mid") || zone.equals("little")
                    : zone.equals("g3d") || zone.equals("mali") || zone.startsWith("mali_");
                if (alternate) fallback = Math.max(fallback, sensor.celsius);
            }
        }
        if (Double.isFinite(max)) return max;
        return Double.isFinite(fallback) ? fallback : Double.NaN;
    }

    double cpuLoad() { return cpus.get("cpu").percent; }

    double memoryLoad() {
        return memory > 0 && available >= 0 ? clamp(100.0 * (memory - available) / memory) : -1;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(100, value)); }
}
