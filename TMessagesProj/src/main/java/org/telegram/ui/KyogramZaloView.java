package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import app.kyogram.ui.ChatListView;
import app.kyogram.ui.GlassFrame;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;
import app.kyogram.ui.ZaloOa;

/**
 * Kyogram x OA on the phone, as on desktop Kyogram: your Zalo Official Account's chats, a chat's
 * messages, and replying, through the calls oa.zalo.me makes for itself (app.kyogram.ui.ZaloOa).
 * You sign in once in oa.zalo.me's own page; it reloads by itself while it's on screen.
 */
public class KyogramZaloView extends FrameLayout {

    private final int topInset, bottomInset;
    private ChatListView list;
    private TextView empty;
    private final ArrayList<ZaloOa.Chat> chats = new ArrayList<>();
    private FrameLayout panel;
    private ZaloOa.Chat open;
    private LinearLayout bubbles;
    private ScrollView bubblesScroll;
    private boolean shown;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!shown) return;
            if (open != null) loadThread(false);
            else loadChats();
            AndroidUtilities.runOnUIThread(this, open != null ? 8000 : 15000);
        }
    };

    public KyogramZaloView(Context context, int topInset, int bottomInset) {
        super(context);
        this.topInset = topInset;
        this.bottomInset = bottomInset;
        ZaloOa.init(context);
        list = new ChatListView(context, false);
        list.inset = 22;
        list.setOnItemClick((item, x) -> {
            for (ZaloOa.Chat c : chats) if (c.convId.hashCode() == item.id) openChat(c);
        });
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -1);
        llp.topMargin = topInset;
        llp.bottomMargin = bottomInset;
        addView(list, llp);
        empty = label(19, Style.INK3, Style.regular);
        empty.setSingleLine(false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(40), 0, px(40), 0);
        addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
    }

    private int px(float v) {
        return Math.round(Style.px(v));
    }

    private TextView label(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(getContext());
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(size));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView button(String text, boolean primary) {
        TextView t = label(19, primary ? Color.WHITE : Style.INK, Style.bold);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(18), px(14), px(18), px(14));
        GradientDrawable bg = primary ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Style.ACCENT_HI, Style.ACCENT}) : new GradientDrawable();
        if (!primary) bg.setColor(Style.fg(26));
        bg.setCornerRadius(px(24));
        t.setBackground(bg);
        return t;
    }

    public void setShown(boolean on) {
        shown = on;
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (on) AndroidUtilities.runOnUIThread(poll);
    }

    public boolean hasPanel() {
        return panel != null;
    }

    /** Back closes an open chat (or the sign-in page) first. */
    public boolean back() {
        if (panel != null) {
            closePanel();
            return true;
        }
        return false;
    }

    // ── chats ──

    private void loadChats() {
        ZaloOa.chats((result, error) -> {
            if ("signed-out".equals(error)) {
                showSignIn();
                return;
            }
            if (error != null) {
                empty.setText("Couldn't load Zalo OA: " + error);
                empty.setAlpha(1);
                return;
            }
            chats.clear();
            chats.addAll(result);
            ArrayList<ChatListView.Item> items = new ArrayList<>();
            for (ZaloOa.Chat c : chats) {
                ChatListView.Item it = new ChatListView.Item();
                it.id = c.convId.hashCode();
                it.name = c.name;
                it.preview = (c.lastFromOa ? "Bạn: " : "") + c.last;
                it.time = time(c.time);
                it.unread = c.unread;
                items.add(it);
            }
            list.setItems(items, ChatListView.FROM_NONE, true, false);
            empty.setText(items.isEmpty() ? "No chats yet." : "");
            empty.animate().alpha(items.isEmpty() ? 1 : 0).setDuration(200).start();
        });
    }

    private static String time(long ms) {
        if (ms <= 0) return "";
        Date d = new Date(ms);
        boolean today = android.text.format.DateUtils.isToday(ms);
        return new SimpleDateFormat(today ? "HH:mm" : "dd/MM", Locale.getDefault()).format(d);
    }

    // ── panels: the sign-in page, a chat ──

    private FrameLayout panel() {
        if (panel != null) removeView(panel);
        panel = new FrameLayout(getContext());
        addView(panel, new FrameLayout.LayoutParams(-1, -1));
        final FrameLayout p = panel;
        p.setAlpha(0);
        new Spring(0, 300, 30, (v, vel) -> {
            // * pushed in from the side, the list slides a little and dims
            float q = Math.max(0, Math.min(1.02f, v));
            p.setAlpha(Math.min(1, v * 4));
            p.setTranslationX((1 - q) * getWidth());
            list.setTranslationX(-q * Style.px(120));
            list.setAlpha(1 - .6f * Math.min(1, q));
        }).to(1);
        return panel;
    }

    private void closePanel() {
        if (panel == null) return;
        final FrameLayout p = panel;
        panel = null;
        open = null;
        AndroidUtilities.hideKeyboard(this);
        new Spring(1, 300, 30, (v, vel) -> {
            float q = Math.max(0, Math.min(1.02f, v));
            p.setTranslationX((1 - q) * getWidth());
            list.setTranslationX(-q * Style.px(120));
            list.setAlpha(1 - .6f * Math.min(1, q));
            if (v <= .001f) removeView(p);
        }).to(0);
        loadChats();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showSignIn() {
        if (panel != null && panel.getTag() == "signin") return;
        FrameLayout p = panel();
        p.setTag("signin");
        GlassFrame card = new GlassFrame(getContext(), 32);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(22), px(22), px(22), px(22));
        card.addView(col, new FrameLayout.LayoutParams(-1, -2));
        TextView h = label(25, Style.INK, Style.display);
        h.setText("Sign in to Zalo OA");
        col.addView(h);
        TextView n = label(16, Style.INK2, Style.regular);
        n.setText("Sign in on Zalo's own page, then press Done. The sign-in stays in Kyogram on this phone.");
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = px(10);
        col.addView(n, np);
        TextView open = button("Open oa.zalo.me", true);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
        op.topMargin = px(16);
        col.addView(open, op);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2);
        clp.setMargins(px(14), topInset, px(14), 0);
        p.addView(card, clp);
        open.setOnClickListener(v -> {
            p.removeAllViews();
            WebView web = new WebView(getContext());
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient());
            web.loadUrl(ZaloOa.ORIGIN + "/chat");
            FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(-1, -1);
            wlp.topMargin = AndroidUtilities.statusBarHeight + px(70);
            wlp.bottomMargin = bottomInset;
            p.addView(web, wlp);
            TextView done = button("Done", true);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, px(56));
            dlp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(8), px(14), 0);
            p.addView(done, dlp);
            done.setOnClickListener(x -> {
                CookieManager.getInstance().flush();
                ZaloOa.status((ok, error) -> {
                    if (Boolean.TRUE.equals(ok)) {
                        web.destroy();
                        closePanel();
                    } else {
                        Toast.makeText(getContext(), "Not signed in yet", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    private void openChat(ZaloOa.Chat c) {
        open = c;
        FrameLayout p = panel();
        p.setTag("chat");
        GlassFrame head = new GlassFrame(getContext(), 30);
        TextView title = label(21, Style.INK, Style.bold);
        title.setText("‹  " + c.name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(px(18), 0, px(18), 0);
        title.setOnClickListener(v -> closePanel());
        head.addView(title, new FrameLayout.LayoutParams(-1, -1));
        // * the customer's Trello cards (zaloOaTrello.ts): the tasks about them, linked on this phone
        if (app.kyogram.ui.Trello.connected(getContext())) {
            TextView trello = label(17, Style.INK2, Style.semibold);
            trello.setText("Trello");
            trello.setGravity(Gravity.CENTER);
            trello.setPadding(px(16), 0, px(16), 0);
            trello.setOnClickListener(v -> customerCards(c));
            head.addView(trello, new FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT));
            title.setPadding(px(18), 0, px(100), 0);
        }
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, px(60));
        hlp.setMargins(px(14), topInset, px(14), 0);
        p.addView(head, hlp);

        bubblesScroll = new ScrollView(getContext());
        bubbles = new LinearLayout(getContext());
        bubbles.setOrientation(LinearLayout.VERTICAL);
        bubbles.setPadding(px(16), px(10), px(16), px(10));
        bubblesScroll.addView(bubbles);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -1);
        blp.topMargin = topInset + px(68);
        blp.bottomMargin = bottomInset + px(78);
        p.addView(bubblesScroll, blp);

        GlassFrame composer = new GlassFrame(getContext(), 31);
        EditText input = new EditText(getContext());
        input.setBackground(null);
        input.setTextColor(Style.INK);
        input.setHintTextColor(Style.fg(140));
        input.setHint("Trả lời khách…");
        input.setTypeface(Style.medium);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(20));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setSingleLine(true);
        input.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
        ilp.leftMargin = px(22);
        ilp.rightMargin = px(84);
        composer.addView(input, ilp);
        TextView send = button("Gửi", true);
        send.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(px(70), px(46), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        slp.rightMargin = px(8);
        composer.addView(send, slp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, px(62), Gravity.BOTTOM);
        clp.setMargins(px(12), 0, px(12), bottomInset + px(10));
        p.addView(composer, clp);
        Runnable doSend = () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            input.setText("");
            ZaloOa.Message m = new ZaloOa.Message();
            m.fromOa = true;
            m.text = text;
            m.time = System.currentTimeMillis();
            addBubble(m, true);
            ZaloOa.sendText(c.convId, c.customerId, text, (r, error) -> {
                if (error != null) Toast.makeText(getContext(), "Zalo: " + error, Toast.LENGTH_LONG).show();
                else loadThread(false);
            });
        };
        send.setOnClickListener(v -> doSend.run());
        input.setOnEditorActionListener((v, a, e) -> {
            doSend.run();
            return true;
        });
        loadThread(true);
        ZaloOa.markRead(c.convId);
    }

    private void customerCards(ZaloOa.Chat chat) {
        Context ctx = getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(20), px(10), px(20), px(10));
        ScrollView sv = new ScrollView(ctx);
        sv.addView(col);
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx);
        b.setTitle("Trello · " + chat.name);
        b.setView(sv);
        b.setNegativeButton("Close", null);
        org.telegram.ui.ActionBar.AlertDialog dialog = b.create();
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            col.removeAllViews();
            for (String[] card : app.kyogram.ui.Trello.customerCards(ctx, chat.convId)) {
                TextView t = label(17, 0xff000000 | Style.ACCENT, Style.semibold);
                t.setText("• " + card[2]);
                t.setPadding(0, px(8), 0, px(8));
                t.setOnClickListener(v -> {
                    try {
                        ctx.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(card[1])));
                    } catch (Exception ignore) {
                    }
                });
                t.setOnLongClickListener(v -> {
                    app.kyogram.ui.Trello.Found f = new app.kyogram.ui.Trello.Found();
                    f.id = card[0];
                    app.kyogram.ui.Trello.linkCustomerCard(ctx, chat.convId, f, false);
                    fill[0].run();
                    return true;
                });
                col.addView(t);
            }
            EditText q = new EditText(ctx);
            q.setHint("Search the board to link a card");
            q.setSingleLine(true);
            q.setText(chat.name);
            col.addView(q);
            LinearLayout results = new LinearLayout(ctx);
            results.setOrientation(LinearLayout.VERTICAL);
            TextView go = label(17, 0xff000000 | Style.ACCENT, Style.bold);
            go.setText("Search");
            go.setPadding(0, px(10), 0, px(10));
            col.addView(go);
            col.addView(results);
            go.setOnClickListener(v -> app.kyogram.ui.Trello.search(ctx, q.getText().toString().trim(), (list, e) -> {
                results.removeAllViews();
                if (list == null) return;
                for (app.kyogram.ui.Trello.Found f : list) {
                    TextView t = label(16, 0xff444444, Style.regular);
                    t.setText("+ " + f.name);
                    t.setPadding(0, px(8), 0, px(8));
                    t.setOnClickListener(x -> {
                        app.kyogram.ui.Trello.linkCustomerCard(ctx, chat.convId, f, true);
                        fill[0].run();
                    });
                    results.addView(t);
                }
            }));
        };
        fill[0].run();
        dialog.show();
    }

    private int shownCount = -1;

    private void loadThread(boolean first) {
        ZaloOa.Chat c = open;
        if (c == null) return;
        ZaloOa.messages(c.convId, (result, error) -> {
            if (open != c || bubbles == null) return;
            if (error != null) {
                if (first) Toast.makeText(getContext(), "Zalo: " + error, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!first && result.size() == shownCount) return;
            boolean popLast = !first && shownCount >= 0 && result.size() > shownCount;
            shownCount = result.size();
            bubbles.removeAllViews();
            for (int i = 0; i < result.size(); i++) addBubble(result.get(i), popLast && i == result.size() - 1);
            bubblesScroll.post(() -> bubblesScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** A bubble: yours in the accent on the right, the customer's see-through glass on the left; a new one pops in. */
    private void addBubble(ZaloOa.Message m, boolean pop) {
        TextView b = label(20, Style.INK, Style.regular);
        b.setText(m.text + "\n" + time(m.time));
        b.setLineSpacing(0, 1.12f);
        b.setPadding(px(18), px(12), px(18), px(10));
        b.setMaxWidth((int) (getWidth() * .8f));
        GradientDrawable bg = m.fromOa ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Style.ACCENT_HI, Style.ACCENT}) : new GradientDrawable();
        if (m.fromOa) b.setTextColor(Color.WHITE);
        else {
            bg.setColor(Style.day ? Color.argb(184, 255, 255, 255) : Color.argb(153, 30, 27, 48));
            bg.setStroke(px(1.5f), Color.argb(46, 255, 255, 255));
        }
        bg.setCornerRadius(px(24));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = m.fromOa ? Gravity.RIGHT : Gravity.LEFT;
        lp.topMargin = px(12);
        bubbles.addView(b, lp);
        if (pop && !Spring.reduced) {
            b.setPivotX(m.fromOa ? 1e4f : 0);
            b.post(() -> {
                b.setPivotX(m.fromOa ? b.getWidth() : 0);
                b.setPivotY(b.getHeight());
                new Spring(0, 300, 18, (p, vel) -> {
                    b.setTranslationY((1 - p) * Style.px(30));
                    b.setScaleX(.6f + .4f * p);
                    b.setScaleY(.6f + .4f * p);
                    b.setAlpha(Math.min(1, p * 2));
                }).to(1);
            });
            bubblesScroll.post(() -> bubblesScroll.fullScroll(View.FOCUS_DOWN));
        }
    }
}
