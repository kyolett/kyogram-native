package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

import app.kyogram.ui.Care;
import app.kyogram.ui.SegmentView;
import app.kyogram.ui.ChatListView;
import app.kyogram.ui.GlassFrame;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;

/**
 * Kyogram: the Care tab, DroidShop's warranty tickets (care.droidshop.vn) as on desktop Kyogram:
 * sign in once, the tickets with their status, search by phone, code or serial, filter by status, and
 * a ticket's page — its details, its history, change its status, add a note. Kyogram's glass and
 * springs (app.kyogram.ui).
 */
public class KyogramCareActivity extends BaseFragment {

    private final boolean hasMainTabs;
    private FrameLayout root;
    private ChatListView list;
    private TextView sub, empty;
    private EditText search;
    private String status = "";
    private final ArrayList<Care.Ticket> tickets = new ArrayList<>();
    private ScrollView overlay;
    private FrameLayout careLayer;
    private KyogramZaloView zalo;
    private SegmentView segment;
    private boolean resumed;
    private Spring overlaySpring;

    public KyogramCareActivity(Bundle args) {
        super(args);
        hasMainTabs = args != null && args.getBoolean("hasMainTabs", false);
    }

    @Override
    public ActionBar createActionBar(Context context) {
        return null;
    }

    private int bottomInset() {
        return hasMainTabs ? AndroidUtilities.dp(76) : AndroidUtilities.dp(12);
    }

