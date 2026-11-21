package local.taskscope.manager;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class GraphView extends View {
    private static final int ANIMATION_MS = 280;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path(), fill = new Path();
    private final int color, slots;
    private final boolean grid, interactive;
    private final float minimum, maximum, density;
    private SampleHistory.Series series;
    private int selected = -1;
    private long selectedTime;
    private float downX, downY;
    private final int touchSlop;
    private final MonitorSettings settings;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final Runnable clear = this::clearInspection;
    private final Runnable hold = this::claimTouch;
    private long animationStart;
    private LinearGradient gradient;
    private float firstX, previousX, previousY;

    GraphView(Context context, int color, int slots, boolean grid, float minimum, float maximum, boolean interactive) {
        super(context);
        this.color = color;
        this.slots = slots;
        this.grid = grid;
        this.interactive = interactive;
        this.minimum = minimum;
        this.maximum = maximum;
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        settings = new MonitorSettings(context);
        setClickable(interactive);
        setFocusable(interactive);
        if (interactive) setContentDescription("График. Нажмите для просмотра значения");
        else setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(SampleHistory.Series next, boolean animate) {
        series = next;
        animationStart = animate && isShown() && ValueAnimator.areAnimatorsEnabled()
            ? SystemClock.uptimeMillis() : 0;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        gradient = new LinearGradient(0, 0, 0, Math.max(1, height),
            (grid ? 0x40000000 : 0x32000000) | (color & 0xffffff),
            0x04000000 | (color & 0xffffff), Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = density * (grid ? 4 : 2);
        float left = inset, right = getWidth() - inset, top = inset, bottom = getHeight() - inset;
        if (bottom <= top || right <= left) return;
        paint.setStrokeWidth(density);
        if (grid) drawGrid(canvas, left, right, top, bottom);
        if (series == null) return;
        float progress = scrollProgress();
        if (progress < 1) postInvalidateOnAnimation();
        float step = (right - left) / (slots - 1);
        boolean open = false;
        line.reset();
        fill.reset();
        canvas.save();
        canvas.clipRect(left, 0, right + inset, getHeight());
        for (int ago = slots; ago >= 0; ago--) {
            float value = series.previous(ago);
            if (!Float.isFinite(value)) {
                if (open) finishArea(bottom);
                open = false;
                continue;
            }
            float x = right - (ago - 1 + progress) * step;
            float y = y(value, top, bottom);
            if (ago == 0 && progress < 1 && open) {
                addPartialCurve(right, y, x);
            } else if (open) {
                addCurve(x, y);
            } else {
                firstX = previousX = x;
                previousY = y;
                line.moveTo(x, y);
                fill.moveTo(x, y);
                open = true;
            }
        }
        if (open) finishArea(bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(gradient);
        canvas.drawPath(fill, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(density * (grid ? 1.7f : 1.35f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(line, paint);
        if (open) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x45000000 | (color & 0xffffff));
            canvas.drawCircle(previousX, previousY, inset, paint);
            paint.setColor(color);
            canvas.drawCircle(previousX, previousY, density * (grid ? 2.4f : 1.5f), paint);
        }
        canvas.restore();
        int marker = selectedSample();
        if (marker >= 0) drawSelection(canvas, left, right, top, bottom, step, progress, marker);
    }

    private float scrollProgress() {
        if (animationStart == 0 || !Float.isFinite(series.previous(0)) || !Float.isFinite(series.previous(1))) return 1;
        return Math.min(1, (SystemClock.uptimeMillis() - animationStart) / (float)ANIMATION_MS);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive || series == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(clear);
                downX = event.getX();
                downY = event.getY();
                choose(downX);
                postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
                if (dx > touchSlop && dx >= dy) {
                    removeCallbacks(hold);
                    claimTouch();
                }
                choose(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(hold);
                choose(event.getX());
                performClick();
                postDelayed(clear, 3200);
                return true;
            case MotionEvent.ACTION_CANCEL:
                clearInspection();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!interactive || series == null) return false;
        if (selected < 0) {
            choose(getWidth());
            postDelayed(clear, 3200);
        }
        int marker = selectedSample();
        if (marker >= 0 && Float.isFinite(series.previous(marker))) {
            announceForAccessibility(reading(series.previous(marker)));
        }
        return true;
    }

    private void claimTouch() {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void choose(float x) {
        float inset = density * (grid ? 4 : 2);
        float usable = Math.max(1f, getWidth() - 2 * inset);
        float index = (getWidth() - inset - x) * (slots - 1) / usable + 1 - scrollProgress();
        selected = Math.max(0, Math.min(slots - 1, Math.round(index)));
        selectedTime = series.time(selected);
        invalidate();
    }

    private int selectedSample() {
        if (selected < 0 || series == null || selectedTime == 0) return selected;
        for (int ago = 0; ago < slots; ago++) {
            if (series.time(ago) == selectedTime) return ago;
        }
        return -1;
    }

    private void clearInspection() {
        removeCallbacks(hold);
        removeCallbacks(clear);
        selected = -1;
        selectedTime = 0;
        invalidate();
    }

    @Override public void onVisibilityAggregated(boolean shown) {
        super.onVisibilityAggregated(shown);
        if (!shown) clearInspection();
    }

    @Override protected void onDetachedFromWindow() {
        clearInspection();
        super.onDetachedFromWindow();
    }

    private String reading(float value) {
        boolean temperature = minimum != 0;
        String number = settings.roundUp() ? Long.toString((long)Math.ceil(value))
            : String.format(Locale.ROOT, temperature ? "%.1f" : "%.2f", value);
        return number + (temperature ? " °C" : "%");
    }

    private void drawSelection(Canvas canvas, float left, float right, float top, float bottom, float step,
            float progress, int marker) {
        float point = series.previous(marker);
        float x = Math.max(left, Math.min(right, right - (marker - 1 + progress) * step));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0xff939393);
        canvas.drawLine(x, top, x, bottom, paint);
        paint.setStyle(Paint.Style.FILL);
        if (Float.isFinite(point)) {
            paint.setColor(0xfff3f3f3);
            canvas.drawCircle(x, y(point, top, bottom), 3.1f * density, paint);
        }
        String label = Float.isFinite(point) ? reading(point) : "Нет замера";
        if (Float.isFinite(point) && getWidth() >= 160 * density) {
            label += "  ·  " + clock.format(new Date(series.time(marker)));
        }
        paint.setTextSize(11.5f * getResources().getDisplayMetrics().scaledDensity);
        float available = Math.max(1, right - left - 10 * density);
        if (paint.measureText(label) > available) {
            paint.setTextSize(paint.getTextSize() * available / paint.measureText(label));
        }
        float padding = 5 * density;
        float width = paint.measureText(label) + 2 * padding;
        float start = Math.max(left, Math.min(right - width, x - width / 2));
        paint.setColor(0xff202020);
        float height = Math.min(bottom - top, 20 * density);
        canvas.drawRoundRect(start, top, start + width, top + height, 5 * density, 5 * density, paint);
        paint.setColor(0xfff3f3f3);
        canvas.drawText(label, start + padding, top + (height - paint.ascent() - paint.descent()) / 2, paint);
    }

    private float y(float value, float top, float bottom) {
        return bottom - (bottom - top) * Math.max(0, Math.min(1, (value - minimum) / (maximum - minimum)));
    }

    private void addCurve(float x, float y) {
        float middle = (previousX + x) / 2;
        line.cubicTo(middle, previousY, middle, y, x, y);
        fill.cubicTo(middle, previousY, middle, y, x, y);
        previousX = x;
        previousY = y;
    }

    private void addPartialCurve(float right, float y, float x) {
        float middle = (previousX + x) / 2, lo = 0, hi = 1;
        for (int i = 0; i < 10; i++) {
            float t = (lo + hi) / 2, u = 1 - t;
            float curveX = u*u*u*previousX + 3*u*t*middle + t*t*t*x;
            if (curveX < right) lo = t;
            else hi = t;
        }
        float t = (lo + hi) / 2;
        float x01 = previousX + (middle - previousX) * t;
        float x23 = middle + (x - middle) * t;
        float y12 = previousY + (y - previousY) * t;
        float x012 = x01 + (middle - x01) * t;
        float x123 = middle + (x23 - middle) * t;
        float y012 = previousY + (y12 - previousY) * t;
        float y123 = y12 + (y - y12) * t;
        float endY = y012 + (y123 - y012) * t;
        float endX = x012 + (x123 - x012) * t;
        line.cubicTo(x01, previousY, x012, y012, endX, endY);
        fill.cubicTo(x01, previousY, x012, y012, endX, endY);
        previousX = endX;
        previousY = endY;
    }

    private void finishArea(float bottom) {
        fill.lineTo(previousX, bottom);
        fill.lineTo(firstX, bottom);
        fill.close();
    }

    private void drawGrid(Canvas canvas, float left, float right, float top, float bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xff303030);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setColor(0xff242424);
        for (int i = 1; i < 6; i++) {
            float x = left + (right - left) * i / 6;
            canvas.drawLine(x, top, x, bottom, paint);
        }
    }
}
