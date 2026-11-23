package app.kyogram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Auto-reply, as desktop Kyogram has it (autoReply.ts): mark a person's chat (⋮ → Auto-reply), and each
 * new message they send gets one of your saved replies. Telegram limits, and can ban, accounts that send
 * in bulk, so it's held back on purpose: one-on-one chats with people only; a short random delay so it
 * reads like typing; at most one reply per chat every 20 seconds; it stops after 5 in a row until you
 * write in that chat yourself; and only messages that arrive now, never old ones being synced.
 */
public final class AutoReply implements NotificationCenter.NotificationCenterDelegate {

    private static final long BASE_DELAY = 1500, COOLDOWN = 20_000;
    private static final int MAX_IN_A_ROW = 5, MAX_MESSAGE_AGE = 60;
    private static final AutoReply INSTANCE = new AutoReply();
    private static final boolean[] started = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private final HashMap<String, Long> lastReplyAt = new HashMap<>();
    private final HashMap<String, Integer> inARow = new HashMap<>();
    private final HashMap<String, Integer> nextIndex = new HashMap<>();
    private final HashSet<String> ours = new HashSet<>();
    private final Random random = new Random();

    private AutoReply() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("kyogram_autoreply", Context.MODE_PRIVATE);
    }

    public static void start(int account) {
        if (account < 0 || account >= started.length || started[account]) return;
        started[account] = true;
        NotificationCenter.getInstance(account).addObserver(INSTANCE, NotificationCenter.didReceiveNewMessages);
    }

    private static Set<String> peers(int account) {
        return new HashSet<>(prefs().getStringSet("peers" + account, new HashSet<>()));
    }

    public static boolean canUse(int account, long dialogId) {
        return DialogObject.isUserDialog(dialogId) && dialogId != UserConfig.getInstance(account).getClientUserId();
    }

    public static boolean isOn(int account, long dialogId) {
        return peers(account).contains(String.valueOf(dialogId));
    }

    public static void toggle(int account, long dialogId) {
        Set<String> p = peers(account);
        if (!p.remove(String.valueOf(dialogId))) p.add(String.valueOf(dialogId));
        prefs().edit().putStringSet("peers" + account, p).apply();
        INSTANCE.inARow.remove(account + ":" + dialogId);
    }

    public static ArrayList<String> messages() {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("messages", "[\"ok\",\"real\",\"who asked\",\"brb\",\"sure buddy\"]"));
            for (int i = 0; i < a.length(); i++) if (!a.optString(i).trim().isEmpty()) out.add(a.optString(i));
        } catch (Exception ignore) {
        }
        return out;
    }

    public static void setMessages(ArrayList<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) if (!s.trim().isEmpty()) a.put(s.trim());
        prefs().edit().putString("messages", a.toString()).apply();
    }

    public static boolean inOrder() {
        return "order".equals(prefs().getString("mode", "random"));
    }

    public static void setInOrder(boolean order) {
        prefs().edit().putString("mode", order ? "order" : "random").apply();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages) return;
        long dialogId = (Long) args[0];
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) return; // * scheduled
        @SuppressWarnings("unchecked") ArrayList<MessageObject> objs = (ArrayList<MessageObject>) args[1];
        if (!isOn(account, dialogId) || !canUse(account, dialogId)) return;
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        if (user == null || user.bot) return;
        String key = account + ":" + dialogId;
        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        boolean theirs = false;
        for (MessageObject mo : objs) {
            if (mo.isOutOwner()) {
                // * you wrote in the chat yourself: the count starts again (our own replies don't count)
                String text = mo.messageText != null ? mo.messageText.toString() : "";
                if (!ours.remove(key + "\n" + text)) inARow.remove(key);
            } else if (Math.abs(now - mo.messageOwner.date) <= MAX_MESSAGE_AGE) {
                theirs = true;
            }
        }
        if (!theirs) return;
        Long last = lastReplyAt.get(key);
        if (last != null && System.currentTimeMillis() - last < COOLDOWN) return;
        int count = inARow.containsKey(key) ? inARow.get(key) : 0;
        if (count >= MAX_IN_A_ROW) return;
        ArrayList<String> list = messages();
        if (list.isEmpty()) return;
        String text;
        if (inOrder()) {
            int i = nextIndex.containsKey(key) ? nextIndex.get(key) : 0;
            text = list.get(i % list.size());
            nextIndex.put(key, i + 1);
        } else {
            text = list.get(random.nextInt(list.size()));
        }
        lastReplyAt.put(key, System.currentTimeMillis());
        inARow.put(key, count + 1);
        long delay = BASE_DELAY + (long) (random.nextFloat() * prefs().getInt("delay", 4) * 1000);
        final String reply = text;
        AndroidUtilities.runOnUIThread(() -> {
            if (!isOn(account, dialogId)) return;
            ours.add(key + "\n" + reply);
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(reply, dialogId, null, null, null, false, null, null, null, true, 0, 0, null, false));
        }, delay);
    }
}
