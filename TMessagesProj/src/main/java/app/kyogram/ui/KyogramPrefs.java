package app.kyogram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Settings → Customize Kyogram, as on the desktop (store.ts, glass.ts): the glass look on or off, Night or
 * Day, the glass colours, how clear the glass is, its frost and shine, Telegram's liquid glass, the layout
 * when unfolded, the font. They belong to the phone, not to an account.
 */
public final class KyogramPrefs {

    public static final String[] TINTS = {"candy", "ocean", "sunset", "mono"};
    public static final String[] TINT_NAMES = {"Candy", "Ocean", "Sunset", "Mono"};

    private KyogramPrefs() {
    }

    private static SharedPreferences p() {
        return ApplicationLoader.applicationContext.getSharedPreferences("kyogram_look", Context.MODE_PRIVATE);
    }

    /** The whole app in glass (the desktop's "Glass look"). */
    public static boolean glass() {
        return p().getBoolean("glass", true);
    }

    public static boolean day() {
        return p().getBoolean("day", false);
    }

    public static String tint() {
        return p().getString("tint", "candy");
    }

    /** How solid the glass is: 0 clear … 1 solid (desktop: Clear). */
    public static float solid() {
        return p().getFloat("solid", .5f);
    }

    /** The frost's blur, in the desktop's pixels (0 … 60). */
    public static float blur() {
        return p().getFloat("blur", 24f);
    }

    /** The light on the glass's top edge and sheen (0 … 1). */
    public static float shine() {
        return p().getFloat("shine", 1f);
    }

    /** Telegram's own Liquid Glass (bends the light under the bars; Android 13+). */
    public static boolean liquid() {
        return p().getBoolean("liquid", true);
    }

    /** Unfolded: Discord's layout (rail | chats | chat). */
    public static boolean discord() {
        return p().getBoolean("discord", true);
    }

    /** Kyogram's colours as the chats' wallpaper (desktop: Discord wallpaper / Look → Wallpaper). */
    public static boolean chatWall() {
        return p().getBoolean("chatWall", true);
    }

    /** "inter" or "roboto": Telegram's headings and Kyogram's own screens. */
    public static String font() {
        return p().getString("font", "inter");
    }

    public static void put(String key, Object value) {
        SharedPreferences.Editor e = p().edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) e.putFloat(key, (Float) value);
        else if (value instanceof String) e.putString(key, (String) value);
        e.apply();
    }

    public static void reset() {
        p().edit().remove("solid").remove("blur").remove("shine").remove("tint").apply();
    }
}
