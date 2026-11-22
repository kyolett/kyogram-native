package app.kyogram.keypad;

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
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * A chat's messages, drawn by hand and laid out from the bottom: incoming bubbles are see-through glass
 * on the left, yours the accent on the right, day chips between days. A bright ring glides to the message
 * in focus and takes its size; a new message pops up from below (scale .6 → 1 on a bouncy spring); the
 * thread scrolls on a spring to keep the ring in view. Sizes as in the prototype: bubbles up to 80% wide,
 * 12 apart, padding 12/18/10, radius 24, text 21.
 */
public class ThreadView extends View {

    static final class Msg {
        MessageObject mo;
        String day;              // * a day chip instead of a message
        boolean out, typing;
        String who, time;
        StaticLayout text, quote;
        ImageReceiver photo;
        float photoW, photoH;
        float w, h, y;           // * y: from the top of the content
        float pop = 1;
        Spring popSpring;
        Shader fill, sheen;
        final android.graphics.Matrix matrix = new android.graphics.Matrix();
    }

    private final ArrayList<Msg> msgs = new ArrayList<>();
    private final ArrayList<Msg> bubbles = new ArrayList<>();
    private int focus = -1;
    private boolean group;
    /** Your messages up to this id have been read (two ticks). */
    public int readOutboxMax;
    private float contentH;

    private final Spring scroll, ringX, ringY, ringW, ringH;
    private float ringAlpha = 0;
    private final Spring ringFade;

    private final TextPaint textPaint = Style.text(Style.regular, 21, Style.INK);
    private final TextPaint whoPaint = Style.text(Style.bold, 17, Style.WARN);
    private final TextPaint timeIn = Style.text(Style.regular, 14, Color.argb(140, 255, 255, 255));
    private final TextPaint timeOut = Style.text(Style.regular, 14, Color.argb(199, 255, 255, 255));
    private final TextPaint quotePaint = Style.text(Style.regular, 16, Style.INK);
    private final TextPaint dayPaint = Style.text(Style.semibold, 15, Color.argb(179, 255, 255, 255));
    private final Paint inFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outGlow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint quoteFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint quoteBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final long start = System.currentTimeMillis();

