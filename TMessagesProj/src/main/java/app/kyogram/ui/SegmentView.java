package app.kyogram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/** Two or more choices in a capsule; the picked one's glass pill glides over, stretching as it goes. */
public class SegmentView extends View {

    public interface Listener {
        void onPick(int index);
    }

    private final String[] labels;
    private int picked;
    private final Spring x;
    private float stretch;
    private final Style.Pill pill = new Style.Pill(false);
    private final TextPaint text = Style.text(Style.semibold, 18, Style.INK);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;

    public SegmentView(Context context, String... labels) {
        super(context);
        this.labels = labels;
        x = new Spring(0, 380, 30, (v, vel) -> {
            stretch = Math.min(.18f, Math.abs(vel) / (6000f * Style.u));
            invalidate();
        });
        box.setColor(Style.fg(18));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int picked() {
        return picked;
    }

    public void pick(int i, boolean snap) {
        picked = i;
        x.to(i, snap);
        if (listener != null) listener.onPick(i);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int i = (int) (e.getX() / (getWidth() / (float) labels.length));
            i = Math.max(0, Math.min(labels.length - 1, i));
            if (i != picked) pick(i, false);
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), seg = w / labels.length, pad = Style.px(4);
        c.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, box);
        c.save();
        c.translate(pad + x.v * seg, pad);
        float pw = seg - pad * 2, ph = h - pad * 2;
        c.scale(1 + stretch, 1 - stretch * .3f, pw / 2f, ph / 2f);
        pill.draw(c, pw, ph, ph / 2f);
        c.restore();
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < labels.length; i++) {
            float near = Math.max(0, 1 - Math.abs(x.v - i));
            text.setAlpha((int) (158 + 97 * near));
            c.drawText(labels[i], i * seg + (seg - text.measureText(labels[i])) / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text);
        }
    }
}
