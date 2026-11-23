package app.kyogram.keypad;

import app.kyogram.ui.*;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;

import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The bar over the three keys under the screen: what the left key, OK and the right key do here. A label
 * that changes fades down and out, then the new one rises in (prototype: .16s fade, .3s slide, swap at
 * 120ms); a pressed key flashes brighter. OK is the accent pill in the middle.
 */
public class SoftKeysView extends View {

    private static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    private final String[] text = {"", "", ""};
    private final String[] pending = {null, null, null};
    private final float[] swap = {0, 0, 0}; // * 0 shown, 1 gone (faded and 10px down)
    private final float[] flash = {0, 0, 0};

    private final Style.Glass glass = new Style.Glass();
    private final TextPaint sidePaint = Style.text(Style.semibold, 21, Style.fg(224));
    private final TextPaint okPaint = Style.text(Style.bold, 21, Color.WHITE);
    private final Paint okFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint okEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint okGlow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ok = new RectF();
    private final Path okTop = new Path();
    private final int[] loc = new int[2];
    private Bitmap glow;
    private float okW = -1;
    private final Spring okWidth;

    public SoftKeysView(Context context) {
        super(context);
        okPaint.setLetterSpacing(.5f / 21f);
        okGlow.setColor(Color.argb(140, 139, 116, 242));
        okEdge.setColor(Color.argb(128, 255, 255, 255));
        // * the OK pill's width follows its word (min-width .3s cubic-bezier(.2,1.2,.4,1))
        okWidth = new Spring(Style.px(120), 340, 24, (v, vel) -> invalidate());
    }

    public void set(String left, String center, String right) {
        setOne(LEFT, left);
        setOne(CENTER, center);
        setOne(RIGHT, right);
    }

    private void setOne(int i, String value) {
        if (value == null) value = "";
        if (value.equals(pending[i] != null ? pending[i] : text[i])) return;
        if (Spring.reduced || getWidth() == 0) {
            text[i] = value;
            pending[i] = null;
            if (i == CENTER) okWidth.to(okWidthFor(value), true);
            invalidate();
            return;
        }
        boolean running = pending[i] != null;
        pending[i] = value;
        if (running) return;
        // * out: opacity .16s, translate .3s; at 120ms the word changes and it comes back
        ValueAnimator out = ValueAnimator.ofFloat(swap[i], 1);
        out.setDuration(120);
        out.setInterpolator(new CubicBezierInterpolator(.2, .9, .3, 1));
        out.addUpdateListener(a -> { swap[i] = (float) a.getAnimatedValue(); invalidate(); });
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                text[i] = pending[i];
                pending[i] = null;
                if (i == CENTER) okWidth.to(okWidthFor(text[i]));
                ValueAnimator in = ValueAnimator.ofFloat(1, 0);
                in.setDuration(300);
                in.setInterpolator(new CubicBezierInterpolator(.2, .9, .3, 1));
                in.addUpdateListener(a -> { swap[i] = (float) a.getAnimatedValue(); invalidate(); });
                in.start();
            }
        });
        out.start();
    }

    private float okWidthFor(String word) {
        return Math.max(Style.px(120), okPaint.measureText(word) + Style.px(56));
    }

    /** The key's label flashes brighter for a moment when pressed (keyframes press .22s). */
    public void press(int which) {
        if (which < 0 || which > 2 || Spring.reduced) return;
        ValueAnimator a = ValueAnimator.ofFloat(0, 1, 0);
        a.setDuration(220);
        a.addUpdateListener(an -> { flash[which] = (float) an.getAnimatedValue(); invalidate(); });
        a.start();
    }

    public static final int KEY_LEFT = LEFT, KEY_OK = CENTER, KEY_RIGHT = RIGHT;

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        okWidth.to(okWidthFor(text[CENTER]), true);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        getLocationInWindow(loc);
        glass.draw(c, w, h, h / 2f, loc[0], loc[1]);

        // * the sides
        drawLabel(c, LEFT, Style.px(26), h / 2f, false);
        drawLabel(c, RIGHT, w - Style.px(26), h / 2f, true);

        // * OK: the accent pill
        float ow = okWidth.v, oh = Style.px(52);
        ok.set((w - ow) / 2f, Style.px(8), (w + ow) / 2f, Style.px(8) + oh);
        if (okW != ow) {
            okW = ow;
            okFill.setShader(Style.accentGradient(ok));
            okTop.reset();
            Path outer = new Path();
            outer.addRoundRect(ok, oh / 2f, oh / 2f, Path.Direction.CW);
            Path shifted = new Path();
            RectF r2 = new RectF(ok);
            r2.offset(0, Style.px(1.5f));
            shifted.addRoundRect(r2, oh / 2f, oh / 2f, Path.Direction.CW);
            okTop.op(outer, shifted, Path.Op.DIFFERENCE);
            int pad = (int) Style.px(24);
            try {
                glow = Bitmap.createBitmap((int) ow + pad * 2, (int) oh + pad * 2, Bitmap.Config.ALPHA_8);
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setMaskFilter(new BlurMaskFilter(Style.px(9), BlurMaskFilter.Blur.NORMAL));
                new Canvas(glow).drawRoundRect(new RectF(pad, pad, pad + ow, pad + oh), oh / 2f, oh / 2f, sp);
            } catch (Throwable e) {
                glow = null;
            }
        }
        if (glow != null) {
            float pad = Style.px(24);
            c.drawBitmap(glow, ok.left - pad, ok.top - pad + Style.px(6), okGlow);
        }
        c.drawRoundRect(ok, oh / 2f, oh / 2f, okFill);
        c.drawPath(okTop, okEdge);
        if (flash[CENTER] > 0) {
            flashPaint.setColor(Color.argb((int) (90 * flash[CENTER]), 255, 255, 255));
            c.drawRoundRect(ok, oh / 2f, oh / 2f, flashPaint);
        }
        drawLabel(c, CENTER, w / 2f, Style.px(8) + oh / 2f, false);
    }

    private void drawLabel(Canvas c, int i, float x, float cy, boolean alignEnd) {
        String s = text[i];
        if (s.isEmpty()) return;
        TextPaint p = i == CENTER ? okPaint : sidePaint;
        float tw = p.measureText(s);
        float left = i == CENTER ? x - tw / 2f : alignEnd ? x - tw : x;
        Paint.FontMetrics fm = p.getFontMetrics();
        int baseAlpha = i == CENTER ? 255 : 224;
        float k = swap[i];
        // * opacity fades over the first part of the slide
        float alpha = Math.max(0, 1 - k * 1.33f);
        int a = (int) (baseAlpha * alpha);
        if (flash[i] > 0 && i != CENTER) a = Math.min(255, (int) (a + 31 * flash[i]));
        p.setAlpha(a);
        c.drawText(s, left, cy - (fm.ascent + fm.descent) / 2f + Style.px(10) * k, p);
        p.setAlpha(baseAlpha);
    }
}