    private int px(float v) {
        return Math.round(Style.px(v));
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        Care.init(context);
        root = new FrameLayout(context) {
            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                if (w > 0 && h > 0 && (w != ow || h != oh)) post(() -> build());
            }
        };
        root.setBackgroundColor(Color.parseColor("#15121c"));
        fragmentView = root;
        return root;
    }

    private void build() {
        Context c = getContext();
        if (c == null || root.getWidth() == 0) return;
        Style.fromPrefs();
        Style.init(c, root.getWidth(), root.getHeight(), AndroidUtilities.statusBarHeight, Style.phoneUnit());
        root.removeAllViews();
        overlay = null;
        root.addView(new View(c) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (Style.wall != null) canvas.drawBitmap(Style.wall, 0, 0, null);
            }
        }, new FrameLayout.LayoutParams(-1, -1));

        // * the header: Care, who's signed in, and a search field
        GlassFrame header = new GlassFrame(c, 36);
        LinearLayout hl = new LinearLayout(c);
        hl.setOrientation(LinearLayout.VERTICAL);
        hl.setGravity(Gravity.CENTER_VERTICAL);
        hl.setPadding(px(10), px(8), px(120), px(8));
        // * Care and Zalo OA side by side: a capsule whose pill glides between them, as wide as its words
        segment = new SegmentView(c, "Care", "Zalo OA");
        android.text.TextPaint segText = Style.text(Style.semibold, 18, 0);
        int segW = Math.round((Math.max(segText.measureText("Care"), segText.measureText("Zalo OA")) + Style.px(48)) * 2);
        sub = label(c, 14, Style.INK3, Style.regular);
        sub.setText(Care.accountName().isEmpty() ? "DroidShop" : "DroidShop · " + Care.accountName());
        hl.addView(segment, new LinearLayout.LayoutParams(segW, px(42)));
        LinearLayout.LayoutParams subp = new LinearLayout.LayoutParams(-2, -2);
        subp.topMargin = px(6);
        subp.leftMargin = px(12);
        hl.addView(sub, subp);
        header.addView(hl, new FrameLayout.LayoutParams(-1, -1));
        ImageView signOut = headerIcon(c, R.drawable.msg_leave);
        signOut.setOnClickListener(v -> {
            if (segment != null && segment.picked() == 1) {
                app.kyogram.ui.ZaloOa.signOut();
                zalo.setShown(true);
                return;
            }
            Care.signOut((r, e) -> {
            tickets.clear();
            build();
            });
        });
        FrameLayout.LayoutParams sop = new FrameLayout.LayoutParams(px(56), px(56), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        sop.rightMargin = px(8);
        header.addView(signOut, sop);
        ImageView lookup = headerIcon(c, R.drawable.msg_search);
        lookup.setOnClickListener(v -> {
            if (segment != null && segment.picked() == 0) showLookup(null);
        });
        FrameLayout.LayoutParams lkp = new FrameLayout.LayoutParams(px(56), px(56), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lkp.rightMargin = px(62);
        header.addView(lookup, lkp);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, px(80));
        hlp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(6), px(14), 0);
        careLayer = new FrameLayout(c);
        root.addView(careLayer, new FrameLayout.LayoutParams(-1, -1));
        zalo = new KyogramZaloView(c, AndroidUtilities.statusBarHeight + px(94), bottomInset());
        zalo.setVisibility(View.GONE);
        root.addView(zalo, new FrameLayout.LayoutParams(-1, -1));
        root.addView(header, hlp);
        final Spring layers = new Spring(0, 300, 30, (p, vel) -> {
            careLayer.setAlpha(1 - Math.min(1, Math.max(0, p)));
            careLayer.setTranslationX(-p * Style.px(60));
            zalo.setAlpha(Math.min(1, Math.max(0, p)));
            zalo.setTranslationX((1 - p) * Style.px(60));
            careLayer.setVisibility(p > .99f ? View.GONE : View.VISIBLE);
            zalo.setVisibility(p < .01f ? View.GONE : View.VISIBLE);
        });
        segment.setListener(i -> {
            layers.to(i);
            sub.setText(i == 1 ? "Kyogram x OA" : Care.accountName().isEmpty() ? "DroidShop" : "DroidShop · " + Care.accountName());
            zalo.setShown(i == 1 && resumed);
        });

        GlassFrame sf = new GlassFrame(c, 25);
        search = new EditText(c);
        search.setBackground(null);
        search.setTextColor(Style.INK);
        search.setHintTextColor(Style.INK3);
        search.setHint("Phone, ticket code or serial");
        search.setTypeface(Style.medium);
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(19));
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(px(20), 0, px(20), 0);
        search.setOnEditorActionListener((v, a, e) -> {
            load();
            AndroidUtilities.hideKeyboard(search);
            return true;
        });
        sf.addView(search, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, px(50));
        slp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(98), px(14), 0);
        careLayer.addView(sf, slp);

        // * the statuses, as chips: the picked one is the accent
        HorizontalScrollView chipsScroll = new HorizontalScrollView(c);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(px(14), 0, px(14), 0);
        chipsScroll.addView(chips);
        ArrayList<TextView> chipViews = new ArrayList<>();
        String[][] all = new String[Care.STATUSES.length + 1][];
        all[0] = new String[]{"", "All", "gray"};
        System.arraycopy(Care.STATUSES, 0, all, 1, Care.STATUSES.length);
        for (String[] s : all) {
            TextView chip = label(c, 16, Style.INK, Style.semibold);
            chip.setText(s[1]);
            chip.setPadding(px(16), px(9), px(16), px(9));
            chip.setTag(s[0]);
            chip.setOnClickListener(v -> {
                status = (String) v.getTag();
                paintChips(chipViews);
                load();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.rightMargin = px(8);
            chips.addView(chip, p);
            chipViews.add(chip);
        }
        paintChips(chipViews);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2);
        clp.topMargin = AndroidUtilities.statusBarHeight + px(160);
        careLayer.addView(chipsScroll, clp);

        list = new ChatListView(c, false);

        list.inset = 22;
        list.setOnItemClick((item, x) -> {
            for (Care.Ticket t : tickets) if (t.code.hashCode() == item.id) openTicket(t.code);
        });
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -1);
        llp.topMargin = AndroidUtilities.statusBarHeight + px(212);
        llp.bottomMargin = bottomInset();
        careLayer.addView(list, llp);
        empty = label(c, 19, Style.INK3, Style.regular);
        empty.setSingleLine(false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(40), 0, px(40), 0);
        careLayer.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));

        if (!Care.hasSession()) showSignIn();
        else load();
    }

    /** A round glass button in the header, with one of Telegram's icons in the ink colour. */
    private ImageView headerIcon(Context c, int res) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setColorFilter(new android.graphics.PorterDuffColorFilter(Style.INK2, android.graphics.PorterDuff.Mode.SRC_IN));
        v.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(Style.fg(30), org.telegram.ui.ActionBar.Theme.RIPPLE_MASK_CIRCLE_20DP));
        return v;
    }

    private void paintChips(ArrayList<TextView> chips) {
        for (TextView chip : chips) {
            boolean on = status.equals(chip.getTag());
            GradientDrawable bg = on ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Style.ACCENT_HI, Style.ACCENT}) : new GradientDrawable();
            if (!on) bg.setColor(Style.fg(26));
            bg.setCornerRadius(px(18));
            chip.setBackground(bg);
            chip.setTextColor(on ? Color.WHITE : Style.INK);
        }
    }

    private TextView label(Context c, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(size));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static int toneColor(String tone) {
        switch (tone) {
            case "blue": return Color.parseColor("#6aa8ff");
            case "amber": return Style.WARN;
            case "teal": return Color.parseColor("#5fd1c8");
            case "green": return Style.OK;
            case "red": return Style.DANGER;
            default: return Style.INK2;
        }
    }

    // ── the list ──

    private void load() {
        if (list == null) return;
        empty.setText("Loading…");
        empty.setAlpha(1);
        Care.tickets(search.getText().toString().trim(), status, (result, error) -> {
            if (list == null) return;
            if ("signed-out".equals(error)) {
                showSignIn();
                return;
            }
            if (error != null) {
                empty.setText("Couldn't load the tickets: " + error);
                return;
            }
            sub.setText(Care.accountName().isEmpty() ? "DroidShop" : "DroidShop · " + Care.accountName());
            tickets.clear();
            tickets.addAll(result);
            ArrayList<ChatListView.Item> items = new ArrayList<>();
            for (Care.Ticket t : tickets) {
                ChatListView.Item it = new ChatListView.Item();
                it.id = t.code.hashCode();
                it.name = t.code + " · " + t.product;
                it.preview = t.customer + (TextUtils.isEmpty(t.date) ? "" : " · " + t.date);
                it.chip = shortStatus(t.status);
                int color = toneColor(Care.tone(t.status));
                it.chipColor = color;
                it.chipBg = Style.alpha(color, .18f);
                items.add(it);
            }
            list.setItems(items, ChatListView.FROM_NONE, false, false);
            empty.animate().alpha(items.isEmpty() ? 1 : 0).setDuration(200).start();
            empty.setText("No tickets.");
        });
    }

    private static String shortStatus(String s) {
        if (s == null) return "";
        int i = s.indexOf(" / ");
        if (i > 0) s = s.substring(0, i);
        i = s.indexOf(" - ");
        if (i > 0) s = s.substring(0, i);
        return s;
    }

    // ── panels over the list: the sign-in and a ticket ──

    private LinearLayout panel(Context c) {
        if (overlay != null) root.removeView(overlay);
        overlay = new ScrollView(c);
        overlay.setFillViewport(true);
        FrameLayout wrap = new FrameLayout(c);
        GlassFrame card = new GlassFrame(c, 32);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(22), px(22), px(22), px(22));
        card.addView(col, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2);
        clp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(14), px(14), bottomInset() + px(14));
        wrap.addView(card, clp);
        overlay.addView(wrap, new FrameLayout.LayoutParams(-1, -2));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        // * it springs up from below, the list sinks behind it
        overlay.setAlpha(0);
        final ScrollView o = overlay;
        overlaySpring = new Spring(0, 330, 24, (p, vel) -> {
            o.setAlpha(Math.min(1, Math.max(0, p) * 2));
            o.setTranslationY((1 - p) * Style.px(70));
            if (list != null) list.setAlpha(1 - .7f * Math.min(1, Math.max(0, p)));
        });
        overlaySpring.to(1);
        return col;
    }

    private void closePanel() {
        if (overlay == null) return;
        final ScrollView o = overlay;
        overlay = null;
        new Spring(1, 330, 30, (p, vel) -> {
            o.setAlpha(Math.max(0, p));
            o.setTranslationY((1 - p) * Style.px(70));
            if (list != null) list.setAlpha(1 - .7f * Math.max(0, p));
            if (p <= .001f) root.removeView(o);
        }).to(0);
        AndroidUtilities.hideKeyboard(root);
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (zalo != null && segment != null) zalo.setShown(segment.picked() == 1);
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        if (zalo != null) zalo.setShown(false);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (zalo != null && segment != null && segment.picked() == 1 && zalo.hasPanel()) {
            if (invoked) zalo.back();
            return false;
        }
        if (overlay != null && Care.hasSession()) {
            if (invoked) closePanel();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private EditText field(Context c, String hint, boolean password) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(Style.INK);
        e.setHintTextColor(Style.INK3);
        e.setTypeface(Style.regular);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(19));
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.fg(20));
        bg.setCornerRadius(px(18));
        e.setBackground(bg);
        e.setPadding(px(18), px(14), px(18), px(14));
        return e;
    }

    private TextView button(Context c, String text, boolean primary) {
        TextView t = label(c, 19, primary ? Color.WHITE : Style.INK, Style.bold);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(18), px(14), px(18), px(14));
        GradientDrawable bg = primary ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Style.ACCENT_HI, Style.ACCENT}) : new GradientDrawable();
        if (!primary) bg.setColor(Style.fg(26));
        bg.setCornerRadius(px(24));
        t.setBackground(bg);
        return t;
    }

    private TextView para(Context c, String text, float size, int color, android.graphics.Typeface face) {
        TextView t = label(c, size, color, face);
        t.setSingleLine(false);
        t.setEllipsize(null);
        t.setLineSpacing(0, 1.15f);
        t.setText(text);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = px(top);
        return p;
    }

    // ── looking a customer up (by phone): purchases → invoice → a new ticket ──

    private static final int REQUEST_PHOTO = 7301;
    private java.io.File pendingPhoto;
    private final ArrayList<java.io.File> formPhotos = new ArrayList<>();
    private TextView photosLabel;

    private void showLookup(String preset) {
        Context c = getContext();
        if (c == null) return;
        LinearLayout col = panel(c);
        TextView back = button(c, "‹ Tickets", false);
        back.setOnClickListener(v -> closePanel());
        col.addView(back, new LinearLayout.LayoutParams(-2, -2));
        col.addView(para(c, "Look up a customer", 25, Style.INK, Style.display), lp(14));
        EditText q = field(c, "Phone, name, product or IMEI", false);
        q.setSingleLine(true);
        if (preset != null) q.setText(preset);
        col.addView(q, lp(12));
        String[][] types = {{"phone", "Phone"}, {"name", "Name"}, {"product", "Product"}, {"imei", "IMEI"}, {"all", "All"}};
        final String[] type = {"phone"};
        LinearLayout typeRow = new LinearLayout(c);
        ArrayList<TextView> chips = new ArrayList<>();
        for (String[] t : types) {
            TextView chip = label(c, 15, Style.INK, Style.semibold);
            chip.setText(t[1]);
            chip.setTag(t[0]);
            chip.setPadding(px(12), px(7), px(12), px(7));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.rightMargin = px(6);
            typeRow.addView(chip, p);
            chips.add(chip);
            chip.setOnClickListener(v -> {
                type[0] = (String) v.getTag();
                String keep = status;
                status = type[0];
                paintChips(chips);
                status = keep;
            });
        }
        String keep = status;
        status = "phone";
        paintChips(chips);
        status = keep;
        col.addView(typeRow, lp(10));
        TextView go = button(c, "Search", true);
        col.addView(go, lp(12));
        LinearLayout results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results, lp(10));
        go.setOnClickListener(v -> {
            String text = q.getText().toString().trim();
            if (text.isEmpty()) return;
            if ("phone".equals(type[0]) && Care.findPhone(text) != null) text = Care.findPhone(text);
            go.setText("Searching…");
            AndroidUtilities.hideKeyboard(q);
            Care.purchases(text, type[0], (list, error) -> {
                go.setText("Search");
                results.removeAllViews();
                if (error != null) {
                    results.addView(para(c, "signed-out".equals(error) ? "Signed out of DroidShop." : error, 17, Style.DANGER, Style.regular));
                    return;
                }
                if (list.isEmpty()) {
                    results.addView(para(c, "No purchases found.", 17, Style.INK2, Style.regular));
                }
                for (Care.Purchase p : list) {
                    LinearLayout card = cardBox(c);
                    card.addView(para(c, p.code + " · " + p.source.toUpperCase(), 18, Style.INK, Style.semibold));
                    card.addView(para(c, p.customer + " · " + p.phone, 16, Style.INK2, Style.regular), lp(4));
                    card.addView(para(c, p.date + (p.total.isEmpty() ? "" : " · " + p.total), 15, Style.INK3, Style.regular), lp(2));
                    if (!TextUtils.isEmpty(p.note)) card.addView(para(c, p.note, 15, Style.INK2, Style.regular), lp(4));
                    card.setOnClickListener(x -> showInvoice(p));
                    results.addView(card, lp(8));
                }
            });
        });
        if (preset != null) go.performClick();
    }

    private LinearLayout cardBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(16), px(12), px(16), px(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.fg(18));
        bg.setCornerRadius(px(18));
        box.setBackground(bg);
        return box;
    }

    private void showInvoice(Care.Purchase p) {
        Context c = getContext();
        if (c == null) return;
        LinearLayout col = panel(c);
        col.addView(para(c, p.code, 25, Style.INK, Style.display));
        TextView loading = para(c, "Loading…", 17, Style.INK2, Style.regular);
        col.addView(loading, lp(10));
        Care.invoice(p, (inv, error) -> {
            if (error != null) {
                loading.setText(error);
                return;
            }
            col.removeAllViews();
            TextView back = button(c, "‹ Search", false);
            back.setOnClickListener(v -> showLookup(p.phone));
            col.addView(back, new LinearLayout.LayoutParams(-2, -2));
            col.addView(para(c, inv.code + (inv.status != null ? " · " + inv.status : ""), 25, Style.INK, Style.display), lp(14));
            col.addView(para(c, inv.customer + "\n" + inv.phone + (inv.address != null ? "\n" + inv.address : ""), 18, Style.INK, Style.semibold), lp(10));
            col.addView(para(c, (inv.date != null ? inv.date : "") + (inv.total != null ? " · " + inv.total : ""), 16, Style.INK3, Style.regular), lp(4));
            if (inv.description != null) col.addView(para(c, inv.description, 17, Style.INK2, Style.regular), lp(8));
            for (Care.Item it : inv.items) {
                LinearLayout card = cardBox(c);
                card.addView(para(c, it.name, 18, Style.INK, Style.semibold));
                if (it.serial != null) card.addView(para(c, "Serial " + it.serial, 15, Style.INK2, Style.regular), lp(4));
                if (it.term != null) {
                    Integer left = Care.warrantyDaysLeft(inv.date, it.term);
                    String w = "Bảo hành " + it.term + (left == null ? "" : left >= 0 ? " · còn " + left + " ngày" : " · hết hạn");
                    card.addView(para(c, w, 15, left == null ? Style.INK2 : left >= 0 ? Style.OK : Style.DANGER, Style.semibold), lp(4));
                }
                if (it.ticketStart != null) {
                    TextView start = button(c, "Tiếp nhận bảo hành", true);
                    start.setOnClickListener(v -> showTicketForm(inv, it, it.ticketStart));
                    card.addView(start, lp(10));
                }
                col.addView(card, lp(10));
            }
            if (inv.ticketStart != null) {
                TextView start = button(c, "Tiếp nhận bảo hành", true);
                start.setOnClickListener(v -> showTicketForm(inv, null, inv.ticketStart));
                col.addView(start, lp(14));
            }
        });
    }

    /** The new-ticket form: what's wrong, coverage, what's done with it, accessories, notes, photos. */
    private void showTicketForm(Care.Invoice inv, Care.Item item, String start) {
        Context c = getContext();
        if (c == null) return;
        formPhotos.clear();
        LinearLayout col = panel(c);
        col.addView(para(c, "Phiếu tiếp nhận", 25, Style.INK, Style.display));
        col.addView(para(c, (item != null ? item.name + (item.serial != null ? " · " + item.serial : "") + "\n" : "") + inv.customer + " · " + inv.phone, 17, Style.INK2, Style.regular), lp(8));
        EditText issue = field(c, "Mô tả lỗi", false);
        issue.setMinLines(3);
        col.addView(issue, lp(12));
        Spinner coverage = spinner(c, Care.COVERAGE, 2);
        col.addView(para(c, "Diện bảo hành", 15, Style.INK3, Style.semibold), lp(12));
        col.addView(coverage, lp(4));
        Spinner handling = spinner(c, Care.HANDLING, 0);
        col.addView(para(c, "Hướng xử lý", 15, Style.INK3, Style.semibold), lp(12));
        col.addView(handling, lp(4));
        col.addView(para(c, "Phụ kiện", 15, Style.INK3, Style.semibold), lp(12));
        ArrayList<CheckBox> accessories = new ArrayList<>();
        for (String[] a : Care.ACCESSORIES) {
            CheckBox box = new CheckBox(c);
            box.setText(a[1]);
            box.setTag(a[0]);
            box.setTextColor(Style.INK);
            accessories.add(box);
            col.addView(box);
        }
        EditText accNote = field(c, "Ghi chú phụ kiện", false);
        col.addView(accNote, lp(8));
        EditText internal = field(c, "Ghi chú nội bộ", false);
        col.addView(internal, lp(8));
        EditText customer = field(c, "Ghi chú cho khách", false);
        col.addView(customer, lp(8));
        photosLabel = para(c, "No photos", 16, Style.INK2, Style.regular);
        col.addView(photosLabel, lp(12));
        TextView photo = button(c, "Take a photo", false);
        photo.setOnClickListener(v -> takePhoto());
        col.addView(photo, lp(6));
        CheckBox makeCard = new CheckBox(c);
        makeCard.setText("Also make a Trello card (photos included)");
        makeCard.setTextColor(Style.INK);
        makeCard.setChecked(app.kyogram.ui.Trello.connected(c));
        makeCard.setVisibility(app.kyogram.ui.Trello.connected(c) ? View.VISIBLE : View.GONE);
        col.addView(makeCard, lp(10));
        TextView save = button(c, "Tạo phiếu", true);
        col.addView(save, lp(16));
        TextView cancel = button(c, "Cancel", false);
        cancel.setOnClickListener(v -> closePanel());
        col.addView(cancel, lp(8));
        save.setOnClickListener(v -> {
            java.util.HashMap<String, Object> f = new java.util.HashMap<>();
            if (issue.getText().length() > 0) f.put("issue_description", issue.getText().toString());
            f.put("warranty_coverage", Care.COVERAGE[coverage.getSelectedItemPosition()][0]);
            f.put("handling_direction", Care.HANDLING[handling.getSelectedItemPosition()][0]);
            ArrayList<String> acc = new ArrayList<>();
            for (CheckBox b : accessories) if (b.isChecked()) acc.add((String) b.getTag());
            if (!acc.isEmpty()) f.put("accessories[]", acc);
            if (accNote.getText().length() > 0) f.put("accessories_note", accNote.getText().toString());
            if (internal.getText().length() > 0) f.put("internal_note", internal.getText().toString());
            if (customer.getText().length() > 0) f.put("customer_note", customer.getText().toString());
            if (start == null) {
                f.put("customer_name", inv.customer);
                f.put("customer_phone", inv.phone);
            }
            save.setText("Saving…");
            Care.newTicket(start, f, new ArrayList<>(formPhotos), (code, error) -> {
                if (error != null) {
                    save.setText("Tạo phiếu");
                    Toast.makeText(c, "signed-out".equals(error) ? "Signed out of DroidShop" : error, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(c, "Saved " + code + " · report copied, paste it in the shop's chat", Toast.LENGTH_LONG).show();
                Integer leftR = item != null ? Care.warrantyDaysLeft(inv.date, item.term) : null;
                StringBuilder report = new StringBuilder("Em tiếp nhận hàng bảo hành mới:");
                String who = (item != null ? item.name : inv.description != null ? inv.description : "") + (TextUtils.isEmpty(inv.customer) ? "" : " của " + inv.customer);
                if (!who.trim().isEmpty()) report.append('\n').append(who.trim()).append(TextUtils.isEmpty(inv.phone) ? "" : " (" + inv.phone + ")");
                if (item != null && item.serial != null) report.append("\nSerial/IMEI: ").append(item.serial);
                if (issue.getText().length() > 0) report.append('\n').append(issue.getText());
                if (inv.date != null) report.append("\nNgày mua: ").append(inv.date);
                report.append("\nHết hạn bảo hành: ").append(leftR == null ? "chưa rõ" : leftR < 0 ? "Có" : "Không");
                if (!TextUtils.isEmpty(code)) report.append("\nMã phiếu: ").append(code);
                AndroidUtilities.addToClipboard(report.toString());
                if (makeCard.isChecked()) {
                    Integer left = item != null ? Care.warrantyDaysLeft(inv.date, item.term) : null;
                    app.kyogram.ui.Trello.warrantyCard(c, inv.customer, inv.phone, item != null ? item.name : inv.description, item != null ? item.serial : null,
                            inv.date, left == null ? null : left < 0, issue.getText().toString(), code, new ArrayList<>(formPhotos), (card, e2) -> {
                                if (e2 != null) Toast.makeText(c, "Trello: " + e2, Toast.LENGTH_LONG).show();
                                else if (!TextUtils.isEmpty(code)) app.kyogram.ui.Trello.setTicketLink(c, code, card);
                            });
                }
                load();
                if (!TextUtils.isEmpty(code)) openTicket(code);
                else closePanel();
            });
        });
    }

    private Spinner spinner(Context c, String[][] options, int selected) {
        Spinner s = new Spinner(c);
        ArrayList<String> names = new ArrayList<>();
        for (String[] o : options) names.add(o[1]);
        ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, names) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(Style.INK);
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.fg(20));
        bg.setCornerRadius(px(18));
        s.setBackground(bg);
        return s;
    }

    private void takePhoto() {
        try {
            pendingPhoto = AndroidUtilities.generatePicturePath();
            android.content.Intent intent = new android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(getParentActivity(), org.telegram.messenger.ApplicationLoader.getApplicationId() + ".provider", pendingPhoto);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_PHOTO);
        } catch (Exception e) {
            Toast.makeText(getContext(), "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == REQUEST_PHOTO && resultCode == android.app.Activity.RESULT_OK && pendingPhoto != null && pendingPhoto.exists()) {
            formPhotos.add(pendingPhoto);
            pendingPhoto = null;
            if (photosLabel != null) photosLabel.setText(formPhotos.size() + (formPhotos.size() == 1 ? " photo" : " photos"));
        }
    }

    /** The ticket's Trello card: found by its code on the board, or picked by hand (ticketTrelloPanel.tsx). */
    private void trelloSection(Context c, LinearLayout col, Care.Detail d) {
        if (!app.kyogram.ui.Trello.connected(c)) return;
        col.addView(para(c, "Trello card", 21, Style.INK, Style.bold), lp(22));
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        col.addView(box, lp(6));
        Runnable[] show = new Runnable[1];
        show[0] = () -> {
            box.removeAllViews();
            String[] link = app.kyogram.ui.Trello.ticketLink(c, d.code);
            if (link != null) {
                LinearLayout card = cardBox(c);
                card.addView(para(c, link.length > 2 ? link[2] : link[1], 18, Style.INK, Style.semibold));
                card.addView(para(c, "Notes you add here are also posted on this card.", 15, Style.INK3, Style.regular), lp(4));
                card.setOnClickListener(v -> {
                    try {
                        getParentActivity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link[1])));
                    } catch (Exception ignore) {
                    }
                });
                box.addView(card);
                TextView unlink = button(c, "Unlink", false);
                unlink.setOnClickListener(v -> {
                    app.kyogram.ui.Trello.setTicketLink(c, d.code, null);
                    show[0].run();
                });
                box.addView(unlink, lp(8));
                return;
            }
            TextView finding = para(c, "Looking for a card that names " + d.code + "…", 16, Style.INK2, Style.regular);
            box.addView(finding);
            app.kyogram.ui.Trello.search(c, "\"" + d.code + "\"", (found, error) -> {
                if (found != null && !found.isEmpty()) {
                    app.kyogram.ui.Trello.Found f = found.get(0);
                    for (app.kyogram.ui.Trello.Found x : found) if (!x.closed) { f = x; break; }
                    app.kyogram.ui.Trello.setTicketLink(c, d.code, f);
                    show[0].run();
                    return;
                }
                box.removeAllViews();
                box.addView(para(c, "No card names this ticket. Search the board to pick one:", 16, Style.INK2, Style.regular));
                EditText q = field(c, "Customer, phone or product", false);
                q.setSingleLine(true);
                box.addView(q, lp(8));
                LinearLayout results = new LinearLayout(c);
                results.setOrientation(LinearLayout.VERTICAL);
                TextView go = button(c, "Search Trello", false);
                box.addView(go, lp(8));
                box.addView(results, lp(6));
                go.setOnClickListener(v -> app.kyogram.ui.Trello.search(c, q.getText().toString().trim(), (list, e2) -> {
                    results.removeAllViews();
                    if (list == null) return;
                    for (app.kyogram.ui.Trello.Found f : list) {
                        LinearLayout card = cardBox(c);
                        card.addView(para(c, f.name, 17, Style.INK, Style.semibold));
                        card.setOnClickListener(x -> {
                            app.kyogram.ui.Trello.setTicketLink(c, d.code, f);
                            app.kyogram.ui.Trello.comment(c, f.id, "🔗 Liên kết phiếu bảo hành DroidShop " + d.code, (ok, e3) -> {});
                            show[0].run();
                        });
                        results.addView(card, lp(6));
                    }
                }));
            });
        };
        show[0].run();
    }

    private void showSignIn() {
        Context c = getContext();
        if (c == null) return;
        LinearLayout col = panel(c);
        col.addView(para(c, "Sign in to DroidShop", 25, Style.INK, Style.display));
        col.addView(para(c, "Your care.droidshop.vn account. Kyogram keeps the sign-in on this phone only.", 16, Style.INK2, Style.regular), lp(10));
        EditText user = field(c, "Username", false);
        user.setSingleLine(true);
        col.addView(user, lp(16));
        EditText pass = field(c, "Password", true);
        col.addView(pass, lp(10));
        CheckBox remember = new CheckBox(c);
        remember.setText("Stay signed in");
        remember.setTextColor(Style.INK2);
        remember.setChecked(true);
        col.addView(remember, lp(10));
        TextView go = button(c, "Sign in", true);
        col.addView(go, lp(14));
        go.setOnClickListener(v -> {
            go.setText("Signing in…");
            Care.signIn(user.getText().toString().trim(), pass.getText().toString(), remember.isChecked(), (name, error) -> {
                if (error != null) {
                    go.setText("Sign in");
                    Toast.makeText(c, error, Toast.LENGTH_LONG).show();
                    return;
                }
                closePanel();
                load();
            });
        });
    }

    private void openTicket(String code) {
        Context c = getContext();
        if (c == null) return;
        LinearLayout col = panel(c);
        col.addView(para(c, code, 25, Style.INK, Style.display));
        TextView loading = para(c, "Loading…", 17, Style.INK2, Style.regular);
        col.addView(loading, lp(10));
        Care.ticket(code, (d, error) -> {
            if (error != null) {
                loading.setText("signed-out".equals(error) ? "Signed out of DroidShop." : "Couldn't open the ticket: " + error);
                return;
            }
            fillTicket(c, col, d);
        });
    }

    private void fillTicket(Context c, LinearLayout col, Care.Detail d) {
        col.removeAllViews();
        TextView close = button(c, "‹ Tickets", false);
        close.setOnClickListener(v -> closePanel());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        col.addView(close, cp);
        col.addView(para(c, d.code, 25, Style.INK, Style.display), lp(14));
        TextView st = label(c, 16, toneColor(Care.tone(d.status)), Style.bold);
        st.setText(d.status);
        st.setPadding(px(12), px(6), px(12), px(6));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Style.alpha(toneColor(Care.tone(d.status)), .18f));
        sbg.setCornerRadius(px(12));
        st.setBackground(sbg);
        col.addView(st, new LinearLayout.LayoutParams(-2, -2));
        col.addView(para(c, d.customer + (d.phone.isEmpty() ? "" : "\n" + d.phone), 19, Style.INK, Style.semibold), lp(12));
        for (String[] m : d.meta) {
            col.addView(para(c, m[0], 15, Style.INK3, Style.semibold), lp(12));
            col.addView(para(c, m[1], 18, Style.INK, Style.regular), lp(2));
        }
        if (!d.issue.isEmpty()) {
            col.addView(para(c, "Mô tả lỗi", 15, Style.INK3, Style.semibold), lp(12));
            col.addView(para(c, d.issue, 18, Style.INK, Style.regular), lp(2));
        }
        if (!d.accessories.isEmpty()) {
            col.addView(para(c, "Phụ kiện", 15, Style.INK3, Style.semibold), lp(12));
            col.addView(para(c, TextUtils.join(" · ", d.accessories), 18, Style.INK, Style.regular), lp(2));
        }

        trelloSection(c, col, d);

        // * change the status
        if (d.canChangeStatus && !d.statusOptions.isEmpty()) {
            col.addView(para(c, "Change status", 21, Style.INK, Style.bold), lp(22));
            Spinner pick = new Spinner(c);
            ArrayList<String> names = new ArrayList<>();
            int sel = 0;
            for (int i = 0; i < d.statusOptions.size(); i++) {
                names.add(d.statusOptions.get(i)[1]);
                if (d.statusOptions.get(i)[0].equals(d.statusValue)) sel = i;
            }
            ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, names) {
                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    View v = super.getView(position, convertView, parent);
                    if (v instanceof TextView) ((TextView) v).setTextColor(Style.INK);
                    return v;
                }
            };
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            pick.setAdapter(a);
            pick.setSelection(sel);
            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(Style.fg(20));
            pbg.setCornerRadius(px(18));
            pick.setBackground(pbg);
            col.addView(pick, lp(8));
            EditText note = field(c, "Note (needed for Từ chối bảo hành)", false);
            col.addView(note, lp(8));
            TextView save = button(c, "Save status", true);
            col.addView(save, lp(10));
            save.setOnClickListener(v -> {
                save.setText("Saving…");
                Care.changeStatus(d.code, d.statusOptions.get(pick.getSelectedItemPosition())[0], note.getText().toString(), (nd, error) -> {
                    if (error != null) {
                        save.setText("Save status");
                        Toast.makeText(c, error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    fillTicket(c, col, nd);
                    load();
                });
            });
        }

        // * add a note
        if (d.canAddNote) {
            col.addView(para(c, "Add a note", 21, Style.INK, Style.bold), lp(22));
            EditText note = field(c, "Internal note", false);
            col.addView(note, lp(8));
            TextView add = button(c, "Add note", true);
            col.addView(add, lp(10));
            add.setOnClickListener(v -> {
                String text = note.getText().toString().trim();
                if (text.isEmpty()) return;
                add.setText("Adding…");
                Care.addNote(d.code, text, (nd, error) -> {
                    if (error != null) {
                        add.setText("Add note");
                        Toast.makeText(c, error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    // * the note goes on the ticket's Trello card too (ticketTrello.ts)
                    String[] link = app.kyogram.ui.Trello.connected(c) ? app.kyogram.ui.Trello.ticketLink(c, d.code) : null;
                    if (link != null) app.kyogram.ui.Trello.comment(c, link[0], "📝 " + d.code + ": " + text, (ok, e2) -> {
                        if (e2 != null) Toast.makeText(c, "Trello: " + e2, Toast.LENGTH_SHORT).show();
                    });
                    fillTicket(c, col, nd);
                });
            });
        }

        // * the history, newest as the site lists it
        if (!d.timeline.isEmpty()) {
            col.addView(para(c, "Tiến độ xử lý", 21, Style.INK, Style.bold), lp(22));
            for (Care.Entry e : d.timeline) {
                LinearLayout box = new LinearLayout(c);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(px(16), px(12), px(16), px(12));
                GradientDrawable bbg = new GradientDrawable();
                bbg.setColor(Style.fg(e.note ? 18 : 10));
                bbg.setCornerRadius(px(18));
                box.setBackground(bbg);
                box.addView(para(c, e.title, 18, Style.INK, Style.semibold));
                box.addView(para(c, e.when + (e.who.isEmpty() ? "" : " · " + e.who), 15, Style.INK3, Style.regular), lp(4));
                if (!TextUtils.isEmpty(e.body)) box.addView(para(c, e.body, 17, Style.INK2, Style.regular), lp(6));
                col.addView(box, lp(8));
            }
        }
    }
}
