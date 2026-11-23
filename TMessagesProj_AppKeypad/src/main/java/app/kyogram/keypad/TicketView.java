package app.kyogram.keypad;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.HashMap;

import app.kyogram.ui.Care;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;

/**
 * A Care ticket, drawn by hand the way a chat thread is: glass cards for what the ticket is (its status, the
 * customer, the details, the issue, what came with it) and then its history as bubbles — notes in the accent
 * on the right, the shop's steps in glass on the left. The same bright ring as in a chat glides to the block
 * in focus and takes its size; the page scrolls on a spring to keep it in view. Blocks pop in one after
 * another when the ticket arrives (the prototype's pop: up from below, .6 → 1 on a bouncy spring), a new note
 * pops in at the bottom, and a status change rolls the chip's words and gives it a squeeze.
 */
final class TicketView extends View {

    interface Listener {
        void onFocus(Block b);
    }

    static final int STATUS = 1, CUSTOMER = 2, DETAILS = 3, ISSUE = 4, ACCESSORIES = 5, NOTE = 6, EVENT = 7, LABEL = 8, SKELETON = 9;

    static final class Block {
        int kind;
        String key, title, who, when;
        CharSequence body;
        ArrayList<String> chips;
        boolean pending, focusable = true;
        // * status
        String status, oldStatus;
        int color, oldColor;
        float chipT = 1, chipS = 1;
        Spring chipSpring, chipScale;
        // * laid out
        float y, h, x, w;
        StaticLayout layout;
        Spring ys, pop;
        float p = 1;
        final Style.Glass glass = new Style.Glass();
    }

    private final ArrayList<Block> blocks = new ArrayList<>();
    private final HashMap<String, Block> byKey = new HashMap<>();
    private int focus;
    private Listener listener;

    private final Spring scroll, ringX, ringY, ringW, ringH, ringFade;
    private float ringAlpha;
    private float laidW;

    private final TextPaint titlePaint = Style.text(Style.semibold, 15, Style.INK3);
    private final TextPaint bodyPaint = Style.text(Style.regular, 20, Style.INK);
    private final TextPaint strongPaint = Style.text(Style.bold, 22, Style.INK);
    private final TextPaint smallPaint = Style.text(Style.regular, 14, Style.INK3);
    private final TextPaint whoPaint = Style.text(Style.bold, 17, Style.WARN);
    private final TextPaint chipPaint = Style.text(Style.bold, 20, Style.INK);
    private final TextPaint accPaint = Style.text(Style.semibold, 17, Style.INK);
    private final TextPaint hintPaint = Style.text(Style.semibold, 14, Style.INK2);
    private final TextPaint labelPaint = Style.text(Style.semibold, 15, Style.fg(179));
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private ValueAnimator shimmer;
    private float shimmerT;

