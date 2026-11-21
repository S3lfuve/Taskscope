package local.taskscope.manager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

final class OverlayWindow {
    private static final int PANEL = 0xec151515, WHITE = 0xfff0f0f0, MUTED = 0xffb0b0b0, LINE = 0xff363636;
    private static final int BUBBLE = 0xff090909;
    private static final PathInterpolator MOTION = new PathInterpolator(.2f, 0, 0, 1);
    private static final PathInterpolator MORPH = new PathInterpolator(.35f, 0, .2f, 1);
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();
    private final Context context;
    private final WindowManager manager;
    private final Display display;
    private final OverlayOptions options;
    private final Runnable changed, close;
    private final MorphSurface surface;
    private final WindowManager.LayoutParams placement;
    private final EnumMap<OverlayMetric, Row> rows = new EnumMap<>(OverlayMetric.class);
    private final List<OverlayMetric> visible = new ArrayList<>();
    private final Rect safe = new Rect();
    private final Rect resting = new Rect();
    private ValueAnimator transition;
    private Reveal reveal;
    private ImageButton collapseButton, closeButton;
    private ViewTreeObserver.OnPreDrawListener pendingStart;
    private final Runnable finishClose = this::completeDismissal;
    private boolean attached, expanded, closing, landscape;
    private int screenWidth, screenHeight;

    OverlayWindow(Context context, Display display, OverlayOptions options, Runnable changed, Runnable close) {
        this.context = context;
        this.display = display;
        this.options = options;
        this.changed = changed;
        this.close = close;
        manager = context.getSystemService(WindowManager.class);
        surface = new MorphSurface(context);
        placement = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        placement.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 34) placement.setCanPlayMoveAnimation(false);
        if (Build.VERSION.SDK_INT >= 30) {
            placement.setFitInsetsTypes(0);
            placement.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        placement.setTitle("Taskscope · Оверлей");
    }

    void attach() {
        readBounds();
        build(-1, -1);
        manager.addView(surface, placement);
        attached = true;
    }

    boolean expanded() { return (expanded || reveal != null) && !closing; }

    List<OverlayMetric> visible() { return new ArrayList<>(visible); }

    void refreshLayout() {
        if (!attached) return;
        Rect previous = new Rect(safe);
        boolean wasLandscape = landscape;
        readBounds();
        if (previous.equals(safe) && wasLandscape == landscape) return;
        cancelTransition();
        if (closing) {
            close.run();
            return;
        }
        build(-1, -1);
        changed.run();
    }

    void refreshMetrics() {
        if (!expanded()) return;
        cancelTransition();
        build(resting.left, resting.top);
        changed.run();
    }

