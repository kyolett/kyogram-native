package app.kyogram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

import org.telegram.messenger.Utilities;

/**
 * Kyogram Keypad's look, measured from the prototype: sizes are the prototype's pixels on its 540-wide
 * screen (the SH-02L's own width), scaled by {@link #u} for any other width; colours and the glass are
 * the prototype's. The colours behind the glass never change, so they are painted once into a bitmap,
 * and a blurred copy of that bitmap is the frost every glass panel shows.
 */
public final class Style {

    public static float u = 1f;
    public static int statusBar;

    public static int INK = Color.parseColor("#f6f4ff");
    public static int INK2 = Color.argb(158, 246, 244, 255); // .62
    public static int INK3 = Color.argb(107, 246, 244, 255); // .42
    public static final int ACCENT = Color.parseColor("#8b74f2");
    public static final int ACCENT_HI = Color.parseColor("#ad9cff");
    public static final int DANGER = Color.parseColor("#ff7a7a");
    public static final int OK = Color.parseColor("#7fe0a8");
    public static final int WARN = Color.parseColor("#ffbf6a");
    public static int BASE = Color.parseColor("#15121c");

    /** Settings → Theme (Night / Day) and Glass tint (the desktop's palettes, _kyogramGlass.scss). */
    public static boolean day;
    public static String tint = "candy";
    /** Customize Kyogram → Glass (the desktop's): how solid the glass is, its frost, its shine. */
    public static float solid = .5f, blurPx = 24f, shine = 1f;
    private static String paintedFor;

    public static void glass(float solidness, float blur, float shineAmount) {
        solid = Math.max(0f, Math.min(1f, solidness));
        blurPx = Math.max(0f, Math.min(60f, blur));
        shine = Math.max(0f, Math.min(1f, shineAmount));
    }

    /** The glass's own colour at the set clearness. */
    public static int glassTint() {
        int a = Math.round(30 + solid * 220);
        return day ? Color.argb(a, 255, 255, 255) : Color.argb(a, 40, 36, 60);
    }

    /** Text-coloured ink at an alpha (white at night, near-black by day). */
    public static int fg(int alpha) {
        return day ? Color.argb(alpha, 29, 26, 38) : Color.argb(alpha, 255, 255, 255);
    }

    public static void theme(boolean isDay, String glassTint) {
        day = isDay;
        tint = glassTint != null ? glassTint : "candy";
        INK = day ? Color.parseColor("#1d1a26") : Color.parseColor("#f6f4ff");
        INK2 = day ? Color.argb(158, 29, 26, 38) : Color.argb(158, 246, 244, 255);
        INK3 = day ? Color.argb(115, 29, 26, 38) : Color.argb(107, 246, 244, 255);
    }

    /** b1..b4 and the base, night and day, per tint. */
    private static int[] palette() {
        switch (tint + (day ? "-day" : "")) {
            case "ocean": return new int[]{Color.argb(217, 30, 90, 160), Color.argb(204, 40, 60, 150), Color.argb(128, 20, 120, 130), Color.argb(128, 60, 90, 170), Color.parseColor("#0f1520")};
            case "sunset": return new int[]{Color.argb(204, 170, 70, 60), Color.argb(191, 150, 50, 110), Color.argb(115, 160, 110, 40), Color.argb(128, 170, 80, 90), Color.parseColor("#1b1214")};
            case "mono": return new int[]{Color.argb(153, 90, 90, 104), Color.argb(153, 70, 72, 86), Color.argb(89, 100, 100, 110), Color.argb(102, 80, 80, 95), Color.parseColor("#121214")};
            case "candy-day": return new int[]{Color.parseColor("#ffb4d6"), Color.parseColor("#b9c8ff"), Color.parseColor("#c8f2df"), Color.parseColor("#ffe1b8"), Color.parseColor("#f6f1fb")};
            case "ocean-day": return new int[]{Color.parseColor("#9fd8ff"), Color.parseColor("#b8c6ff"), Color.parseColor("#bff3ea"), Color.parseColor("#d6f0ff"), Color.parseColor("#eef6fb")};
            case "sunset-day": return new int[]{Color.parseColor("#ffb39b"), Color.parseColor("#ffc9e0"), Color.parseColor("#ffe3a8"), Color.parseColor("#ffd0b0"), Color.parseColor("#fff4ee")};
            case "mono-day": return new int[]{Color.parseColor("#e3e3ea"), Color.parseColor("#d7d9e2"), Color.parseColor("#eceef2"), Color.parseColor("#e6e3ea"), Color.parseColor("#f4f4f6")};
            default: return new int[]{Color.argb(242, 122, 58, 143), Color.argb(230, 44, 78, 168), Color.argb(128, 31, 122, 98), Color.argb(140, 150, 70, 110), Color.parseColor("#15121c")};
        }
    }
    public static final int KEY_INK = Color.parseColor("#2a2340");

