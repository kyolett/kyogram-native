package org.telegram.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/**
 * Kyogram: a group's members, split into Online and Offline with a status dot, as the Discord
 * layout's members list on desktop (membersPanel.tsx). Tap someone to open your chat with them.
 */
public final class KyogramMembers {

    private KyogramMembers() {
    }

    public static void show(BaseFragment fragment, long chatId) {
        if (fragment.getParentActivity() == null) return;
        MessagesController mc = fragment.getMessagesController();
        TLRPC.Chat chat = mc.getChat(chatId);
        LinearLayout col = new LinearLayout(fragment.getParentActivity());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), AndroidUtilities.dp(8));
        ScrollView sv = new ScrollView(fragment.getParentActivity());
        sv.addView(col);
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(chat != null ? chat.title : "Members");
        b.setView(sv);
        b.setNegativeButton("Close", null);
        AlertDialog dialog = b.create();
        fragment.showDialog(dialog);
        TextView loading = new TextView(fragment.getParentActivity());
        loading.setText("Loading…");
        col.addView(loading);

        if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
            req.channel = mc.getInputChannel(chatId);
            req.filter = new TLRPC.TL_channelParticipantsRecent();
            req.limit = 200;
            ConnectionsManager.getInstance(fragment.getCurrentAccount()).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.TL_channels_channelParticipants)) {
                    loading.setText("Couldn't load the members");
                    return;
                }
                TLRPC.TL_channels_channelParticipants res = (TLRPC.TL_channels_channelParticipants) response;
                mc.putUsers(res.users, false);
                fill(fragment, dialog, col, res.users);
            }));
        } else {
            TLRPC.ChatFull full = mc.getChatFull(chatId);
            ArrayList<TLRPC.User> users = new ArrayList<>();
            if (full != null && full.participants != null) {
                for (TLRPC.ChatParticipant p : full.participants.participants) {
                    TLRPC.User u = mc.getUser(p.user_id);
                    if (u != null) users.add(u);
                }
            }
            fill(fragment, dialog, col, users);
        }
    }

    private static boolean online(BaseFragment f, TLRPC.User u) {
        if (UserObject.isUserSelf(u)) return true;
        return u.status != null && u.status.expires > f.getConnectionsManager().getCurrentTime();
    }

    private static void fill(BaseFragment f, AlertDialog dialog, LinearLayout col, ArrayList<TLRPC.User> users) {
        col.removeAllViews();
        ArrayList<TLRPC.User> on = new ArrayList<>(), off = new ArrayList<>();
        for (TLRPC.User u : users) if (!u.deleted) (online(f, u) ? on : off).add(u);
        section(f, dialog, col, "ONLINE — " + on.size(), on, 0xff3ba55d);
        section(f, dialog, col, "OFFLINE — " + off.size(), off, 0xff747f8d);
    }

    private static void section(BaseFragment f, AlertDialog dialog, LinearLayout col, String title, ArrayList<TLRPC.User> users, int dot) {
        TextView h = new TextView(f.getParentActivity());
        h.setText(title);
        h.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        h.setTypeface(AndroidUtilities.bold());
        h.setTextColor(0xff8e8e98);
        h.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4));
        col.addView(h);
        for (TLRPC.User u : users) {
            LinearLayout row = new LinearLayout(f.getParentActivity());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
            View d = new View(f.getParentActivity());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(dot);
            d.setBackground(g);
            row.addView(d, new LinearLayout.LayoutParams(AndroidUtilities.dp(10), AndroidUtilities.dp(10)));
            LinearLayout texts = new LinearLayout(f.getParentActivity());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(AndroidUtilities.dp(12), 0, 0, 0);
            TextView name = new TextView(f.getParentActivity());
            name.setText(UserObject.getUserName(u));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(f.getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
            TextView st = new TextView(f.getParentActivity());
            st.setText(LocaleController.formatUserStatus(f.getCurrentAccount(), u));
            st.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            st.setTextColor(Color.GRAY);
            texts.addView(name);
            texts.addView(st);
            row.addView(texts);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (UserObject.isUserSelf(u)) return;
                Bundle args = new Bundle();
                args.putLong("user_id", u.id);
                f.presentFragment(new ChatActivity(args));
            });
            col.addView(row);
        }
    }
}