    private void readBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = manager.getMaximumWindowMetrics();
            screenWidth = metrics.getBounds().width();
            screenHeight = metrics.getBounds().height();
            int protectedEdges = WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout();
            if (screenWidth <= screenHeight) protectedEdges |= WindowInsets.Type.systemBars();
            Insets edge = metrics.getWindowInsets().getInsetsIgnoringVisibility(protectedEdges);
            safe.set(0, 0, screenWidth, screenHeight);
            safe.inset(edge.left, edge.top, edge.right, edge.bottom);
        } else {
            Point raw = new Point(), usable = new Point();
            display.getRealSize(raw);
            display.getSize(usable);
            screenWidth = raw.x;
            screenHeight = raw.y;
            safe.set(0, raw.x > raw.y ? 0 : dp(24), Math.min(raw.x, usable.x), Math.min(raw.y, usable.y));
        }
        landscape = screenWidth > screenHeight;
        int margin = dp(4);
        if (safe.width() > margin * 2 && safe.height() > margin * 2) safe.inset(margin, margin);
    }

    private void build(int x, int y) {
        reveal = null;
        stopRows();
        View content = expanded ? panel() : bubble();
        showOnly(content, preferredBounds(content, x, y));
    }

    private Rect preferredBounds(View content, int x, int y) {
        int width = content.getLayoutParams().width;
        int height = content.getLayoutParams().height;
        String prefix = positionKey();
        SharedPreferences prefs = options.store();
        float savedX = prefs.getFloat(prefix + "_x", -1), savedY = prefs.getFloat(prefix + "_y", -1);
        int originX = x >= 0 && savedX < 0 ? x :
            safe.left + Math.round(Math.max(0, safe.width() - width) * (savedX < 0 ? .99f : savedX));
        int originY = y >= 0 && savedY < 0 ? y :
            safe.top + Math.round(Math.max(0, safe.height() - height) * (savedY < 0 ? .19f : savedY));
        Rect bounds = new Rect(0, 0, width, height);
        moveInsideSafeArea(bounds, originX, originY);
        return bounds;
    }

    private void showOnly(View content, Rect bounds) {
        for (int i = surface.getChildCount() - 1; i >= 0; i--) {
            if (surface.getChildAt(i) != content) surface.removeViewAt(i);
        }
        content.setAlpha(1);
        content.setTranslationX(0);
        content.setTranslationY(0);
        positionChild(content, bounds, bounds);
        resting.set(bounds);
        surface.clearMorph();
        moveWindow(bounds);
    }

    private void positionChild(View content, Rect bounds, Rect window) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(bounds.width(), bounds.height(),
            Gravity.TOP | Gravity.LEFT);
        params.leftMargin = bounds.left - window.left;
        params.topMargin = bounds.top - window.top;
        if (content.getParent() == surface) content.setLayoutParams(params);
        else surface.addView(content, params);
    }

    private View bubble() {
        int size = Math.max(1, Math.min(dp(46), Math.min(safe.width(), safe.height())));
        ImageButton button = new ImageButton(context);
        button.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        button.setBackground(null);
        button.setImageDrawable(new Glyph("pulse", WHITE, dp(23)));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription("Раскрыть оверлей Taskscope");
        button.setOnClickListener(view -> toggle());
        bindDrag(button, true);
        return button;
    }

    private View panel() {
        int width = Math.max(1, Math.min(dp(landscape ? 184 : 190), safe.width()));
        int heightLimit = (int)Math.min(safe.height(), ((long)screenWidth * screenHeight) / (4L * width));
        if (heightLimit < dp(52) && width > 1) {
            width = Math.max(1, Math.min(width, (int)(((long)screenWidth * screenHeight) / (4L * Math.max(1, dp(52))))));
            heightLimit = (int)Math.min(safe.height(), ((long)screenWidth * screenHeight) / (4L * width));
        }
        LinearLayout table = new LinearLayout(context);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setPadding(dp(8), dp(3), dp(5), dp(4));

        int headerHeight = headerHeight();
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView grip = label("Taskscope", 13, WHITE);
        grip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        grip.setContentDescription("Taskscope, область перемещения");
        header.addView(grip, new LinearLayout.LayoutParams(0, -1, 1));
        bindDrag(grip, false);
        collapseButton = control("chevron-left", "Свернуть оверлей", this::toggle);
        closeButton = control("close", "Закрыть оверлей", this::dismiss);
        header.addView(collapseButton, new LinearLayout.LayoutParams(dp(34), -1));
        header.addView(closeButton, new LinearLayout.LayoutParams(dp(34), -1));
        table.addView(header, new LinearLayout.LayoutParams(-1, headerHeight));
        int height = headerHeight + table.getPaddingTop() + table.getPaddingBottom();
        List<OverlayMetric> chosen = options.enabled();
        int footerHeight = Math.max(dp(20), (int)(12 * context.getResources().getDisplayMetrics().scaledDensity * 1.35f));
        for (int i = 0; i < chosen.size(); i++) {
            OverlayMetric metric = chosen.get(i);
            int rowHeight = metricHeight(metric);
            int reserve = i < chosen.size() - 1 ? footerHeight : 0;
            if (height + rowHeight + reserve > heightLimit) break;
            Row row = new Row(metric);
            rows.put(metric, row);
            visible.add(metric);
            table.addView(row.view, new LinearLayout.LayoutParams(-1, rowHeight));
            height += rowHeight;
        }
        if (chosen.isEmpty() || visible.size() < chosen.size()) {
            String message = chosen.isEmpty() ? "Выберите показатели в настройках"
                : "Ещё " + (chosen.size() - visible.size()) + " за пределами панели";
            if (height + footerHeight <= heightLimit) {
                TextView more = label(message, 10, MUTED);
                table.addView(more, new LinearLayout.LayoutParams(-1, footerHeight));
                height += footerHeight;
            }
        }
        table.setLayoutParams(new FrameLayout.LayoutParams(width, Math.max(1, Math.min(heightLimit, height))));
        return table;
    }

    private int headerHeight() { return dp(landscape ? 34 : 36); }

    private int metricHeight(OverlayMetric metric) {
        float scaled = context.getResources().getDisplayMetrics().scaledDensity;
        int line = Math.max(dp(landscape ? 17 : 19), (int)Math.ceil(12 * scaled * 1.35));
        return metric.multiline ? line * (metric == OverlayMetric.CPU_FREQ ? 4 : 3) : line;
    }

    private final class Row {
        final LinearLayout view = new LinearLayout(context);
        final AnimatedValue reading;
        private ValueAnimator colorAnimation;
        private int shown = OverlayMetric.BASE_COLOR, target = shown;

        Row(OverlayMetric metric) {
            view.setOrientation(LinearLayout.VERTICAL);
            View separator = new View(context);
            separator.setBackgroundColor(LINE);
            view.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
            LinearLayout contents = new LinearLayout(context);
            contents.setGravity(Gravity.CENTER_VERTICAL);
            contents.setOrientation(metric.multiline ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            contents.setPadding(0, 0, dp(3), 0);
            view.addView(contents, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView name = label(metric.label, 11, MUTED);
            if (metric.multiline) {
                contents.addView(name, new LinearLayout.LayoutParams(-1, -2));
                reading = null;
                detail = label(OverlayMetric.NA, 11, WHITE);
                detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                detail.setMaxLines(metric == OverlayMetric.CPU_FREQ ? 3 : 2);
                detail.setSingleLine(false);
                detail.setFontFeatureSettings("tnum");
                contents.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            } else {
                LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -1, 1);
                labelSize.setMarginEnd(dp(4));
                contents.addView(name, labelSize);
                int textSize = landscape ? 11 : 12;
                reading = new AnimatedValue(context, textSize, shown, true);
                reading.setValueGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                reading.setValueAutoSize(8, textSize, 1, TypedValue.COMPLEX_UNIT_SP);
                reading.bind(OverlayMetric.NA, false);
                contents.addView(reading, new LinearLayout.LayoutParams(dp(metric == OverlayMetric.RAM_USED ? 111 : 59), -1));
                detail = null;
            }
        }

        final TextView detail;

        void update(String value, int color) {
            if (detail != null) {
                if (!TextUtils.equals(detail.getText(), value)) detail.setText(value);
                return;
            }
            reading.show(value);
            if (target == color) return;
            target = color;
            if (colorAnimation != null) colorAnimation.cancel();
            if (!ValueAnimator.areAnimatorsEnabled()) {
                shown = color;
                reading.setValueColor(color);
                return;
            }
            colorAnimation = ValueAnimator.ofObject(COLORS, shown, color);
            colorAnimation.setInterpolator(MOTION);
            colorAnimation.setDuration(150);
            colorAnimation.addUpdateListener(animation -> {
                shown = (int)animation.getAnimatedValue();
                reading.setValueColor(shown);
            });
            colorAnimation.start();
        }

        void stop() { if (colorAnimation != null) colorAnimation.cancel(); }
    }

    void show(OverlayMetric.Values values) {
        if (!expanded()) return;
        for (java.util.Map.Entry<OverlayMetric, Row> entry : rows.entrySet()) {
            OverlayMetric metric = entry.getKey();
            entry.getValue().update(metric.value(values), metric.color(values));
        }
    }

    private void toggle() {
        if (closing) return;
        if (reveal != null) {
            expanded = !expanded;
            playReveal(!transition.isStarted());
            return;
        }

        savePosition();
        expanded = !expanded;
        View current = surface.getChildAt(0);
        View target = expanded ? panel() : bubble();
        Rect end = preferredBounds(target, resting.left, resting.top);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            if (!expanded) stopRows();
            showOnly(target, end);
            changed.run();
            return;
        }

        reveal = expanded ? new Reveal(current, resting, target, end, 0)
            : new Reveal(target, end, current, resting, 1);
        moveWindow(reveal.window);
        playReveal(true);
        if (expanded) changed.run();
    }

    private void playReveal(boolean afterLayout) {
        cancelTransition();
        Reveal active = reveal;
        float start = active.progress;
        float end = expanded ? 1 : 0;
        ValueAnimator animator = start == end
            ? ValueAnimator.ofFloat(start, end == 0 ? .035f : .965f, end)
            : ValueAnimator.ofFloat(start, end);
        long duration = Math.max(105, Math.round(Math.abs(end - start) * (expanded ? 300 : 290)));
        animator.addUpdateListener(frame -> active.draw((float)frame.getAnimatedValue()));
        play(animator, duration, afterLayout, () -> {
            reveal = null;
            if (expanded) {
                showOnly(active.panel, active.panelBounds);
            } else {
                stopRows();
                showOnly(active.bubble, active.bubbleBounds);
                changed.run();
            }
        });
    }

    private final class Reveal {
        final View bubble, panel;
        final Rect bubbleBounds, panelBounds, window;
        final RectF bubbleShape, panelShape;
        float progress;

        Reveal(View bubble, Rect bubbleBounds, View panel, Rect panelBounds, float progress) {
            this.bubble = bubble;
            this.panel = panel;
            this.bubbleBounds = new Rect(bubbleBounds);
            this.panelBounds = new Rect(panelBounds);
            this.progress = progress;
            window = new Rect(bubbleBounds);
            window.union(panelBounds);
            bubbleShape = new RectF(bubbleBounds);
            bubbleShape.offset(-window.left, -window.top);
            panelShape = new RectF(panelBounds);
            panelShape.offset(-window.left, -window.top);
            positionChild(panel, panelBounds, window);
            positionChild(bubble, bubbleBounds, window);
        }

        void draw(float fraction) {
            progress = fraction;
            surface.morph(bubbleShape, panelShape, fraction, between(bubbleShape.width() / 2f, dp(12), fraction),
                (int)COLORS.evaluate(fraction, BUBBLE, PANEL), 1);
            bubble.setAlpha(1 - fadeBetween(fraction, 0, .45f));
            panel.setAlpha(fadeBetween(fraction, .18f, .9f));
            bubble.setTranslationX((panelShape.centerX() - bubbleShape.centerX()) * fraction);
            bubble.setTranslationY((panelShape.centerY() - bubbleShape.centerY()) * fraction);
        }
    }

    private void dismiss() {
        if ((!expanded && reveal == null) || closing) return;
        if (reveal == null) savePosition();
        cancelTransition();
        closing = true;
        changed.run();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishDismissalAnimation();
            return;
        }

        Reveal active = reveal;
        View panel = active == null ? surface.getChildAt(0) : active.panel;
        View pulse = active == null ? null : active.bubble;
        RectF start = active == null ? new RectF(0, 0, resting.width(), resting.height()) : new RectF(surface.mask);
        RectF end = new RectF(start);
        end.inset(Math.min(dp(6), end.width() / 4), Math.min(dp(4), end.height() / 4));
        float radius = active == null ? dp(12) : surface.radius;
        int color = active == null ? PANEL : surface.color;
        float panelAlpha = panel.getAlpha();
        float pulseAlpha = pulse == null ? 0 : pulse.getAlpha();
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.addUpdateListener(frame -> {
            float fraction = (float)frame.getAnimatedValue();
            float alpha = 1 - fadeBetween(fraction, 0, 1);
            surface.morph(start, end, fraction, radius, color, alpha);
            panel.setAlpha(panelAlpha * alpha);
            if (pulse != null) pulse.setAlpha(pulseAlpha * alpha);
        });
        play(animator, 185, false, this::finishDismissalAnimation);
    }

    private void play(ValueAnimator animator, long duration, boolean afterLayout, Runnable finish) {
        transition = animator;
        animator.setDuration(duration);
        animator.setInterpolator(MORPH);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (transition != ended) return;
                transition = null;
                finish.run();
            }
        });
        animator.setCurrentFraction(0);
        if (!afterLayout) {
            animator.start();
            return;
        }
        pendingStart = () -> {
            clearPendingStart();
            surface.postOnAnimation(() -> {
                if (transition == animator) animator.start();
            });
            return true;
        };
        surface.getViewTreeObserver().addOnPreDrawListener(pendingStart);
    }

    private void finishDismissalAnimation() {
        surface.setAlpha(0);
        if (Build.VERSION.SDK_INT >= 29 && surface.isHardwareAccelerated()) {
            surface.getViewTreeObserver().registerFrameCommitCallback(finishClose);
            surface.postDelayed(finishClose, 100);
        } else {
            surface.postOnAnimation(finishClose);
        }
    }

    private void completeDismissal() {
        surface.removeCallbacks(finishClose);
        if (attached) close.run();
    }

    private void clearPendingStart() {
        if (pendingStart == null) return;
        surface.getViewTreeObserver().removeOnPreDrawListener(pendingStart);
        pendingStart = null;
    }

    private void cancelTransition() {
        clearPendingStart();
        ValueAnimator active = transition;
        transition = null;
        if (active != null) active.cancel();
    }

    private static float between(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float fadeBetween(float progress, float from, float to) {
        float fraction = Math.max(0, Math.min(1, (progress - from) / (to - from)));
        return fraction * fraction * (3 - 2 * fraction);
    }

    private final class MorphSurface extends FrameLayout {
        private final RectF mask = new RectF();
        private final Path outline = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float borderWidth;
        private final int touchSlop;
        private final int[] location = new int[2];
        private View tappedControl;
        private float downX, downY;
        private boolean morphing, tapEligible;
        private float radius, opacity;
        private int color;

        MorphSurface(Context context) {
            super(context);
            borderWidth = dp(1);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        void morph(RectF start, RectF end, float progress, float radius, int color, float opacity) {
            morphing = true;
            mask.set(between(start.left, end.left, progress), between(start.top, end.top, progress),
                between(start.right, end.right, progress), between(start.bottom, end.bottom, progress));
            this.radius = radius;
            this.color = color;
            this.opacity = opacity;
            invalidate();
        }

        void clearMorph() {
            morphing = false;
            invalidate();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && (reveal != null || closing)) {
                if (closing || !mask.contains(event.getRawX() - reveal.window.left,
                        event.getRawY() - reveal.window.top)) return false;
                tappedControl = controlAt(event);
                if (tappedControl == null) return false;
                tapEligible = true;
                tappedControl.setPressed(true);
                downX = event.getRawX();
                downY = event.getRawY();
                return true;
            }
            if (tappedControl == null) return super.dispatchTouchEvent(event);

            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
            if (action == MotionEvent.ACTION_POINTER_DOWN || dx * dx + dy * dy > touchSlop * touchSlop) {
                tapEligible = false;
                tappedControl.setPressed(false);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                View control = tappedControl;
                tappedControl = null;
                boolean click = action == MotionEvent.ACTION_UP && tapEligible;
                control.setPressed(false);
                if (click) control.performClick();
            }
            return true;
        }

        private View controlAt(MotionEvent event) {
            if (reveal.panel.getAlpha() > 0) {
                if (hit(closeButton, event)) return closeButton;
                if (hit(collapseButton, event)) return collapseButton;
            }
            return reveal.bubble.getAlpha() > 0 && hit(reveal.bubble, event) ? reveal.bubble : null;
        }

        private boolean hit(View control, MotionEvent event) {
            control.getLocationOnScreen(location);
            float x = event.getRawX() - location[0], y = event.getRawY() - location[1];
            return x >= 0 && x < control.getWidth() && y >= 0 && y < control.getHeight();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            if (!morphing) {
                mask.set(0, 0, getWidth(), getHeight());
                radius = expanded ? dp(12) : mask.width() / 2f;
                color = expanded ? PANEL : BUBBLE;
                opacity = 1;
            }
            float corner = Math.min(radius, Math.min(mask.width(), mask.height()) / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha(Math.round(paint.getAlpha() * opacity));
            canvas.drawRoundRect(mask, corner, corner, paint);

            int saved = canvas.save();
            outline.rewind();
            outline.addRoundRect(mask, corner, corner, Path.Direction.CW);
            canvas.clipPath(outline);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);

            float inset = borderWidth / 2;
            if (mask.width() <= borderWidth || mask.height() <= borderWidth) return;
            paint.setColor(LINE);
            paint.setAlpha(Math.round(255 * opacity));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(borderWidth);
            float innerCorner = Math.max(0, corner - inset);
            canvas.drawRoundRect(mask.left + inset, mask.top + inset, mask.right - inset, mask.bottom - inset,
                innerCorner, innerCorner, paint);
        }
    }

    private void bindDrag(View grip, boolean opens) {
        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float firstX, firstY;
            private int startX, startY;
            private boolean dragging;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (transition != null || closing) return false;
                        firstX = event.getRawX();
                        firstY = event.getRawY();
                        startX = placement.x;
                        startY = placement.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - firstX, dy = event.getRawY() - firstY;
                        if (!dragging && dx * dx + dy * dy < slop * slop) return true;
                        dragging = true;
                        place(startX + Math.round(dx), startY + Math.round(dy));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) savePosition();
                        else if (opens) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) savePosition();
                        return true;
                    default: return true;
                }
            }
        });
    }

    private void place(int x, int y) {
        moveInsideSafeArea(resting, x, y);
        moveWindow(resting);
    }

    private void moveInsideSafeArea(Rect bounds, int x, int y) {
        bounds.offsetTo(Math.max(safe.left, Math.min(safe.right - bounds.width(), x)),
            Math.max(safe.top, Math.min(safe.bottom - bounds.height(), y)));
    }

    private void moveWindow(Rect bounds) {
        if (placement.x == bounds.left && placement.y == bounds.top
                && placement.width == bounds.width() && placement.height == bounds.height()) return;
        placement.x = bounds.left;
        placement.y = bounds.top;
        placement.width = bounds.width();
        placement.height = bounds.height();
        if (attached) {
            try { manager.updateViewLayout(surface, placement); }
            catch (IllegalArgumentException | SecurityException ex) { close.run(); }
        }
    }

    private String positionKey() {
        return (expanded ? "panel" : "circle") + (landscape ? "_land" : "_port");
    }

    private void savePosition() {
        String key = positionKey();
        float x = (resting.left - safe.left) / (float)Math.max(1, safe.width() - resting.width());
        float y = (resting.top - safe.top) / (float)Math.max(1, safe.height() - resting.height());
        options.store().edit().putFloat(key + "_x", x).putFloat(key + "_y", y).apply();
    }

    private void stopRows() {
        for (Row row : rows.values()) row.stop();
        rows.clear();
        visible.clear();
    }

    void detach() {
        stopRows();
        if (!attached) return;
        if (reveal == null && transition == null && !closing) savePosition();
        attached = false;
        cancelTransition();
        surface.removeCallbacks(finishClose);
        if (Build.VERSION.SDK_INT >= 29 && surface.getViewTreeObserver().isAlive()) {
            surface.getViewTreeObserver().unregisterFrameCommitCallback(finishClose);
        }
        try { manager.removeViewImmediate(surface); }
        catch (IllegalArgumentException ignored) { }
    }

    private ImageButton control(String key, String description, Runnable action) {
        ImageButton view = new ImageButton(context);
        view.setImageDrawable(new Glyph(key, WHITE, dp(16)));
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26ffffff), null, shape(WHITE, 8)));
        view.setContentDescription(description);
        view.setPadding(0, 0, 0, 0);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setSingleLine(true);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), LINE);
        return result;
    }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