    public static Typeface regular, medium, semibold, bold, display;

    public static Bitmap wall, frost;
    private static float frostScale;

    private Style() {
    }

    /** The phone app's unit: the prototype's sizes a little under the flip phone's, for a phone held close. */
    public static float phoneUnit() {
        return org.telegram.messenger.AndroidUtilities.density * .6f;
    }

    /** The phone app: Night or Day, the colours and the glass from Customize Kyogram. */
    public static void fromPrefs() {
        theme(KyogramPrefs.day(), KyogramPrefs.tint());
        glass(KyogramPrefs.solid(), KyogramPrefs.blur(), KyogramPrefs.shine());
    }

    public static float px(float prototypePx) {
        return prototypePx * u;
    }

    public static void init(Context context, int width, int height, int statusBarHeight) {
        init(context, width, height, statusBarHeight, width / 540f);
    }

    /** With a set scale: the phone app sizes things by its density (the prototype's 540 = 360dp). */
    public static void init(Context context, int width, int height, int statusBarHeight, float unit) {
        u = unit;
        statusBar = statusBarHeight;
        if (regular == null) {
            regular = font(context, "Inter-Regular.otf", Typeface.NORMAL);
            medium = font(context, "Inter-Medium.otf", Typeface.NORMAL);
            semibold = font(context, "Inter-SemiBold.otf", Typeface.BOLD);
            bold = font(context, "Inter-Bold.otf", Typeface.BOLD);
            display = font(context, "InterDisplay-Bold.otf", Typeface.BOLD);
        }
        String key = tint + day + blurPx + width + "x" + height;
        if (wall == null || !key.equals(paintedFor)) {
            paintedFor = key;
            paintWall(width, height);
        }
    }

