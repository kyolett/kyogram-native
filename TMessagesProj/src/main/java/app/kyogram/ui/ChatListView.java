package app.kyogram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageReceiver;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The chat list (and the lists on Care, Tasks, Settings): rows drawn by hand, each on its own springs so
 * they slide to a new place, fade in and out of a folder and drift sideways when the folder changes; one
 * glass pill glides to the row in focus and stretches as it moves; the list scrolls on a spring to keep
 * the focus in view. As in the prototype: row 94, pill radius 30, list inset 10 at the sides, 6 at the top.
 */
public class ChatListView extends View {

    public static final float ROW = 94;

    public static class Item {
        public long id;
        public TLObject peer;               // * a user or chat, for the avatar (chat list only)
        public String name;
        public CharSequence preview;
        public String time;
        public int unread;
        public boolean muted;
        public int key;                     // * 1–9: the number key that opens it
        // * pages: a status chip, a value at the end, a check box
        public String chip;
        public int chipColor, chipBg;
        public String value;
        public boolean checkable, done;

        // ── state ──
        StaticLayout nameLayout, prevLayout, oldPrevLayout;
        int laidWidth = -1;
        String laidName;
        boolean laidDone;
        CharSequence laidPreview;
        ImageReceiver avatar;
        AvatarDrawable avatarDrawable;
        Spring ys, os, xs, focusScale, badgeScale;
        float y, o = 1, x, scale = 1, badge = 1;
        int shownUnread = -1, oldUnread;
        float roll = 1, prevIn = 1, check;
    }

    private final ArrayList<Item> all = new ArrayList<>();
    private final ArrayList<Item> visible = new ArrayList<>();
    private final HashMap<Long, Item> byId = new HashMap<>();
    private int focus;
    private final boolean avatars;

    private final Spring scroll, pillY;
    private float pillStretch;
    private final Style.Pill pill = new Style.Pill(true);

    private final TextPaint namePaint = Style.text(Style.bold, 23, Style.INK);
    private final TextPaint doneNamePaint = Style.text(Style.bold, 23, Style.INK3);
    private final TextPaint prevPaint = Style.text(Style.regular, 19, Style.INK2);
    private final TextPaint timePaint = Style.text(Style.regular, 16, Style.INK3);
    private final TextPaint badgePaint = Style.text(Style.bold, 17, Style.INK);
    private final TextPaint keyPaint = Style.text(Style.bold, 14, Style.KEY_INK);
    private final TextPaint chipPaint = Style.text(Style.bold, 15, Style.INK);
    private final TextPaint valuePaint = Style.text(Style.regular, 18, Style.INK2);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final android.graphics.Path checkPath = new android.graphics.Path();

    public interface Listener {
        void onFocusChanged(Item item);
    }

    private Listener listener;

