package local.taskscope.manager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FrameSampler {
    private static final String SEPARATOR = "TASKSCOPE_FRAME_DATA";
    private static final Pattern FOCUS = Pattern.compile(
        "topResumedActivity=ActivityRecord\\{([^ ]+) u[0-9]+ ([\\w.]+)/([^ ]+)");
    private static final Pattern PROCESS = Pattern.compile("app=ProcessRecord\\{[^ ]+ ([0-9]+):");
    private static final Pattern LAYER = Pattern.compile("^RequestedLayerState\\{(.+#\\d+)(?:[ }].*)$");
    private String lastFocus = "";
    private Integer lastTimeline;
    private String latencyLayer;
    private long nextDiscovery;

    interface Source { String command(String command) throws IOException; }

    static final class Reading {
        final double fps, frameMillis;
        final String packageName;

        Reading(double frameMillis, String packageName) {
            this.fps = 1000 / frameMillis;
            this.frameMillis = frameMillis;
            this.packageName = packageName;
        }
    }

    private static final class Target {
        final String component, pkg;
        final int pid;

        Target(String pkg, String component, int pid) {
            this.pkg = pkg;
            this.component = component;
            this.pid = pid;
        }
    }

    Reading read(Source source, String ownPackage, long uptime) throws IOException {
        String report = source.command("f");
        int split = report.indexOf(SEPARATOR);
        if (split < 0) return null;
        Target target = target(report.substring(0, split));
        if (target == null || ownPackage.equals(target.pkg)) {
            lastFocus = "";
            return null;
        }
        String key = target.component + ":" + target.pid;
        boolean changed = !lastFocus.equals(key);
        if (changed) {
            lastFocus = key;
            lastTimeline = null;
            latencyLayer = null;
            nextDiscovery = 0;
        }

        String timeline = report.substring(split + SEPARATOR.length());
        if (timeline.contains("Number of display frames")) {
            int signature = timeline.hashCode();
            boolean fresh = lastTimeline != null && lastTimeline != signature;
            lastTimeline = signature;
            Reading frames = fromTimeline(timeline, target);
            if (frames != null) return fresh ? frames : null;
        }
        if (latencyLayer != null) {
            Reading frames = fromLatency(source.command("a " + latencyLayer), target.pkg, System.nanoTime());
            if (frames != null) return frames;
            latencyLayer = null;
        }
        if (uptime < nextDiscovery) return null;
        nextDiscovery = uptime + 8000;
        List<String> names = layers(source.command("l"), target.pkg);
        for (int i = 0; i < Math.min(3, names.size()); i++) {
            String name = names.get(i);
            Reading frames = fromLatency(source.command("a " + name), target.pkg, System.nanoTime());
            if (frames != null) {
                latencyLayer = name;
                return frames;
            }
        }
        return null;
    }

    private static Target target(String data) {
        Matcher focus = FOCUS.matcher(data);
        if (!focus.find()) return null;
        String record = focus.group(1), pkg = focus.group(2);
        String[] lines = data.substring(focus.end()).split("\n");
        for (int i = 0; i < Math.min(lines.length, 50); i++) {
            if (lines[i].contains("Hist") && !lines[i].contains("ActivityRecord{" + record + " ")) break;
            Matcher process = PROCESS.matcher(lines[i]);
            if (process.find()) return new Target(pkg, pkg + "/" + focus.group(3), Integer.parseInt(process.group(1)));
        }
        return null;
    }

    private static List<String> layers(String data, String pkg) {
        List<String> names = new ArrayList<>();
        for (String row : data.split("\n")) {
            String line = row.trim();
            Matcher modern = LAYER.matcher(line);
            String name = modern.matches() ? modern.group(1) : line;
            if (name.length() > 350 || !name.contains(pkg + "/")) continue;
            if (name.contains("SurfaceView") || name.startsWith("VRI-") || name.startsWith(pkg + "/")) names.add(name);
        }
        names.sort(Comparator.comparingInt(FrameSampler::layerPriority).reversed());
        return names;
    }

    private static int layerPriority(String name) {
        if (name.contains("SurfaceView") && name.contains("BLAST")) return 3;
        if (name.startsWith("VRI-") || name.contains("[BBQ]")) return 2;
        return 1;
    }

    private static Reading fromTimeline(String data, Target target) {
        Map<String, List<Double>> layers = new HashMap<>();
        double newest = -1, present = -1;
        String layer = null;
        boolean buffer = false, displayed = false;
        int owner = -1;
        for (String raw : (data + "\nDisplay Frame").split("\n")) {
            String line = raw.trim();
            boolean newFrame = line.startsWith("Display Frame");
            if (newFrame || line.startsWith("Layer - ")) {
                boolean correctOwner = owner == target.pid || (owner == -1 && layer != null &&
                    layer.contains(target.component));
                if (layer != null && buffer && displayed && correctOwner && present >= 0) {
                    layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(present);
                }
                layer = newFrame ? null : line.substring("Layer - ".length()).replaceFirst("\\s+\\[\\*\\]\\s*$", "");
                buffer = displayed = false;
                owner = -1;
                if (newFrame) present = -1;
            } else if (layer == null && line.startsWith("Actual")) {
                int column = line.lastIndexOf('|');
                if (column >= 0) {
                    present = number(line.substring(column + 1).trim());
                    if (present > newest) newest = present;
                }
            } else if (line.startsWith("Is Buffer?")) {
                buffer = line.endsWith(": 1") || line.endsWith(": true");
            } else if (line.startsWith("Owner Pid")) {
                owner = (int)number(line.substring(line.indexOf(':') + 1).trim());
            } else if (line.startsWith("Present State")) {
                displayed = line.endsWith(": Presented");
            }
        }
        Reading best = null;
        int bestCount = 0, priority = 0;
        for (Map.Entry<String, List<Double>> entry : layers.entrySet()) {
            List<Double> times = entry.getValue();
            Reading candidate = window(times, newest, target.pkg);
            int rank = layerPriority(entry.getKey());
            if (candidate != null && (times.size() > bestCount || (times.size() == bestCount && rank > priority))) {
                best = candidate;
                bestCount = times.size();
                priority = rank;
            }
        }
        return best;
    }

    private static Reading fromLatency(String data, String pkg, long now) {
        ArrayList<Double> times = new ArrayList<>();
        for (String line : data.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 3) continue;
            double present = number(fields[1]);
            if (present > 0 && present < now + 100_000_000d && now - present < 1_200_000_000d) {
                times.add(present / 1_000_000);
            }
        }
        return window(times, now / 1_000_000d, pkg);
    }

    private static Reading window(List<Double> times, double newest, String pkg) {
        double first = -1, last = -1;
        int count = 0;
        for (double millis : times) {
            if (millis <= last || millis < newest - 850) continue;
            if (first < 0) first = millis;
            last = millis;
            count++;
        }
        if (count < 4 || last - first < 110 || newest - last > 320) return null;
        double mean = (last - first) / (count - 1);
        return mean >= 1.6 && mean <= 270 ? new Reading(mean, pkg) : null;
    }

    private static double number(String value) {
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
