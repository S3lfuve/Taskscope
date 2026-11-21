package local.taskscope.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

public final class OverlayService extends Service {
    static final String ACTIVATION = "overlay_activation";
    private static final String CHANNEL = "taskscope_overlay";
    private static final int NOTIFICATION = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private OverlayOptions options;
    private DisplayManager displays;
    private Display display;
    private OverlayWindow overlay;
    private OverlaySampler sampler;
    private int generation;
    private boolean destroyed;
    private final Runnable layout = () -> { if (overlay != null) overlay.refreshLayout(); };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) {
            if (id == Display.DEFAULT_DISPLAY) stopSelf();
        }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) requestLayout();
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reconcile(); }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (overlay != null && ("selected".equals(key) || "order".equals(key))) overlay.refreshMetrics();
    };

    @Override public void onCreate() {
        super.onCreate();
        options = new OverlayOptions(this);
        displays = getSystemService(DisplayManager.class);
        display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        displays.registerDisplayListener(displayListener, main);
        options.store().registerOnSharedPreferenceChangeListener(preferenceListener);
        IntentFilter events = new IntentFilter(Intent.ACTION_SCREEN_ON);
        events.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, events, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, events);

        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Оверлей Taskscope",
            NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver receiver = intent == null ? null : intent.getParcelableExtra(ACTIVATION);
        if (overlay != null) {
            if (receiver != null) receiver.send(1, null);
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION, notification());
            if (!Settings.canDrawOverlays(this) || display == null) throw new IllegalStateException();
            Context window = createDisplayContext(display);
            if (Build.VERSION.SDK_INT >= 30) {
                window = window.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            }
            OverlayWindow created = new OverlayWindow(window, display, options, this::reconcile, this::stopSelf);
            created.attach();
            overlay = created;
            if (receiver != null) receiver.send(1, null);
        } catch (RuntimeException ex) {
            if (receiver != null) receiver.send(0, null);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.icon)
            .setContentTitle("Оверлей Taskscope").setContentText("Мониторинг поверх приложений")
            .setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setOngoing(true).build();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        requestLayout();
    }

    private void requestLayout() {
        main.removeCallbacks(layout);
        main.postDelayed(layout, 60);
    }

    private void reconcile() {
        stopSampler();
        if (destroyed || overlay == null || !overlay.expanded()
                || !getSystemService(PowerManager.class).isInteractive()) return;
        if (overlay.visible().isEmpty()) return;
        int token = generation;
        sampler = new OverlaySampler(this, options, overlay.visible(), data ->
            main.post(() -> {
                if (!destroyed && token == generation && overlay != null) overlay.show(data);
            }));
        sampler.start();
    }

    private void stopSampler() {
        generation++;
        if (sampler != null) {
            sampler.close();
            sampler = null;
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopSampler();
        main.removeCallbacksAndMessages(null);
        if (overlay != null) {
            overlay.detach();
            overlay = null;
        }
        displays.unregisterDisplayListener(displayListener);
        options.store().unregisterOnSharedPreferenceChangeListener(preferenceListener);
        unregisterReceiver(screen);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