    TicketView(Context c) {
        super(c);
        scroll = new Spring(0, 300, 34, (v, vel) -> invalidate());
        ringX = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringY = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringW = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringH = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringFade = new Spring(0, 400, 40, (v, vel) -> { ringAlpha = v; invalidate(); });
        inFill.setColor(Style.day ? Color.argb(184, 255, 255, 255) : Color.argb(153, 30, 27, 48));
        inRim.setStyle(Paint.Style.STROKE);
        inRim.setStrokeWidth(Style.px(1.5f));
        inRim.setColor(Color.argb(46, 255, 255, 255));
        outGlow.setColor(Color.argb(77, 139, 116, 242));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Style.px(2.5f));
        ringPaint.setColor(Color.WHITE);
        ringGlow.setStyle(Paint.Style.STROKE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ── content ──

    /** Grey cards that breathe while the ticket loads. */
    void showLoading() {
        clear();
        int[] heights = {118, 132, 176, 120};
        for (int i = 0; i < heights.length; i++) {
            Block b = new Block();
            b.kind = SKELETON;
            b.key = "sk" + i;
            b.focusable = false;
            b.h = Style.px(heights[i]);
            add(b);
        }
        layoutBlocks(true);
        if (shimmer == null) {
            shimmer = ValueAnimator.ofFloat(0, 1);
            shimmer.setDuration(1200);
            shimmer.setRepeatCount(ValueAnimator.INFINITE);
            shimmer.setInterpolator(new LinearInterpolator());
            shimmer.addUpdateListener(a -> { shimmerT = (float) a.getAnimatedValue(); invalidate(); });
        }
        if (!Spring.reduced) shimmer.start();
        focus = 0;
        ringFade.to(0, true);
        scroll.to(0, true);
        stagger(true);
    }

    void clear() {
        blocks.clear();
        byKey.clear();
        invalidate();
    }

    /**
     * The ticket. Blocks it already had stay where they are (and slide if something comes in above them);
     * new ones pop in, one after another; a changed status rolls its chip.
     */
    void setDetail(Care.Detail d, String statusLabel, int statusColor, boolean keepFocus) {
        if (shimmer != null) shimmer.cancel();
        boolean first = blocks.isEmpty() || blocks.get(0).kind == SKELETON;
        Block was = keepFocus ? focused() : null;
        String keepKey = was != null ? was.key : null;
        HashMap<String, Block> old = new HashMap<>(byKey);
        blocks.clear();
        byKey.clear();

        Block st = reuse(old, "status", STATUS);
        st.title = "STATUS";
        if (st.status != null && !st.status.equals(statusLabel)) {
            st.oldStatus = st.status;
            st.oldColor = st.color;
            st.chipT = 0;
            if (st.chipSpring == null) st.chipSpring = new Spring(0, 300, 30, (v, vel) -> { st.chipT = v; invalidate(); });
            if (st.chipScale == null) st.chipScale = new Spring(1, 500, 16, (v, vel) -> { st.chipS = v; invalidate(); });
            st.chipSpring.to(0, true);
            st.chipSpring.to(1);
            st.chipScale.to(.86f, true);
            st.chipScale.to(1);
        }
        st.status = statusLabel;
        st.color = statusColor;
        st.body = TextUtils.isEmpty(d.header) ? null : d.header;
        add(st);

        if (!TextUtils.isEmpty(d.customer) || !TextUtils.isEmpty(d.phone)) {
            Block cu = reuse(old, "customer", CUSTOMER);
            cu.title = "CUSTOMER";
            cu.who = d.customer;
            cu.body = TextUtils.isEmpty(d.phone) ? null : "✆  " + d.phone;
            add(cu);
        }
        if (!d.meta.isEmpty()) {
            Block de = reuse(old, "details", DETAILS);
            de.title = "DETAILS";
            SpannableStringBuilder sb = new SpannableStringBuilder();
            for (String[] kv : d.meta) {
                if (kv.length < 2 || TextUtils.isEmpty(kv[1])) continue;
                if (sb.length() > 0) sb.append('\n');
                int s = sb.length();
                sb.append(kv[0]).append("  ");
                sb.setSpan(new ForegroundColorSpan(Style.INK2), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(kv[1]);
            }
            de.body = sb;
            if (sb.length() > 0) add(de);
        }
        if (!TextUtils.isEmpty(d.issue)) {
            Block is = reuse(old, "issue", ISSUE);
            is.title = "ISSUE";
            is.body = d.issue;
            add(is);
        }
        if (!d.accessories.isEmpty()) {
            Block ac = reuse(old, "acc", ACCESSORIES);
            ac.title = "CAME WITH IT";
            ac.chips = new ArrayList<>(d.accessories);
            add(ac);
        }
        if (!d.timeline.isEmpty()) {
            Block lb = reuse(old, "label", LABEL);
            lb.title = "History";
            lb.focusable = false;
            add(lb);
            // * oldest first, so the newest sits by the note box
            ArrayList<Care.Entry> tl = new ArrayList<>(d.timeline);
            if (tl.size() > 1 && isNewestFirst(tl)) java.util.Collections.reverse(tl);
            for (Care.Entry e : tl) {
                String k = "t:" + e.title + "|" + e.when + "|" + e.body;
                Block b = reuse(old, k, e.note ? NOTE : EVENT);
                b.title = e.title;
                b.who = e.who;
                b.when = e.when;
                b.body = e.body;
                b.pending = false;
                add(b);
            }
        }
        // * a note still on its way stays at the bottom until the site has it
        for (Block b : old.values()) {
            if (b.pending && !byKey.containsKey(b.key)) add(b);
        }
        layoutBlocks(first);
        if (first) stagger(false);
        else for (Block b : blocks) if (b.p < 1 && (b.pop == null || !b.pop.moving())) popIn(b, 0);

        int idx = keepKey != null ? indexOfKey(keepKey) : -1;
        focus = idx >= 0 ? idx : firstFocusable();
        moveFocus(0, first);
    }

    private static boolean isNewestFirst(ArrayList<Care.Entry> tl) {
        String a = tl.get(0).when, b = tl.get(tl.size() - 1).when;
        return a != null && b != null && a.compareTo(b) > 0 && a.length() == b.length();
    }

    /** A note you just sent: it pops in at once, a little faint, and firms up when DroidShop has it. */
    Block addPending(String text) {
        Block b = new Block();
        b.kind = NOTE;
        b.key = "pending:" + System.nanoTime();
        b.title = "Note";
        b.who = "You";
        b.when = "sending…";
        b.body = text;
        b.pending = true;
        b.p = 0;
        add(b);
        layoutBlocks(false);
        popIn(b, 0);
        focus = blocks.size() - 1;
        moveFocus(0, false);
        return b;
    }

    void dropPending() {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block b = blocks.get(i);
            if (b.pending) {
                blocks.remove(i);
                byKey.remove(b.key);
            }
        }
        layoutBlocks(false);
        focus = Math.min(focus, blocks.size() - 1);
        moveFocus(0, false);
    }

    private Block reuse(HashMap<String, Block> old, String key, int kind) {
        Block b = old.remove(key);
        if (b == null || b.kind != kind) {
            b = new Block();
            b.kind = kind;
            b.key = key;
            b.p = 0;
        }
        return b;
    }

    private void add(Block b) {
        blocks.add(b);
        byKey.put(b.key, b);
    }

    private int indexOfKey(String k) {
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).key.equals(k)) return i;
        return -1;
    }

    private int firstFocusable() {
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).focusable) return i;
        return 0;
    }

    private void stagger(boolean skeleton) {
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            b.p = 0;
            popIn(b, (skeleton ? 30 : 45) * i);
        }
    }

    private void popIn(Block b, long delay) {
        if (Spring.reduced) {
            b.p = 1;
            invalidate();
            return;
        }
        if (b.pop == null) b.pop = new Spring(0, 300, 18, (v, vel) -> { b.p = v; invalidate(); });
        b.pop.to(0, true);
        b.p = 0;
        Runnable go = () -> b.pop.to(1);
        if (delay > 0) postDelayed(go, delay);
        else go.run();
    }

    // ── laying out ──

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != laidW) {
            layoutBlocks(true);
            moveFocus(0, true);
        }
    }

    private void layoutBlocks(boolean snap) {
        float W = getWidth();
        if (W <= 0) return;
        laidW = W;
        float side = Style.px(16), pad = Style.px(18), gap = Style.px(12), y = Style.px(10);
        float card = W - side * 2;
        for (Block b : blocks) {
            switch (b.kind) {
                case SKELETON:
                    b.x = side;
                    b.w = card;
                    break;
                case STATUS: {
                    b.x = side;
                    b.w = card;
                    float h = pad + titleH() + Style.px(10) + Style.px(44);
                    if (b.body != null) {
                        b.layout = layout(b.body, smallPaint, (int) (card - pad * 2));
                        h += Style.px(10) + b.layout.getHeight();
                    }
                    b.h = h + pad;
                    break;
                }
                case CUSTOMER: {
                    b.x = side;
                    b.w = card;
                    SpannableStringBuilder s = new SpannableStringBuilder();
                    if (!TextUtils.isEmpty(b.who)) s.append(b.who);
                    b.layout = layout(s, strongPaint, (int) (card - pad * 2));
                    float h = pad + titleH() + Style.px(8) + (s.length() > 0 ? b.layout.getHeight() : 0);
                    if (b.body != null) h += Style.px(6) + lineH(bodyPaint);
                    b.h = h + pad;
                    break;
                }
                case DETAILS:
                case ISSUE:
                    b.x = side;
                    b.w = card;
                    b.layout = layout(b.body, bodyPaint, (int) (card - pad * 2));
                    b.h = pad + titleH() + Style.px(8) + b.layout.getHeight() + pad;
                    break;
                case ACCESSORIES: {
                    b.x = side;
                    b.w = card;
                    float cx = 0, rows = 1, chipH = Style.px(36), cg = Style.px(8), room = card - pad * 2;
                    for (String ch : b.chips) {
                        float cw = accPaint.measureText(ch) + Style.px(28);
                        if (cx > 0 && cx + cw > room) {
                            rows++;
                            cx = 0;
                        }
                        cx += cw + cg;
                    }
                    b.h = pad + titleH() + Style.px(10) + rows * chipH + (rows - 1) * cg + pad;
                    break;
                }
                case LABEL:
                    b.w = labelPaint.measureText(b.title) + Style.px(28);
                    b.x = (W - b.w) / 2f;
                    b.h = Style.px(30);
                    break;
                case NOTE:
                case EVENT: {
                    float max = W * .8f - side;
                    SpannableStringBuilder s = new SpannableStringBuilder();
                    if (b.kind == EVENT && !TextUtils.isEmpty(b.title)) {
                        s.append(b.title);
                        s.setSpan(new ForegroundColorSpan(Style.WARN), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (!TextUtils.isEmpty(b.body)) s.append('\n');
                    }
                    if (!TextUtils.isEmpty(b.body)) s.append(b.body);
                    if (s.length() == 0) s.append(" ");
                    b.layout = layout(s, bodyPaint, (int) (max - Style.px(36)));
                    float tw = 0;
                    for (int i = 0; i < b.layout.getLineCount(); i++) tw = Math.max(tw, b.layout.getLineWidth(i));
                    String foot = foot(b);
                    tw = Math.max(tw, smallPaint.measureText(foot));
                    b.w = Math.min(max, tw + Style.px(36));
                    b.x = b.kind == NOTE ? W - side - b.w : side;
                    b.h = Style.px(12) + b.layout.getHeight() + Style.px(4) + lineH(smallPaint) + Style.px(10);
                    break;
                }
            }
            if (b.ys == null) {
                b.ys = new Spring(y, 260, 26, (v, vel) -> { b.y = v; invalidate(); });
                b.y = y;
            } else {
                b.ys.to(y, snap || b.p < .05f);
            }
            y += b.h + gap;
        }
        invalidate();
    }

    private static String foot(Block b) {
        String who = b.who != null ? b.who : "";
        String when = b.when != null ? b.when : "";
        return who.isEmpty() ? when : when.isEmpty() ? who : who + " · " + when;
    }

    private float contentHeight() {
        if (blocks.isEmpty()) return 0;
        Block last = blocks.get(blocks.size() - 1);
        return last.ys.t + last.h + Style.px(16);
    }

    private float titleH() {
        return lineH(titlePaint);
    }

    private static float lineH(TextPaint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return fm.descent - fm.ascent;
    }

    private static StaticLayout layout(CharSequence s, TextPaint p, int w) {
        return new StaticLayout(s == null ? "" : s, p, Math.max(1, w), Layout.Alignment.ALIGN_NORMAL, 1.15f, 0, false);
    }

    // ── focus: the ring ──

    Block focused() {
        return focus >= 0 && focus < blocks.size() ? blocks.get(focus) : null;
    }

    boolean atLast() {
        for (int i = focus + 1; i < blocks.size(); i++) if (blocks.get(i).focusable) return false;
        return true;
    }

    void move(int d) {
        int i = focus;
        do {
            i += d;
        } while (i >= 0 && i < blocks.size() && !blocks.get(i).focusable);
        if (i < 0 || i >= blocks.size()) {
            // * past the top: just show the very top
            if (d < 0) scroll.to(0);
            return;
        }
        focus = i;
        moveFocus(0, false);
    }

    void focusLast() {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (blocks.get(i).focusable) {
                focus = i;
                break;
            }
        }
        moveFocus(0, false);
    }

    void showRing(boolean show) {
        ringFade.to(show ? 1 : 0);
    }

    void moveFocus(int d, boolean snap) {
        Block b = focused();
        if (b == null || getHeight() == 0) return;
        float y = b.ys != null ? b.ys.t : b.y, port = getHeight();
        float ns = scroll.t;
        if (y - Style.px(24) < ns) ns = y - Style.px(24);
        if (y + b.h + Style.px(14) > ns + port) ns = y + b.h + Style.px(14) - port;
        ns = Math.max(0, Math.min(ns, Math.max(0, contentHeight() - port)));
        if (b == firstFocusableBlock()) ns = 0;
        scroll.to(ns, snap);
        ringX.to(b.x - Style.px(4), snap);
        ringY.to(y - Style.px(4), snap);
        ringW.to(b.w + Style.px(8), snap);
        ringH.to(b.h + Style.px(8), snap);
        if (listener != null) listener.onFocus(b);
    }

    private Block firstFocusableBlock() {
        int i = firstFocusable();
        return i < blocks.size() ? blocks.get(i) : null;
    }

    /** The block in focus gives a small squeeze (a press that does nothing more). */
    void bounce() {
        Block b = focused();
        if (b == null || Spring.reduced) return;
        if (b.pop == null) b.pop = new Spring(1, 300, 18, (v, vel) -> { b.p = v; invalidate(); });
        b.pop.to(.9f, true);
        b.pop.to(1);
    }

    /** The status chip pulses while a change is on its way. */
    void pulseStatus(boolean on) {
        Block st = byKey.get("status");
        if (st == null) return;
        if (st.chipScale == null) st.chipScale = new Spring(1, 500, 16, (v, vel) -> { st.chipS = v; invalidate(); });
        st.chipScale.to(on ? .92f : 1f);
    }

    // ── drawing ──

    @Override
    protected void onDraw(Canvas c) {
        getLocationInWindow(loc);
        float sc = scroll.v;
        for (Block b : blocks) {
            float top = b.y - sc;
            if (top > getHeight() + Style.px(40) || top + b.h < -Style.px(40)) continue;
            float p = Math.max(0, b.p);
            if (p <= .001f) continue;
            c.save();
            boolean bubble = b.kind == NOTE || b.kind == EVENT;
            float s = bubble ? .6f + .4f * p : .92f + .08f * p;
            float px = b.kind == NOTE ? b.x + b.w : b.kind == EVENT ? b.x : b.x + b.w / 2f;
            c.translate(0, (1 - p) * Style.px(30));
            c.scale(s, s, px, top + b.h);
            int alpha = (int) (255 * Math.min(1, p * 2) * (b.pending ? .62f : 1f));
            int layer = alpha < 255 ? c.saveLayerAlpha(b.x - Style.px(30), top - Style.px(30), b.x + b.w + Style.px(30), top + b.h + Style.px(30), alpha) : -1;
            drawBlock(c, b, top);
            if (layer >= 0) c.restoreToCount(layer);
            c.restore();
        }
        if (ringAlpha > .01f && focused() != null) {
            float dy = ringY.v - sc;
            r.set(ringX.v, dy, ringX.v + ringW.v, dy + ringH.v);
            float rad = Style.px(30);
            for (int k = 3; k >= 1; k--) {
                ringGlow.setStrokeWidth(Style.px(4 * k + 2));
                ringGlow.setColor(Color.argb((int) (34 / k * ringAlpha), 255, 255, 255));
                c.drawRoundRect(r, rad, rad, ringGlow);
            }
            ringPaint.setAlpha((int) (255 * ringAlpha));
            float i = Style.px(1.25f);
            c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, rad, rad, ringPaint);
        }
    }

    private void drawBlock(Canvas c, Block b, float top) {
        float pad = Style.px(18);
        switch (b.kind) {
            case SKELETON: {
                c.save();
                c.translate(b.x, top);
                b.glass.draw(c, b.w, b.h, Style.px(26), loc[0] + b.x, loc[1] + top);
                c.restore();
                float pulse = .5f + .5f * (float) Math.sin(shimmerT * Math.PI * 2 + top / Style.px(120));
                fill.setShader(null);
                fill.setColor(Style.fg((int) (22 + 22 * pulse)));
                float bx = b.x + pad, by = top + pad;
                c.drawRoundRect(bx, by, bx + Style.px(110), by + Style.px(14), Style.px(7), Style.px(7), fill);
                c.drawRoundRect(bx, by + Style.px(30), b.x + b.w - pad - Style.px(40), by + Style.px(52), Style.px(11), Style.px(11), fill);
                if (b.h > Style.px(125)) c.drawRoundRect(bx, by + Style.px(66), b.x + b.w * .6f, by + Style.px(88), Style.px(11), Style.px(11), fill);
                return;
            }
            case LABEL: {
                fill.setShader(null);
                fill.setColor(Style.fg(26));
                r.set(b.x, top, b.x + b.w, top + b.h);
                c.drawRoundRect(r, b.h / 2f, b.h / 2f, fill);
                Paint.FontMetrics fm = labelPaint.getFontMetrics();
                c.drawText(b.title, b.x + Style.px(14), top + b.h / 2f - (fm.ascent + fm.descent) / 2f, labelPaint);
                return;
            }
            case NOTE:
            case EVENT: {
                float rad = Style.px(24);
                r.set(b.x, top, b.x + b.w, top + b.h);
                if (b.kind == NOTE) {
                    fill.setShader(null);
                    c.drawRoundRect(r.left, r.top + Style.px(6), r.right, r.bottom + Style.px(6), rad, rad, outGlow);
                    fill.setShader(new LinearGradient(r.left, r.top, r.right * .6f + r.left * .4f, r.bottom, Style.ACCENT_HI, Style.ACCENT, Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, rad, rad, fill);
                    fill.setShader(null);
                } else {
                    c.drawRoundRect(r, rad, rad, inFill);
                    c.drawRoundRect(r, rad, rad, inRim);
                }
                c.save();
                c.translate(b.x + Style.px(18), top + Style.px(12));
                b.layout.draw(c);
                c.restore();
                smallPaint.setColor(b.kind == NOTE ? Color.argb(199, 255, 255, 255) : Style.INK3);
                String foot = foot(b);
                float fy = top + Style.px(12) + b.layout.getHeight() + Style.px(4) - smallPaint.getFontMetrics().ascent;
                c.drawText(foot, b.x + b.w - Style.px(18) - smallPaint.measureText(foot), fy, smallPaint);
                smallPaint.setColor(Style.INK3);
                return;
            }
        }
        // * a glass card
        c.save();
        c.translate(b.x, top);
        b.glass.draw(c, b.w, b.h, Style.px(26), loc[0] + b.x, loc[1] + top);
        c.restore();
        float x = b.x + pad, y = top + pad;
        Paint.FontMetrics tm = titlePaint.getFontMetrics();
        c.drawText(b.title, x, y - tm.ascent, titlePaint);
        y += titleH();
        switch (b.kind) {
            case STATUS: {
                y += Style.px(10);
                float chipH = Style.px(44);
                drawStatusChip(c, b, x, y, chipH);
                if (focused() == b && ringAlpha > .01f) {
                    String h = "OK · change";
                    hintPaint.setAlpha((int) (255 * ringAlpha));
                    Paint.FontMetrics hm = hintPaint.getFontMetrics();
                    c.drawText(h, b.x + b.w - pad - hintPaint.measureText(h), y + chipH / 2f - (hm.ascent + hm.descent) / 2f, hintPaint);
                    hintPaint.setAlpha(255);
                }
                y += chipH;
                if (b.layout != null && b.body != null) {
                    c.save();
                    c.translate(x, y + Style.px(10));
                    b.layout.draw(c);
                    c.restore();
                }
                break;
            }
            case CUSTOMER: {
                y += Style.px(8);
                if (!TextUtils.isEmpty(b.who)) {
                    c.save();
                    c.translate(x, y);
                    b.layout.draw(c);
                    c.restore();
                    y += b.layout.getHeight();
                }
                if (b.body != null) {
                    y += Style.px(6);
                    Paint.FontMetrics bm = bodyPaint.getFontMetrics();
                    bodyPaint.setColor(Style.ACCENT_HI);
                    c.drawText(b.body.toString(), x, y - bm.ascent, bodyPaint);
                    bodyPaint.setColor(Style.INK);
                    if (focused() == b && ringAlpha > .01f) {
                        String h = "OK · call";
                        hintPaint.setAlpha((int) (255 * ringAlpha));
                        c.drawText(h, b.x + b.w - pad - hintPaint.measureText(h), y - bm.ascent, hintPaint);
                        hintPaint.setAlpha(255);
                    }
                }
                break;
            }
            case DETAILS:
            case ISSUE:
                c.save();
                c.translate(x, y + Style.px(8));
                b.layout.draw(c);
                c.restore();
                break;
            case ACCESSORIES: {
                y += Style.px(10);
                float cx = 0, chipH = Style.px(36), cg = Style.px(8), room = b.w - pad * 2;
                Paint.FontMetrics am = accPaint.getFontMetrics();
                fill.setShader(null);
                fill.setColor(Style.fg(30));
                for (String ch : b.chips) {
                    float cw = accPaint.measureText(ch) + Style.px(28);
                    if (cx > 0 && cx + cw > room) {
                        cx = 0;
                        y += chipH + cg;
                    }
                    r.set(x + cx, y, x + cx + cw, y + chipH);
                    c.drawRoundRect(r, chipH / 2f, chipH / 2f, fill);
                    c.drawText(ch, r.left + Style.px(14), r.centerY() - (am.ascent + am.descent) / 2f, accPaint);
                    cx += cw + cg;
                }
                break;
            }
        }
    }

    /** The status as a big chip; on a change the old words roll up and out as the new roll in. */
    private void drawStatusChip(Canvas c, Block b, float x, float y, float h) {
        float t = Math.max(0, Math.min(1, b.chipT));
        String now = b.status != null ? b.status : "";
        String before = b.oldStatus != null && t < 1 ? b.oldStatus : null;
        float wNow = chipPaint.measureText(now) + Style.px(32);
        float wOld = before != null ? chipPaint.measureText(before) + Style.px(32) : wNow;
        float w = wOld + (wNow - wOld) * t;
        int col = before != null ? blend(b.oldColor, b.color, t) : b.color;
        c.save();
        c.scale(b.chipS, b.chipS, x + w / 2f, y + h / 2f);
        r.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(Color.argb(56, Color.red(col), Color.green(col), Color.blue(col)));
        c.drawRoundRect(r, h / 2f, h / 2f, fill);
        fill.setColor(Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)));
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Style.px(1.5f));
        c.drawRoundRect(r.left + Style.px(.75f), r.top + Style.px(.75f), r.right - Style.px(.75f), r.bottom - Style.px(.75f), h / 2f, h / 2f, fill);
        fill.setStyle(Paint.Style.FILL);
        c.save();
        c.clipRect(r);
        Paint.FontMetrics fm = chipPaint.getFontMetrics();
        float base = y + h / 2f - (fm.ascent + fm.descent) / 2f;
        chipPaint.setColor(col);
        if (before != null) {
            chipPaint.setAlpha((int) (255 * (1 - t)));
            c.drawText(before, x + Style.px(16), base - h * t, chipPaint);
            chipPaint.setAlpha((int) (255 * t));
            c.drawText(now, x + Style.px(16), base + h * (1 - t), chipPaint);
        } else {
            chipPaint.setAlpha(255);
            c.drawText(now, x + Style.px(16), base, chipPaint);
        }
        c.restore();
        c.restore();
    }

    static int blend(int a, int b, float t) {
        return Color.argb(255,
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (shimmer != null) shimmer.cancel();
    }
}
