package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

import app.kyogram.ui.ChatListView;
import app.kyogram.ui.GlassFrame;
import app.kyogram.ui.Hidden;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;

/** Kyogram: the Hidden chats screen (hidden.ts), behind a PIN if you set one. Hold the Chats tab to get here. */
public class KyogramHiddenActivity extends BaseFragment {

    private FrameLayout root;
    private ChatListView list;
    private boolean unlocked;

    @Override
    public ActionBar createActionBar(Context context) {
        return null;
    }

    private int px(float v) {
        return Math.round(Style.px(v));
    }

    @Override
    public View createView(Context context) {
        root = new FrameLayout(context) {
            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                if (w > 0 && h > 0 && (w != ow || h != oh)) post(() -> build());
            }
        };
        root.setBackgroundColor(Color.parseColor("#15121c"));
        fragmentView = root;
        unlocked = !Hidden.hasPin();
        return root;
    }

    private TextView label(Context c, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(size));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
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

    private EditText pinField(Context c) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setTextColor(Style.INK);
        e.setHintTextColor(Style.INK3);
        e.setHint("PIN");
        e.setGravity(Gravity.CENTER);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(26));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.fg(20));
        bg.setCornerRadius(px(18));
        e.setBackground(bg);
        e.setPadding(px(18), px(14), px(18), px(14));
        return e;
    }

    private void build() {
        Context c = getContext();
        if (c == null || root.getWidth() == 0) return;
        Style.init(c, root.getWidth(), root.getHeight(), AndroidUtilities.statusBarHeight, AndroidUtilities.density * 360f / 540f);
        root.removeAllViews();
        root.addView(new View(c) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (Style.wall != null) canvas.drawBitmap(Style.wall, 0, 0, null);
            }
        }, new FrameLayout.LayoutParams(-1, -1));

        GlassFrame header = new GlassFrame(c, 36);
        TextView title = label(c, 23, Style.INK, Style.bold);
        title.setText("‹  Hidden chats");
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(px(20), 0, px(20), 0);
        title.setOnClickListener(v -> finishFragment());
        header.addView(title, new FrameLayout.LayoutParams(-1, -1));
        TextView pinBtn = label(c, 17, Style.INK2, Style.semibold);
        pinBtn.setText(Hidden.hasPin() ? "Change PIN" : "Set a PIN");
        pinBtn.setGravity(Gravity.CENTER);
        pinBtn.setPadding(px(16), 0, px(16), 0);
        pinBtn.setOnClickListener(v -> setPin());
        header.addView(pinBtn, new FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, px(72));
        hlp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(6), px(14), 0);
        root.addView(header, hlp);

        if (!unlocked) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            EditText pin = pinField(c);
            col.addView(pin, new LinearLayout.LayoutParams(px(240), -2));
            TextView open = button(c, "Unlock", true);
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(px(240), -2);
            op.topMargin = px(14);
            col.addView(open, op);
            open.setOnClickListener(v -> {
                if (Hidden.checkPin(pin.getText().toString())) {
                    unlocked = true;
                    AndroidUtilities.hideKeyboard(pin);
                    build();
                } else {
                    // * a wrong PIN shakes the field
                    pin.setText("");
                    new Spring(1, 900, 12, (p, vel) -> pin.setTranslationX((float) Math.sin(p * 30) * Style.px(12) * p)).to(0);
                }
            });
            root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            return;
        }

        list = new ChatListView(c, true);
        list.setOnItemClick((item, x) -> {
            Bundle args = new Bundle();
            if (DialogObject.isUserDialog(item.id)) args.putLong("user_id", item.id);
            else args.putLong("chat_id", -item.id);
            presentFragment(new ChatActivity(args));
        });
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -1);
        llp.topMargin = AndroidUtilities.statusBarHeight + px(88);
        root.addView(list, llp);
        reload();
    }

    private void reload() {
        if (list == null) return;
        MessagesController mc = getMessagesController();
        ArrayList<ChatListView.Item> items = new ArrayList<>();
        for (Long id : Hidden.all()) {
            ChatListView.Item it = new ChatListView.Item();
            it.id = id;
            TLObject peer = DialogObject.isUserDialog(id) ? mc.getUser(id) : mc.getChat(-id);
            if (peer == null) continue;
            it.peer = peer;
            it.name = peer instanceof TLRPC.User ? UserObject.getUserName((TLRPC.User) peer) : ((TLRPC.Chat) peer).title;
            TLRPC.Dialog d = mc.dialogs_dict.get(id);
            it.unread = d != null ? d.unread_count : 0;
            it.preview = "Hidden · open it, then ⋮ → Unhide chat";
            items.add(it);
        }
        list.setItems(items, ChatListView.FROM_NONE, true, false);
    }

    private void setPin() {
        Context c = getContext();
        if (c == null) return;
        EditText pin = pinField(c);
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(c);
        b.setTitle(Hidden.hasPin() ? "New PIN (empty: no PIN)" : "Set a PIN");
        b.setView(pin);
        b.setPositiveButton("Save", (d, w) -> {
            Hidden.setPin(pin.getText().toString().trim());
            Toast.makeText(c, Hidden.hasPin() ? "PIN set" : "PIN removed", Toast.LENGTH_SHORT).show();
            build();
        });
        b.setNegativeButton("Cancel", null);
        showDialog(b.create());
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }
}
