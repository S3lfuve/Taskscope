package local.taskscope.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TelemetryMonitor extends Service {
    static final String ROOT_REQUIRED = "Для работы приложение требует Root, выдайте его через ваш менеджер";
    private static final String CHANNEL = "taskscope_collection";
    private static final int NOTIFICATION_ID = 1;
    private static final long PROCESS_MEMORY_INTERVAL = 6000;

    final SampleHistory history = new SampleHistory();
    Telemetry latest, latestTasks;
    Map<Integer, ProcessMemory> processMemory = Collections.emptyMap();
    long sampledAt, tasksAt, throttledSince, lastThrottle;
    int thermalStatus = -1;
    String failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService memoryExecutor = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::sample;
    private final Runnable backgroundTimeout = this::stopCollection;
    private final Binder binder = new LocalBinder();
    private final Set<Runnable> observers = new LinkedHashSet<>();
    private boolean collecting, trackProcessMemory, memoryInFlight;
    private int generation;
    private long backgroundDeadline, nextMemoryAt;
    private MonitorSettings settings;
    private String binary;
    private Runnable unregisterThermal;

    final class LocalBinder extends Binder {
        TelemetryMonitor monitor() { return TelemetryMonitor.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        settings = new MonitorSettings(this);
        binary = Root.quote(new File(getApplicationInfo().nativeLibraryDir, "libtaskscope_probe.so").getPath());
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Мониторинг Taskscope", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = getSystemService(PowerManager.class);
            PowerManager.OnThermalStatusChangedListener listener = this::updateThermalStatus;
            try {
                updateThermalStatus(power.getCurrentThermalStatus());
                power.addThermalStatusListener(listener);
                unregisterThermal = () -> power.removeThermalStatusListener(listener);
            } catch (RuntimeException ignored) { }
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!collecting) {
            PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE);
            Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Taskscope")
                .setContentText("Сбор данных о производительности и температуре")
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).build();
            startForeground(NOTIFICATION_ID, notice);
            failure = null;
            collecting = true;
            generation++;
            scheduleBackgroundTimeout();
            publish();
            sample();
        }
        return START_NOT_STICKY;
    }

    void observe(Runnable observer) {
        observers.add(observer);
        scheduleBackgroundTimeout();
        observer.run();
    }

    void unobserve(Runnable observer) {
        if (observers.remove(observer)) scheduleBackgroundTimeout();
    }

    void trackProcessMemory(boolean visible) {
        trackProcessMemory = visible;
        sampleProcessMemory();
    }

    private void sampleProcessMemory() {
        if (!collecting || observers.isEmpty() || latestTasks == null || !trackProcessMemory || memoryInFlight
                || settings.memoryMethod() == MonitorSettings.MemoryMethod.RSS
                || SystemClock.elapsedRealtime() < nextMemoryAt) return;
        int sampleGeneration = generation;
        memoryInFlight = true;
        memoryExecutor.execute(() -> {
            Map<Integer, ProcessMemory> received = Collections.emptyMap();
            try {
                Root.Result result = Root.run("exec " + binary + " process-memory", 15);
                if (result.code == 0) received = ProcessMemory.parse(result.output);
            } catch (IOException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Map<Integer, ProcessMemory> readings = received;
            main.post(() -> {
                memoryInFlight = false;
                if (!collecting || sampleGeneration != generation) return;
                nextMemoryAt = SystemClock.elapsedRealtime() + PROCESS_MEMORY_INTERVAL;
                processMemory = readings;
                if (trackProcessMemory) publish();
            });
        });
    }

    private void scheduleBackgroundTimeout() {
        main.removeCallbacks(backgroundTimeout);
        backgroundDeadline = 0;
        if (collecting && observers.isEmpty()) {
            long duration = settings.backgroundMillis();
            backgroundDeadline = SystemClock.elapsedRealtime() + duration;
            main.postDelayed(backgroundTimeout, duration);
        }
    }

    private void stopCollection() {
        collecting = false;
        backgroundDeadline = 0;
        main.removeCallbacks(poll);
        main.removeCallbacks(backgroundTimeout);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean backgroundExpired() {
        if (backgroundDeadline == 0 || SystemClock.elapsedRealtime() < backgroundDeadline) return false;
        stopCollection();
        return true;
    }

    private void sample() {
        if (!collecting || backgroundExpired()) return;
        boolean full = !observers.isEmpty();
        int sampleGeneration = generation;
        Telemetry previous = latest;
        long now = SystemClock.elapsedRealtime();
        Telemetry previousTasks = full && now - tasksAt < 3500 ? latestTasks : null;
        executor.execute(() -> {
            Telemetry received = null;
            String error = null;
            try {
                Root.Result result = Root.run("exec " + binary + (full ? " snapshot" : " metrics"), 25);
                if (result.code == 0) {
                    received = Telemetry.parse(result.output, previous, previousTasks, full);
                    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (battery != null && battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                        if (tenths >= -400 && tenths <= 1800) received.batteryCelsius = tenths / 10.0;
                    }
                } else {
                    error = ROOT_REQUIRED;
                }
            } catch (IllegalStateException ex) {
                error = ex.getMessage();
            } catch (Exception ex) {
                error = ROOT_REQUIRED;
            }
            Telemetry value = received;
            String message = error;
            main.post(() -> {
                if (sampleGeneration == generation) complete(value, full, now, message);
            });
        });
    }

    private void complete(Telemetry value, boolean full, long started, String error) {
        if (!collecting || backgroundExpired()) return;
        if (value == null) {
            failure = error == null ? ROOT_REQUIRED : error;
            publish();
            stopCollection();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        history.append(value, System.currentTimeMillis(), sampledAt > 0 && now - sampledAt > 3500);
        latest = value;
        sampledAt = now;
        if (full) {
            latestTasks = value;
            tasksAt = now;
            sampleProcessMemory();
        }
        publish();
        main.postDelayed(poll, Math.max(0, settings.intervalMillis() - (now - started)));
    }

    private void updateThermalStatus(int status) {
        if (thermalStatus == status) return;
        thermalStatus = status;
        if (status > PowerManager.THERMAL_STATUS_NONE && throttledSince == 0) {
            throttledSince = System.currentTimeMillis();
        } else if (status == PowerManager.THERMAL_STATUS_NONE && throttledSince > 0) {
            lastThrottle = System.currentTimeMillis();
            throttledSince = 0;
        }
        if (collecting) publish();
    }

    private void publish() {
        for (Runnable observer : observers) observer.run();
    }

    @Override public void onDestroy() {
        collecting = false;
        observers.clear();
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        memoryExecutor.shutdownNow();
        if (unregisterThermal != null) unregisterThermal.run();
        super.onDestroy();
    }
}
