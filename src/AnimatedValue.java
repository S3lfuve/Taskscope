package local.taskscope.manager;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.animation.AlphaAnimation;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextSwitcher;
import android.widget.TextView;

final class AnimatedValue extends TextSwitcher {
    private static final int DURATION = 150;
    private static final AccelerateDecelerateInterpolator EASE = new AccelerateDecelerateInterpolator();
    private static final String LOADING_SPACE = "\u00a0";
    private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int spinnerColor;
    private int textGravity = Gravity.START | Gravity.CENTER_VERTICAL;
    private String value;

    AnimatedValue(Context context, float size, int color, boolean bold) {
        super(context);
        spinnerColor = color;
        setWillNotDraw(false);
        setAnimateFirstView(false);
        setMinimumWidth(Math.round(16 * getResources().getDisplayMetrics().density));
        setFactory(() -> {
            TextView label = new TextView(context);
            label.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextSize(size);
            label.setTextColor(color);
            label.setGravity(textGravity);
            label.setFontFeatureSettings("tnum");
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            return label;
        });
        setDisplayedChild(0);
        setInAnimation(fade(0f, 1f));
        setOutAnimation(fade(1f, 0f));
        setCurrentText(LOADING_SPACE);
        setContentDescription("Загрузка");
    }

    private static AlphaAnimation fade(float from, float to) {
        AlphaAnimation animation = new AlphaAnimation(from, to);
        animation.setDuration(DURATION);
        animation.setInterpolator(EASE);
        return animation;
    }

    void show(String next) { bind(next, true); }

    void bind(String next, boolean animate) {
        if (TextUtils.equals(value, next)) {
            if (!animate) clearFades();
            return;
        }
        boolean crossfade = animate && value != null && next != null && !value.isEmpty() && !next.isEmpty()
            && isShown() && isLaidOut() && ValueAnimator.areAnimatorsEnabled();
        value = next;
        clearFades();
        if (crossfade) {
            setText(next);
        } else {
            setCurrentText(next == null ? LOADING_SPACE : next);
            ((TextView)getNextView()).setText(null);
        }
        setContentDescription(next == null ? "Загрузка" : next);
        invalidate();
    }

    private void clearFades() {
        getChildAt(0).clearAnimation();
        getChildAt(1).clearAnimation();
    }

    void setValueGravity(int gravity) {
        textGravity = gravity;
        ((TextView)getChildAt(0)).setGravity(gravity);
        ((TextView)getChildAt(1)).setGravity(gravity);
    }

    void setValueColor(int color) {
        ((TextView)getChildAt(0)).setTextColor(color);
        ((TextView)getChildAt(1)).setTextColor(color);
    }

    void setValueAutoSize(int min, int max, int step, int unit) {
        ((TextView)getChildAt(0)).setAutoSizeTextTypeUniformWithConfiguration(min, max, step, unit);
        ((TextView)getChildAt(1)).setAutoSizeTextTypeUniformWithConfiguration(min, max, step, unit);
    }

    @Override protected void onDetachedFromWindow() {
        clearFades();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (value != null) return;
        float density = getResources().getDisplayMetrics().density;
        float diameter = Math.min(14 * density, Math.min(getWidth(), getHeight()) - 4 * density);
        if (diameter <= 0) return;

        int horizontal = Gravity.getAbsoluteGravity(textGravity, getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        float x = horizontal == Gravity.CENTER_HORIZONTAL ? (left + right - diameter) / 2f
            : horizontal == Gravity.RIGHT ? right - diameter : left;
        float y = (getHeight() - diameter) / 2f;
        spinner.setStrokeWidth(1.7f * density);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
        spinner.setColor(0xff3c3c3c);
        canvas.drawOval(x, y, x + diameter, y + diameter, spinner);
        spinner.setColor(spinnerColor);
        float angle = (SystemClock.uptimeMillis() % 800) * 360f / 800 - 90;
        canvas.drawArc(x, y, x + diameter, y + diameter, angle, 105, false, spinner);
        if (ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation();
    }
}
