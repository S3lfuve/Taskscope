package local.taskscope.manager;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class OverlaySampler implements AutoCloseable {
    private final Context context;

    private final OverlayOptions options;
    private final Consumer<OverlayMetric.Values> consumer;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final FrameSampler frames = new FrameSampler();
    private final boolean useRoot, useKernel, useFrames, useSensors;
    private final int mask;
    private volatile Process process;
    private volatile boolean closed;
    private boolean rootFailed;
    private BufferedReader input;
    private BufferedWriter output;
    private Telemetry previous, sensors;
    private FrameSampler.Reading frame;
    private long sensorAt, frameAt, publicAt;
    private long totalRam = -1, availableRam = -1;
    private double batteryTemperature = Double.NaN, batteryLevel = Double.NaN;

    OverlaySampler(Context context, OverlayOptions options, List<OverlayMetric> visible,
            Consumer<OverlayMetric.Values> consumer) {
        this.context = context;

        this.options = options;
        this.consumer = consumer;
        boolean cpu = false, gpu = false, memory = false, frequency = false, thermal = false, fps = false;
        for (OverlayMetric metric : visible) {
            cpu |= metric.source == OverlayMetric.Source.CPU || metric.source == OverlayMetric.Source.CLUSTER;
            gpu |= metric.source == OverlayMetric.Source.GPU;
            frequency |= metric.source == OverlayMetric.Source.FREQUENCY || metric.source == OverlayMetric.Source.CLUSTER;
            thermal |= metric.source == OverlayMetric.Source.SENSOR;
            fps |= metric.source == OverlayMetric.Source.FRAME;
            memory |= metric == OverlayMetric.RAM_USED || metric == OverlayMetric.RAM_TOTAL || metric == OverlayMetric.RAM_PERCENT;
        }
        useKernel = cpu || gpu || memory || frequency;
        useRoot = useKernel || thermal || fps;
        useFrames = fps;
        useSensors = thermal;

        mask = (memory ? 1 : 0) | (gpu ? 2 : 0) | (frequency ? 4 : 0);
    }

    void start() { worker.execute(this::tick); }

    private void tick() {
        if (closed) return;
        long started = SystemClock.elapsedRealtime();
        if (publicAt == 0 || started - publicAt >= 850) {
            publicAt = started;
            readPublic();
        }
        if (useRoot && !rootFailed) {
            try {
                boolean thermal = useSensors && (sensorAt == 0 || started - sensorAt >= 1000);
                if (useKernel || thermal) {
                    Telemetry next = Telemetry.parse(exchange("m " + (mask | (thermal ? 8 : 0))), previous, null, false);
                    previous = next;
                    if (thermal) {
                        sensors = next;
                        sensorAt = started;
                    }
                }
                if (useFrames && (frameAt == 0 || started - frameAt >= 1350)) {
                    frameAt = started;
                    frame = frames.read(this::exchange, context.getPackageName(), started);
                }
            } catch (IOException | IllegalStateException ex) {
                rootFailed = true;
                previous = sensors = null;
                frame = null;
                killProcess();
            }
        }
        if (closed) return;
        OverlayMetric.Values values = new OverlayMetric.Values();
        values.kernel = previous;
        values.sensors = sensors;
        values.frame = frame;
        values.totalRam = totalRam;
        values.availableRam = availableRam;

        values.batteryTemperature = batteryTemperature;
        values.batteryLevel = batteryLevel;

        consumer.accept(values);
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (!closed) {
            try { worker.schedule(this::tick, Math.max(0, options.interval() - elapsed), TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }

    private void readPublic() {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        totalRam = memory.totalMem;
        availableRam = memory.availMem;
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        batteryLevel = level >= 0 && scale > 0 && level <= scale ? level * 100.0 / scale : Double.NaN;
        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        batteryTemperature = tenths >= -400 && tenths <= 900 ? tenths / 10.0 : Double.NaN;

    }

    private String exchange(String command) throws IOException {
        Process active = process;
        if (active == null) {
            String binary = new File(context.getApplicationInfo().nativeLibraryDir, "libtaskscope_probe.so").getPath();
            active = Root.start("exec " + Root.quote(binary) + " overlay-stream");
            process = active;
            if (closed) {
                active.destroyForcibly();
                throw new IOException("Сбор завершён");
            }
            input = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(active.getOutputStream(), StandardCharsets.UTF_8));
        }
        ScheduledFuture<?> watchdog = Root.timeout(active, 6);
        try {
            output.write(command);
            output.newLine();
            output.flush();
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("TASKSCOPE_OVERLAY_END")) return data.toString();
                if (data.length() + line.length() > 1_500_000) throw new IOException("Слишком большой ответ");
                data.append(line).append('\n');
            }
            throw new IOException("Root-процесс завершён");
        } finally {
            watchdog.cancel(false);
        }
    }

    private void killProcess() {
        Process active = process;
        if (active != null) active.destroyForcibly();
    }

    @Override public void close() {
        closed = true;
        killProcess();
        worker.shutdownNow();
    }
}
