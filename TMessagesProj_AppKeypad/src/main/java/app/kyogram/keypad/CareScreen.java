package app.kyogram.keypad;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

import app.kyogram.ui.Care;
import app.kyogram.ui.ChatListView;
import app.kyogram.ui.GlassFrame;
import app.kyogram.ui.MenuView;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;

/**
 * Care on the flip phone: DroidShop's warranty tickets, driven by the keys, in the prototype's motion.
 *
 * <ul>
 * <li>Signing in: a glass card of rows — username, password, remember me, the button — that the pill
 * glides between; the button folds into a round, breathing spinner while it signs in, the card shakes on a
 * wrong password, and on the way in it sinks away as the tickets slide in.</li>
 * <li>The tickets: the chat list's header says "Care" and its tabs are the statuses (◀ ▶, the pill glides,
 * the rows slide in from that side); Search turns the header into a field and rows that don't match fade
 * out as the rest close up.</li>
 * <li>A ticket grows out of its row the way a chat does, and is a page of glass cards and history bubbles
 * with the chat's focus ring; a note pops in at the bottom; a new status rolls the chip's words.</li>
 * <li>Options and the statuses come up in the menu's glass sheet.</li>
 * </ul>
 */
final class CareScreen extends FrameLayout {

    interface Host {
        void soft(String left, String center, String right);

        void close();

        void keyboard(EditText field, boolean show);
    }

    private enum S { SIGNIN, FIELD, BUSY, LIST, SEARCH, TICKET, NOTE, SHEET }

    static final int K_UP = 1, K_DOWN = 2, K_LEFT = 3, K_RIGHT = 4, K_OK = 5, K_SOFT_L = 6, K_SOFT_R = 7, K_BACK = 8;

    /** Short names for the status tabs and chips (the site's are long). */
    private static final String[] SHORT = {"Tiếp nhận", "Kiểm tra", "Chờ khách", "Xử lý", "Chờ linh kiện", "Đã xử lý", "Không sửa", "Hoàn thành", "Từ chối"};

    private final Host host;
    private final int sb;
    private S s = S.LIST, sheetFrom = S.TICKET, ticketFrom = S.LIST;

    // ── sign in ──
    private FrameLayout signLayer;
    private GlassFrame card;
    private FormRows rows;
    private EditText user, pass;
    private Check remember;
    private SignButton button;
    private TextView error;
    private Spring signP, shake, errP;
    private int row;

    // ── tickets ──
    private FrameLayout listLayer;
    private HeaderView header;
    private TextView hint, empty;
    private Dots dots;
    private ChatListView list;
    private int tab;
    private String query = "";
    private final HashMap<String, ArrayList<Care.Ticket>> cache = new HashMap<>();
    private final HashMap<Long, Care.Ticket> byId = new HashMap<>();
    private final HashMap<String, Long> ids = new HashMap<>();
    private int listGen;
    private Runnable searchLater;

    // ── a ticket ──
    private KeypadActivity.RevealFrame ticketLayer;
    private Head head;
    private TicketView ticket;
    private GlassFrame composer;
    private EditText note;
    private Spring openP;
    private float openTop, openBottom;
    private Care.Ticket open;
    private Care.Detail detail;
    private int ticketGen;

    // ── sheet and toast ──
    private View dim;
    private GlassFrame sheet;
    private MenuView menu;
    private String sheetKind;
    private Spring sheetP, toastP;
    private GlassFrame toast;
    private TextView toastText;
    private final Runnable hideToast = () -> toastP.to(0);

    CareScreen(Context c, int statusBar, Host host) {
        super(c);
        this.host = host;
        this.sb = statusBar;
        Care.init(c);
        buildList(c);
        buildTicket(c);
        buildSignIn(c);
        buildSheet(c);
    }

    private int px(float v) {
        return Math.round(Style.px(v));
    }

