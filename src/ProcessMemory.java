package local.taskscope.manager;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class ProcessMemory {
    final long start, pss, uss;

    private ProcessMemory(long start, long pss, long uss) {
        this.start = start;
        this.pss = pss;
        this.uss = uss;
    }

    static long value(Telemetry.Task task, MonitorSettings.MemoryMethod method, Map<Integer, ProcessMemory> samples) {
        if (method == MonitorSettings.MemoryMethod.RSS) return task.rss;
        if (task.kernel()) return -1;
        ProcessMemory sample = samples.get(task.pid);
        if (sample == null || sample.start != task.start) return -1;
        return method == MonitorSettings.MemoryMethod.PSS ? sample.pss : sample.uss;
    }

    static Map<Integer, ProcessMemory> parse(String output) {
        Map<Integer, ProcessMemory> samples = new HashMap<>();
        for (String line : output.split("\n")) {
            if (line.equals("END")) return samples;
            String[] fields = line.split("\t");
            if (fields.length != 4) continue;
            try {
                int pid = Integer.parseInt(fields[0]);
                long start = Long.parseLong(fields[1]);
                long pss = Long.parseLong(fields[2]);
                long uss = Long.parseLong(fields[3]);
                if (pid > 0 && start >= 0 && (pss >= 0 || uss >= 0)) {
                    samples.put(pid, new ProcessMemory(start, pss, uss));
                }
            } catch (NumberFormatException ignored) { }
        }
        return Collections.emptyMap();
    }
}
