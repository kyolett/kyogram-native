package app.kyogram.keypad;

import app.kyogram.ui.*;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * The chat list's header: a glass capsule with "Kyogram" and the folder tabs, where the picked tab's pill
 * glides; Search turns it into a field (title and tabs rise and fade, the field rises in from below).
 */
public class HeaderView extends GlassFrame {

    private final Content content;
    public final EditText search;
    private float searching; // * 0 title and tabs, 1 the search field
    private ValueAnimator searchAnim;

    private final ArrayList<String> tabs = new ArrayList<>();
    private int current;
    private final Spring glideX, glideW, tabsOffset;

    public HeaderView(Context context) {
        super(context, 32);
        content = new Content(context);
        addView(content, new FrameLayout.LayoutParams(-1, -1));

        search = new EditText(context);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setTextColor(Style.INK);
        search.setHintTextColor(Style.INK3);
        search.setHint("Search chats");
        search.setTypeface(Style.semibold);
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(22));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(0, 0, 0, 0);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setAlpha(0);
        search.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.leftMargin = (int) Style.px(22 + 22 + 12);
        lp.rightMargin = (int) Style.px(22);
        addView(search, lp);

        glideX = new Spring(0, 380, 30, (v, vel) -> content.invalidate());
        glideW = new Spring(0, 380, 30, (v, vel) -> content.invalidate());
        tabsOffset = new Spring(0, 300, 34, (v, vel) -> content.invalidate());
    }

    public void setTabs(ArrayList<String> names, int selected, boolean snap) {
        tabs.clear();
        tabs.addAll(names);
        select(selected, snap);
    }

    public void select(int index, boolean snap) {
        current = Math.max(0, Math.min(tabs.size() - 1, index));
        if (getWidth() == 0) {
            post(() -> select(current, true));
            return;
        }
        float[] xs = content.tabX();
        if (xs == null) return;
        float x = xs[current * 2], w = xs[current * 2 + 1];
        glideX.to(x, snap);
        glideW.to(w, snap);
        // * keep the picked tab in the space the tabs have
        float room = content.tabsRoom(), total = content.tabsWidth(), off = tabsOffset.t;
        if (total > room) {
            if (x - off < 0) off = x;
            if (x + w - off > room) off = x + w - room;
            off = Math.max(0, Math.min(off, total - room));
        } else {
            off = 0;
        }
        tabsOffset.to(off, snap);
        content.invalidate();
    }

    public int selected() {
        return current;
    }

    public int tabCount() {
        return tabs.size();
    }

    public void setSearching(boolean on) {
        if (searchAnim != null) searchAnim.cancel();
        search.setFocusable(on);
        search.setFocusableInTouchMode(on);
        if (Spring.reduced) {
            searching = on ? 1 : 0;
            apply(searching, searching);
            return;
        }
        float from = searching;
        searchAnim = ValueAnimator.ofFloat(0, 1);
        searchAnim.setDuration(300);
        org.telegram.ui.Components.CubicBezierInterpolator ease = new org.telegram.ui.Components.CubicBezierInterpolator(.2, .9, .3, 1);
        float to = on ? 1 : 0;
        searchAnim.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            // * opacity .2s linear-ish, movement .3s ease-out
            float fade = from + (to - from) * Math.min(1, t / (200f / 300f));
            float move = from + (to - from) * ease.getInterpolation(t);
            searching = move;
            apply(fade, move);
        });
        searchAnim.start();
    }

    private void apply(float fade, float move) {
        search.setAlpha(fade);
        search.setTranslationY(Style.px(14) * (1 - move));
        content.fade = 1 - fade;
        content.lift = move;
        content.invalidate();
    }

    /** The title and the tabs, drawn by hand. */
    private final class Content extends View {
        float fade = 1, lift;
        private final TextPaint title = Style.text(Style.display, 27, Style.INK);
        private final TextPaint tab = Style.text(Style.semibold, 18, Style.INK);
        private final TextPaint icon = Style.text(Style.regular, 22, Style.fg(179));
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glideRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Content(Context context) {
            super(context);
            box.setColor(Style.fg(18));
            glide.setColor(Color.argb(46, 255, 255, 255));
            glideRim.setStyle(Paint.Style.STROKE);
            glideRim.setStrokeWidth(Style.px(1.5f));
            glideRim.setColor(Color.argb(77, 255, 255, 255));
        }

        float tabsWidth() {
            float w = Style.px(8);
            for (int i = 0; i < tabs.size(); i++) w += tab.measureText(tabs.get(i)) + Style.px(28) + (i > 0 ? Style.px(4) : 0);
            return w;
        }

        float tabsRoom() {
            float titleW = title.measureText("Kyogram");
            return getWidth() - Style.px(22) - titleW - Style.px(12) - Style.px(10);
        }

        /** x and width of each tab, from the tabs box's left edge. */
        float[] tabX() {
            float[] xs = new float[tabs.size() * 2];
            float x = Style.px(4);
            for (int i = 0; i < tabs.size(); i++) {
                float w = tab.measureText(tabs.get(i)) + Style.px(28);
                xs[i * 2] = x;
                xs[i * 2 + 1] = w;
                x += w + Style.px(4);
            }
            return xs;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (fade <= .01f && searching >= .99f) {
                drawIcon(c);
                return;
            }
            float h = getHeight();
            c.save();
            c.translate(0, -Style.px(14) * lift);
            int a = c.saveLayerAlpha(0, -Style.px(20), getWidth(), h + Style.px(20), (int) (255 * fade));
            Paint.FontMetrics fm = title.getFontMetrics();
            c.drawText("Kyogram", Style.px(22), h / 2f - (fm.ascent + fm.descent) / 2f, title);

            float room = Math.min(tabsRoom(), tabsWidth());
            float boxH = Style.px(44), right = getWidth() - Style.px(10), left = right - room;
            r.set(left, (h - boxH) / 2f, right, (h + boxH) / 2f);
            c.drawRoundRect(r, Style.px(24), Style.px(24), box);
            c.save();
            c.clipRect(r);
            c.translate(left - tabsOffset.v, r.top);
            float[] xs = tabX();
            // * the pill
            RectF g = new RectF(glideX.v, Style.px(4), glideX.v + glideW.v, boxH - Style.px(4));
            c.drawRoundRect(g, Style.px(20), Style.px(20), glide);
            g.inset(Style.px(.75f), Style.px(.75f));
            c.drawRoundRect(g, Style.px(20), Style.px(20), glideRim);
            Paint.FontMetrics tm = tab.getFontMetrics();
            for (int i = 0; i < tabs.size(); i++) {
                // * the label under the pill is full white, the others dimmed; between, as the pill passes
                float mid = xs[i * 2] + xs[i * 2 + 1] / 2f, gm = glideX.v + glideW.v / 2f;
                float near = Math.max(0, 1 - Math.abs(mid - gm) / Math.max(1, xs[i * 2 + 1]));
                tab.setAlpha((int) (158 + (255 - 158) * near));
                c.drawText(tabs.get(i), xs[i * 2] + Style.px(14), boxH / 2f - (tm.ascent + tm.descent) / 2f, tab);
            }
            c.restore();
            c.restoreToCount(a);
            c.restore();
            drawIcon(c);
        }

        private void drawIcon(Canvas c) {
            if (searching <= .01f) return;
            icon.setAlpha((int) (179 * Math.min(1, searching)));
            Paint.FontMetrics fm = icon.getFontMetrics();
            c.drawText("⌕", Style.px(22), getHeight() / 2f - (fm.ascent + fm.descent) / 2f + Style.px(14) * (1 - searching), icon);
        }
    }
}
