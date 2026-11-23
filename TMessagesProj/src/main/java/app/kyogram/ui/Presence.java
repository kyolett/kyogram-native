package app.kyogram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Discord-style statuses, as desktop Kyogram makes them (presence.ts). Telegram only knows "online"
 * and "last seen", so they're made of what the app sends and what it lets ring:
 *   online    — as usual;
 *   idle      — looks like "last seen" to others (Telegram has no "away"); still notifies;
 *   dnd       — online, but no notification sounds or vibration;
 *   invisible — never reported online; reading a chat doesn't mark it read for them, no typing.
 */
public final class Presence {

    public static final String[] STATUSES = {"online", "idle", "dnd", "invisible"};
    public static final String[] NAMES = {"Online", "Idle", "Do not disturb", "Invisible"};
    public static final int[] COLORS = {0xff3ba55d, 0xfffaa81a, 0xffed4245, 0xff747f8d};

    private Presence() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("kyogram_presence", Context.MODE_PRIVATE);
    }

    public static String status() {
        try {
            return prefs().getString("status", "online");
        } catch (Throwable e) {
            return "online";
        }
    }

    public static int index() {
        String s = status();
        for (int i = 0; i < STATUSES.length; i++) if (STATUSES[i].equals(s)) return i;
        return 0;
    }

    public static void set(String status) {
        prefs().edit().putString("status", status).apply();
    }

    /** Never shown online, no read receipts, no typing. */
    public static boolean invisible() {
        return "invisible".equals(status());
    }

    /** Shown as "last seen" (not online). */
    public static boolean hidesOnline() {
        String s = status();
        return "invisible".equals(s) || "idle".equals(s);
    }

    /** No sounds or vibration. */
    public static boolean dnd() {
        return "dnd".equals(status());
    }
}
