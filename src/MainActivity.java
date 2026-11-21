package local.taskscope.manager;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private static final int BACK = 0xff080808, PANEL = 0xff161616, LINE = 0xff2a2a2a;
    private static final int WHITE = 0xfff3f3f3, MUTED = 0xffa5a5a5;
    private static final int ALL_PROCESS_TYPES = 7;
    private static final String[] PROCESS_COLUMNS = {"Процесс", "ЦП", "ОЗУ"};
    private static final int PRIMARY = 0xffededed, SECONDARY = 0xffbdbdbd, TERTIARY = 0xff919191;
    private static final Pattern SERVICES = Pattern.compile("^\\[init\\.svc\\.([a-zA-Z0-9_.@:-]+)\\]: \\[([^\\]]+)\\]$");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private boolean checkingUpdate, manualUpdateCheck;
    private Popup updatePopup;
    private final Map<String, String> labels = new HashMap<>();
    private final ArrayList<Telemetry.Task> visible = new ArrayList<>();
    private static final String[] METRIC_KEYS = {"cpu", "ram", "gpu"};
    private final ArrayList<InitService> services = new ArrayList<>();
    private final Map<String, CoreCard> coreCards = new LinkedHashMap<>();
    private final Tab[] tabButtons = new Tab[4];
    private final TextView[] headings = new TextView[PROCESS_COLUMNS.length];
    private final Metric[] metrics = new Metric[3];
    private final Chart[] performance = new Chart[3];

    private LinearLayout root, banner, processPage, performancePage, servicePage, thermalPage, cores, rootError;
    private ViewAnimator content;
    private LinearLayout frequencyRows;
    private TextView rootStatus, rootErrorMessage, scope, thermalStatus, cpuLimitReason, throttleTime, coolingState;
    private AnimatedValue taskCount, serviceStatus;
    private boolean thermalCpuIdle;
    private long idleChangedAt;
    private ListView taskList;
    private TaskAdapter table;
    private BaseAdapter serviceTable;
    private final TextView[] serviceHeadings = new TextView[2];
    private final Map<Integer, FrequencyCard> frequencyCards = new LinkedHashMap<>();
    private final Metric[] temperatures = new Metric[6];
    private FrequencyCard gpuFrequency;
    private Icons icons;
    private Telemetry snapshot, sample;
    private Map<Integer, ProcessMemory> memorySamples = Collections.emptyMap();
    private MonitorSettings settings;
    private boolean landscape, pageInitialized, overlayPermissionPending, overlayStarting;
    private TelemetryMonitor monitor;
    private SampleHistory history;
    private Consumer<Telemetry.Task> processObserver;
    private String binary, query = "";
    private int section, filter = ALL_PROCESS_TYPES, sorted = 1, serviceSorted, inspectedPid;
    private boolean descending = true, serviceDescending, running, bound, servicesLoading, servicesRead;
    private final Runnable sampleObserver = this::receiveSample;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            monitor = ((TelemetryMonitor.LocalBinder)binder).monitor();
            if (running) observeMonitor();
        }

        @Override public void onServiceDisconnected(ComponentName name) { monitor = null; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        icons = new Icons(this);
        settings = new MonitorSettings(this);
        Configuration config = getResources().getConfiguration();
        landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 550;
        if (state != null) {
            overlayPermissionPending = state.getBoolean("overlayPermissionPending");
            filter = state.getInt("filter", ALL_PROCESS_TYPES);
            query = state.getString("query", "");
            sorted = state.getInt("sort", 1);
            descending = state.getBoolean("descending", true);
            serviceSorted = state.getInt("serviceSort");
            serviceDescending = state.getBoolean("serviceDescending");
        }
        binary = Root.quote(new File(getApplicationInfo().nativeLibraryDir, "libtaskscope_probe.so").getPath());
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        root = row(false);
        root.setBackgroundColor(BACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets pad = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(pad.left, pad.top, pad.right, pad.bottom);
            }
            return insets;
        });
        setContentView(root);
        LinearLayout chrome = root;
        if (landscape) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            ScrollView sidebar = new ScrollView(this);
            sidebar.setVerticalScrollBarEnabled(false);
            chrome = row(false);
            sidebar.addView(chrome);
            root.addView(sidebar, new LinearLayout.LayoutParams(dp(270), -1));
        }

        banner = row(!landscape);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(20), dp(15), dp(20), dp(15));
        LinearLayout branding = landscape ? row(true) : banner;
        branding.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setPadding(dp(9), dp(9), dp(9), dp(9));
        mark.setImageDrawable(new Glyph("pulse", WHITE, dp(23)));
        mark.setBackground(outlined(PANEL, 13));
        LinearLayout.LayoutParams markSize = new LinearLayout.LayoutParams(dp(42), dp(42));
        markSize.rightMargin = dp(10);
        branding.addView(mark, markSize);
        LinearLayout title = row(false);
        TextView appName = text("Taskscope", 25, WHITE, true);
        appName.setMaxLines(1);
        appName.setAutoSizeTextTypeUniformWithConfiguration(17, 25, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        title.addView(appName, new LinearLayout.LayoutParams(-1, -2));
        rootStatus = text("Проверка root-доступа", 12, MUTED, false);
        rootStatus.setSingleLine(true);
        rootStatus.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(rootStatus);
        branding.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (landscape) banner.addView(branding);
        LinearLayout actions = landscape ? row(true) : banner;
        actions.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton gear = new ImageButton(this);
        gear.setContentDescription("Настройки");
        gear.setImageDrawable(new Glyph("settings", WHITE, dp(20)));
        gear.setScaleType(ImageView.ScaleType.CENTER);
        gear.setPadding(0, 0, 0, 0);
        gear.setBackground(tappable(PANEL, 12));
        gear.setOnClickListener(view -> openSettings());
        LinearLayout.LayoutParams gearSize = new LinearLayout.LayoutParams(dp(44), dp(44));
        gearSize.rightMargin = dp(7);
        actions.addView(gear, gearSize);
        TextView launch = button("Оверлей", BACK, this::enableOverlay);
        addGlyph(launch, "overlay", BACK, 17);
        launch.setBackground(surface(WHITE, 12));
        if (landscape) {
            actions.addView(launch, new LinearLayout.LayoutParams(0, dp(44), 1));
            spaced(banner, actions, 8);
        } else {
            actions.addView(launch, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        chrome.addView(banner);

        LinearLayout summaries = row(true);
        summaries.setPadding(dp(16), 0, dp(16), dp(14));
        String[] names = {"ЦП", "ОЗУ", "GPU"};
        int[] colors = {PRIMARY, SECONDARY, TERTIARY};
        for (int i = 0; i < metrics.length; i++) {
            metrics[i] = new Metric(names[i], colors[i], METRIC_KEYS[i], i == 0, false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(92), 1);
            p.setMargins(dp(4), 0, dp(4), 0);
            summaries.addView(metrics[i], p);
        }
        chrome.addView(summaries);

        LinearLayout tabs = row(!landscape);
        tabs.setPadding(dp(18), 0, dp(18), dp(14));
        LinearLayout tabLine = tabs;
        String[] sections = {"Процессы", "Графики", "Службы", "Нагрев"};
        String[] sectionIcons = {"process", "pulse", "services", "temperature"};
        for (int i = 0; i < sections.length; i++) {
            final int index = i;
            Tab tab = new Tab(sections[i], sectionIcons[i], () -> select(index));
            tabButtons[i] = tab;
            if (landscape && i % 2 == 0) {
                tabLine = row(true);
                spaced(tabs, tabLine, i == 0 ? 0 : 6);
            }
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(50), 1);
            if (landscape ? i % 2 == 1 : i > 0) size.leftMargin = dp(6);
            tabLine.addView(tab, size);
        }
        chrome.addView(tabs);

        rootError = card(12);
        rootErrorMessage = text("", 13, WHITE, false);
        rootError.addView(rootErrorMessage);
        TextView retry = actionButton("Повторить проверку Root", "refresh", false, this::startMonitor);
        spaced(rootError, retry, 8);
        LinearLayout.LayoutParams errorSize = new LinearLayout.LayoutParams(-1, -2);
        errorSize.setMargins(dp(20), 0, dp(20), dp(12));
        chrome.addView(rootError, errorSize);
        rootError.setVisibility(View.GONE);

        content = new ViewAnimator(this);
        content.setAnimateFirstView(false);
        root.addView(content, landscape ? new LinearLayout.LayoutParams(0, -1, 1)
            : new LinearLayout.LayoutParams(-1, 0, 1));
        buildProcesses();
        buildPerformance();
        buildServices();
        buildThermal();
        for (LinearLayout page : new LinearLayout[]{processPage, performancePage, servicePage, thermalPage}) {
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        }
        select(state == null ? 0 : state.getInt("section"));
        worker.execute(() -> {
            Map<String, String> apps = new HashMap<>();
            PackageManager pm = getPackageManager();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                apps.put(info.packageName, info.loadLabel(pm).toString());
            }
            main.post(() -> {
                if (isDestroyed()) return;
                labels.putAll(apps);
                updateTable();
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("section", section);
        state.putInt("filter", filter);
        state.putBoolean("overlayPermissionPending", overlayPermissionPending);
        state.putString("query", query);
        state.putInt("sort", sorted);
        state.putBoolean("descending", descending);
        state.putInt("serviceSort", serviceSorted);
        state.putBoolean("serviceDescending", serviceDescending);
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, TelemetryMonitor.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        stopService(new Intent(this, OverlayService.class));
        startMonitor();
        if (monitor != null) observeMonitor();
        if (overlayPermissionPending) {
            overlayPermissionPending = false;
            if (Settings.canDrawOverlays(this)) startOverlay();
            else toast("Для оверлея нужно разрешить показ поверх приложений");
        }
        checkForUpdate(false);
    }

    @Override protected void onPause() {
        running = false;
        super.onPause();
    }

    @Override protected void onStop() {
        if (monitor != null) monitor.unobserve(sampleObserver);
        if (bound) unbindService(connection);
        bound = false;
        monitor = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (updatePopup != null) {
            updatePopup.dialog.setOnDismissListener(null);
            updatePopup.dismiss();
        }
        main.removeCallbacksAndMessages(null);
        updateWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void startMonitor() {
        try {
            startForegroundService(new Intent(this, TelemetryMonitor.class));
        } catch (IllegalStateException | SecurityException ex) {
            showMonitorError("Android не запустил службу мониторинга. Повторите проверку.");
        }
    }

    private void observeMonitor() {
        monitor.observe(sampleObserver);
        monitor.trackProcessMemory(section == 0);
    }

    private void receiveSample() {
        history = monitor.history;
        if (monitor.failure != null) {
            showMonitorError(monitor.failure);
            return;
        }
        rootError.setVisibility(View.GONE);
        if (monitor.latest == null) {
            rootStatus.setText("Проверка root-доступа");
            setEmptyValues(null);
            return;
        }
        updateText(rootStatus, "●  Root  ·  " + Build.MODEL);
        boolean updated = sample != monitor.latest;
        sample = monitor.latest;
        if (monitor.latestTasks != null && (snapshot != monitor.latestTasks || memorySamples != monitor.processMemory)) {
            snapshot = monitor.latestTasks;
            memorySamples = monitor.processMemory;
            if (section == 0) updateTable();
            if (processObserver != null) processObserver.accept(snapshot.tasks.get(inspectedPid));
        }
        renderCharts(updated);
        if (section == 3) renderThermal(updated);
        if (section == 2 && !servicesRead) refreshServices();
    }

    private void showMonitorError(String message) {
        rootStatus.setText("Мониторинг недоступен");
        rootErrorMessage.setText(message);
        rootError.setVisibility(View.VISIBLE);
        if (sample == null) setEmptyValues("н/д");
    }

    private void setEmptyValues(String value) {
        for (Metric metric : metrics) metric.value.show(value);
        for (Chart chart : performance) chart.value.show(value);
        for (Metric metric : temperatures) metric.value.show(value);
        metrics[0].temperature.show(value);
        performance[0].temperature.show(value);
        taskCount.show(value);
    }

    private void select(int page) {
        if (pageInitialized && section == page) return;
        boolean forward = page > section;
        boolean animate = pageInitialized && content.isLaidOut() && ValueAnimator.areAnimatorsEnabled();
        for (int i = 0; i < content.getChildCount(); i++) content.getChildAt(i).clearAnimation();
        content.setInAnimation(animate ? tabMotion(true, forward) : null);
        content.setOutAnimation(animate ? tabMotion(false, forward) : null);
        section = page;
        if (monitor != null) monitor.trackProcessMemory(page == 0);
        pageInitialized = true;
        content.setDisplayedChild(page);
        for (int i = 0; i < tabButtons.length; i++) tabButtons[i].update(page == i);
        if (page == 0) updateTable();
        if (page == 1) renderCharts(false);
        if (page == 2) refreshServices();
        if (page == 3) renderThermal(false);
    }

    private Animation tabMotion(boolean entering, boolean forward) {
        Animation animation = entering ? AnimationUtils.makeInAnimation(this, forward)
            : AnimationUtils.makeOutAnimation(this, forward);
        animation.setDuration(175);
        return animation;
    }

    private void buildProcesses() {
        processPage = row(false);
        processPage.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout tools = row(true);
        EditText search = field("Поиск");
        search.setSingleLine(true);
        search.setFilters(new InputFilter[]{MainActivity::filterSearchInput});
        search.setText(query);
        addGlyph(search, "search", MUTED, 17);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(46), 1));
        scope = button(filterLabel(), SECONDARY, this::chooseFilter);
        scope.setBackground(outlined(PANEL, 10));
        LinearLayout.LayoutParams scopeSize = new LinearLayout.LayoutParams(-2, dp(46));
        scopeSize.leftMargin = dp(8);
        tools.addView(scope, scopeSize);
        processPage.addView(tools);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                updateTable();
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        taskCount = number(12, MUTED, false);
        taskCount.setPadding(dp(5), dp(11), 0, dp(9));
        processPage.addView(taskCount);

        LinearLayout header = columns();
        header.setBackground(surface(PANEL, 8));
        for (int i = 0; i < PROCESS_COLUMNS.length; i++) {
            final int index = i;
            headings[i] = text(PROCESS_COLUMNS[i], 12, MUTED, true);
            headings[i].setGravity(i == 0 ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
            headings[i].setOnClickListener(v -> {
                descending = sorted == index ? !descending : index > 0;
                sorted = index;
                updateTable();
            });
            setColumn(header, headings[i], i);
        }
        processPage.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));
        taskList = new ListView(this);
        taskList.setDivider(new android.graphics.drawable.ColorDrawable(LINE));
        taskList.setDividerHeight(dp(1));
        taskList.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        table = new TaskAdapter();
        taskList.setAdapter(table);
        taskList.setOnItemClickListener((v, item, position, id) -> {
            item.setPressed(false);
            v.setPressed(false);
            taskList.jumpDrawablesToCurrentState();
            inspect(visible.get(position));
        });
        processPage.addView(taskList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void chooseFilter() {
        String[] types = {"Приложения", "Системные", "Ядро"};
        Popup popup = new Popup("Тип процесса", null, "process", null);
        for (int i = 0; i < types.length; i++) {
            int bit = 1 << i;
            CheckBox choice = new CheckBox(this);
            choice.setText(types[i]);
            choice.setTextColor(WHITE);
            choice.setTextSize(14);
            choice.setButtonDrawable((Drawable)null);
            choice.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, filterCheckIcon(), null);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setChecked((filter & bit) != 0);
            choice.setMinHeight(dp(48));
            choice.setPadding(dp(12), 0, dp(12), 0);
            choice.setBackground(tappable(0xff242424, 10));
            choice.setOnCheckedChangeListener((view, enabled) -> {
                filter = enabled ? filter | bit : filter & ~bit;
                scope.setText(filterLabel());
                updateTable();
            });
            spaced(popup.body, choice, i == 0 ? 0 : 6);
        }
        popup.show();
    }

    private Drawable filterCheckIcon() {
        GradientDrawable off = surface(PANEL, 6);
        off.setStroke(dp(1), 0xff626262);
        off.setSize(dp(22), dp(22));

        GradientDrawable on = outlined(0xff393939, 6);
        on.setSize(dp(22), dp(22));
        LayerDrawable checked = new LayerDrawable(new Drawable[]{on, new Glyph("check", WHITE, dp(16))});
        checked.setLayerSize(1, dp(16), dp(16));
        checked.setLayerGravity(1, Gravity.CENTER);

        StateListDrawable icon = new StateListDrawable();
        icon.addState(new int[]{android.R.attr.state_checked}, checked);
        icon.addState(new int[0], off);
        return icon;
    }

    private String filterLabel() {
        return filter == ALL_PROCESS_TYPES ? "Тип ▾" : "Тип " + Integer.bitCount(filter) + "/3 ▾";
    }

    private static final Pattern KEYCAP = Pattern.compile("[0-9#*]\uFE0F?\u20E3");

    private static CharSequence filterSearchInput(CharSequence source, int start, int end, Spanned destination,
            int replaceStart, int replaceEnd) {
        if (start == end) return null;
        SpannableStringBuilder cleaned = new SpannableStringBuilder(source, start, end);
        Matcher keycap = KEYCAP.matcher(cleaned.toString());
        ArrayList<int[]> keycaps = new ArrayList<>();
        while (keycap.find()) keycaps.add(new int[]{keycap.start(), keycap.end()});
        for (int i = keycaps.size() - 1; i >= 0; i--) cleaned.delete(keycaps.get(i)[0], keycaps.get(i)[1]);
        for (int i = cleaned.length(); i > 0;) {
            int code = Character.codePointBefore(cleaned, i);
            int previous = i - Character.charCount(code);
            if (code == 0x200d || code == 0xfe0e || code == 0xfe0f || code == 0x20e3
                    || (code >= 0xe0020 && code <= 0xe007f)
                    || (code > 127 && android.icu.lang.UCharacter.hasBinaryProperty(code, android.icu.lang.UProperty.EMOJI))) {
                cleaned.delete(previous, i);
            }
            i = previous;
        }
        return cleaned.length() == end - start ? null : cleaned;
    }

    private boolean application(Telemetry.Task task) {
        return task.uid % 100000 >= 10000 && !task.kernel();
    }

    private String label(Telemetry.Task task) {
        return labels.getOrDefault(task.packageCandidate(), task.name);
    }

    private void updateTable() {
        if (snapshot == null || table == null) return;
        int oldCount = visible.size();
        visible.clear();
        for (Telemetry.Task task : snapshot.tasks.values()) {
            int type = task.kernel() ? 4 : application(task) ? 1 : 2;
            if ((filter & type) == 0) continue;
            if (!query.isEmpty() && !task.name.toLowerCase(Locale.ROOT).contains(query)
                    && !label(task).toLowerCase(Locale.ROOT).contains(query)
                    && !Integer.toString(task.pid).contains(query)) continue;
            visible.add(task);
        }
        Comparator<Telemetry.Task> comparator;
        MonitorSettings.MemoryMethod method = settings.memoryMethod();
        switch (sorted) {
            case 0: comparator = Comparator.comparing(this::label, String.CASE_INSENSITIVE_ORDER); break;
            case 2:
                long missing = descending ? Long.MIN_VALUE : Long.MAX_VALUE;
                comparator = Comparator.comparingLong(t -> {
                    long measured = ProcessMemory.value(t, method, memorySamples);
                    return measured < 0 ? missing : measured;
                });
                break;
            default: comparator = Comparator.comparingDouble(t -> t.cpu); break;
        }
        if (descending) comparator = comparator.reversed();
        visible.sort(comparator.thenComparingInt(t -> t.pid));
        for (int i = 0; i < headings.length; i++) {
            updateText(headings[i], PROCESS_COLUMNS[i] + (i == sorted ? descending ? " ↓" : " ↑" : ""));
            headings[i].setTextColor(i == sorted ? PRIMARY : MUTED);
        }
        int kernel = 0;
        for (Telemetry.Task task : visible) if (task.kernel()) kernel++;
        int processes = visible.size() - kernel;
        String processCount = processes == snapshot.processCount ? Integer.toString(processes)
            : processes + "/" + snapshot.processCount;
        String kernelCount = kernel == snapshot.kernelCount ? Integer.toString(kernel)
            : kernel + "/" + snapshot.kernelCount;
        String processLabel = plural(snapshot.processCount, "процесс", "процесса", "процессов");
        String kernelLabel = plural(snapshot.kernelCount, "поток", "потока", "потоков") + " ядра";
        String counts = (filter & 3) == 0 ? "" : processCount + " " + processLabel;
        if ((filter & 4) != 0) counts += (counts.isEmpty() ? "" : "  ·  ") + kernelCount + " " + kernelLabel;
        taskCount.show(counts.isEmpty() ? "Все типы скрыты" : counts);

        int first = taskList.getFirstVisiblePosition();
        int rows = taskList.getChildCount();
        if (rows == 0 || oldCount != visible.size() || first + rows > visible.size()) {
            table.notifyDataSetChanged();
            return;
        }
        for (int i = 0; i < rows; i++) {
            TaskRow row = (TaskRow)taskList.getChildAt(i);
            if (!visible.get(first + i).isSame(row.task)) {
                table.notifyDataSetChanged();
                return;
            }
        }
        for (int i = 0; i < rows; i++) ((TaskRow)taskList.getChildAt(i)).bind(visible.get(first + i));
    }

    private void buildPerformance() {
        performancePage = row(false);
        LinearLayout stack = scrollPage(performancePage);
        String[] names = {"Загрузка процессора", "Оперативная память", "Графический чип"};
        int[] colors = {PRIMARY, SECONDARY, TERTIARY};
        for (int i = 0; i < performance.length; i++) {
            performance[i] = new Chart(names[i], colors[i], METRIC_KEYS[i], i == 0);
            spaced(stack, performance[i], i == 0 ? 0 : 12);
        }
        spaced(stack, text("Загрузка по ядрам", 15, WHITE, true), 16);
        cores = row(false);
        spaced(stack, cores, 8);
    }

    private LinearLayout scrollPage(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        LinearLayout stack = row(false);
        stack.setPadding(dp(20), 0, dp(20), dp(20));
        scroll.addView(stack);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return stack;
    }

    private void renderCharts(boolean animate) {
        if (sample == null) return;
        double[] values = {sample.cpuLoad(), sample.memoryLoad(), sample.gpuLoad};
        for (int i = 0; i < values.length; i++) {
            String value = i == 0 && values[i] < 0 ? null : percent(values[i]);
            metrics[i].update(value, animate);
            if (section == 1) performance[i].update(value, animate);
        }
        double cpuTemperature = sample.cpuTemperature();
        String heat = Double.isFinite(cpuTemperature) ? formatted(cpuTemperature, "%.0f") + " °C" : "н/д";
        metrics[0].temperature.show(heat);
        if (section != 1) return;
        performance[0].temperature.show(heat);
        int coreCount = sample.cpus.size() - 1;
        performance[0].caption.show(processorModel());
        performance[0].extra.show(coreCount + " " + plural(coreCount, "ядро", "ядра", "ядер")
            + (snapshot == null ? "" : "  ·  " + snapshot.processCount + " "
                + plural(snapshot.processCount, "процесс", "процесса", "процессов")));
        performance[1].caption.show(sample.memory > 0 && sample.available >= 0
            ? storage(sample.memory - sample.available) + " / " + storage(sample.memory) : "н/д");
        performance[1].extra.show(sample.swap > 0 && sample.swapFree >= 0
            ? "Своп  " + storage(sample.swap - sample.swapFree) + " / " + storage(sample.swap) : "Своп  н/д");
        performance[2].caption.show((sample.gpu.isEmpty() ? "GPU" : sample.gpu)
            + (sample.gpuHz > 0 ? "  ·  " + frequency(sample.gpuHz) : ""));
        performance[2].extra.show(sample.gpuLoad >= 0 ? "Источник загрузки · драйвер KGSL"
            : "Драйвер не предоставил загрузку GPU");

        for (String key : sample.cpus.keySet()) {
            if (key.equals("cpu") || coreCards.containsKey(key)) continue;
            CoreCard card = new CoreCard(key);
            LinearLayout pair;
            if (coreCards.size() % 2 == 0) {
                pair = row(true);
                spaced(cores, pair, cores.getChildCount() == 0 ? 0 : 8);
                pair.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            } else {
                pair = (LinearLayout)cores.getChildAt(cores.getChildCount() - 1);
                pair.removeViewAt(1);
            }
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            if (coreCards.size() % 2 == 0) pair.addView(card, 0, size);
            else {
                size.leftMargin = dp(8);
                pair.addView(card, size);
            }
            coreCards.put(key, card);
        }
        for (Map.Entry<String, CoreCard> entry : coreCards.entrySet()) {
            Telemetry.Cpu reading = sample.cpus.get(entry.getKey());
            CoreCard core = entry.getValue();
            core.value.show(reading == null ? "н/д" : reading.percent < 0 ? null : percent(reading.percent));
            core.highPerformance(sample.highPerformanceCore(entry.getKey()));
            core.plot.show(history == null ? null : history.get(entry.getKey()), animate);
        }
    }

    private String processorModel() {
        String model = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE;
        if ("SM8850".equals(model)) return "Snapdragon 8 Elite Gen 5";
        return model == null || model.isEmpty() || Build.UNKNOWN.equals(model) ? Build.HARDWARE : model;
    }

    private void buildThermal() {
        thermalPage = row(false);
        LinearLayout stack = scrollPage(thermalPage);
        stack.addView(text("Мониторинг температур", 20, WHITE, true));
        String[] names = {"ЦП макс.", "GPU макс.", "Батарея", "Корпус", "ОЗУ", "Модем"};
        String[] keys = {"tempCpu", "tempGpu", "tempBattery", Telemetry.EXTRA_SENSOR_KEYS[0],
            Telemetry.EXTRA_SENSOR_KEYS[1], Telemetry.EXTRA_SENSOR_KEYS[2]};
        LinearLayout readings = null;
        for (int i = 0; i < temperatures.length; i++) {
            if (i % 3 == 0) {
                readings = row(true);
                spaced(stack, readings, i == 0 ? 12 : 8);
            }
            temperatures[i] = new Metric(names[i], PRIMARY, keys[i], false, true);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(82), 1);
            if (i % 3 > 0) size.leftMargin = dp(8);
            readings.addView(temperatures[i], size);
        }

        LinearLayout state = card(13);
        state.addView(text("Термостатус Android", 12, MUTED, true));
        thermalStatus = text("Чтение данных", 17, WHITE, true);
        thermalStatus.setSingleLine();
        thermalStatus.setEllipsize(TextUtils.TruncateAt.END);
        spaced(state, thermalStatus, 3);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(-1, dp(1));
        rule.topMargin = dp(9);
        rule.bottomMargin = dp(4);
        state.addView(divider, rule);
        coolingState = thermalLine(state, "Лимит частоты");
        cpuLimitReason = thermalLine(state, "Причина ЦП");
        throttleTime = thermalLine(state, "Троттлинг");
        spaced(stack, state, 12);

        spaced(stack, text("Частоты и лимиты", 15, WHITE, true), 17);
        frequencyRows = row(false);
        spaced(stack, frequencyRows, 6);
        gpuFrequency = new FrequencyCard("GPU");
        spaced(stack, gpuFrequency.pane, 8);
    }

    private TextView thermalLine(LinearLayout card, String label) {
        LinearLayout line = row(true);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(23));
        TextView caption = text(label, 12, MUTED, false);
        caption.setSingleLine();
        line.addView(caption, new LinearLayout.LayoutParams(-2, -2));
        TextView value = text("—", 12, WHITE, true);
        value.setSingleLine();
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.leftMargin = dp(8);
        line.addView(value, size);
        card.addView(line);
        return value;
    }

    private void renderThermal(boolean animate) {
        if (sample == null) return;
        double[] readings = {sample.cpuTemperature(), sample.gpuTemperature(), sample.batteryTemperature(),
            sample.extraTemperature(0), sample.extraTemperature(1), sample.extraTemperature(2)};
        for (int i = 0; i < readings.length; i++) temperatures[i].update(temperature(readings[i]), animate);
        int status = monitor == null ? -1 : monitor.thermalStatus;
        String[] severity = {"Без троттлинга", "Лёгкий троттлинг", "Умеренный троттлинг",
            "Сильный троттлинг", "Критический нагрев", "Аварийный режим", "Отключение из-за нагрева"};
        String state = status >= 0 && status < severity.length ? severity[status] : "Нет данных";
        updateText(thermalStatus, state);

        int cpuCut = 0;
        for (Map.Entry<Integer, Telemetry.Frequency> entry : sample.frequencies.entrySet()) {
            Telemetry.Frequency value = entry.getValue();
            FrequencyCard card = frequencyCards.get(entry.getKey());
            if (card == null) {
                card = new FrequencyCard("ЦП");
                frequencyCards.put(entry.getKey(), card);
                spaced(frequencyRows, card.pane, frequencyRows.getChildCount() == 0 ? 0 : 8);
            }
            card.title.show(value.cores.isEmpty() ? "ЦП · policy" + entry.getKey() : "ЦП · ядра " + value.cores);
            card.update(value.hz, value.limitHz, value.maximumHz, value.hardware ? null : "Частота по данным CPUFreq");
            card.pane.setVisibility(View.VISIBLE);
            cpuCut = Math.max(cpuCut, frequencyCut(value.limitHz, value.maximumHz));
        }
        for (Map.Entry<Integer, FrequencyCard> entry : frequencyCards.entrySet()) {
            if (!sample.frequencies.containsKey(entry.getKey())) entry.getValue().pane.setVisibility(View.GONE);
        }
        gpuFrequency.update(sample.gpuHz, sample.gpuLimitHz, sample.gpuMaximumHz, null);
        int gpuCut = frequencyCut(sample.gpuLimitHz, sample.gpuMaximumHz);
        if (cpuCut > 0 || gpuCut > 0) {
            String cpu = cpuCut > 0 ? "ЦП −" + cpuCut + "%" : "";
            String gpu = gpuCut > 0 ? "GPU −" + gpuCut + "%" : "";
            updateText(coolingState, cpu + (cpuCut > 0 && gpuCut > 0 ? " · " : "") + gpu);
        } else {
            boolean limitKnown = sample.frequencies.values().stream().anyMatch(policy ->
                policy.limitHz > 0 && policy.maximumHz > 0)
                || (sample.gpuLimitHz > 0 && sample.gpuMaximumHz > 0);
            updateText(coolingState, limitKnown ? "Нет" : "Нет данных");
        }
        if (animate) updateThermalIdle();
        updateText(cpuLimitReason, cpuFrequencyReason(sample, cpuCut, thermalCpuIdle));
        long activeSince = monitor == null ? 0 : monitor.throttledSince;
        long eventTime = activeSince > 0 ? activeSince : monitor == null ? 0 : monitor.lastThrottle;
        if (status < 0) {
            updateText(throttleTime, "Нет данных");
        } else if (eventTime == 0) {
            updateText(throttleTime, "Не наблюдался");
        } else {
            String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(eventTime));
            String prefix = activeSince > 0 ? "С " : "До ";
            updateText(throttleTime, prefix + time);
        }
    }

    private void updateThermalIdle() {
        double load = sample.cpuLoad();
        int loadLimit = thermalCpuIdle ? 18 : 12;
        int coreLimit = thermalCpuIdle ? 60 : 45;
        boolean idle = load >= 0 && load <= loadLimit
            && sample.cpus.values().stream().noneMatch(core -> core.percent >= coreLimit)
            && sample.frequencies.values().stream().anyMatch(policy ->
                policy.hz > 0 && policy.limitHz > 0 && policy.hz < policy.limitHz * .7);
        long now = SystemClock.elapsedRealtime();
        if (idle == thermalCpuIdle) {
            idleChangedAt = 0;
        } else if (idleChangedAt == 0) {
            idleChangedAt = now;
        } else if (now - idleChangedAt >= 2000) {
            thermalCpuIdle = idle;
            idleChangedAt = 0;
        }
    }

    private static String cpuFrequencyReason(Telemetry sample, int cut, boolean idle) {
        for (String device : sample.cooling.keySet()) {
            String name = device.toLowerCase(Locale.ROOT);
            if (name.startsWith("cpu") || name.startsWith("pause-cpu")) return "Термозащита ЦП";
        }
        if (cut > 0) return idle ? "Неизвестно · простой" : "Неизвестно";
        return idle ? "Простой" : "—";
    }

    private int frequencyCut(long limit, long maximum) {
        if (limit <= 0 || limit >= maximum) return 0;
        return Math.max(1, (int)Math.round(100.0 * (maximum - limit) / maximum));
    }

    private static final class InitService {
        final String name, state;
        InitService(String name, String state) { this.name = name; this.state = state; }

        String status() {
            switch (state) {
                case "running": return "Работает";
                case "stopped": return "Остановлена";
                case "restarting": return "Перезапуск";
                case "stopping": return "Останавливается";
                default: return state;
            }
        }
    }

    private void buildServices() {
        servicePage = row(false);
        servicePage.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout bar = row(true);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        serviceStatus = number(12, MUTED, false);
        serviceStatus.show("Службы Android init");
        bar.addView(serviceStatus, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = actionButton("Обновить", "refresh", false, this::refreshServices);
        refresh.setMinHeight(0);
        refresh.setMinimumHeight(0);
        bar.addView(refresh, new LinearLayout.LayoutParams(-2, dp(35)));
        servicePage.addView(bar);
        LinearLayout header = columns();
        header.setBackground(outlined(PANEL, 8));
        for (int i = 0; i < serviceHeadings.length; i++) {
            final int index = i;
            serviceHeadings[i] = text("", 12, MUTED, true);
            serviceHeadings[i].setGravity(Gravity.CENTER_VERTICAL);
            serviceHeadings[i].setMinHeight(dp(40));
            serviceHeadings[i].setFocusable(true);
            serviceHeadings[i].setOnClickListener(v -> {
                serviceDescending = serviceSorted == index && !serviceDescending;
                serviceSorted = index;
                sortServices();
            });
            serviceColumn(header, serviceHeadings[i], index);
        }
        spaced(servicePage, header, 8);
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        serviceTable = new BaseAdapter() {
            @Override public int getCount() { return services.size(); }
            @Override public InitService getItem(int position) { return services.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout line;
                if (recycled == null) {
                    line = columns();
                    line.setMinimumHeight(dp(47));
                    TextView name = text("", 13, WHITE, true);
                    TextView status = text("", 11, MUTED, false);
                    for (TextView cell : new TextView[]{name, status}) {
                        cell.setSingleLine(true);
                        cell.setEllipsize(TextUtils.TruncateAt.END);
                    }
                    serviceColumn(line, name, 0);
                    serviceColumn(line, status, 1);
                } else line = (LinearLayout)recycled;
                InitService entry = getItem(position);
                ((TextView)line.getChildAt(0)).setText(entry.name);
                ((TextView)line.getChildAt(1)).setText(entry.status());
                line.getChildAt(1).setAlpha("running".equals(entry.state) ? 1f : .72f);
                return line;
            }
        };
        list.setAdapter(serviceTable);
        list.setOnItemClickListener((parent, view, position, id) -> inspectService(services.get(position)));
        servicePage.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        sortServices();
    }

    private void serviceColumn(LinearLayout line, View view, int index) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, index == 0 ? 65 : 35);
        if (index == 0) size.rightMargin = dp(8);
        line.addView(view, size);
    }

    private void sortServices() {
        Comparator<InitService> names = Comparator.comparing(item -> item.name, String.CASE_INSENSITIVE_ORDER);
        Comparator<InitService> order = serviceSorted == 0 ? names
            : Comparator.comparing(InitService::status, String.CASE_INSENSITIVE_ORDER);
        if (serviceDescending) order = order.reversed();
        services.sort(order.thenComparing(names));
        String[] titles = {"Служба", "Состояние"};
        for (int i = 0; i < titles.length; i++) {
            serviceHeadings[i].setText(titles[i] + (i == serviceSorted ? serviceDescending ? " ↓" : " ↑" : ""));
            serviceHeadings[i].setTextColor(i == serviceSorted ? PRIMARY : MUTED);
        }
        serviceTable.notifyDataSetChanged();
    }

    private void inspectService(InitService service) {
        Popup popup = new Popup(service.name, service.status(), "services", null);
        String[] options = {"Запустить", "Остановить", "Перезапустить"};
        String[] actions = {"start", "stop", "restart"};
        String[] glyphs = {"play", "stop", "refresh"};
        for (int i = 0; i < options.length; i++) {
            if (i == 0 && "running".equals(service.state)) continue;
            final int index = i;
            popup.action(options[i], null, glyphs[i], () -> confirm(options[index] + " службу?",
                service.name + "\nЭто системная служба. Действие может нарушить работу Android.",
                () -> {
                    popup.dismiss();
                    command("/system/bin/setprop ctl." + actions[index] + " " + Root.quote(service.name),
                        "Команда отправлена Android init", () -> main.postDelayed(this::refreshServices, 800));
                }));
        }
        popup.show();
    }

    private void refreshServices() {
        if (!running || servicesLoading) return;
        if (monitor != null && monitor.failure != null) {
            serviceStatus.show("Службы недоступны");
            return;
        }
        if (sample == null) return;
        servicesLoading = true;
        serviceStatus.show(null);
        execute("/system/bin/getprop", result -> {
            servicesLoading = false;
            servicesRead = true;
            if (result.code != 0) {
                serviceStatus.show("Службы недоступны");
                return;
            }
            services.clear();
            for (String line : result.output.split("\n")) {
                Matcher match = SERVICES.matcher(line);
                if (match.matches()) services.add(new InitService(match.group(1), match.group(2)));
            }
            sortServices();
            serviceStatus.show(services.size() + " " + plural(services.size(), "служба", "службы", "служб") + " Android init");
        });
    }

    private ApplicationInfo packageInfo(Telemetry.Task task) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(task.packageCandidate(), 0);
            return info.uid % 100000 == task.uid % 100000 ? info : null;
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private void inspect(Telemetry.Task task) {
        ApplicationInfo info = packageInfo(task);
        android.graphics.Bitmap icon = info == null ? null : icons.get(info.packageName);
        Popup popup = new Popup(label(task), null, task.kernel() ? "services" : "process", icon);
        if (!task.name.equals(label(task))) popup.body.addView(text(task.name, 12, MUTED, false));
        LinearLayout details = card(12);
        String[][] titles = {{"PID", "UID"}, {"Родительский PID", "Потоки"},
            {"Загрузка ЦП", "Память " + settings.memoryMethod().name()}, {"Состояние", "Приоритет nice"}};
        AnimatedValue[] values = new AnimatedValue[8];
        for (int i = 0; i < titles.length; i++) {
            LinearLayout pair = row(true);
            for (int j = 0; j < titles[i].length; j++) {
                LinearLayout field = row(false);
                field.addView(text(titles[i][j], 11, MUTED, true));
                values[i * 2 + j] = number(14, WHITE, true);
                spaced(field, values[i * 2 + j], 2);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                if (j > 0) size.leftMargin = dp(10);
                pair.addView(field, size);
            }
            spaced(details, pair, i == 0 ? 0 : 10);
        }
        spaced(popup.body, details, popup.body.getChildCount() == 0 ? 0 : 10);
        Consumer<Telemetry.Task> update = current -> {
            if (!task.isSame(current)) {
                values[4].show("н/д");
                values[5].show("н/д");
                values[6].show("Завершён");
                return;
            }
            String[] readings = {Integer.toString(current.pid), Integer.toString(current.uid), Integer.toString(current.ppid),
                Integer.toString(current.threads), current.cpu < 0 ? null : percent(current.cpu),
                storage(ProcessMemory.value(current, settings.memoryMethod(), memorySamples)),
                taskState(current.state), Integer.toString(current.nice)};
            for (int i = 0; i < values.length; i++) values[i].show(readings[i]);
        };
        update.accept(task);
        inspectedPid = task.pid;
        processObserver = update;
        popup.dialog.setOnDismissListener(dialog -> {
            if (processObserver == update) processObserver = null;
        });

        popup.group("Процесс");
        if (task.pid > 2 && task.pid != android.os.Process.myPid() && !task.kernel()) {
            popup.action("Завершить", canStopPackage(info) ? "Остановить приложение" : "Отправить SIGTERM", "stop",
                () -> finishTask(task, info, popup));
            popup.action("Другие действия", "Пауза, продолжение, принудительное завершение", "process", () -> signals(task, popup));
        }
        popup.action("Подробности из /proc", "Статус, ввод-вывод и командная строка", "details", () -> {
            Popup detailsPopup = new Popup("Подробности процесса", "PID " + task.pid, "details", null);
            LinearLayout loading = row(true);
            loading.setGravity(Gravity.CENTER_VERTICAL);
            loading.addView(progress(), new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView message = text("Чтение данных процесса", 13, MUTED, false);
            message.setPadding(dp(10), 0, 0, 0);
            loading.addView(message);
            detailsPopup.body.addView(loading);
            detailsPopup.show();
            execute(taskCommand("details", task), result -> {
                if (!detailsPopup.dialog.isShowing()) return;
                detailsPopup.body.removeAllViews();
                fillOutput(detailsPopup, result);
            });
        });
        if (info != null) {
            popup.group("Приложение");
            Intent launch = getPackageManager().getLaunchIntentForPackage(info.packageName);
            if (task.user() == android.os.Process.myUid() / 100000) {
                if (launch != null) popup.action("Открыть приложение", null, "play", () -> {
                    popup.dismiss();
                    startActivity(launch);
                });
                popup.action("Настройки приложения", null, "settings", () -> {
                    popup.dismiss();
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + info.packageName)));
                });
            }
        }
        popup.show();
    }

    private boolean canStopPackage(ApplicationInfo info) {
        return info != null && !info.packageName.equals(getPackageName());
    }

    private void finishTask(Telemetry.Task task, ApplicationInfo info, Popup popup) {
        if (!canStopPackage(info)) {
            confirmSignal(task, "Завершить процесс?", "term", popup::dismiss);
            return;
        }
        confirm("Остановить приложение?", label(task) + "\nAndroid завершит все процессы приложения для пользователя " + task.user() + ".",
            () -> {
                popup.dismiss();
                command("/system/bin/am force-stop --user " + task.user() + " " + Root.quote(info.packageName),
                    "Приложение остановлено", null);
            });
    }

    private static String taskState(String state) {
        switch (state) {
            case "R": return "Выполняется";
            case "S": return "Спит";
            case "D": return "Ожидает ввода-вывода";
            case "T": case "t": return "Приостановлен";
            case "I": return "Простаивает";
            case "Z": return "Зомби";
            default: return state;
        }
    }

    private void signals(Telemetry.Task task, Popup parent) {
        Popup popup = new Popup("Управление процессом", "PID " + task.pid, "process", null);
        String[] labels = {"Приостановить", "Продолжить", "Завершить принудительно"};
        String[] descriptions = {"SIGSTOP", "SIGCONT", "SIGKILL"};
        String[] commands = {"pause", "resume", "kill"};
        String[] glyphs = {"pause", "play", "stop"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            popup.action(labels[i], descriptions[i], glyphs[i], () ->
                confirmSignal(task, labels[index] + " процесс?", commands[index], () -> {
                    popup.dismiss();
                    if ("kill".equals(commands[index])) parent.dismiss();
                }));
        }
        popup.show();
    }

    private void confirmSignal(Telemetry.Task task, String title, String signal, Runnable after) {
        String description = task.name;
        if (!application(task) && !"resume".equals(signal)) {
            description += "\nСистемный процесс. Действие может нарушить работу Android.";
        }
        confirm(title, description, () -> {
            after.run();
            command(taskCommand(signal, task), "Сигнал отправлен", null);
        });
    }

    private String taskCommand(String action, Telemetry.Task task) {
        return "exec " + binary + " " + action + " " + task.pid + " " + task.start;
    }

    private void enableOverlay() {
        if (overlayStarting || overlayPermissionPending) return;
        if (Settings.canDrawOverlays(this)) {
            startOverlay();
            return;
        }
        overlayPermissionPending = true;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException ex) {
            overlayPermissionPending = false;
            toast("Android не открыл разрешение показа поверх приложений");
        }
    }

    private void startOverlay() {
        if (overlayStarting) return;
        overlayStarting = true;
        ResultReceiver ready = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int result, Bundle data) {
                overlayStarting = false;
                if (isDestroyed() || isFinishing()) return;
                if (result == 1) {
                    stopService(new Intent(MainActivity.this, TelemetryMonitor.class));
                    moveTaskToBack(true);
                } else {
                    toast("Оверлей не запустился. Проверьте разрешение.");
                }
            }
        };
        try {
            startForegroundService(new Intent(this, OverlayService.class).putExtra(OverlayService.ACTIVATION, ready));
        } catch (IllegalStateException | SecurityException ex) {
            overlayStarting = false;
            toast("Android не разрешил запустить оверлей");
        }
    }

    private ColorStateList toggleColors() {
        return new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{WHITE, MUTED});
    }

    private void openSettings() {
        Popup popup = new Popup("Настройки", null, "settings", null);
        String[] minutes = new String[MonitorSettings.BACKGROUND_MINUTES.length];
        for (int i = 0; i < minutes.length; i++) minutes[i] = MonitorSettings.BACKGROUND_MINUTES[i] + " мин";
        settingChoice(popup.body, "Время работы в фоне", minutes, settings.backgroundIndex(), settings::setBackground);

        Switch decimals = new Switch(this);
        decimals.setText("Скрытие десятичных");
        decimals.setTextColor(WHITE);
        decimals.setTextSize(14);
        decimals.setChecked(settings.roundUp());
        decimals.setThumbTintList(toggleColors());
        decimals.setTrackTintList(ColorStateList.valueOf(0xff666666));
        decimals.setPadding(dp(12), 0, dp(12), 0);
        decimals.setMinimumHeight(dp(56));
        decimals.setBackground(tappable(0xff242424, 10));
        decimals.setOnCheckedChangeListener((view, checked) -> {
            settings.setRoundUp(checked);
            updateTable();
            renderCharts(false);
            if (section == 3) renderThermal(false);
        });
        spaced(popup.body, decimals, 8);

        settingChoice(popup.body, "Метод проверки ОЗУ", MonitorSettings.MEMORY_LABELS,
            settings.memoryIndex(), index -> {
                settings.setMemoryMethod(index);
                if (monitor != null) monitor.trackProcessMemory(section == 0);
                if (section == 0) updateTable();
            });
        settingChoice(popup.body, "Частота обновления", MonitorSettings.SPEED_LABELS,
            settings.speedIndex(), settings::setSpeed);
        popup.action("Настройки оверлея", "Показатели, порядок и интервал", "overlay", this::openOverlaySettings);
        popup.action("Проверить обновления", "GitHub Releases", "refresh", () -> {
            popup.dismiss();
            checkForUpdate(true);
        });
        popup.show();
    }

    private void checkForUpdate(boolean manual) {
        if (updatePopup != null) return;
        SharedPreferences updates = getSharedPreferences("updates", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long nextCheck = updates.getLong("next_check", 0);
        if (!manual && nextCheck > now && nextCheck - now <= TimeUnit.HOURS.toMillis(6)) return;
        if (manual && !manualUpdateCheck) toast("Проверяем обновления…");
        manualUpdateCheck |= manual;
        if (checkingUpdate) return;
        checkingUpdate = true;
        updateWorker.execute(() -> {
            try {
                UpdateCheck.Release release = UpdateCheck.fetch(getPackageName());
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                long installed = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                main.post(() -> finishUpdateCheck(updates, release, installed, info.versionName, false));
            } catch (IOException | JSONException | PackageManager.NameNotFoundException ex) {
                main.post(() -> finishUpdateCheck(updates, null, 0, null, true));
            }
        });
    }

    private void finishUpdateCheck(SharedPreferences updates, UpdateCheck.Release release, long installed,
                                   String installedName, boolean failed) {
        boolean manual = manualUpdateCheck;
        checkingUpdate = false;
        manualUpdateCheck = false;
        if (isDestroyed() || isFinishing() || !running) return;
        long now = System.currentTimeMillis();
        long next = now + (failed || release == null ? TimeUnit.MINUTES.toMillis(15) : TimeUnit.HOURS.toMillis(6));
        updates.edit().putLong("next_check", next).apply();
        if (failed || release == null || release.code <= installed) {
            if (manual) {
                toast(failed ? "Не удалось проверить обновления. Попробуйте позже."
                    : release == null ? "В ветке release пока нет данных об обновлениях."
                    : "Установлена актуальная версия Taskscope.");
            }
            return;
        }
        long deferUntil = updates.getLong("defer_until", 0);
        if (!manual && release.code == updates.getLong("deferred_version", 0)
                && deferUntil > now && deferUntil - now <= TimeUnit.DAYS.toMillis(1)) return;
        showUpdate(updates, release, installedName);
    }

    private void showUpdate(SharedPreferences updates, UpdateCheck.Release release, String installedName) {
        Popup popup = new Popup("Доступно обновление", "Taskscope " + release.version, "refresh", null);
        popup.body.addView(text("Установлена версия " + installedName + ". APK новой версии доступен в GitHub Releases.",
            14, SECONDARY, false));
        popup.footer("Позже", "Обновиться", () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES)));
                popup.dismiss();
            } catch (android.content.ActivityNotFoundException ex) {
                toast("Не удалось открыть GitHub.");
            }
        });
        popup.dialog.setOnDismissListener(dialog -> {
            updates.edit().putLong("deferred_version", release.code)
                .putLong("defer_until", System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)).apply();
            updatePopup = null;
        });
        updatePopup = popup;
        popup.show();
    }

    private void settingChoice(LinearLayout body, String title, String[] choices, int current, IntConsumer change) {
        LinearLayout field = card(10);
        field.setBackground(tappable(0xff242424, 10));
        field.setMinimumHeight(dp(56));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.addView(text(title, 13, MUTED, true));
        LinearLayout value = row(true);
        value.setGravity(Gravity.CENTER_VERTICAL);
        TextView selection = text(choices[current], 14, WHITE, true);
        value.addView(selection, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(new Glyph("chevron-down", SECONDARY, dp(16)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        value.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(18)));
        spaced(field, value, 2);
        int[] selected = {current};
        field.setFocusable(true);
        field.setOnClickListener(v -> {
            PopupWindow dropdown = new PopupWindow(this);
            LinearLayout options = card(13);
            options.setBackground(outlined(0xff272727, 13));
            options.setPadding(dp(6), dp(6), dp(6), dp(6));
            for (int i = 0; i < choices.length; i++) {
                int index = i;
                TextView choice = actionButton(choices[i], i == selected[0] ? "check" : null, false, () -> {
                    selected[0] = index;
                    change.accept(index);
                    selection.setText(choices[index]);
                    dropdown.dismiss();
                });
                choice.setSelected(i == selected[0]);
                choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                choice.setBackground(tappable(i == selected[0] ? 0xff373737 : 0xff272727, 8));
                spaced(options, choice, i == 0 ? 0 : 2);
            }
            dropdown.setContentView(options);
            dropdown.setWidth(field.getWidth());
            dropdown.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            dropdown.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dropdown.setElevation(dp(12));
            dropdown.setFocusable(true);
            dropdown.setOutsideTouchable(true);
            dropdown.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            dropdown.setAnimationStyle(ValueAnimator.areAnimatorsEnabled() ? R.style.TaskscopeWindowMotion : 0);
            dropdown.showAsDropDown(field, 0, dp(4));
        });
        spaced(body, field, body.getChildCount() == 0 ? 0 : 8);
    }

    private void openOverlaySettings() {
        OverlayOptions options = new OverlayOptions(this);
        Popup popup = new Popup("Настройки оверлея", null, "overlay", null);
        settingChoice(popup.body, "Интервал обновления", OverlayOptions.INTERVAL_NAMES,
            options.intervalIndex(), options::setInterval);
        popup.group("Показатели");
        LinearLayout metrics = row(false);
        spaced(popup.body, metrics, 7);
        overlayOptionRows(options, metrics);
        popup.show();
    }

    private void overlayOptionRows(OverlayOptions options, LinearLayout list) {
        list.removeAllViews();
        List<OverlayMetric> order = options.order();
        for (int index = 0; index < order.size(); index++) {
            OverlayMetric metric = order.get(index);
            LinearLayout item = row(true);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackground(outlined(PANEL, 9));
            item.setPadding(dp(8), 0, dp(2), 0);
            CheckBox choice = new CheckBox(this);
            choice.setText(metric.label);
            choice.setTextColor(WHITE);
            choice.setTextSize(12);
            choice.setMinHeight(dp(40));
            choice.setBackground(null);
            choice.setButtonDrawable(new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
                filterCheckIcon(), surface(WHITE, 6)));
            choice.setPaddingRelative(dp(8), 0, 0, 0);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setChecked(options.enabled(metric));
            choice.setOnCheckedChangeListener((view, enabled) -> options.setEnabled(metric, enabled));
            item.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));

            ImageButton up = overlayOrderButton("chevron-up", "Выше: " + metric.label, index > 0,
                () -> { options.move(metric, -1); overlayOptionRows(options, list); });
            ImageButton down = overlayOrderButton("chevron-down", "Ниже: " + metric.label, index + 1 < order.size(),
                () -> { options.move(metric, 1); overlayOptionRows(options, list); });
            item.addView(up, new LinearLayout.LayoutParams(dp(34), dp(40)));
            item.addView(down, new LinearLayout.LayoutParams(dp(34), dp(40)));
            spaced(list, item, index == 0 ? 0 : 5);
        }
    }

    private ImageButton overlayOrderButton(String glyph, String description, boolean enabled, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(new Glyph(glyph, enabled ? WHITE : LINE, dp(14)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff), null, surface(WHITE, 7)));
        button.setContentDescription(description);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    interface ResultHandler { void accept(Root.Result result); }

    private void execute(String command, ResultHandler handler) {
        try {
            worker.execute(() -> {
                Root.Result value;
                try {
                    value = Root.run(command, 25);
                } catch (Exception ex) {
                    value = new Root.Result(1, ex.toString());
                }
                Root.Result captured = value;
                main.post(() -> { if (!isDestroyed() && !isFinishing()) handler.accept(captured); });
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private void command(String command, String success, Runnable after) {
        execute(command, value -> {
            if (value.code != 0) showText("Действие отклонено", value);
            else {
                toast(success);
                if (after != null) after.run();
            }
        });
    }

    private void confirm(String title, String message, Runnable action) {
        Popup popup = new Popup(title, null, "details", null);
        popup.body.addView(text(message, 14, SECONDARY, false));
        popup.footer("Подтвердить", () -> {
            popup.dismiss();
            action.run();
        });
        popup.show();
    }

    private void showText(String title, Root.Result result) {
        Popup popup = new Popup(title, null, "details", null);
        fillOutput(popup, result);
        popup.show();
    }

    private void fillOutput(Popup popup, Root.Result result) {
        String output = result.output.trim();
        if (monitor != null && TelemetryMonitor.ROOT_REQUIRED.equals(monitor.failure)) {
            output = TelemetryMonitor.ROOT_REQUIRED;
        }
        if (output.isEmpty()) {
            AnimatedValue code = number(13, WHITE, false);
            code.show("Вывод пуст  ·  код " + result.code);
            popup.body.addView(code);
        } else {
            TextView body = text(output, 12, WHITE, false);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setPadding(dp(12), dp(10), dp(12), dp(10));
            body.setBackground(outlined(0xff101010, 10));
            popup.body.addView(body, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private static void updateText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private static String plural(int amount, String one, String few, String many) {
        int lastTwo = amount % 100, last = amount % 10;
        if (lastTwo >= 11 && lastTwo <= 14) return many;
        if (last == 1) return one;
        return last >= 2 && last <= 4 ? few : many;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout row(boolean horizontal) {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        return result;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable outlined(int color, int radius) {
        GradientDrawable shape = surface(color, radius);
        shape.setStroke(dp(1), LINE);
        return shape;
    }

    private void addGlyph(TextView label, String name, int color, int size) {
        label.setCompoundDrawablePadding(dp(6));
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(new Glyph(name, color, dp(size)), null, null, null);
    }

    private TextView button(String label, int color, Runnable click) {
        TextView result = text(label, 13, color, true);
        result.setGravity(Gravity.CENTER);
        result.setMinHeight(dp(42));
        result.setPadding(dp(10), 0, dp(10), 0);
        result.setBackground(new RippleDrawable(ColorStateList.valueOf(0x336f6f6f), null, surface(WHITE, 11)));
        result.setOnClickListener(v -> click.run());
        return result;
    }

    private EditText field(String hint) {
        EditText result = new EditText(this);
        result.setTextColor(WHITE);
        result.setTextSize(14);
        result.setHintTextColor(MUTED);
        result.setHint(hint);
        result.setPadding(dp(10), dp(7), dp(10), dp(7));
        result.setBackground(surface(PANEL, 9));
        return result;
    }

    private AnimatedValue number(float size, int color, boolean bold) {
        return new AnimatedValue(this, size, color, bold);
    }

    private AnimatedValue temperaturePill(int size) {
        AnimatedValue value = number(size, WHITE, true);
        value.setPadding(dp(4), dp(1), dp(4), dp(1));
        value.setBackground(outlined(0xff242424, 6));
        return value;
    }

    private LinearLayout card(int radius) {
        LinearLayout box = row(false);
        box.setBackground(outlined(PANEL, radius));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        return box;
    }

    private void spaced(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        parent.addView(view, size);
    }

    private RippleDrawable tappable(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x307f7f7f), outlined(color, radius), surface(WHITE, radius));
    }

    private TextView actionButton(String title, String glyph, boolean primary, Runnable action) {
        TextView view = button(title, primary ? BACK : WHITE, action);
        if (glyph != null) addGlyph(view, glyph, primary ? BACK : WHITE, 16);
        view.setBackground(tappable(primary ? WHITE : 0xff242424, 9));
        view.setMinHeight(dp(44));
        view.setFocusable(true);
        return view;
    }

    private ProgressBar progress() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        bar.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        bar.setContentDescription("Загрузка");
        return bar;
    }

    private String percent(double value) {
        return !Double.isFinite(value) || value < 0 ? "н/д"
            : formatted(value, value > 0 && value < 10 ? "%.1f" : "%.0f") + "%";
    }

    private String temperature(double value) {
        return Double.isFinite(value) ? formatted(value, "%.1f") + " °C" : "н/д";
    }

    private String frequency(long hz) {
        return hz > 0 ? formatted(hz / 1_000_000.0, "%.0f") + " МГц" : "н/д";
    }

    private String storage(long kb) {
        if (kb < 0) return "н/д";
        if (kb >= 1024 * 1024) return formatted(kb / 1048576.0, "%.1f") + " ГиБ";
        if (kb >= 1024) return formatted(kb / 1024.0, "%.0f") + " МиБ";
        return kb + " КиБ";
    }

    private String formatted(double value, String precise) {
        return settings.roundUp() ? Long.toString((long)Math.ceil(value)) : String.format(Locale.ROOT, precise, value);
    }

    private LinearLayout columns() {
        LinearLayout line = row(true);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(8), 0, dp(4), 0);
        return line;
    }

    private void setColumn(LinearLayout line, View cell, int index) {
        int[] widths = {0, 63, 77};
        line.addView(cell, new LinearLayout.LayoutParams(dp(widths[index]), -1, index == 0 ? 1 : 0));
    }

    private final class Tab extends LinearLayout {
        final String glyph;
        final ImageView picture = new ImageView(MainActivity.this);
        final TextView caption;

        Tab(String title, String glyph, Runnable click) {
            super(MainActivity.this);
            this.glyph = glyph;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setContentDescription(title);
            setFocusable(true);
            setOnClickListener(view -> click.run());
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(picture, new LinearLayout.LayoutParams(dp(16), dp(16)));
            caption = text(title, 13, MUTED, true);
            caption.setMaxLines(1);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams captionSize = new LinearLayout.LayoutParams(-2, -2);
            captionSize.leftMargin = dp(6);
            addView(caption, captionSize);
        }

        @Override protected void onMeasure(int width, int height) {
            boolean compact = View.MeasureSpec.getSize(width) < dp(102);
            int orientation = compact ? VERTICAL : HORIZONTAL;
            if (getOrientation() != orientation) {
                setOrientation(orientation);
                LinearLayout.LayoutParams size = (LinearLayout.LayoutParams)caption.getLayoutParams();
                size.leftMargin = compact ? 0 : dp(6);
                size.topMargin = compact ? dp(3) : 0;
            }
            super.onMeasure(width, height);
        }

        void update(boolean active) {
            int color = active ? WHITE : MUTED;
            caption.setTextColor(color);
            picture.setImageDrawable(new Glyph(glyph, color, dp(16)));
            setSelected(active);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),
                active ? outlined(PANEL, 12) : null, surface(WHITE, 12)));
        }
    }

    private final class TaskAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Telemetry.Task getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return getItem(position).pid; }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TaskRow line = recycled instanceof TaskRow ? (TaskRow)recycled : new TaskRow();
            line.bind(getItem(position));
            return line;
        }
    }

    private final class TaskRow extends LinearLayout {
        final ImageView icon = new ImageView(MainActivity.this);
        final Glyph system = new Glyph("services", MUTED, dp(18));
        final Glyph terminal = new Glyph("terminal", MUTED, dp(18));
        final TextView name = text("", 14, WHITE, true);
        final TextView meta = text("", 10, MUTED, false);
        final AnimatedValue cpu = number(13, PRIMARY, true), memory = number(12, WHITE, false);
        Telemetry.Task task;
        private boolean hasAppIcon;

        TaskRow() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), 0, dp(4), 0);
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(56)));
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x26ffffff), null, surface(WHITE, 6)));
            LinearLayout pair = row(false);
            pair.setGravity(Gravity.CENTER_VERTICAL);
            pair.setPadding(0, 0, dp(6), 0);
            for (TextView line : new TextView[]{name, meta}) {
                line.setSingleLine(true);
                line.setEllipsize(TextUtils.TruncateAt.END);
                pair.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout lead = row(true);
            lead.setGravity(Gravity.CENTER_VERTICAL);
            icon.setBackground(surface(PANEL, 8));
            LinearLayout.LayoutParams iconCell = new LinearLayout.LayoutParams(dp(31), dp(31));
            iconCell.rightMargin = dp(8);
            lead.addView(icon, iconCell);
            lead.addView(pair, new LinearLayout.LayoutParams(0, -1, 1));
            setColumn(this, lead, 0);
            AnimatedValue[] cells = {cpu, memory};
            for (int i = 0; i < cells.length; i++) {
                cells[i].setValueGravity(Gravity.CENTER);
                setColumn(this, cells[i], i + 1);
            }
        }

        void bind(Telemetry.Task next) {
            boolean same = next.isSame(task);
            boolean appIcon = labels.containsKey(next.packageCandidate());
            if (!same || appIcon != hasAppIcon) {
                android.graphics.Bitmap art = appIcon ? icons.get(next.packageCandidate()) : null;
                if (art == null) icon.setImageDrawable(next.kernel() ? system : terminal);
                else icon.setImageBitmap(art);
                int inset = dp(art == null ? 5 : 3);
                icon.setPadding(inset, inset, inset, inset);
                hasAppIcon = appIcon;
            }
            updateText(name, label(next));
            updateText(meta, next.state + "  ·  " + (next.kernel() ? "ядро  ·  " : "") + next.name);
            String processor = next.cpu >= 0 ? percent(next.cpu) : same && task.cpu >= 0 ? percent(task.cpu) : "н/д";
            cpu.bind(processor, same);
            memory.bind(storage(ProcessMemory.value(next, settings.memoryMethod(), memorySamples)), same);
            task = next;
        }
    }

    private final class Metric extends LinearLayout {
        final AnimatedValue value, temperature;
        final GraphView plot;
        final String key;

        Metric(String name, int color, String key, boolean withTemperature, boolean thermal) {
            super(MainActivity.this);
            this.key = key;
            setOrientation(VERTICAL);
            setBackground(outlined(PANEL, 14));
            setPadding(dp(9), dp(7), dp(9), dp(7));
            LinearLayout top = row(true);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(name, 11, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
            temperature = withTemperature ? temperaturePill(10) : null;
            if (withTemperature) top.addView(temperature);
            addView(top);
            value = number(thermal ? 19 : 23, color, true);
            value.setValueAutoSize(12, thermal ? 19 : 23, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
            addView(value, new LinearLayout.LayoutParams(-1, dp(26)));
            plot = new GraphView(MainActivity.this, color, 32, false, thermal ? 15 : 0, thermal ? 105 : 100, false);
            addView(plot, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        void update(String amount, boolean animate) {
            value.show(amount);
            plot.show(history == null ? null : history.get(key), animate);
        }
    }

    private final class Chart extends LinearLayout {
        final AnimatedValue value, caption, extra, temperature;
        final GraphView plot;
        final String key;

        Chart(String name, int color, String key, boolean withTemperature) {
            super(MainActivity.this);
            this.key = key;
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(11), dp(14), dp(10));
            setBackground(outlined(PANEL, 14));
            LinearLayout top = row(true);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(name, 15, WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
            value = number(22, color, true);
            value.setValueGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            top.addView(value);
            addView(top);
            LinearLayout context = row(true);
            context.setGravity(Gravity.CENTER_VERTICAL);
            caption = number(12, MUTED, false);
            caption.show("");
            context.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            temperature = withTemperature ? temperaturePill(12) : null;
            if (withTemperature) {
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
                size.leftMargin = dp(8);
                context.addView(temperature, size);
            }
            spaced(this, context, 4);
            extra = number(11, MUTED, false);
            spaced(this, extra, 1);
            plot = new GraphView(MainActivity.this, color, SampleHistory.CAPACITY, true, 0, 100, true);
            LinearLayout.LayoutParams plotSize = new LinearLayout.LayoutParams(-1, dp(92));
            plotSize.topMargin = dp(8);
            addView(plot, plotSize);
            LinearLayout legend = row(true);
            legend.addView(text("60 замеров", 10, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            legend.addView(text("Сейчас", 10, MUTED, false));
            spaced(this, legend, 2);
        }

        void update(String amount, boolean animate) {
            value.show(amount);
            plot.show(history == null ? null : history.get(key), animate);
        }
    }

    private final class CoreCard extends LinearLayout {
        final AnimatedValue value;
        final GraphView plot;
        final TextView title;
        private boolean powerful;

        CoreCard(String key) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(10), dp(7), dp(10), dp(7));
            setBackground(outlined(PANEL, 11));
            LinearLayout top = row(true);
            top.setGravity(Gravity.CENTER_VERTICAL);
            title = text("Ядро " + key.substring(3), 11, MUTED, true);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            value = number(14, PRIMARY, true);
            value.setValueGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            top.addView(value);
            addView(top);
            plot = new GraphView(MainActivity.this, SECONDARY, 36, false, 0, 100, true);
            addView(plot, new LinearLayout.LayoutParams(-1, dp(42)));
        }

        void highPerformance(boolean enabled) {
            if (powerful == enabled) return;
            powerful = enabled;
            if (enabled) {
                addGlyph(title, "speedometer", PRIMARY, 13);
                title.setContentDescription(title.getText() + ", производительное");
            } else {
                title.setCompoundDrawablesRelative(null, null, null, null);
                title.setContentDescription(null);
            }
        }
    }

    private final class FrequencyCard {
        final LinearLayout pane = card(10);
        final AnimatedValue title = number(12, MUTED, true);
        final AnimatedValue current = number(17, WHITE, true);
        final AnimatedValue limits = number(12, SECONDARY, false);
        final TextView source = text("", 11, MUTED, false);

        FrequencyCard(String name) {
            LinearLayout head = row(true);
            head.setGravity(Gravity.CENTER_VERTICAL);
            title.show(name);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            current.setValueGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            head.addView(current);
            pane.addView(head);
            spaced(pane, limits, 4);
            spaced(pane, source, 3);
            source.setVisibility(View.GONE);
        }

        void update(long value, long limit, long maximum, String detail) {
            current.show(frequency(value));
            boolean capped = limit > 0 && maximum > limit;
            if (limit > 0 && maximum > 0) {
                limits.show("Лимит " + frequency(limit) + "  /  Макс. " + frequency(maximum));
            } else if (limit > 0 || maximum > 0) {
                limits.show(limit > 0 ? "Лимит " + frequency(limit) : "Макс. " + frequency(maximum));
            } else {
                limits.show("Лимит недоступен");
            }
            limits.setValueColor(capped ? WHITE : SECONDARY);
            source.setVisibility(detail == null ? View.GONE : View.VISIBLE);
            if (detail != null) updateText(source, detail);
        }
    }

    private final class Popup {
        final Dialog dialog = new Dialog(MainActivity.this);
        final LinearLayout panel = row(false), body = row(false), footer = row(true);
        final FrameLayout content = new FrameLayout(MainActivity.this);

        Popup(String title, String subtitle, String glyph, android.graphics.Bitmap bitmap) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(true);
            panel.setBackground(outlined(0xff1b1b1b, 22));
            panel.setPadding(dp(15), dp(13), dp(15), dp(14));
            LinearLayout head = row(true);
            head.setGravity(Gravity.CENTER_VERTICAL);
            ImageView symbol = new ImageView(MainActivity.this);
            symbol.setPadding(dp(9), dp(9), dp(9), dp(9));
            symbol.setBackground(outlined(0xff242424, 11));
            if (bitmap == null) symbol.setImageDrawable(new Glyph(glyph, WHITE, dp(20)));
            else symbol.setImageBitmap(bitmap);
            symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams symbolSize = new LinearLayout.LayoutParams(dp(38), dp(38));
            symbolSize.rightMargin = dp(9);
            head.addView(symbol, symbolSize);
            LinearLayout heading = row(false);
            TextView name = text(title, 19, WHITE, true);
            name.setMaxLines(3);
            name.setEllipsize(TextUtils.TruncateAt.END);
            if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
            heading.addView(name);
            if (subtitle != null) spaced(heading, text(subtitle, 12, MUTED, false), 2);
            head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            TextView close = button("", WHITE, this::dismiss);
            close.setMinHeight(0);
            close.setPadding(dp(11), dp(11), dp(11), dp(11));
            addGlyph(close, "close", MUTED, 16);
            close.setBackground(tappable(0xff242424, 10));
            close.setContentDescription("Закрыть");
            LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(dp(38), dp(38));
            closeSize.leftMargin = dp(7);
            head.addView(close, closeSize);
            panel.addView(head);

            ScrollView scroll = new ScrollView(MainActivity.this) {
                @Override protected void onMeasure(int width, int height) {
                    super.onMeasure(width, limitPopupHeight(height));
                }
            };
            scroll.setClipToPadding(false);
            scroll.addView(body);
            content.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
            spaced(panel, content, 13);
            footer.setVisibility(View.GONE);
            spaced(panel, footer, 12);
            dialog.setContentView(panel);
        }

        void setContent(View view) {
            content.removeAllViews();
            int height = View.MeasureSpec.getSize(limitPopupHeight(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)));
            content.addView(view, new FrameLayout.LayoutParams(-1, Math.min(height, dp(420))));
        }

        void group(String name) {
            TextView label = text(name, 12, MUTED, true);
            if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
            spaced(body, label, 14);
        }

        void action(String title, String subtitle, String glyph, Runnable click) {
            LinearLayout item = row(true);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(dp(48));
            item.setPadding(dp(11), dp(7), dp(9), dp(7));
            item.setBackground(tappable(0xff242424, 10));
            item.setOnClickListener(v -> click.run());
            item.setFocusable(true);
            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageDrawable(new Glyph(glyph, PRIMARY, dp(18)));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));
            LinearLayout labels = row(false);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.setMargins(dp(10), 0, dp(4), 0);
            labels.addView(text(title, 14, WHITE, true));
            if (subtitle != null) spaced(labels, text(subtitle, 11, MUTED, false), 2);
            item.addView(labels, size);
            ImageView arrow = new ImageView(MainActivity.this);
            arrow.setImageDrawable(new Glyph("chevron", MUTED, dp(15)));
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
            spaced(body, item, body.getChildCount() == 0 ? 0 : 6);
        }

        void footer(String label, Runnable action) {
            footer("Отмена", label, action);
        }

        void footer(String cancel, String label, Runnable action) {
            footer.setVisibility(View.VISIBLE);
            footer.addView(actionButton(cancel, null, false, this::dismiss), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1.25f);
            size.leftMargin = dp(8);
            footer.addView(actionButton(label, null, true, action), size);
        }

        void show() {
            Window window = dialog.getWindow();
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setWindowAnimations(ValueAnimator.areAnimatorsEnabled() ? R.style.TaskscopeWindowMotion : 0);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.57f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.show();
            int width = getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(dp(460), Math.max(dp(180), width - dp(28))), -2);
        }

        void dismiss() { dialog.dismiss(); }
    }

    private int limitPopupHeight(int constraint) {
        int screen = getResources().getDisplayMetrics().heightPixels;
        int height = Math.max(dp(86), screen - dp(landscape ? 127 : 185));
        int mode = View.MeasureSpec.getMode(constraint);
        if (mode != View.MeasureSpec.UNSPECIFIED) height = Math.min(height, View.MeasureSpec.getSize(constraint));
        return View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST);
    }
}
