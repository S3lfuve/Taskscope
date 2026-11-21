package local.taskscope.manager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

final class Icons {
    private final PackageManager packages;
    private final int size;
    private final LruCache<String, Bitmap> cache = new LruCache<>(240);
    private final ColorMatrixColorFilter grayscale;

    Icons(Context context) {
        packages = context.getPackageManager();
        size = Math.round(38 * context.getResources().getDisplayMetrics().density);
        ColorMatrix colors = new ColorMatrix();
        colors.setSaturation(0);
        grayscale = new ColorMatrixColorFilter(colors);
    }

    Bitmap get(String packageName) {
        Bitmap cached = cache.get(packageName);
        if (cached != null) return cached;
        Drawable icon;
        try {
            icon = packages.getApplicationIcon(packageName).mutate();
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
        Drawable mono = Build.VERSION.SDK_INT >= 33 && icon instanceof AdaptiveIconDrawable
            ? ((AdaptiveIconDrawable)icon).getMonochrome() : null;
        if (mono != null) {
            icon = mono.mutate();
            icon.setTint(0xffededed);
        } else {
            icon.setColorFilter(grayscale);
        }
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(image));
        cache.put(packageName, image);
        return image;
    }
}