    public ChatListView(Context context, boolean avatars) {
        super(context);
        this.avatars = avatars;
        scroll = new Spring(0, 300, 34, (v, vel) -> invalidate());
        pillY = new Spring(0, 420, 30, (v, vel) -> {
            pillStretch = Math.min(.14f, Math.abs(vel) / (9000f * Style.u));
            invalidate();
        });
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength((int) Style.px(20));
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(0xffffffff);
        doneNamePaint.setStrikeThruText(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ── touch (the phone app): drag to scroll with a fling, tap a row ──

    public interface OnItemClick {
        /** x is where in the row it was tapped (the check box is at its start). */
        void onClick(Item item, float x);
    }

    private OnItemClick onItemClick;
    private VelocityTracker velocity;
    private float downY, downX, downScroll;
    private boolean dragging;

    public void setOnItemClick(OnItemClick l) {
        onItemClick = l;
        setClickable(true);
    }

    private float maxScroll() {
        return Math.max(0, visible.size() * Style.px(ROW) - getHeight() + Style.px(40));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (onItemClick == null) return super.onTouchEvent(e);
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downX = e.getX();
                downScroll = scroll.v;
                dragging = false;
                scroll.to(scroll.v, true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY;
                if (!dragging && Math.abs(dy) > slop) dragging = true;
                if (dragging) {
                    float s = downScroll - dy, max = maxScroll();
                    // * past the ends it gives a little, like a rubber band
                    if (s < 0) s = s / 3f;
                    if (s > max) s = max + (s - max) / 3f;
                    scroll.to(s, true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float target = scroll.v - velocity.getYVelocity() * .35f;
                    scroll.to(Math.max(0, Math.min(maxScroll(), target)));
                } else {
                    float y = e.getY() - Style.px(6) + scroll.v;
                    int i = (int) (y / Style.px(ROW));
                    if (i >= 0 && i < visible.size()) {
                        focus = i;
                        moveFocus(0, false);
                        onItemClick.onClick(visible.get(i), downX - Style.px(10));
                    }
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                dragging = false;
                return true;
        }
        return true;
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return scroll.v > 1 ? 1f : 0f;
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 1f;
    }

    // ── data ──

    private Item stateFor(Item fresh) {
        Item found = byId.get(fresh.id);
        final Item it = found != null ? found : fresh;
        if (found == null) {
            final Item f = it;
            it.ys = new Spring(0, 260, 26, (v, vel) -> { f.y = v; invalidate(); });
            it.os = new Spring(0, 260, 30, (v, vel) -> { f.o = v; invalidate(); });
            it.xs = new Spring(0, 260, 28, (v, vel) -> { f.x = v; invalidate(); });
            it.focusScale = new Spring(1, 300, 20, (v, vel) -> { f.scale = v; invalidate(); });
            it.badgeScale = new Spring(fresh.unread > 0 ? 1 : 0, 320, 18, (v, vel) -> { f.badge = v; invalidate(); });
            it.o = 0;
            it.shownUnread = fresh.unread;
            it.check = fresh.done ? 1 : 0;
            if (avatars && fresh.peer != null) {
                it.avatar = new ImageReceiver(this);
                it.avatar.setRoundRadius((int) Style.px(31));
                it.avatarDrawable = new AvatarDrawable();
                if (isAttachedToWindow()) it.avatar.onAttachedToWindow();
            }
            byId.put(it.id, it);
        } else {
            // * the same row with new content: what changed animates
            boolean previewChanged = it.preview != null && fresh.preview != null && !TextUtils.equals(it.preview.toString(), fresh.preview.toString());
            it.peer = fresh.peer;
            it.name = fresh.name;
            if (previewChanged) {
                it.oldPrevLayout = it.prevLayout;
                animate01(v -> it.prevIn = v, 320);
            }
            it.preview = fresh.preview;
            it.time = fresh.time;
            it.muted = fresh.muted;
            it.key = fresh.key;
            it.chip = fresh.chip;
            it.chipColor = fresh.chipColor;
            it.chipBg = fresh.chipBg;
            it.value = fresh.value;
            it.checkable = fresh.checkable;
            if (fresh.unread != it.unread) setUnread(it, fresh.unread);
            if (fresh.done != it.done) {
                it.done = fresh.done;
                animate01(v -> it.check = it.done ? v : 1 - v, 350);
            }
        }
        it.unread = fresh.unread;
        if (it.avatar != null && it.peer != null) {
            it.avatarDrawable.setInfo(it.peer);
            it.avatar.setForUserOrChat(it.peer, it.avatarDrawable);
        }
        return it;
    }

    private void setUnread(Item it, int unread) {
        it.badgeScale.to(unread > 0 ? 1 : 0);
        if (unread > 0 && it.shownUnread > 0 && unread != it.shownUnread) {
            // * the number rolls: the old one goes up and out, the new one comes up from below
            it.oldUnread = it.shownUnread;
            animate01(v -> it.roll = v, 350);
        }
        if (unread > 0) it.shownUnread = unread;
    }

    private interface F {
        void set(float v);
    }

    private void animate01(F f, long ms) {
        if (Spring.reduced) {
            f.set(1);
            invalidate();
            return;
        }
        f.set(0);
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(ms);
        a.setInterpolator(new CubicBezierInterpolator(.2, .9, .3, 1));
        a.addUpdateListener(an -> {
            f.set((float) an.getAnimatedValue());
            invalidate();
        });
        a.start();
    }

    public static final int FROM_NONE = 0, FROM_RIGHT = 1, FROM_LEFT = -1;

    /**
     * Shows these items (in this order): new ones fade in where they belong, gone ones fade out, the
     * rest slide to their new place. {@code from} slides them in from the side (a folder change).
     */
    public void setItems(ArrayList<Item> items, int from, boolean keepFocus, boolean snap) {
        Item focused = focusedItem();
        ArrayList<Item> next = new ArrayList<>();
        for (Item fresh : items) {
            next.add(stateFor(fresh));
        }
        float slide = Style.px(60) * from;
        for (Item it : byId.values()) {
            int i = next.indexOf(it);
            if (i >= 0) {
                if (it.o < .05f || snap) it.ys.to(i * Style.px(ROW), true);
                else it.ys.to(i * Style.px(ROW));
                it.os.to(1, snap);
                if (from != FROM_NONE) {
                    it.xs.to(slide, true);
                    it.xs.to(0);
                }
            } else {
                it.os.to(0, snap);
                if (from != FROM_NONE) it.xs.to(-slide);
            }
        }
        if (!all.isEmpty() || !next.isEmpty()) {
            all.clear();
            all.addAll(byId.values());
        }
        visible.clear();
        visible.addAll(next);
        int kept = focused != null ? visible.indexOf(focused) : -1;
        focus = keepFocus && kept >= 0 ? kept : Math.min(keepFocus ? focus : 0, Math.max(0, visible.size() - 1));
        moveFocus(0, snap);
        invalidate();
    }

    /** The row in focus gives a small bounce (a press that does nothing more). */
    public void bounce() {
        Item it = focusedItem();
        if (it == null || Spring.reduced) return;
        it.focusScale.to(.86f, true);
        it.focusScale.to(1.025f);
    }

    public void clear() {
        for (Item it : byId.values()) {
            if (it.avatar != null) it.avatar.onDetachedFromWindow();
        }
        byId.clear();
        all.clear();
        visible.clear();
        focus = 0;
        scroll.to(0, true);
        invalidate();
    }

    public Item focusedItem() {
        return focus >= 0 && focus < visible.size() ? visible.get(focus) : null;
    }

    public Item itemWithKey(int key) {
        for (Item it : visible) if (it.key == key) return it;
        return null;
    }

    public ArrayList<Item> items() {
        return visible;
    }

    public int count() {
        return visible.size();
    }

    public void focusItem(Item it, boolean snap) {
        int i = visible.indexOf(it);
        if (i >= 0) {
            focus = i;
            moveFocus(0, snap);
        }
    }

    public void moveFocus(int d, boolean snap) {
        if (visible.isEmpty()) {
            invalidate();
            return;
        }
        focus = Math.max(0, Math.min(visible.size() - 1, focus + d));
        for (int i = 0; i < visible.size(); i++) {
            visible.get(i).focusScale.to(i == focus ? 1.025f : 1f, snap);
        }
        float row = Style.px(ROW);
        pillY.to(focus * row, snap);
        float port = getHeight() - Style.px(30), top = focus * row, s = scroll.t;
        float ns = s;
        if (top - row * .6f < s) ns = top - row * .6f;
        if (top + row * 1.6f > s + port) ns = top + row * 1.6f - port;
        ns = Math.max(0, Math.min(ns, Math.max(0, visible.size() * row - port + Style.px(20))));
        scroll.to(ns, snap);
        if (listener != null) listener.onFocusChanged(focusedItem());
    }

    /** Where a row is on this view right now (for growing a chat out of it). */
    public float rowTop(Item it) {
        return Style.px(6) + it.y - scroll.v;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (Item it : byId.values()) if (it.avatar != null) it.avatar.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (Item it : byId.values()) if (it.avatar != null) it.avatar.onDetachedFromWindow();
    }

    // ── drawing ──

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() - Style.px(20), row = Style.px(ROW);
        c.save();
        c.translate(Style.px(10), Style.px(6) - scroll.v);

        if (!visible.isEmpty()) {
            c.save();
            float st = pillStretch;
            c.translate(0, pillY.v);
            c.scale(1 - st * .25f, 1 + st, w / 2f, row / 2f);
            pill.draw(c, w, row, Style.px(30));
            c.restore();
        }

        float top = scroll.v - row, bottom = scroll.v + getHeight() + row;
        for (Item it : all) {
            if (it.o < .01f || it.y < top || it.y > bottom) continue;
            drawRow(c, it, w, row);
        }
        c.restore();
    }

    private void drawRow(Canvas c, Item it, float w, float h) {
        int save = c.save();
        c.translate(it.x, it.y);
        if (it.o < .999f) c.saveLayerAlpha(-Style.px(20), -Style.px(20), w + Style.px(20), h + Style.px(20), (int) (255 * it.o));
        c.scale(it.scale, it.scale, w / 2f, h / 2f);

        float pad = Style.px(14), gap = Style.px(16);
        float x = pad;

        if (it.checkable) {
            float s = Style.px(40), cy = (h - s) / 2f;
            r.set(x, cy, x + s, cy + s);
            float k = it.check;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Style.alpha(Style.ACCENT, k));
            c.drawRoundRect(r, Style.px(13), Style.px(13), fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Style.px(2.5f));
            fill.setColor(Style.alpha(0x73ffffff, 1 - k));
            r.inset(Style.px(1.25f), Style.px(1.25f));
            c.drawRoundRect(r, Style.px(12), Style.px(12), fill);
            if (k > 0) {
                // * the tick draws itself in (stroke-dashoffset in the prototype)
                checkPath.reset();
                float ox = x + Style.px(8), oy = cy + Style.px(8), sc = Style.px(1);
                android.graphics.Path full = new android.graphics.Path();
                full.moveTo(ox + 5 * sc, oy + 12.5f * sc);
                full.lineTo(ox + 9.5f * sc, oy + 17 * sc);
                full.lineTo(ox + 19 * sc, oy + 7.5f * sc);
                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(full, false);
                float len = 0;
                do { len += pm.getLength(); } while (pm.nextContour());
                pm.setPath(full, false);
                pm.getSegment(0, len * Math.min(1, k * 1.15f), checkPath, true);
                checkPaint.setStrokeWidth(Style.px(3.2f));
                c.drawPath(checkPath, checkPaint);
            }
            x += s + gap;
        } else if (avatars) {
            float s = Style.px(62), cy = (h - s) / 2f;
            if (it.avatar != null) {
                it.avatar.setImageCoords(x, cy, s, s);
                it.avatar.draw(c);
            }
            x += s + gap;
        }

        // * the end: time over the badge, or a chip, or a value
        float right = w - pad, sideW = 0;
        String badgeText = it.shownUnread > 0 ? String.valueOf(it.shownUnread) : "";
        if (it.time != null) {
            float tw = timePaint.measureText(it.time);
            float bw = Math.max(Style.px(32), badgePaint.measureText(badgeText) + Style.px(18));
            sideW = Math.max(tw, bw);
            float colH = Style.px(19 + 8 + 32), top = (h - colH) / 2f;
            Paint.FontMetrics fm = timePaint.getFontMetrics();
            c.drawText(it.time, right - tw, top + Style.px(19) / 2f - (fm.ascent + fm.descent) / 2f, timePaint);
            if (it.badge > .01f && it.shownUnread > 0) drawBadge(c, it, right - bw, top + Style.px(27), bw, badgeText);
        } else if (it.chip != null) {
            float cw = chipPaint.measureText(it.chip) + Style.px(22), ch = Style.px(28);
            sideW = cw;
            r.set(right - cw, (h - ch) / 2f, right, (h + ch) / 2f);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(it.chipBg);
            c.drawRoundRect(r, Style.px(12), Style.px(12), fill);
            chipPaint.setColor(it.chipColor);
            Paint.FontMetrics fm = chipPaint.getFontMetrics();
            c.drawText(it.chip, r.left + Style.px(11), r.centerY() - (fm.ascent + fm.descent) / 2f, chipPaint);
        } else if (it.value != null) {
            String v = it.value + " ›";
            float vw = valuePaint.measureText(v);
            sideW = vw;
            Paint.FontMetrics fm = valuePaint.getFontMetrics();
            c.drawText(v, right - vw, h / 2f - (fm.ascent + fm.descent) / 2f, valuePaint);
        }

        // * name and preview
        int textW = (int) Math.max(Style.px(40), right - (sideW > 0 ? sideW + gap : 0) - x);
        if (it.laidWidth != textW || !TextUtils.equals(it.laidName, it.name) || it.laidPreview != it.preview || it.laidDone != it.done) {
            it.laidDone = it.done;
            it.nameLayout = single(it.name, it.done ? doneNamePaint : namePaint, textW);
            if (it.preview != null) {
                CharSequence p = Emoji.replaceEmoji(it.preview, prevPaint.getFontMetricsInt(), false);
                it.prevLayout = single(p, prevPaint, textW);
            } else {
                it.prevLayout = null;
            }
            it.laidWidth = textW;
            it.laidName = it.name;
            it.laidPreview = it.preview;
        }
        float nameH = it.nameLayout.getHeight(), prevH = it.prevLayout != null ? Style.px(26) : 0;
        float blockH = nameH + (it.prevLayout != null ? Style.px(4) + prevH : 0);
        float ty = (h - blockH) / 2f;
        c.save();
        c.translate(x, ty);
        it.nameLayout.draw(c);
        c.restore();
        if (it.prevLayout != null) {
            float py = ty + nameH + Style.px(4);
            if (it.prevIn < 1 && it.oldPrevLayout != null) {
                int a = c.saveLayerAlpha(x, py - Style.px(10), x + textW, py + prevH + Style.px(10), (int) (255 * (1 - it.prevIn)));
                c.translate(x, py);
                it.oldPrevLayout.draw(c);
                c.restoreToCount(a);
            }
            int a = c.saveLayerAlpha(x, py - Style.px(10), x + textW, py + prevH + Style.px(10), (int) (255 * it.prevIn));
            c.translate(x, py + Style.px(8) * (1 - it.prevIn));
            it.prevLayout.draw(c);
            c.restoreToCount(a);
        }

        // * the number key, over the avatar's corner
        if (it.key > 0) {
            float ks = Style.px(24);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xe6ffffff);
            c.drawCircle(Style.px(4) + ks / 2f, Style.px(6) + ks / 2f, ks / 2f, fill);
            String k = String.valueOf(it.key);
            Paint.FontMetrics fm = keyPaint.getFontMetrics();
            c.drawText(k, Style.px(4) + (ks - keyPaint.measureText(k)) / 2f, Style.px(6) + ks / 2f - (fm.ascent + fm.descent) / 2f, keyPaint);
        }
        c.restoreToCount(save);
    }

    private void drawBadge(Canvas c, Item it, float x, float y, float bw, String text) {
        float bh = Style.px(32);
        c.save();
        c.scale(it.badge, it.badge, x + bw / 2f, y + bh / 2f);
        r.set(x, y, x + bw, y + bh);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(it.muted ? 0x38ffffff : Style.ACCENT);
        c.drawRoundRect(r, bh / 2f, bh / 2f, fill);
        c.clipRect(r);
        Paint.FontMetrics fm = badgePaint.getFontMetrics();
        float base = y + bh / 2f - (fm.ascent + fm.descent) / 2f;
        if (it.roll < 1 && it.oldUnread > 0) {
            String old = String.valueOf(it.oldUnread);
            badgePaint.setAlpha((int) (255 * Math.max(0, 1 - it.roll * 1.4f)));
            c.drawText(old, x + (bw - badgePaint.measureText(old)) / 2f, base - bh * it.roll, badgePaint);
            badgePaint.setAlpha((int) (255 * Math.min(1, it.roll * 1.4f)));
            c.drawText(text, x + (bw - badgePaint.measureText(text)) / 2f, base + bh * (1 - it.roll), badgePaint);
            badgePaint.setAlpha(255);
        } else {
            c.drawText(text, x + (bw - badgePaint.measureText(text)) / 2f, base, badgePaint);
        }
        c.restore();
    }

    private static StaticLayout single(CharSequence text, TextPaint paint, int width) {
        if (text == null) text = "";
        String flat = text.toString().replace('\n', ' ');
        CharSequence src = text instanceof Spanned ? new SpannableStringBuilder(text) : flat;
        if (src instanceof SpannableStringBuilder) {
            SpannableStringBuilder sb = (SpannableStringBuilder) src;
            for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == '\n') sb.replace(i, i + 1, " ");
        }
        return StaticLayout.Builder.obtain(src, 0, src.length(), paint, Math.max(1, width))
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
    }

    /** "Draft: " in red before a draft, as the prototype shows it. */
    public static CharSequence draft(String label, CharSequence text) {
        SpannableStringBuilder sb = new SpannableStringBuilder(label).append(' ').append(text);
        sb.setSpan(new ForegroundColorSpan(Style.DANGER), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