    private FrameLayout.LayoutParams box(int left, int top, int right, int bottom, int height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, height > 0 ? height : -1);
        lp.leftMargin = px(left);
        lp.rightMargin = px(right);
        if (top >= 0) {
            lp.topMargin = top;
            lp.gravity = Gravity.TOP;
        }
        if (bottom >= 0) {
            lp.bottomMargin = bottom;
            if (top < 0) lp.gravity = Gravity.BOTTOM;
        }
        return lp;
    }

    private TextView label(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(getContext());
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(size));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private EditText field(String hintText, boolean password) {
        EditText e = new EditText(getContext());
        e.setBackground(null);
        e.setTextColor(Style.INK);
        e.setHintTextColor(Style.INK3);
        e.setHint(hintText);
        e.setTypeface(Style.semibold);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(21));
        e.setSingleLine(true);
        e.setPadding(0, 0, 0, 0);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
        e.setFocusable(false);
        return e;
    }

    // ── building ──

    private void buildList(Context c) {
        listLayer = new FrameLayout(c);
        addView(listLayer, new FrameLayout.LayoutParams(-1, -1));
        header = new HeaderView(c);
        header.setTitle("Care");
        header.search.setHint("Phone, code or serial");
        listLayer.addView(header, box(14, sb + px(6), 14, -1, px(64)));
        header.search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence q, int a, int b, int d) {}
            public void onTextChanged(CharSequence q, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                if (s != S.SEARCH) return;
                query = e.toString().trim();
                if (searchLater != null) removeCallbacks(searchLater);
                searchLater = () -> loadTickets(ChatListView.FROM_NONE, true);
                postDelayed(searchLater, 450);
            }
        });
        ArrayList<String> names = new ArrayList<>();
        names.add("Tất cả");
        for (String n : SHORT) names.add(n);
        header.setTabs(names, 0, true);

        hint = label(15, Style.INK3, Style.regular);
        hint.setGravity(Gravity.CENTER);
        hint.setText("◀ ▶ status · OK opens a ticket");
        listLayer.addView(hint, box(0, sb + px(76), 0, -1, px(22)));
        dots = new Dots(c);
        dots.setAlpha(0);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(px(60), px(22), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dl.topMargin = sb + px(76);
        listLayer.addView(dots, dl);

        list = new ChatListView(c, false);
        listLayer.addView(list, box(0, sb + px(102), 0, px(84), -1));
        empty = label(20, Style.INK3, Style.regular);
        empty.setGravity(Gravity.CENTER);
        empty.setAlpha(0);
        listLayer.addView(empty, box(0, sb + px(162), 0, -1, px(30)));
    }

    private void buildTicket(Context c) {
        ticketLayer = new KeypadActivity.RevealFrame(c);
        ticketLayer.setVisibility(View.GONE);
        addView(ticketLayer, new FrameLayout.LayoutParams(-1, -1));
        ticketLayer.addView(new KeypadActivity.WallView(c), new FrameLayout.LayoutParams(-1, -1));
        head = new Head(c);
        ticketLayer.addView(head, box(14, sb + px(6), 14, -1, px(72)));
        ticket = new TicketView(c);
        ticket.setListener(b -> {
            if (s == S.TICKET) host.soft("Back", centerFor(b), "Options");
        });
        ticketLayer.addView(ticket, box(0, sb + px(84), 0, px(162), -1));
        composer = new GlassFrame(c, 31);
        note = field("Add a note…", false);
        note.setTypeface(Style.medium);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(20));
        note.setHintTextColor(Style.fg(140));
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_SEND);
        note.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-1, -1);
        nl.leftMargin = px(22);
        nl.rightMargin = px(22);
        composer.addView(note, nl);
        ticketLayer.addView(composer, box(12, -1, 12, px(92), px(62)));
        openP = new Spring(0, 260, 27, (p, v) -> applyOpen(p));
    }

    private void buildSignIn(Context c) {
        signLayer = new FrameLayout(c);
        signLayer.setVisibility(View.GONE);
        addView(signLayer, new FrameLayout.LayoutParams(-1, -1));
        Head sh = new Head(c);
        sh.set("☺", 0xff5fd1a0, 0xff2a9a6a, "Care", "Sign in to DroidShop");
        signLayer.addView(sh, box(14, sb + px(6), 14, -1, px(72)));

        card = new GlassFrame(c, 34);
        rows = new FormRows(c);
        card.addView(rows, new FrameLayout.LayoutParams(-1, -1));
        user = field("Username", false);
        pass = field("Password", true);
        rows.addField(0, "USERNAME", user);
        rows.addField(1, "PASSWORD", pass);
        remember = new Check(c);
        rows.addRemember(remember);
        button = new SignButton(c);
        rows.addButton(button);
        signLayer.addView(card, box(14, sb + px(96), 14, -1, px(FormRows.HEIGHT)));

        error = label(17, Style.DANGER, Style.semibold);
        error.setGravity(Gravity.CENTER);
        error.setAlpha(0);
        signLayer.addView(error, box(24, sb + px(96 + FormRows.HEIGHT + 16), 24, -1, px(60)));

        signP = new Spring(1, 300, 30, (p, v) -> {
            float q = Math.max(0, p);
            card.setAlpha(Math.min(1, q * 1.5f));
            float sc = .9f + .1f * q;
            card.setScaleX(sc);
            card.setScaleY(sc);
            card.setTranslationY((1 - q) * Style.px(40));
            if (q <= .01f && s != S.SIGNIN && s != S.FIELD && s != S.BUSY) signLayer.setVisibility(View.GONE);
        });
        shake = new Spring(0, 700, 14, (v, vel) -> card.setTranslationX(v));
        errP = new Spring(0, 340, 26, (p, v) -> {
            error.setAlpha(Math.min(1, Math.max(0, p) * 1.5f));
            error.setTranslationY((1 - p) * -Style.px(16));
        });
        String saved = c.getSharedPreferences("kyogram_care", Context.MODE_PRIVATE).getString("user", "");
        user.setText(saved);
        remember.set(true, true);
    }

    private void buildSheet(Context c) {
        dim = new View(c);
        dim.setBackgroundColor(Color.argb(128, 10, 8, 18));
        dim.setAlpha(0);
        dim.setVisibility(View.GONE);
        addView(dim, new FrameLayout.LayoutParams(-1, -1));
        sheet = new GlassFrame(c, 38);
        sheet.setVisibility(View.GONE);
        menu = new MenuView(c);
        FrameLayout.LayoutParams mvl = new FrameLayout.LayoutParams(-1, -1);
        mvl.setMargins(px(14), px(14), px(14), px(14));
        sheet.addView(menu, mvl);
        addView(sheet, box(14, -1, 14, px(96), px(28) + px(MenuView.TITLE_H) + px(MenuView.ITEM) * 4));
        sheetP = new Spring(0, 330, 24, (p, v) -> {
            float q = Math.max(0, p);
            sheet.setAlpha(Math.min(1, q * 2));
            sheet.setTranslationY((1 - q) * Style.px(70));
            float sc = .86f + .14f * q;
            sheet.setScaleX(sc);
            sheet.setScaleY(sc);
            dim.setAlpha(Math.min(1, q));
            if (q <= .001f && s != S.SHEET) {
                sheet.setVisibility(View.GONE);
                dim.setVisibility(View.GONE);
            }
        });

        toast = new GlassFrame(c, 24);
        toastText = label(18, Style.INK, Style.semibold);
        toastText.setGravity(Gravity.CENTER);
        toastText.setPadding(px(18), 0, px(18), 0);
        toast.addView(toastText, new FrameLayout.LayoutParams(-1, -1));
        toast.setAlpha(0);
        addView(toast, box(34, sb + px(86), 34, -1, px(48)));
        toastP = new Spring(0, 340, 26, (p, v) -> {
            toast.setAlpha(Math.min(1, Math.max(0, p) * 1.5f));
            toast.setTranslationY((1 - p) * -Style.px(30));
            float sc = .94f + .06f * p;
            toast.setScaleX(sc);
            toast.setScaleY(sc);
        });
    }

    // ── coming in and going out ──

    /** Care opens: the tickets if this phone is signed in, else the sign-in card. */
    void enter() {
        closeSheet(true);
        ticketLayer.setVisibility(View.GONE);
        openP.to(0, true);
        if (Care.hasSession()) {
            showList(true);
            loadTickets(ChatListView.FROM_NONE, false);
        } else {
            showSignIn(true);
        }
    }

    /** Care closes: no field left listening, no keyboard left up. */
    void leave() {
        host.keyboard(user, false);
        host.keyboard(pass, false);
        host.keyboard(note, false);
        host.keyboard(header.search, false);
        if (s == S.SEARCH) {
            header.setSearching(false);
            header.search.setText("");
            query = "";
        }
    }

    boolean typing() {
        return s == S.FIELD || s == S.SEARCH || s == S.NOTE;
    }

    EditText field() {
        if (s == S.FIELD) return row == 0 ? user : pass;
        if (s == S.SEARCH) return header.search;
        if (s == S.NOTE) return note;
        return null;
    }

    void restoreKeys() {
        switch (s) {
            case SIGNIN: host.soft("Back", rowLabel(), ""); break;
            case FIELD: host.soft("Done", "NEXT", ""); break;
            case BUSY: host.soft("", "…", ""); break;
            case LIST: host.soft("Back", "OPEN", "Search"); break;
            case SEARCH: host.soft("Close", "OPEN", ""); break;
            case TICKET: host.soft("Back", centerFor(ticket.focused()), "Options"); break;
            case NOTE: host.soft("Cancel", "SEND", ""); break;
            case SHEET: host.soft("Close", "SELECT", ""); break;
        }
    }

    // ── keys ──

    void press(int k, int digit) {
        switch (s) {
            case SIGNIN:
                if (k == K_UP) moveRow(-1);
                else if (k == K_DOWN) moveRow(1);
                else if (k == K_OK) rowOk();
                else if (k == K_SOFT_L || k == K_BACK) host.close();
                break;
            case FIELD:
                if (k == K_UP || k == K_DOWN) {
                    endField();
                    moveRow(k == K_UP ? -1 : 1);
                } else if (k == K_OK) {
                    endField();
                    moveRow(1);
                    if (row == 1) startField();
                } else if (k == K_SOFT_L || k == K_BACK) endField();
                break;
            case BUSY:
                break;
            case LIST:
                if (k == K_UP) list.moveFocus(-1, false);
                else if (k == K_DOWN) list.moveFocus(1, false);
                else if (k == K_LEFT) switchTab(-1);
                else if (k == K_RIGHT) switchTab(1);
                else if (k == K_OK) openFocused();
                else if (k == K_SOFT_R) openSearch();
                else if (k == K_SOFT_L || k == K_BACK) host.close();
                break;
            case SEARCH:
                if (k == K_UP) list.moveFocus(-1, false);
                else if (k == K_DOWN) list.moveFocus(1, false);
                else if (k == K_OK) openFocused();
                else if (k == K_SOFT_L || k == K_BACK) closeSearch();
                break;
            case TICKET:
                if (k == K_UP) ticket.move(-1);
                else if (k == K_DOWN) {
                    if (!ticket.atLast()) ticket.move(1);
                    else if (detail != null && detail.canAddNote) startNote();
                    else ticket.bounce();
                } else if (k == K_OK) blockOk();
                else if (k == K_SOFT_R) openOptions();
                else if (k == K_SOFT_L || k == K_BACK) closeTicket(false);
                break;
            case NOTE:
                if (k == K_OK) sendNote();
                else if (k == K_SOFT_L || k == K_BACK) endNote();
                break;
            case SHEET:
                if (k == K_UP) menu.move(-1, false);
                else if (k == K_DOWN) menu.move(1, false);
                else if (k == K_OK) choose(menu.focused(), false);
                else if (digit > 0) choose(menu.byKey(digit), true);
                else if (k == K_SOFT_L || k == K_BACK) closeSheet(false);
                break;
        }
    }

    // ── sign in ──

    private void showSignIn(boolean snap) {
        s = S.SIGNIN;
        listLayer.setVisibility(View.GONE);
        signLayer.setVisibility(View.VISIBLE);
        signP.to(0, true);
        signP.to(1, snap);
        button.busy(false, true);
        errP.to(0, true);
        row = user.length() > 0 ? (pass.length() > 0 ? 3 : 1) : 0;
        rows.focus(row, true);
        restoreKeys();
    }

    private String rowLabel() {
        return row <= 1 ? "EDIT" : row == 2 ? "TOGGLE" : "SIGN IN";
    }

    private void moveRow(int d) {
        row = Math.max(0, Math.min(3, row + d));
        rows.focus(row, false);
        restoreKeys();
    }

    private void rowOk() {
        if (row <= 1) startField();
        else if (row == 2) remember.set(!remember.on, false);
        else submit();
    }

    private void startField() {
        s = S.FIELD;
        EditText f = row == 0 ? user : pass;
        host.keyboard(f, true);
        f.setSelection(f.length());
        restoreKeys();
    }

    private void endField() {
        host.keyboard(row == 0 ? user : pass, false);
        s = S.SIGNIN;
        restoreKeys();
    }

    private void submit() {
        String u = user.getText().toString().trim(), p = pass.getText().toString();
        if (u.isEmpty() || p.isEmpty()) {
            showError(u.isEmpty() ? "Type your username" : "Type your password");
            row = u.isEmpty() ? 0 : 1;
            rows.focus(row, false);
            restoreKeys();
            return;
        }
        s = S.BUSY;
        errP.to(0);
        button.busy(true, false);
        restoreKeys();
        Care.signIn(u, p, remember.on, (name, err) -> {
            if (err != null) {
                s = S.SIGNIN;
                button.busy(false, false);
                showError(err);
                restoreKeys();
                return;
            }
            pass.setText("");
            s = S.LIST;
            // * the card sinks away, the tickets come in from the side
            signP.to(0);
            postDelayed(() -> {
                showList(false);
                loadTickets(ChatListView.FROM_RIGHT, false);
                toast("Signed in" + (TextUtils.isEmpty(name) ? "" : " · " + name));
            }, Spring.reduced ? 0 : 160);
        });
    }

    private void showError(String text) {
        error.setText(text);
        errP.to(0, true);
        errP.to(1);
        if (!Spring.reduced) {
            shake.to(0, true);
            shake.vel = Style.px(900);
            shake.to(0);
        }
    }

    // ── the tickets ──

    private void showList(boolean snap) {
        s = S.LIST;
        signLayer.setVisibility(snap ? View.GONE : signLayer.getVisibility());
        listLayer.setVisibility(View.VISIBLE);
        listLayer.setAlpha(0);
        listLayer.animate().alpha(1).setDuration(snap || Spring.reduced ? 0 : 220).start();
        header.select(tab, true);
        restoreKeys();
    }

    private String statusValue() {
        return tab == 0 ? "" : Care.STATUSES[tab - 1][0];
    }

    private void switchTab(int d) {
        int next = Math.max(0, Math.min(header.tabCount() - 1, tab + d));
        if (next == tab) {
            list.bounce();
            return;
        }
        tab = next;
        header.select(tab, false);
        loadTickets(d > 0 ? ChatListView.FROM_RIGHT : ChatListView.FROM_LEFT, false);
    }

    /** What's cached shows at once (sliding in from {@code from}); the site's answer then settles in. */
    private void loadTickets(int from, boolean keepFocus) {
        final int gen = ++listGen;
        final String q = s == S.SEARCH ? query : "";
        final String key = q.isEmpty() ? "s:" + statusValue() : null;
        ArrayList<Care.Ticket> cached = key != null ? cache.get(key) : null;
        if (cached != null) showTickets(cached, from, keepFocus, false);
        else if (from != ChatListView.FROM_NONE || list.count() > 0) showTickets(new ArrayList<>(), from, false, true);
        busy(true);
        if (s == S.SEARCH && q.isEmpty()) {
            busy(false);
            showTickets(new ArrayList<>(), ChatListView.FROM_NONE, false, false);
            empty.setText("Type a phone, code or serial");
            empty.animate().alpha(1).setDuration(250).start();
            return;
        }
        Care.tickets(q, q.isEmpty() ? statusValue() : "", (res, err) -> {
            if (gen != listGen) return;
            busy(false);
            if ("signed-out".equals(err)) {
                toast("Signed out of DroidShop");
                showSignIn(false);
                return;
            }
            if (err != null) {
                toast(err);
                if (cached == null) {
                    empty.setText("Couldn't reach DroidShop");
                    empty.animate().alpha(1).setDuration(250).start();
                }
                return;
            }
            if (key != null) cache.put(key, res);
            showTickets(res, ChatListView.FROM_NONE, cached != null || keepFocus, false);
        });
    }

    private void showTickets(ArrayList<Care.Ticket> tickets, int from, boolean keepFocus, boolean loading) {
        ArrayList<ChatListView.Item> items = new ArrayList<>();
        for (Care.Ticket t : tickets) {
            ChatListView.Item it = new ChatListView.Item();
            it.id = idFor(t.code);
            byId.put(it.id, t);
            it.name = t.code + (TextUtils.isEmpty(t.product) ? "" : " · " + t.product);
            String who = TextUtils.isEmpty(t.customer) ? "" : t.customer;
            String when = shortDate(t.date);
            it.preview = who.isEmpty() ? when : when.isEmpty() ? who : who + " · " + when;
            it.chip = shortStatus(t.status);
            int col = toneColor(Care.tone(t.status));
            it.chipColor = col;
            it.chipBg = Color.argb(46, Color.red(col), Color.green(col), Color.blue(col));
            items.add(it);
        }
        list.setItems(items, from, keepFocus, false);
        boolean none = items.isEmpty() && !loading;
        if (none) empty.setText(s == S.SEARCH ? "No tickets match" : "No tickets here");
        empty.animate().alpha(none ? 1 : 0).setDuration(250).start();
    }

    private void busy(boolean on) {
        // * the hint gives way to three dots that hop, as someone typing does
        hint.animate().alpha(on || s == S.SEARCH ? 0 : 1).setDuration(200).start();
        dots.animate().alpha(on ? 1 : 0).setDuration(200).start();
        dots.run(on);
    }

    private long idFor(String code) {
        Long id = ids.get(code);
        if (id == null) {
            id = 5000L + ids.size();
            ids.put(code, id);
        }
        return id;
    }

    private static String shortDate(String d) {
        if (d == null) return "";
        // * 2026-11-23 10:54 → 23/11 10:54
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(.*)").matcher(d.trim());
        return m.matches() ? m.group(3) + "/" + m.group(2) + m.group(4) : d.trim();
    }

    static String shortStatus(String label) {
        if (label == null) return "";
        for (int i = 0; i < Care.STATUSES.length; i++) if (Care.STATUSES[i][1].equalsIgnoreCase(label.trim())) return SHORT[i];
        return label.length() > 14 ? label.substring(0, 13) + "…" : label;
    }

    static int toneColor(String tone) {
        switch (tone) {
            case "blue": return Style.ACCENT_HI;
            case "amber": return Style.WARN;
            case "teal": return Color.parseColor("#6fd6c8");
            case "green": return Style.OK;
            case "red": return Style.DANGER;
            default: return Color.parseColor("#b8b4c8");
        }
    }

    private void openSearch() {
        s = S.SEARCH;
        header.setSearching(true);
        hint.animate().alpha(0).setDuration(200).start();
        restoreKeys();
        postDelayed(() -> host.keyboard(header.search, true), 60);
        query = "";
        showTickets(new ArrayList<>(), ChatListView.FROM_NONE, false, false);
        empty.setText("Type a phone, code or serial");
    }

    private void closeSearch() {
        if (searchLater != null) removeCallbacks(searchLater);
        host.keyboard(header.search, false);
        header.setSearching(false);
        header.search.setText("");
        query = "";
        s = S.LIST;
        hint.animate().alpha(1).setDuration(200).start();
        restoreKeys();
        loadTickets(ChatListView.FROM_NONE, false);
    }

    // ── a ticket: grows out of its row ──

    private void openFocused() {
        ChatListView.Item it = list.focusedItem();
        if (it == null) {
            list.bounce();
            return;
        }
        Care.Ticket t = byId.get(it.id);
        if (t == null) return;
        if (s == S.SEARCH) host.keyboard(header.search, false);
        ticketFrom = s;
        open = t;
        detail = null;
        float top = list.getTop() + list.rowTop(it);
        openTop = Math.max(sb, top);
        openBottom = Math.min(getHeight() - Style.px(80), top + Style.px(ChatListView.ROW));
        int col = toneColor(Care.tone(t.status));
        head.set(initial(t), lighten(col), darken(col), t.code, TextUtils.isEmpty(t.product) ? t.customer : t.product);
        note.setText("");
        ticket.showLoading();
        ticket.showRing(false);
        ticketLayer.setVisibility(View.VISIBLE);
        s = S.TICKET;
        host.soft("Back", "", "Options");
        openP.to(0, true);
        openP.to(1);
        loadTicket(false);
    }

    private static String initial(Care.Ticket t) {
        String p = t.product != null ? t.product.trim() : "";
        return p.isEmpty() ? "#" : p.substring(0, 1).toUpperCase();
    }

    private void loadTicket(boolean keepFocus) {
        final Care.Ticket t = open;
        final int gen = ++ticketGen;
        Care.ticket(t.code, (d, err) -> {
            if (gen != ticketGen || open != t) return;
            if ("signed-out".equals(err)) {
                closeTicket(true);
                toast("Signed out of DroidShop");
                showSignIn(false);
                return;
            }
            if (err != null || d == null) {
                toast(err != null ? err : "Couldn't open " + t.code);
                return;
            }
            showDetail(d, keepFocus);
        });
    }

    private void showDetail(Care.Detail d, boolean keepFocus) {
        detail = d;
        String label = !TextUtils.isEmpty(d.status) ? d.status : open.status;
        int col = toneColor(Care.tone(label));
        head.setTileColors(lighten(col), darken(col));
        ticket.setDetail(d, shortStatus(label), col, keepFocus);
        if (s == S.TICKET) {
            ticket.showRing(true);
            restoreKeys();
        }
        // * the list behind keeps up
        open.status = label;
        cache.clear();
    }

    private String centerFor(TicketView.Block b) {
        if (b == null) return "";
        switch (b.kind) {
            case TicketView.STATUS: return detail != null && detail.canChangeStatus ? "CHANGE" : "";
            case TicketView.CUSTOMER: return detail != null && !TextUtils.isEmpty(detail.phone) ? "CALL" : "";
            case TicketView.NOTE:
            case TicketView.EVENT: return detail != null && detail.canAddNote ? "NOTE" : "";
            default: return "";
        }
    }

    private void blockOk() {
        TicketView.Block b = ticket.focused();
        if (b == null || detail == null) return;
        if (b.kind == TicketView.STATUS && detail.canChangeStatus) openStatuses();
        else if (b.kind == TicketView.CUSTOMER && !TextUtils.isEmpty(detail.phone)) call();
        else if ((b.kind == TicketView.NOTE || b.kind == TicketView.EVENT) && detail.canAddNote) startNote();
        else ticket.bounce();
    }

    private void call() {
        ticket.bounce();
        try {
            getContext().startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + detail.phone.replaceAll("[^0-9+]", ""))));
        } catch (Exception e) {
            toast("No phone app");
        }
    }

    private void closeTicket(boolean snap) {
        host.keyboard(note, false);
        ChatListView.Item it = null;
        if (open != null) {
            Long id = ids.get(open.code);
            for (ChatListView.Item i : list.items()) if (id != null && i.id == id) it = i;
        }
        if (it != null) {
            list.focusItem(it, true);
            float top = list.getTop() + list.rowTop(it);
            openTop = Math.max(sb, top);
            openBottom = Math.min(getHeight() - Style.px(80), top + Style.px(ChatListView.ROW));
        }
        ticketGen++;
        s = ticketFrom == S.SEARCH ? S.SEARCH : S.LIST;
        open = null;
        restoreKeys();
        openP.to(0, snap);
        // * the list picks up whatever changed on the ticket
        if (!snap) postDelayed(() -> {
            if (s == S.LIST) loadTickets(ChatListView.FROM_NONE, true);
        }, 350);
    }

    private void applyOpen(float p) {
        float q = Math.max(0, Math.min(1.04f, p));
        float H = getHeight(), W = getWidth();
        float top = openTop * (1 - q), bottom = (H - openBottom) * (1 - q), side = Style.px(10) * (1 - q);
        float radius = Style.px(30) * (1 - q) + Style.px(6) * q;
        ticketLayer.setClip(side, top, W - side, H - bottom, radius);
        ticketLayer.setAlpha(Math.min(1, p * 3));
        ticket.setTranslationY((1 - q) * Style.px(40));
        float sc = 1 - .06f * q;
        listLayer.setScaleX(sc);
        listLayer.setScaleY(sc);
        listLayer.setAlpha(1 - .65f * Math.min(1, q));
        if (p <= .001f && s != S.TICKET && s != S.NOTE && !(s == S.SHEET && sheetFrom != S.LIST)) ticketLayer.setVisibility(View.GONE);
    }

    // ── a note ──

    private void startNote() {
        s = S.NOTE;
        ticket.showRing(false);
        host.keyboard(note, true);
        bump(composer);
        restoreKeys();
    }

    private void endNote() {
        host.keyboard(note, false);
        s = S.TICKET;
        ticket.showRing(true);
        restoreKeys();
    }

    private void sendNote() {
        String text = note.getText().toString().trim();
        if (text.isEmpty()) {
            endNote();
            return;
        }
        note.setText("");
        endNote();
        ticket.addPending(text);
        final Care.Ticket t = open;
        Care.addNote(t.code, text, (d, err) -> {
            if (open != t) return;
            if (err != null || d == null) {
                ticket.dropPending();
                toast(err != null ? err : "The note didn't go through");
                return;
            }
            showDetail(d, false);
            ticket.focusLast();
            toast("Note added");
        });
    }

    // ── the sheet: options, statuses ──

    private void openOptions() {
        if (detail == null) {
            ticket.bounce();
            return;
        }
        ArrayList<MenuView.Entry> e = new ArrayList<>();
        if (detail.canChangeStatus) e.add(new MenuView.Entry("status", "Change status", "⇄", "#a593ff", "#7a5ae6"));
        if (detail.canAddNote) e.add(new MenuView.Entry("note", "Add a note", "✎", "#ffb46a", "#e07a2a"));
        if (!TextUtils.isEmpty(detail.phone)) e.add(new MenuView.Entry("call", "Call customer", "✆", "#5fd1a0", "#2a9a6a"));
        e.add(new MenuView.Entry("refresh", "Refresh", "↻", "#c0c0d0", "#80809a"));
        openSheet("options", "Ticket", e);
    }

    private void openStatuses() {
        ArrayList<MenuView.Entry> e = new ArrayList<>();
        String[][] opts = detail.statusOptions.isEmpty() ? Care.STATUSES : detail.statusOptions.toArray(new String[0][]);
        for (String[] o : opts) {
            if (o.length < 2 || TextUtils.isEmpty(o[0])) continue;
            int col = toneColor(Care.tone(o[1]));
            e.add(new MenuView.Entry("st:" + o[0], shortStatus(o[1]), "●", hex(lighten(col)), hex(darken(col))));
            if (e.size() == 9) break;
        }
        openSheet("status", "Change status", e);
    }

    private void openSheet(String kind, String title, ArrayList<MenuView.Entry> entries) {
        sheetKind = kind;
        sheetFrom = s;
        menu.title = title;
        menu.setEntries(entries);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        lp.height = px(28) + menu.preferredHeight();
        sheet.setLayoutParams(lp);
        s = S.SHEET;
        ticket.showRing(false);
        sheet.setVisibility(View.VISIBLE);
        dim.setVisibility(View.VISIBLE);
        sheet.setPivotX(getWidth() * .8f);
        sheet.setPivotY(lp.height);
        restoreKeys();
        sheetP.to(1);
    }

    private void closeSheet(boolean snap) {
        if (s == S.SHEET) s = sheetFrom;
        sheetP.to(0, snap);
        if (s == S.TICKET) ticket.showRing(true);
        restoreKeys();
    }

    private void choose(MenuView.Entry e, boolean viaKey) {
        if (e == null) return;
        Runnable go = () -> {
            closeSheet(false);
            if (e.id.equals("status")) postDelayed(this::openStatuses, 180);
            else if (e.id.equals("note")) startNote();
            else if (e.id.equals("call")) call();
            else if (e.id.equals("refresh")) {
                ticket.bounce();
                loadTicket(true);
            } else if (e.id.startsWith("st:")) changeStatus(e.id.substring(3), e.label);
        };
        // * a number key: the pill glides to it first (prototype: 140ms)
        if (viaKey) postDelayed(go, 140);
        else go.run();
    }

    private void changeStatus(String value, String label) {
        if (detail == null || value.equals(detail.statusValue)) {
            ticket.bounce();
            return;
        }
        final Care.Ticket t = open;
        ticket.pulseStatus(true);
        Care.changeStatus(t.code, value, "", (d, err) -> {
            if (open != t) return;
            ticket.pulseStatus(false);
            if (err != null || d == null) {
                toast(err != null ? err : "The status didn't change");
                return;
            }
            showDetail(d, true);
            toast("Now: " + label);
        });
    }

    // ── small things ──

    private void toast(String text) {
        toastText.setText(text);
        removeCallbacks(hideToast);
        toastP.to(0, true);
        toastP.to(1);
        postDelayed(hideToast, 2400);
    }

    private static void bump(View v) {
        if (Spring.reduced) return;
        Spring sp = new Spring(.94f, 500, 16, (val, vel) -> {
            v.setScaleX(val);
            v.setScaleY(val);
        });
        sp.to(1);
    }

    static int lighten(int c) {
        return Color.rgb(Math.min(255, (int) (Color.red(c) * 1.08f + 14)), Math.min(255, (int) (Color.green(c) * 1.08f + 14)), Math.min(255, (int) (Color.blue(c) * 1.08f + 14)));
    }

    static int darken(int c) {
        return Color.rgb((int) (Color.red(c) * .62f), (int) (Color.green(c) * .62f), (int) (Color.blue(c) * .62f));
    }

    private static String hex(int c) {
        return String.format("#%06x", c & 0xffffff);
    }

    /** The sign-in card's rows, with the list's pill gliding between them (and taking each one's height). */
    private final class FormRows extends FrameLayout {
        static final int HEIGHT = 14 + 78 + 6 + 78 + 6 + 70 + 6 + 72 + 14;
        private final float[] tops = {14, 14 + 84, 14 + 168, 14 + 244};
        private final float[] heights = {78, 78, 70, 72};
        private final Style.Pill pill = new Style.Pill(false);
        private final Spring pillY, pillH;
        private float stretch;
        private final TextPaint small = Style.text(Style.semibold, 14, Style.INK3);
        private final String[] names = new String[2];

        FormRows(Context c) {
            super(c);
            setWillNotDraw(false);
            pillY = new Spring(Style.px(tops[0]), 420, 30, (v, vel) -> {
                stretch = Math.min(.14f, Math.abs(vel) / (9000f * Style.u));
                invalidate();
            });
            pillH = new Spring(Style.px(heights[0]), 420, 30, (v, vel) -> invalidate());
        }

        void addField(int i, String name, EditText e) {
            names[i] = name;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(34));
            lp.topMargin = px(tops[i] + 34);
            lp.leftMargin = px(22);
            lp.rightMargin = px(22);
            addView(e, lp);
        }

        void addRemember(Check c) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(heights[2]));
            lp.topMargin = px(tops[2]);
            addView(c, lp);
        }

        void addButton(SignButton b) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(heights[3] - 12));
            lp.topMargin = px(tops[3] + 6);
            lp.leftMargin = px(14);
            lp.rightMargin = px(14);
            addView(b, lp);
        }

        void focus(int i, boolean snap) {
            pillY.to(Style.px(tops[i]), snap);
            pillH.to(Style.px(heights[i]), snap);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth() - Style.px(14) * 2;
            c.save();
            c.translate(Style.px(14), pillY.v);
            c.scale(1 - stretch * .25f, 1 + stretch, w / 2f, pillH.v / 2f);
            pill.draw(c, w, pillH.v, Style.px(26));
            c.restore();
            Paint.FontMetrics fm = small.getFontMetrics();
            for (int i = 0; i < 2; i++) {
                if (names[i] == null) continue;
                c.drawText(names[i], Style.px(36), Style.px(tops[i] + 14) - fm.ascent, small);
            }
            // * thin lines under the two fields
            small.setColor(Style.fg(30));
            for (int i = 0; i < 2; i++) {
                float y = Style.px(tops[i] + heights[i] - 10);
                c.drawRect(Style.px(36), y, getWidth() - Style.px(36), y + Style.px(1.5f), small);
            }
            small.setColor(Style.INK3);
        }
    }

    /** "Remember me" with a box whose tick draws itself in (the prototype's task check). */
    private final class Check extends View {
        boolean on;
        private float t;
        private final Spring sp;
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = Style.text(Style.semibold, 21, Style.INK);
        private final Path path = new Path(), part = new Path();
        private final PathMeasure pm = new PathMeasure();

        Check(Context c) {
            super(c);
            sp = new Spring(0, 420, 30, (v, vel) -> { t = v; invalidate(); });
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(Style.px(3.2f));
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            tick.setColor(Color.WHITE);
        }

        void set(boolean value, boolean snap) {
            on = value;
            sp.to(on ? 1 : 0, snap);
            if (!snap && !Spring.reduced) bump(this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Style.px(40), x = Style.px(32), y = (getHeight() - s) / 2f, k = Math.max(0, Math.min(1, t));
            box.setStyle(Paint.Style.FILL);
            box.setColor(TicketView.blend(Color.argb(255, 60, 54, 84), Style.ACCENT, k));
            box.setAlpha((int) (255 * (.35f + .65f * k)));
            c.drawRoundRect(x, y, x + s, y + s, Style.px(13), Style.px(13), box);
            if (k < 1) {
                box.setStyle(Paint.Style.STROKE);
                box.setStrokeWidth(Style.px(2.5f));
                box.setColor(Color.argb((int) (115 * (1 - k)), 255, 255, 255));
                c.drawRoundRect(x + Style.px(1.25f), y + Style.px(1.25f), x + s - Style.px(1.25f), y + s - Style.px(1.25f), Style.px(12), Style.px(12), box);
            }
            if (k > .01f) {
                float u = s / 24f;
                path.reset();
                path.moveTo(x + 5 * u, y + 12.5f * u);
                path.lineTo(x + 9.5f * u, y + 17 * u);
                path.lineTo(x + 19 * u, y + 7.5f * u);
                pm.setPath(path, false);
                part.reset();
                pm.getSegment(0, pm.getLength() * k, part, true);
                c.drawPath(part, tick);
            }
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText("Remember me", x + s + Style.px(16), getHeight() / 2f - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    /** The sign-in button: an accent capsule that folds into a round, breathing spinner while it works. */
    private final class SignButton extends View {
        private float m, phase;
        private final Spring morph;
        private ValueAnimator spin;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = Style.text(Style.bold, 22, Color.WHITE);
        private final RectF r = new RectF();

        SignButton(Context c) {
            super(c);
            morph = new Spring(0, 380, 26, (v, vel) -> { m = v; invalidate(); });
            dot.setColor(Color.WHITE);
            glow.setColor(Color.argb(89, 139, 116, 242));
        }

        void busy(boolean on, boolean snap) {
            morph.to(on ? 1 : 0, snap);
            if (on && !Spring.reduced) {
                if (spin == null) {
                    spin = ValueAnimator.ofFloat(0, 1);
                    spin.setDuration(1000);
                    spin.setRepeatCount(ValueAnimator.INFINITE);
                    spin.setInterpolator(new LinearInterpolator());
                    spin.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
                }
                spin.start();
            } else if (spin != null) spin.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), W = getWidth(), k = Math.max(0, Math.min(1.1f, m));
            float w = W - (W - h) * Math.min(1, k);
            float breathe = k > .9f ? 1 + .04f * (float) Math.sin(phase * Math.PI * 2) : 1;
            c.save();
            c.scale(breathe, breathe, W / 2f, h / 2f);
            r.set((W - w) / 2f, 0, (W + w) / 2f, h);
            c.drawRoundRect(r.left, r.top + Style.px(6), r.right, r.bottom + Style.px(6), h / 2f, h / 2f, glow);
            fill.setShader(new LinearGradient(r.left, 0, r.right, h, Style.ACCENT_HI, Style.ACCENT, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
            float la = Math.max(0, 1 - k * 2.2f);
            if (la > 0) {
                text.setAlpha((int) (255 * la));
                Paint.FontMetrics fm = text.getFontMetrics();
                String s = "Sign in";
                c.drawText(s, (W - text.measureText(s)) / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text);
            }
            // * three dots that hop in turn (the prototype's typing dots)
            float da = Math.max(0, Math.min(1, (k - .55f) * 2.5f));
            if (da > 0) {
                float gap = Style.px(11), rr = Style.px(4.5f);
                for (int i = 0; i < 3; i++) {
                    float t = (phase - i * .15f + 1) % 1, lift = t < .3f ? t / .3f : t < .6f ? 1 - (t - .3f) / .3f : 0;
                    dot.setAlpha((int) (255 * da * (.5f + .5f * lift)));
                    c.drawCircle(W / 2f + (i - 1) * gap, h / 2f - Style.px(5) * lift, rr, dot);
                }
            }
            c.restore();
        }
    }

    /** Three dots that hop in turn, beside the list's header while tickets load. */
    private static final class Dots extends View {
        private float phase;
        private ValueAnimator a;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dots(Context c) {
            super(c);
            dot.setColor(Style.INK2);
        }

        void run(boolean on) {
            if (on && !Spring.reduced) {
                if (a == null) {
                    a = ValueAnimator.ofFloat(0, 1);
                    a.setDuration(1000);
                    a.setRepeatCount(ValueAnimator.INFINITE);
                    a.setInterpolator(new LinearInterpolator());
                    a.addUpdateListener(an -> { phase = (float) an.getAnimatedValue(); invalidate(); });
                }
                if (!a.isRunning()) a.start();
            } else if (a != null) a.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float gap = Style.px(14), rr = Style.px(4.5f), cx = getWidth() / 2f, cy = getHeight() / 2f + Style.px(2);
            for (int i = 0; i < 3; i++) {
                float t = (phase - i * .15f + 1) % 1, lift = t < .3f ? t / .3f : t < .6f ? 1 - (t - .3f) / .3f : 0;
                dot.setAlpha((int) (255 * (.5f + .5f * lift)));
                c.drawCircle(cx + (i - 1) * gap, cy - Style.px(5) * lift, rr, dot);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (a != null) a.cancel();
        }
    }

    /** A glass header: a coloured tile with a letter, a name, a line under it (a page's head). */
    private static final class Head extends GlassFrame {
        private String glyph = "", name = "", sub = "";
        private int from, to;
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint glyphPaint = Style.text(Style.bold, 24, Color.WHITE);
        private final TextPaint namePaint = Style.text(Style.bold, 23, Style.INK);
        private final TextPaint subPaint = Style.text(Style.regular, 16, Style.INK3);
        private final View content;

        Head(Context c) {
            super(c, 36);
            content = new View(c) {
                @Override
                protected void onDraw(Canvas cv) {
                    drawContent(cv);
                }
            };
            addView(content, new FrameLayout.LayoutParams(-1, -1));
        }

        void set(String g, int from, int to, String name, String sub) {
            glyph = g != null ? g : "";
            this.name = name != null ? name : "";
            this.sub = sub != null ? sub : "";
            setTileColors(from, to);
        }

        void setTileColors(int from, int to) {
            this.from = from;
            this.to = to;
            float s = Style.px(56);
            tile.setShader(new LinearGradient(0, 0, s, s, from, to, Shader.TileMode.CLAMP));
            content.invalidate();
        }

        private void drawContent(Canvas c) {
            float h = content.getHeight(), s = Style.px(56), x = Style.px(8), y = (h - s) / 2f;
            c.save();
            c.translate(x, y);
            c.drawRoundRect(0, 0, s, s, Style.px(18), Style.px(18), tile);
            Paint.FontMetrics gm = glyphPaint.getFontMetrics();
            c.drawText(glyph, (s - glyphPaint.measureText(glyph)) / 2f, s / 2f - (gm.ascent + gm.descent) / 2f, glyphPaint);
            c.restore();
            float tx = x + s + Style.px(14), maxW = content.getWidth() - tx - Style.px(18);
            String n = TextUtils.ellipsize(name, namePaint, maxW, TextUtils.TruncateAt.END).toString();
            String st = TextUtils.ellipsize(sub, subPaint, maxW, TextUtils.TruncateAt.END).toString();
            Paint.FontMetrics nm = namePaint.getFontMetrics(), sm = subPaint.getFontMetrics();
            float nh = nm.descent - nm.ascent, sh = sm.descent - sm.ascent, top = (h - nh - Style.px(2) - sh) / 2f;
            c.drawText(n, tx, top - nm.ascent, namePaint);
            c.drawText(st, tx, top + nh + Style.px(2) - sm.ascent, subPaint);
        }
    }
}
