package local.taskscope.manager;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class Glyph extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    private final int size;

    Glyph(String name, int color, int size) {
        this.name = name;
        this.size = size;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (name) {
            case "pulse":
                path(canvas, 2, 13, 6, 13, 9, 6, 13, 18, 16, 11, 22, 11);
                break;
            case "process":
                canvas.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
                path(canvas, 3, 9, 21, 9);
                path(canvas, 8, 9, 8, 20);
                path(canvas, 12, 13, 18, 13);
                path(canvas, 12, 16, 16, 16);
                break;
            case "services":
                canvas.drawRoundRect(5, 5, 19, 19, 2, 2, paint);
                canvas.drawRoundRect(9, 9, 15, 15, 1, 1, paint);
                for (int i = 8; i <= 16; i += 4) {
                    path(canvas, i, 2, i, 5);
                    path(canvas, i, 19, i, 22);
                    path(canvas, 2, i, 5, i);
                    path(canvas, 19, i, 22, i);
                }
                break;
            case "packs":
                path(canvas, 12, 3, 21, 8, 12, 13, 3, 8, 12, 3);
                path(canvas, 3, 12, 12, 17, 21, 12);
                path(canvas, 3, 16, 12, 21, 21, 16);
                break;
            case "search":
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint);
                path(canvas, 15, 15, 20, 20);
                break;
            case "plus":
                path(canvas, 12, 5, 12, 19);
                path(canvas, 5, 12, 19, 12);
                break;
            case "speedometer":
                canvas.drawArc(3, 4, 21, 22, 145, 250, false, paint);
                path(canvas, 12, 14, 16.5f, 9.5f);
                path(canvas, 7, 18, 17, 18);
                canvas.drawCircle(12, 14, 1, paint);
                break;
            case "temperature":
                canvas.drawRoundRect(9, 3, 15, 16, 3, 3, paint);
                canvas.drawCircle(12, 17.5f, 4, paint);
                path(canvas, 12, 8, 12, 18);
                break;
            case "refresh":
                canvas.drawArc(5, 5, 19, 19, 26, 304, false, paint);
                path(canvas, 14, 6, 19, 6, 19, 11);
                break;
            case "close":
                path(canvas, 6, 6, 18, 18);
                path(canvas, 6, 18, 18, 6);
                break;
            case "chevron-up":
                path(canvas, 5, 15, 12, 8, 19, 15);
                break;
            case "chevron-down":
                path(canvas, 5, 9, 12, 16, 19, 9);
                break;
            case "chevron-left":
                path(canvas, 15, 5, 8, 12, 15, 19);
                break;
            case "overlay":
                canvas.drawRoundRect(3, 4, 20, 18, 2, 2, paint);
                path(canvas, 7, 14, 10, 10, 13, 13, 17, 8);
                path(canvas, 7, 21, 22, 21, 22, 9);
                break;
            case "chevron":
                path(canvas, 9, 5, 16, 12, 9, 19);
                break;
            case "check":
                path(canvas, 5, 12, 10, 17, 19, 7);
                break;
            case "play":
                path(canvas, 8, 5, 19, 12, 8, 19, 8, 5);
                break;
            case "pause":
                path(canvas, 9, 5, 9, 19);
                path(canvas, 15, 5, 15, 19);
                break;
            case "stop":
                canvas.drawRoundRect(6, 6, 18, 18, 2, 2, paint);
                break;
            case "settings":
                canvas.drawCircle(12, 12, 3, paint);
                path(canvas, 10, 3, 14, 3, 15, 6, 18, 6, 20, 9, 19, 12, 20, 15,
                    18, 18, 15, 18, 14, 21, 10, 21, 9, 18, 6, 18, 4, 15, 5, 12, 4, 9,
                    6, 6, 9, 6, 10, 3);
                break;
            case "details":
                canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint);
                path(canvas, 8, 8, 16, 8);
                path(canvas, 8, 12, 16, 12);
                path(canvas, 8, 16, 13, 16);
                break;
            default:
                canvas.drawRoundRect(4, 6, 20, 18, 2, 2, paint);
                path(canvas, 7, 9, 10, 12, 7, 15);
                path(canvas, 12, 15, 16, 15);
                break;
        }
        canvas.restore();
    }

    private void path(Canvas canvas, float... coordinates) {
        Path path = new Path();
        path.moveTo(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) path.lineTo(coordinates[i], coordinates[i + 1]);
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
}
