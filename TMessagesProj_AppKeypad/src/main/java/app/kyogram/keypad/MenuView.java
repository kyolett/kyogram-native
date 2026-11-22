package app.kyogram.keypad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;

/** The menu's items in its glass sheet: a numbered key, a coloured icon, the label; the pill glides. */
public class MenuView extends View {

    public static final class Entry {
        public final String id, label, icon;
        public final int from, to;

        public Entry(String id, String label, String icon, String from, String to) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.from = Color.parseColor(from);
            this.to = Color.parseColor(to);
        }
    }

    public static final float TITLE_H = 47, ITEM = 72;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private int focus;
    private final Spring pillY;
    private float stretch;
    private final Style.Pill pill = new Style.Pill(false);
    private final TextPaint title = Style.text(Style.display, 22, Color.argb(191, 255, 255, 255));
    private final TextPaint label = Style.text(Style.semibold, 23, Style.INK);
    private final TextPaint key = Style.text(Style.bold, 19, Style.INK);
    private final TextPaint icon = Style.text(Style.bold, 22, Color.WHITE);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Spring[] keyFocus = new Spring[10];
    private final float[] keyK = new float[10];

    public MenuView(Context context) {
        super(context);
        pillY = new Spring(0, 420, 30, (v, vel) -> {
            stretch = Math.min(.14f, Math.abs(vel) / (9000f * Style.u));
            invalidate();
        });
        for (int i = 0; i < keyFocus.length; i++) {
            final int k = i;
            keyFocus[i] = new Spring(0, 400, 40, (v, vel) -> { keyK[k] = v; invalidate(); });
        }
    }

    public void setEntries(ArrayList<Entry> list) {
        entries.clear();
        entries.addAll(list);
        focus = 0;
        move(0, true);
        requestLayout();
    }

    public int preferredHeight() {
        return (int) (Style.px(TITLE_H) + Style.px(ITEM) * entries.size());
    }

    public Entry focused() {
        return focus < entries.size() ? entries.get(focus) : null;
    }

    public Entry byKey(int k) {
        if (k < 1 || k > entries.size()) return null;
        focus = k - 1;
        move(0, false);
        return entries.get(k - 1);
    }

    /** Up and down wrap round, as in the prototype. */
    public void move(int d, boolean snap) {
        if (entries.isEmpty()) return;
        focus = (focus + d + entries.size()) % entries.size();
        pillY.to(focus * Style.px(ITEM), snap);
        for (int i = 0; i < Math.min(entries.size(), keyFocus.length); i++) keyFocus[i].to(i == focus ? 1 : 0, snap);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), item = Style.px(ITEM);
        Paint.FontMetrics fm = title.getFontMetrics();
        c.drawText("Menu", Style.px(16), Style.px(8) - fm.ascent, title);
        c.save();
        c.translate(0, Style.px(TITLE_H));
        c.save();
        c.translate(0, pillY.v);
        c.scale(1 - stretch * .25f, 1 + stretch, w / 2f, item / 2f);
        pill.draw(c, w, item, Style.px(26));
        c.restore();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            float y = i * item, cy = y + item / 2f;
            // * the number: a dim disc, white with dark ink when in focus
            float k = i < keyK.length ? keyK[i] : 0;
            float ks = Style.px(38), kx = Style.px(16);
            fill.setShader(null);
            fill.setColor(blend(Color.argb(36, 255, 255, 255), Color.WHITE, k));
            c.drawCircle(kx + ks / 2f, cy, ks / 2f, fill);
            key.setColor(blend(Style.INK, Style.KEY_INK, k));
            String n = String.valueOf(i + 1);
            Paint.FontMetrics km = key.getFontMetrics();
            c.drawText(n, kx + (ks - key.measureText(n)) / 2f, cy - (km.ascent + km.descent) / 2f, key);
            // * the icon tile
            float is = Style.px(44), ix = kx + ks + Style.px(16);
            r.set(ix, cy - is / 2f, ix + is, cy + is / 2f);
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, e.from, e.to, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, Style.px(14), Style.px(14), fill);
            fill.setShader(null);
            Paint.FontMetrics im = icon.getFontMetrics();
            c.drawText(e.icon, r.centerX() - icon.measureText(e.icon) / 2f, cy - (im.ascent + im.descent) / 2f, icon);
            Paint.FontMetrics lm = label.getFontMetrics();
            c.drawText(e.label, ix + is + Style.px(16), cy - (lm.ascent + lm.descent) / 2f, label);
        }
        c.restore();
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        return Color.argb((int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