    public ThreadView(Context context) {
        super(context);
        scroll = new Spring(0, 300, 34, (v, vel) -> invalidate());
        ringX = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringY = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringW = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringH = new Spring(0, 420, 32, (v, vel) -> invalidate());
        ringFade = new Spring(0, 400, 40, (v, vel) -> { ringAlpha = v; invalidate(); });
        inFill.setColor(Color.argb(153, 30, 27, 48));
        inRim.setStyle(Paint.Style.STROKE);
        inRim.setStrokeWidth(Style.px(1.5f));
        inRim.setColor(Color.argb(46, 255, 255, 255));
        outEdge.setColor(Color.argb(89, 255, 255, 255));
        outGlow.setColor(Color.argb(77, 139, 116, 242));
        quoteFill.setColor(Color.argb(36, 255, 255, 255));
        quoteBar.setColor(Color.argb(179, 255, 255, 255));
        dayFill.setColor(Color.argb(26, 255, 255, 255));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Style.px(2.5f));
        ringPaint.setColor(Color.WHITE);
        ringGlow.setStyle(Paint.Style.STROKE);
    }

    public void setGroup(boolean group) {
        this.group = group;
    }

    // ── content ──

    public void setMessages(ArrayList<MessageObject> list) {
        for (Msg m : msgs) if (m.photo != null) m.photo.onDetachedFromWindow();
        msgs.clear();
        for (MessageObject mo : list) add(mo, false);
        relayout();
        scroll.to(0, true);
        focus = bubbles.size() - 1;
        placeRing(true);
    }

    /** A message that just came in or was just sent: pops in at the bottom. */
    public void append(MessageObject mo) {
        for (Msg m : msgs) if (m.mo != null && m.mo.getId() == mo.getId() && m.mo.getDialogId() == mo.getDialogId()) return;
        boolean atEnd = focus >= bubbles.size() - 2;
        setTyping(false);
        Msg m = add(mo, true);
        relayout();
        scroll.to(0);
        if (atEnd) focus = bubbles.size() - 1;
        placeRing(false);
        if (m != null) {
            // * once it has popped to its size, the ring settles on it (prototype: 420ms)
            postDelayed(() -> placeRing(false), 420);
        }
    }

    /** A message changed (sent, read, edited, its id from the server). */
    public void replace(int oldId, MessageObject mo) {
        for (Msg m : msgs) {
            if (m.mo != null && m.mo.getId() == oldId) {
                m.mo = mo;
                build(m);
            }
        }
        relayout();
        placeRing(true);
    }

    public void refresh() {
        for (Msg m : msgs) if (m.mo != null) build(m);
        relayout();
        placeRing(true);
    }

    private Msg add(MessageObject mo, boolean pop) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(mo.messageOwner.date * 1000L);
        int day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        Msg lastMsg = null;
        for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i).mo != null) { lastMsg = msgs.get(i); break; }
        int lastDay = -1;
        if (lastMsg != null) {
            c.setTimeInMillis(lastMsg.mo.messageOwner.date * 1000L);
            lastDay = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        }
        if (day != lastDay) {
            Msg d = new Msg();
            d.day = LocaleController.formatDateChat(mo.messageOwner.date);
            msgs.add(d);
        }
        Msg m = new Msg();
        m.mo = mo;
        build(m);
        msgs.add(m);
        if (pop && !Spring.reduced) {
            m.pop = 0;
            m.popSpring = new Spring(0, 300, 18, (v, vel) -> { m.pop = v; invalidate(); });
            m.popSpring.to(1);
        }
        return m;
    }

    private void build(Msg m) {
        MessageObject mo = m.mo;
        m.out = mo.isOutOwner();
        m.who = null;
        if (group && !m.out) {
            TLRPC.User u = org.telegram.messenger.MessagesController.getInstance(mo.currentAccount).getUser(mo.getFromChatId());
            if (u != null) m.who = u.first_name != null && !u.first_name.isEmpty() ? u.first_name : u.last_name;
        }
        String t = LocaleController.getInstance().getFormatterDay().format(mo.messageOwner.date * 1000L);
        if (m.out) t += mo.isSending() ? " …" : mo.getId() > 0 && mo.getId() <= readOutboxMax ? " ✓✓" : " ✓";
        m.time = t;

        float maxW = Math.max(Style.px(100), (getWidth() > 0 ? getWidth() : Style.px(508)) * .8f - Style.px(36));
        CharSequence body = mo.caption != null && mo.caption.length() > 0 ? mo.caption : (mo.isPhoto() ? null : mo.messageText);
        if (body != null && body.length() > 0) {
            CharSequence e = Emoji.replaceEmoji(body, textPaint.getFontMetricsInt(), false);
            m.text = StaticLayout.Builder.obtain(e, 0, e.length(), textPaint, (int) maxW)
                    .setLineSpacing(0, 1.12f).setIncludePad(false).build();
        } else {
            m.text = null;
        }
        if (mo.replyMessageObject != null && mo.replyMessageObject.messageText != null) {
            CharSequence q = Emoji.replaceEmoji(mo.replyMessageObject.messageText, quotePaint.getFontMetricsInt(), false);
            m.quote = StaticLayout.Builder.obtain(q, 0, q.length(), quotePaint, (int) (maxW - Style.px(24)))
                    .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
        } else {
            m.quote = null;
        }
        if (mo.isPhoto() && mo.photoThumbs != null) {
            TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, 320);
            TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, 40);
            float pw = big != null && big.w > 0 ? big.w : 300, ph = big != null && big.h > 0 ? big.h : 300;
            float s = Math.min(Style.px(300) / pw, Style.px(300) / ph);
            m.photoW = pw * s;
            m.photoH = ph * s;
            if (m.photo == null) {
                m.photo = new ImageReceiver(this);
                m.photo.setRoundRadius((int) Style.px(16));
                if (isAttachedToWindow()) m.photo.onAttachedToWindow();
            }
            m.photo.setImage(ImageLocation.getForObject(big, mo.photoThumbsObject), "300_300", ImageLocation.getForObject(small, mo.photoThumbsObject), "300_300_b", 0, null, mo, 0);
        }
        // * the bubble's size
        float tw = 0, th = 0;
        if (m.text != null) {
            for (int i = 0; i < m.text.getLineCount(); i++) tw = Math.max(tw, m.text.getLineWidth(i));
            th = m.text.getHeight();
        }
        TextPaint tp = m.out ? timeOut : timeIn;
        tw = Math.max(tw, tp.measureText(m.time));
        if (m.who != null) tw = Math.max(tw, whoPaint.measureText(m.who));
        if (m.quote != null) tw = Math.max(tw, Math.min(maxW, m.quote.getLineWidth(0) + Style.px(24)));
        if (m.photo != null) tw = Math.max(tw, m.photoW);
        float h = Style.px(12);
        if (m.who != null) h += Style.px(23);
        if (m.quote != null) h += Style.px(36);
        if (m.photo != null) h += m.photoH + (m.text != null ? Style.px(8) : 0);
        h += th + Style.px(3) + Style.px(18) + Style.px(10);
        m.w = tw + Style.px(36);
        m.h = h;
        // * the fills, made once per size and moved with a matrix when drawn
        RectF b = new RectF(0, 0, m.w, m.h);
        m.fill = m.out ? Style.accentGradient(b) : null;
        m.sheen = m.out ? null : new LinearGradient(0, 0, 0, m.h * .7f, Color.argb(26, 255, 255, 255), 0x00ffffff, Shader.TileMode.CLAMP);
    }

    private void relayout() {
        bubbles.clear();
        float y = 0, gap = Style.px(12);
        for (Msg m : msgs) {
            if (m.day != null) {
                m.h = Style.px(28);
            }
            m.y = y;
            y += m.h + gap;
            if (m.mo != null) bubbles.add(m);
        }
        contentH = Math.max(0, y - gap);
        if (focus >= bubbles.size()) focus = bubbles.size() - 1;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != ow) refresh();
    }

    // ── focus ──

    public MessageObject focused() {
        return focus >= 0 && focus < bubbles.size() ? bubbles.get(focus).mo : null;
    }

    public boolean atLast() {
        return focus >= bubbles.size() - 1;
    }

    public void move(int d) {
        if (bubbles.isEmpty()) return;
        focus = Math.max(0, Math.min(bubbles.size() - 1, focus + d));
        placeRing(false);
    }

    public void focusLast() {
        focus = bubbles.size() - 1;
        placeRing(false);
        postDelayed(() -> placeRing(false), 420);
    }

    public void showRing(boolean show) {
        ringFade.to(show ? 1 : 0);
    }

    private float bottomY() {
        return getHeight() - Style.px(10);
    }

    /** Where a message's top is on the screen at scroll s. */
    private float topOf(Msg m, float s) {
        return bottomY() - contentH + m.y + s;
    }

    private void placeRing(boolean snap) {
        if (focus < 0 || focus >= bubbles.size()) {
            ringFade.to(0, snap);
            return;
        }
        Msg m = bubbles.get(focus);
        float port = getHeight();
        float relTop = topOf(m, 0);
        float ns = scroll.t;
        if (relTop + ns < Style.px(24)) ns = Style.px(24) - relTop;
        if (relTop + m.h + ns > port - Style.px(14)) ns = port - Style.px(14) - relTop - m.h;
        ns = Math.max(0, ns);
        scroll.to(ns, snap);
        float x = m.out ? getWidth() - Style.px(16) - m.w : Style.px(16);
        ringX.to(x - Style.px(4), snap);
        ringY.to(relTop + ns - Style.px(4), snap);
        ringW.to(m.w + Style.px(8), snap);
        ringH.to(m.h + Style.px(8), snap);
    }

    // ── someone is typing: three dots in a bubble at the bottom ──

    private boolean typing;

    public void setTyping(boolean on) {
        if (typing == on) return;
        typing = on;
        if (on) {
            Msg t = new Msg();
            t.typing = true;
            t.w = Style.px(36 + 9 * 3 + 12);
            t.h = Style.px(41);
            if (!Spring.reduced) {
                t.pop = 0;
                t.popSpring = new Spring(0, 300, 18, (v, vel) -> { t.pop = v; invalidate(); });
                t.popSpring.to(1);
            }
            msgs.add(t);
        } else {
            for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i).typing) msgs.remove(i);
        }
        relayout();
    }

    // ── drawing ──

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (Msg m : msgs) if (m.photo != null) m.photo.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (Msg m : msgs) if (m.photo != null) m.photo.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth();
        boolean animating = false;
        for (Msg m : msgs) {
            float top = topOf(m, scroll.v);
            if (top > getHeight() || top + m.h < -Style.px(40)) continue;
            if (m.day != null) {
                float tw = dayPaint.measureText(m.day) + Style.px(28);
                r.set((W - tw) / 2f, top, (W + tw) / 2f, top + m.h);
                c.drawRoundRect(r, Style.px(14), Style.px(14), dayFill);
                Paint.FontMetrics fm = dayPaint.getFontMetrics();
                c.drawText(m.day, r.left + Style.px(14), r.centerY() - (fm.ascent + fm.descent) / 2f, dayPaint);
                continue;
            }
            float x = m.out ? W - Style.px(16) - m.w : Style.px(16);
            int save = c.save();
            if (m.pop < 1) {
                float p = m.pop;
                c.translate(0, (1 - p) * Style.px(30));
                c.scale(.6f + .4f * p, .6f + .4f * p, m.out ? x + m.w : x, top + m.h);
                c.saveLayerAlpha(x - Style.px(20), top - Style.px(20), x + m.w + Style.px(20), top + m.h + Style.px(20), (int) (255 * Math.min(1, p * 2)));
            }
            r.set(x, top, x + m.w, top + m.h);
            float rad = Style.px(24);
            if (m.typing) {
                c.drawRoundRect(r, rad, rad, inFill);
                c.drawRoundRect(r, rad, rad, inRim);
                long t = System.currentTimeMillis() - start;
                for (int i = 0; i < 3; i++) {
                    float ph = ((t + 1000 - i * 150) % 1000) / 1000f;
                    float lift = ph < .3f ? ph / .3f : ph < .6f ? 1 - (ph - .3f) / .3f : 0;
                    dotPaint.setColor(Color.argb((int) (255 * (.5f + .5f * lift) * .7f), 255, 255, 255));
                    c.drawCircle(x + Style.px(18 + 4.5f + i * 15), top + m.h / 2f - Style.px(5) * lift, Style.px(4.5f), dotPaint);
                }
                animating = true;
            } else if (m.out) {
                m.matrix.setTranslate(x, top);
                m.fill.setLocalMatrix(m.matrix);
                outFill.setShader(m.fill);
                c.drawRoundRect(r, rad, rad, outFill);
                c.save();
                path.reset();
                path.addRoundRect(r, rad, rad, Path.Direction.CW);
                c.clipPath(path);
                c.drawRect(r.left, r.top, r.right, r.top + Style.px(1.5f), outEdge);
                c.restore();
            } else {
                c.drawRoundRect(r, rad, rad, inFill);
                m.matrix.setTranslate(x, top);
                m.sheen.setLocalMatrix(m.matrix);
                sheenPaint.setShader(m.sheen);
                c.drawRoundRect(r, rad, rad, sheenPaint);
                c.drawRoundRect(r, rad, rad, inRim);
            }
            if (!m.typing) drawContent(c, m, x, top);
            c.restoreToCount(save);
        }

        // * the ring around the message in focus
        if (ringAlpha > .01f) {
            r.set(ringX.v, ringY.v + (scroll.v - scroll.t), ringX.v + ringW.v, ringY.v + (scroll.v - scroll.t) + ringH.v);
            // * the glow (box-shadow 0 0 24px .35): a few soft strokes, since this phone can't blur a stroke
            for (int k = 3; k >= 1; k--) {
                ringGlow.setStrokeWidth(Style.px(4 * k + 2));
                ringGlow.setColor(Color.argb((int) (34 / k * ringAlpha), 255, 255, 255));
                c.drawRoundRect(r, Style.px(26), Style.px(26), ringGlow);
            }
            ringPaint.setAlpha((int) (255 * ringAlpha));
            float i = Style.px(1.25f);
            c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, Style.px(26), Style.px(26), ringPaint);
        }
        if (animating) postInvalidateOnAnimation();
    }

    private void drawContent(Canvas c, Msg m, float x, float top) {
        float cx = x + Style.px(18), y = top + Style.px(12);
        if (m.who != null) {
            Paint.FontMetrics fm = whoPaint.getFontMetrics();
            c.drawText(m.who, cx, y - fm.ascent, whoPaint);
            y += Style.px(23);
        }
        if (m.quote != null) {
            float qh = Style.px(30);
            r.set(cx, y + Style.px(2), x + m.w - Style.px(18), y + Style.px(2) + qh);
            c.drawRoundRect(r, Style.px(10), Style.px(10), quoteFill);
            c.drawRect(r.left, r.top + Style.px(2), r.left + Style.px(3), r.bottom - Style.px(2), quoteBar);
            c.save();
            c.translate(r.left + Style.px(10), r.centerY() - m.quote.getHeight() / 2f);
            m.quote.draw(c);
            c.restore();
            y += Style.px(36);
        }
        if (m.photo != null) {
            m.photo.setImageCoords(cx - Style.px(10), y, m.photoW, m.photoH);
            m.photo.draw(c);
            y += m.photoH + (m.text != null ? Style.px(8) : 0);
        }
        if (m.text != null) {
            c.save();
            c.translate(cx, y);
            m.text.draw(c);
            c.restore();
            y += m.text.getHeight();
        }
        TextPaint tp = m.out ? timeOut : timeIn;
        float tw = tp.measureText(m.time);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(m.time, x + m.w - Style.px(18) - tw, y + Style.px(3) - fm.ascent, tp);
    }
}
