package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.kyogram.ui.ChatListView;
import app.kyogram.ui.GlassFrame;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;
import app.kyogram.ui.Trello;

/**
 * Kyogram: the Tasks tab, the shift's to-do list from Trello, as on desktop Kyogram (shiftTasks.tsx):
 * the cards of the Trello column set for the shift that's on now. Tick a card to mark it complete on
 * Trello, tap it for its description, add a task at the bottom. It follows the clock and reloads every
 * minute. Kyogram's glass, springs and gliding pill (app.kyogram.ui, shared with Kyogram Keypad).
 */
public class KyogramTasksActivity extends BaseFragment {

    private final boolean hasMainTabs;
    private FrameLayout root;
    private TextView title, sub, empty;
    private ChatListView list;
    private EditText add;
    private ScrollView setup;
    private final ArrayList<Trello.Card> cards = new ArrayList<>();
    private boolean resumed;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load();
            if (resumed) AndroidUtilities.runOnUIThread(this, 60_000);
        }
    };

    public KyogramTasksActivity(Bundle args) {
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

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
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
        root.addView(new View(c) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (Style.wall != null) canvas.drawBitmap(Style.wall, 0, 0, null);
            }
        }, new FrameLayout.LayoutParams(-1, -1));

        // * the header: Tasks, the shift on now, and a gear for the Trello settings
        GlassFrame header = new GlassFrame(c, 36);
        LinearLayout hl = new LinearLayout(c);
        hl.setOrientation(LinearLayout.VERTICAL);
        hl.setGravity(Gravity.CENTER_VERTICAL);
        hl.setPadding(px(22), 0, px(70), 0);
        title = label(c, 23, Style.INK, Style.bold);
        title.setText("Tasks");
        sub = label(c, 16, Style.INK3, Style.regular);
        hl.addView(title);
        hl.addView(sub);
        header.addView(hl, new FrameLayout.LayoutParams(-1, -1));
        TextView gear = label(c, 24, Style.INK2, Style.regular);
        gear.setText("⚙");
        gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(v -> showSetup(true));
        header.addView(gear, new FrameLayout.LayoutParams(px(64), -1, Gravity.RIGHT));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, px(72));
        hlp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(6), px(14), 0);
        root.addView(header, hlp);

        // * the cards
        list = new ChatListView(c, false);
        list.inset = 22;
        list.setOnItemClick((item, x) -> {
            if (x < Style.px(14 + 40 + 16)) toggle(item);
            else details(item);
        });
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -1);
        llp.topMargin = AndroidUtilities.statusBarHeight + px(88);
        llp.bottomMargin = bottomInset() + px(84);
        root.addView(list, llp);
        empty = label(c, 19, Style.INK3, Style.regular);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(40), 0, px(40), 0);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        root.addView(empty, elp);

        // * add a task
        GlassFrame composer = new GlassFrame(c, 31);
        add = new EditText(c);
        add.setBackground(null);
        add.setTextColor(Style.INK);
        add.setHintTextColor(Style.fg(140));
        add.setHint("Add a task…");
        add.setTypeface(Style.medium);
        add.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(20));
        add.setSingleLine(true);
        add.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add.setPadding(0, 0, 0, 0);
        add.setOnEditorActionListener((v, actionId, event) -> {
            addTask();
            return true;
        });
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(-1, -1);
        alp.leftMargin = px(22);
        alp.rightMargin = px(80);
        composer.addView(add, alp);
        TextView send = label(c, 18, Color.WHITE, Style.bold);
        send.setText("Add");
        send.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Style.ACCENT_HI, Style.ACCENT});
        sb.setCornerRadius(px(23));
        send.setBackground(sb);
        send.setOnClickListener(v -> addTask());
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(px(68), px(46), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        slp.rightMargin = px(8);
        composer.addView(send, slp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, px(62), Gravity.BOTTOM);
        clp.setMargins(px(12), 0, px(12), bottomInset() + px(10));
        root.addView(composer, clp);

        if (!Trello.connected(c)) showSetup(false);
        else load();
    }

    private int px(float v) {
        return Math.round(Style.px(v));
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

    // ── the list ──

    private void load() {
        Context c = getContext();
        if (c == null || list == null || !Trello.connected(c)) return;
        Trello.Shift shift = Trello.currentShift(c);
        sub.setText(shift != null ? shift.name + " · " + shift.start + "–" + shift.end : "Between shifts");
        String listId = Trello.tasksListId(c);
        if (TextUtils.isEmpty(listId)) {
            setEmpty("No Trello column is set for now. Tap ⚙ to pick one for each shift.");
            list.setItems(new ArrayList<>(), ChatListView.FROM_NONE, false, false);
            return;
        }
        Trello.cards(c, listId, (result, error) -> {
            if (list == null) return;
            if (error != null) {
                setEmpty("Couldn't load the tasks: " + error);
                return;
            }
            cards.clear();
            cards.addAll(result);
            show(false);
        });
    }

    private void setEmpty(String text) {
        empty.setText(text);
        empty.setSingleLine(false);
        empty.animate().alpha(TextUtils.isEmpty(text) ? 0 : 1).setDuration(200).start();
    }

    private void show(boolean snap) {
        ArrayList<ChatListView.Item> items = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            Trello.Card card = cards.get(i);
            ChatListView.Item it = new ChatListView.Item();
            it.id = card.id.hashCode();
            it.name = card.name;
            it.checkable = true;
            it.done = card.done;
            it.preview = describe(card);
            items.add(it);
        }
        list.setItems(items, ChatListView.FROM_NONE, true, snap);
        setEmpty(cards.isEmpty() ? "Nothing to do on this shift." : "");
    }

    private String describe(Trello.Card card) {
        ArrayList<String> parts = new ArrayList<>();
        if (card.due != null) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = in.parse(card.due);
                parts.add("Due " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d));
            } catch (Exception ignore) {
            }
        }
        for (String l : card.labels) if (!TextUtils.isEmpty(l)) parts.add(l);
        if (card.checkItems > 0) parts.add("☑ " + card.checkItemsChecked + "/" + card.checkItems);
        if (card.attachments > 0) parts.add("📎 " + card.attachments);
        return TextUtils.join(" · ", parts);
    }

    private Trello.Card cardFor(ChatListView.Item item) {
        for (Trello.Card c : cards) if (c.id.hashCode() == item.id) return c;
        return null;
    }

    private void toggle(ChatListView.Item item) {
        Trello.Card card = cardFor(item);
        if (card == null) return;
        // * ticks at once (the tick draws itself in), and goes back if Trello says no
        card.done = !card.done;
        show(false);
        Trello.setDone(getContext(), card.id, card.done, (result, error) -> {
            if (error != null) {
                card.done = !card.done;
                show(false);
                Toast.makeText(getContext(), "Trello: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void details(ChatListView.Item item) {
        Trello.Card card = cardFor(item);
        if (card == null || getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(card.name);
        b.setMessage("Loading…");
        b.setPositiveButton("Open in Trello", (d, w) -> {
            try {
                getParentActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(card.shortUrl)));
            } catch (Exception ignore) {
            }
        });
        b.setNegativeButton("Close", null);
        // * the card's photos, under its text (tap one for the full size in the browser)
        LinearLayout photos = new LinearLayout(getParentActivity());
        photos.setOrientation(LinearLayout.VERTICAL);
        photos.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
        b.setView(photos);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        if (card.attachments > 0) Trello.photos(getContext(), card.id, (urls, err) -> {
            if (urls == null) return;
            for (String url : urls) {
                android.widget.ImageView img = new android.widget.ImageView(getParentActivity());
                img.setAdjustViewBounds(true);
                img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
                ip.topMargin = AndroidUtilities.dp(8);
                photos.addView(img, ip);
                Trello.image(getContext(), url, (bmp, e2) -> {
                    if (bmp != null) {
                        img.setImageBitmap(bmp);
                        img.setAlpha(0f);
                        img.animate().alpha(1f).setDuration(200).start();
                    }
                });
            }
        });
        Trello.details(getContext(), card.id, (desc, error) -> {
            String text = error != null ? "Couldn't load the card: " + error : TextUtils.isEmpty(desc) ? "No description." : desc;
            String meta = describe(card);
            dialog.setMessage(TextUtils.isEmpty(meta) ? text : meta + "\n\n" + text);
        });
    }

    private void addTask() {
        String name = add.getText().toString().trim();
        Context c = getContext();
        String listId = c != null ? Trello.tasksListId(c) : null;
        if (name.isEmpty() || TextUtils.isEmpty(listId)) return;
        add.setText("");
        Trello.addTask(c, listId, name, (card, error) -> {
            if (error != null) {
                Toast.makeText(getContext(), "Trello: " + error, Toast.LENGTH_SHORT).show();
                add.setText(name);
                return;
            }
            cards.add(card);
            show(false);
            if (list != null && !list.items().isEmpty()) list.focusItem(list.items().get(list.items().size() - 1), false);
        });
    }

    // ── connecting Trello (once on the phone, as in Settings → Customize Kyogram on desktop) ──

    private EditText field(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Style.INK);
        e.setHintTextColor(Style.INK3);
        e.setTypeface(Style.regular);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(19));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
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

    private TextView note(Context c, String text) {
        TextView t = label(c, 16, Style.INK2, Style.regular);
        t.setSingleLine(false);
        t.setText(text);
        return t;
    }

    private void showSetup(boolean editing) {
        Context c = getContext();
        if (c == null) return;
        if (setup != null) root.removeView(setup);
        setup = new ScrollView(c);
        setup.setFillViewport(true);
        GlassFrame card = new GlassFrame(c, 32);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(22), px(22), px(22), px(22));
        card.addView(col, new FrameLayout.LayoutParams(-1, -2));

        TextView h = label(c, 25, Style.INK, Style.display);
        h.setText("Connect Trello");
        col.addView(h);
        col.addView(note(c, "Your shift's to-do list comes from a Trello column. Kyogram keeps your key and token on this phone only."), lp(10));

        EditText key = field(c, "API key (trello.com/power-ups/admin)", Trello.key(c));
        col.addView(key, lp(16));
        TextView getToken = button(c, "Get a token", false);
        getToken.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) {
                Toast.makeText(c, "Enter your API key first", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Trello.tokenUrl(k))));
            } catch (Exception ignore) {
            }
        });
        col.addView(getToken, lp(10));
        EditText token = field(c, "Token (paste it here)", Trello.token(c));
        col.addView(token, lp(10));
        EditText board = field(c, "Board link (trello.com/b/…)", Trello.boardId(c));
        col.addView(board, lp(10));

        LinearLayout shiftsBox = new LinearLayout(c);
        shiftsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(shiftsBox, lp(6));

        TextView connect = button(c, editing && Trello.connected(c) ? "Load columns" : "Connect", true);
        col.addView(connect, lp(16));
        if (editing && Trello.connected(c)) {
            TextView disconnect = button(c, "Disconnect Trello", false);
            disconnect.setOnClickListener(v -> {
                Trello.disconnect(c);
                cards.clear();
                show(true);
                showSetup(false);
            });
            col.addView(disconnect, lp(10));
            TextView close = button(c, "Close", false);
            close.setOnClickListener(v -> hideSetup());
            col.addView(close, lp(10));
        }

        connect.setOnClickListener(v -> {
            String k = key.getText().toString().trim(), t = token.getText().toString().trim(), link = board.getText().toString().trim();
            if (k.isEmpty() || t.isEmpty() || link.isEmpty()) {
                Toast.makeText(c, "Fill in the key, the token and the board", Toast.LENGTH_SHORT).show();
                return;
            }
            connect.setText("Connecting…");
            Trello.board(k, t, link, (b, error) -> {
                if (error != null) {
                    connect.setText("Connect");
                    Toast.makeText(c, "Trello: " + error, Toast.LENGTH_LONG).show();
                    return;
                }
                Trello.lists(k, t, b[0], (lists, err) -> {
                    if (err != null) {
                        connect.setText("Connect");
                        Toast.makeText(c, "Trello: " + err, Toast.LENGTH_LONG).show();
                        return;
                    }
                    shiftEditor(c, shiftsBox, lists, k, t, b[0], b[1]);
                    connect.setVisibility(View.GONE);
                });
            });
        });

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2);
        clp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(14), px(14), bottomInset() + px(14));
        FrameLayout wrap = new FrameLayout(c);
        wrap.addView(card, clp);
        setup.addView(wrap, new FrameLayout.LayoutParams(-1, -2));
        root.addView(setup, new FrameLayout.LayoutParams(-1, -1));
        // * the card springs up from below
        setup.setAlpha(0);
        setup.setTranslationY(Style.px(70));
        new Spring(0, 330, 24, (p, vel) -> {
            if (setup == null) return;
            setup.setAlpha(Math.min(1, Math.max(0, p) * 2));
            setup.setTranslationY((1 - p) * Style.px(70));
        }).to(1);
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = px(top);
        return p;
    }

    /** Three shifts (name, start, end, the column of its to-do list) and the column between shifts. */
    private void shiftEditor(Context c, LinearLayout box, ArrayList<Trello.TrelloList> lists, String key, String token, String boardId, String boardName) {
        box.removeAllViews();
        box.addView(note(c, "Board: " + boardName + ". Your shifts, and which column is each shift's to-do list:"), lp(14));
        ArrayList<String> names = new ArrayList<>();
        names.add("— none —");
        for (Trello.TrelloList l : lists) names.add(l.name);
        ArrayList<Trello.Shift> saved = Trello.shifts(c);
        String[][] defaults = {{"Morning", "08:00", "14:00"}, {"Afternoon", "14:00", "22:00"}, {"", "", ""}};
        ArrayList<EditText[]> rows = new ArrayList<>();
        ArrayList<Spinner> pickers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Trello.Shift s = i < saved.size() ? saved.get(i) : null;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            EditText name = field(c, "Shift", s != null ? s.name : defaults[i][0]);
            EditText start = field(c, "08:00", s != null ? s.start : defaults[i][1]);
            EditText end = field(c, "14:00", s != null ? s.end : defaults[i][2]);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1.4f));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = px(8);
            row.addView(start, tp);
            LinearLayout.LayoutParams tp2 = new LinearLayout.LayoutParams(0, -2, 1);
            tp2.leftMargin = px(8);
            row.addView(end, tp2);
            box.addView(row, lp(12));
            Spinner pick = picker(c, names, lists, s != null ? s.listId : "");
            box.addView(pick, lp(6));
            rows.add(new EditText[]{name, start, end});
            pickers.add(pick);
        }
        box.addView(note(c, "Between shifts:"), lp(14));
        Spinner fallback = picker(c, names, lists, Trello.fallbackListId(c));
        box.addView(fallback, lp(6));
        TextView save = button(c, "Save", true);
        save.setOnClickListener(v -> {
            ArrayList<Trello.Shift> shifts = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                EditText[] r = rows.get(i);
                String n = r[0].getText().toString().trim(), a = r[1].getText().toString().trim(), e = r[2].getText().toString().trim();
                if (n.isEmpty() && a.isEmpty()) continue;
                if (!Trello.validTime(a) || !Trello.validTime(e)) {
                    Toast.makeText(c, "Times look like 08:00", Toast.LENGTH_SHORT).show();
                    return;
                }
                Trello.Shift s = new Trello.Shift();
                s.name = n.isEmpty() ? "Shift " + (i + 1) : n;
                s.start = a;
                s.end = e;
                int sel = pickers.get(i).getSelectedItemPosition();
                if (sel > 0) {
                    s.listId = lists.get(sel - 1).id;
                    s.listName = lists.get(sel - 1).name;
                }
                shifts.add(s);
            }
            int fb = fallback.getSelectedItemPosition();
            Trello.save(c, key, token, boardId, boardName, shifts, fb > 0 ? lists.get(fb - 1).id : "");
            hideSetup();
            load();
        });
        box.addView(save, lp(16));
    }

    private Spinner picker(Context c, ArrayList<String> names, ArrayList<Trello.TrelloList> lists, String selectedId) {
        Spinner s = new Spinner(c);
        ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, names) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Style.INK);
                    ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(19));
                }
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        for (int i = 0; i < lists.size(); i++) if (lists.get(i).id.equals(selectedId)) s.setSelection(i + 1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.fg(20));
        bg.setCornerRadius(px(18));
        s.setBackground(bg);
        s.setPadding(px(10), px(6), px(10), px(6));
        return s;
    }

    private void hideSetup() {
        if (setup == null) return;
        ScrollView s = setup;
        setup = null;
        s.animate().alpha(0).translationY(Style.px(40)).setDuration(200).withEndAction(() -> root.removeView(s)).start();
        AndroidUtilities.hideKeyboard(root);
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.runOnUIThread(refresh, 300);
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }
}