    private static Typeface font(Context context, String name, int fallbackStyle) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/" + name);
        } catch (Throwable e) {
            return Typeface.create(Typeface.DEFAULT, fallbackStyle);
        }
    }

    public static TextPaint text(Typeface face, float sizePx, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(px(sizePx));
        p.setColor(color);
        return p;
    }

    // ── the colours behind everything ──────────────────────────────────────────

    /** CSS radial-gradient(circle at x% y%, color 0, transparent stop%) over the base colour. */
    /** The colours behind the glass, painted at a size (also the chats' wallpaper on the phone app). */
    public static Bitmap wallBitmap(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int[] pal = palette();
        BASE = pal[4];
        c.drawColor(BASE);
        // * CSS draws the first gradient on top, so paint them last to first
        blob(c, w, h, .92f, .08f, pal[3], .28f);
        blob(c, w, h, .62f, .48f, pal[2], .32f);
        blob(c, w, h, .96f, .96f, pal[1], .46f);
        blob(c, w, h, .08f, .06f, pal[0], .38f);
        return b;
    }

    private static void paintWall(int w, int h) {
        wall = wallBitmap(w, h);

        // * the frost: small, blurred (CSS blur(18px)) and saturated (saturate(1.5)), scaled back up when drawn
        frostScale = 6f;
        Bitmap small = Bitmap.createScaledBitmap(wall, Math.max(1, (int) (w / frostScale)), Math.max(1, (int) (h / frostScale)), true);
        Bitmap mutable = small.copy(Bitmap.Config.ARGB_8888, true);
        if (blurPx > 0) Utilities.stackBlurBitmap(mutable, Math.max(1, (int) (px(blurPx * .75f) / frostScale * 1.6f)));
        frost = Bitmap.createBitmap(mutable.getWidth(), mutable.getHeight(), Bitmap.Config.ARGB_8888);
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.5f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(frost).drawBitmap(mutable, 0, 0, p);
    }

    private static void blob(Canvas c, int w, int h, float fx, float fy, int color, float stop) {
        float cx = fx * w, cy = fy * h;
        float farthest = (float) Math.max(Math.max(Math.hypot(cx, cy), Math.hypot(w - cx, cy)), Math.max(Math.hypot(cx, h - cy), Math.hypot(w - cx, h - cy)));
        float r = farthest * stop;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, r, new int[]{color, color & 0x00ffffff}, new float[]{0, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    // ── glass ──────────────────────────────────────────────────────────────────

    /**
     * A glass panel: the frost behind it, the tint, the sheen at the top, the rim and its bright top edge,
     * and a soft shadow. Sizes are cached, so drawing a panel is a handful of shader fills.
     */
    public static final class Glass {
        private final RectF rect = new RectF();
        private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Path topEdge = new Path();
        private final Matrix matrix = new Matrix();
        private Bitmap shadow;
        private float w, h, radius;
        private final int shadowColor;
        private final float shadowY, shadowBlur;

        public Glass() {
            this(Color.argb(77, 0, 0, 0), 8, 22);
        }

        public Glass(int shadowColor, float shadowY, float shadowBlur) {
            this.shadowColor = shadowColor;
            this.shadowY = shadowY;
            this.shadowBlur = shadowBlur;
            tint.setColor(glassTint());
            rim.setStyle(Paint.Style.STROKE);
            rim.setColor(day ? Color.argb(204, 255, 255, 255) : Color.argb(56, 255, 255, 255));
            top.setColor(Color.argb(Math.round(102 * shine), 255, 255, 255));
        }

        private void size(float w, float h, float radius) {
            if (this.w == w && this.h == h && this.radius == radius) return;
            this.w = w;
            this.h = h;
            this.radius = radius;
            rect.set(0, 0, w, h);
            sheen.setShader(new LinearGradient(0, 0, 0, h * .6f, Color.argb(Math.round((day ? 153 : 41) * shine), 255, 255, 255), 0x00ffffff, Shader.TileMode.CLAMP));
            rim.setStrokeWidth(px(1.5f));
            Path outer = new Path();
            outer.addRoundRect(rect, radius, radius, Path.Direction.CW);
            Path shifted = new Path();
            RectF r2 = new RectF(rect);
            r2.offset(0, px(1.5f));
            shifted.addRoundRect(r2, radius, radius, Path.Direction.CW);
            topEdge.reset();
            topEdge.op(outer, shifted, Path.Op.DIFFERENCE);
            shadow = null;
            if (shadowBlur > 0 && w > 0 && h > 0) {
                int pad = (int) px(shadowBlur * 1.2f);
                try {
                    shadow = Bitmap.createBitmap((int) w + pad * 2, (int) h + pad * 2, Bitmap.Config.ALPHA_8);
                    Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    sp.setMaskFilter(new BlurMaskFilter(px(shadowBlur) / 2f, BlurMaskFilter.Blur.NORMAL));
                    new Canvas(shadow).drawRoundRect(new RectF(pad, pad, pad + w, pad + h), radius, radius, sp);
                    shadowPaint.setColor(shadowColor);
                } catch (Throwable e) {
                    shadow = null;
                }
            }
        }

        /**
         * Draws the panel at (0,0)-(w,h) of the canvas; screenX/screenY are where that corner is on the
         * screen, so the frost lines up with the colours behind it.
         */
        public void draw(Canvas c, float w, float h, float radius, float screenX, float screenY) {
            size(w, h, radius);
            if (shadow != null) {
                float pad = px(shadowBlur * 1.2f);
                c.drawBitmap(shadow, -pad, -pad + px(shadowY), shadowPaint);
            }
            if (frost != null) {
                BitmapShader s = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                matrix.setScale(frostScale * (wall.getWidth() / (frost.getWidth() * frostScale)), frostScale * (wall.getHeight() / (frost.getHeight() * frostScale)));
                matrix.postTranslate(-screenX, -screenY);
                s.setLocalMatrix(matrix);
                frostPaint.setShader(s);
                c.drawRoundRect(rect, radius, radius, frostPaint);
            }
            c.drawRoundRect(rect, radius, radius, tint);
            c.drawRoundRect(rect, radius, radius, sheen);
            float i = px(.75f);
            c.drawRoundRect(i, i, w - i, h - i, radius - i, radius - i, rim);
            c.drawPath(topEdge, top);
        }
    }

    /** The focus pill: accent glass with a bright rim and top edge, and a violet glow under it. */
    public static final class Pill {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Path topEdge = new Path();
        private final RectF rect = new RectF();
        private Bitmap glow;
        private float w, h, radius;
        private final boolean withGlow;

        public Pill(boolean withGlow) {
            this.withGlow = withGlow;
            fill.setColor(Color.argb(77, 139, 116, 242));
            rim.setStyle(Paint.Style.STROKE);
            rim.setColor(Color.argb(107, 255, 255, 255));
            top.setColor(Color.argb(140, 255, 255, 255));
            glowPaint.setColor(Color.argb(89, 80, 50, 180));
        }

        public void draw(Canvas c, float w, float h, float radius) {
            if (this.w != w || this.h != h || this.radius != radius) {
                this.w = w;
                this.h = h;
                this.radius = radius;
                rect.set(0, 0, w, h);
                sheen.setShader(new LinearGradient(0, 0, 0, h * .7f, Color.argb(51, 255, 255, 255), Color.argb(10, 255, 255, 255), Shader.TileMode.CLAMP));
                rim.setStrokeWidth(px(2));
                Path outer = new Path();
                outer.addRoundRect(rect, radius, radius, Path.Direction.CW);
                Path shifted = new Path();
                RectF r2 = new RectF(rect);
                r2.offset(0, px(2));
                shifted.addRoundRect(r2, radius, radius, Path.Direction.CW);
                topEdge.reset();
                topEdge.op(outer, shifted, Path.Op.DIFFERENCE);
                glow = null;
                if (withGlow && w > 0 && h > 0) {
                    int pad = (int) px(32);
                    try {
                        glow = Bitmap.createBitmap((int) w + pad * 2, (int) h + pad * 2, Bitmap.Config.ALPHA_8);
                        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                        sp.setMaskFilter(new BlurMaskFilter(px(13), BlurMaskFilter.Blur.NORMAL));
                        new Canvas(glow).drawRoundRect(new RectF(pad, pad, pad + w, pad + h), radius, radius, sp);
                    } catch (Throwable e) {
                        glow = null;
                    }
                }
            }
            if (glow != null) {
                float pad = px(32);
                c.drawBitmap(glow, -pad, -pad + px(10), glowPaint);
            }
            c.drawRoundRect(rect, radius, radius, fill);
            c.drawRoundRect(rect, radius, radius, sheen);
            float i = px(1);
            c.drawRoundRect(i, i, w - i, h - i, radius - i, radius - i, rim);
            c.drawPath(topEdge, top);
        }
    }

    /** The accent button: a gradient at 165°, a glow under it and a bright top edge (the OK key, send). */
    public static void accentButton(Canvas c, RectF r, float radius, Paint fill, Paint glow, Paint edge) {
        c.drawRoundRect(r, radius, radius, fill);
        c.drawRoundRect(r, radius, radius, edge);
    }

    public static LinearGradient accentGradient(RectF r) {
        // * CSS 165deg: from near the top towards the bottom, leaning a little to the right
        double a = Math.toRadians(165 - 90);
        float cx = r.centerX(), cy = r.centerY();
        float half = (float) (Math.abs(r.width() * Math.sin(Math.toRadians(165))) + Math.abs(r.height() * Math.cos(Math.toRadians(165)))) / 2f;
        float dx = (float) Math.cos(a) * half, dy = (float) Math.sin(a) * half;
        return new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, ACCENT_HI, ACCENT, Shader.TileMode.CLAMP);
    }

    public static int alpha(int color, float a) {
        return (color & 0x00ffffff) | (Math.max(0, Math.min(255, Math.round(Color.alpha(color) * a))) << 24);
    }
}
