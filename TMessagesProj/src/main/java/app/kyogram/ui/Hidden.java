package app.kyogram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Hidden chats, as desktop Kyogram has them (hidden.ts): chats taken out of every chat list on this
 * phone and kept in their own "Hidden chats" screen, optionally behind a PIN. A privacy curtain for
 * people looking over your shoulder, not encryption: the chats are still in your account.
 */
public final class Hidden {

    private static Set<Long> ids;

    private Hidden() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("kyogram_hidden", Context.MODE_PRIVATE);
    }

    private static Set<Long> ids() {
        if (ids == null) {
            ids = new HashSet<>();
            for (String s : prefs().getStringSet("ids", new HashSet<>())) {
                try {
                    ids.add(Long.parseLong(s));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return ids;
    }

    private static void save() {
        HashSet<String> out = new HashSet<>();
        for (Long id : ids()) out.add(String.valueOf(id));
        prefs().edit().putStringSet("ids", out).apply();
    }

    public static boolean any() {
        return !ids().isEmpty();
    }

    public static boolean isHidden(long dialogId) {
        return ids().contains(dialogId);
    }

    public static void setHidden(long dialogId, boolean hidden) {
        if (hidden) ids().add(dialogId);
        else ids().remove(dialogId);
        save();
    }

    public static ArrayList<Long> all() {
        return new ArrayList<>(ids());
    }

    /** The chat lists without hidden chats (the same list when nothing is hidden). */
    public static ArrayList<TLRPC.Dialog> filter(ArrayList<TLRPC.Dialog> list) {
        if (list == null || !any()) return list;
        ArrayList<TLRPC.Dialog> out = new ArrayList<>(list.size());
        for (TLRPC.Dialog d : list) if (d == null || !ids().contains(d.id)) out.add(d);
        return out;
    }

    // ── the PIN ──

    private static String hash(String pin) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("kyogram:" + pin).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return pin;
        }
    }

    public static boolean hasPin() {
        return !prefs().getString("pin", "").isEmpty();
    }

    public static boolean checkPin(String pin) {
        return hash(pin).equals(prefs().getString("pin", ""));
    }

    public static void setPin(String pin) {
        prefs().edit().putString("pin", pin == null || pin.isEmpty() ? "" : hash(pin)).apply();
    }
}
