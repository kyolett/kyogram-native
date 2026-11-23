package app.kyogram.ui;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeColors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Kyogram's Liquid glass on Telegram's own screens: a theme written from Telegram's Night (or Day) theme
 * with Kyogram's glass in it — the glass colours behind every screen (the window's background) and as the
 * chats' wallpaper, headers, lists and others' messages as see-through glass at the set clearness, yours
 * in the accent gradient — and Telegram's own Liquid Glass and frosted bars on. Made again whenever
 * Customize Kyogram changes.
 *
 * The theme is written as a file here rather than with Telegram's "new theme", which saves a built-in
 * theme's default accent as the light theme's colours.
 */
public final class KyogramLook {

    public static final String NAME = "Kyogram Liquid";
    private static final String DONE = "kyogram_liquid_v2";

    private KyogramLook() {
    }

    /** Notifications with the app closed: Telegram's kept connection, since Kyogram can't get Telegram's pushes. */
    public static void keepConnected() {
        SharedPreferences n = MessagesController.getGlobalNotificationsSettings();
        if (!n.contains("pushService")) {
            n.edit().putBoolean("pushService", true).apply();
            ApplicationLoader.startPushService();
        }
    }

    /** At start: the look once (again after an update that changes it), then whatever the user changes. */
    public static void applyOnce() {
        keepConnected();
        Style.fromPrefs();
        SharedPreferences p = MessagesController.getGlobalMainSettings();
        if (p.getBoolean(DONE, false)) return;
        p.edit().putBoolean(DONE, true).apply();
        if (KyogramPrefs.glass()) apply();
    }

    /** Whether Telegram's screens are in Kyogram's glass right now. */
    public static boolean isOn() {
        Theme.ThemeInfo t = Theme.getActiveTheme();
        return KyogramPrefs.glass() && t != null && t.name != null && t.name.startsWith(NAME);
    }

    /** Customize Kyogram → Glass look off: back to Telegram's own Night or Day. */
    public static void turnOff() {
        try {
            Theme.ThemeInfo base = Theme.getTheme(KyogramPrefs.day() ? "Day" : "Night");
            if (base != null) Theme.applyTheme(base);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, base, false, null, -1);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Writes Kyogram's theme from the settings and applies it. */
    public static void apply() {
        Style.fromPrefs();
        wallCache = null;
        try {
            // * the theme stays put: no switching to Telegram's own by the time of day or the system's dark mode
            Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
            Theme.saveAutoNightThemeConfig();
            Theme.cancelAutoNightThemeCallbacks();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            setLite(LiteMode.FLAG_LIQUID_GLASS, KyogramPrefs.liquid());
            setLite(LiteMode.FLAG_CHAT_BLUR, true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            boolean day = KyogramPrefs.day();
            int[] colors = Theme.getDefaultColors().clone();
            SparseIntArray base = Theme.getThemeFileValues(null, day ? "day.attheme" : "night.attheme", null);
            for (int i = 0; i < base.size(); i++) {
                int k = base.keyAt(i);
                if (k >= 0 && k < colors.length) colors[k] = base.valueAt(i);
            }
            glassColors(colors, day);

            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < colors.length; k++) {
                String name = ThemeColors.getStringName(k);
                if (name == null || k == Theme.key_chat_wallpaper || k == Theme.key_chat_wallpaper_gradient_to1 || k == Theme.key_chat_wallpaper_gradient_to2 || k == Theme.key_chat_wallpaper_gradient_to3) continue;
                sb.append(name).append('=').append(colors[k]).append('\n');
            }
            File tmp = new File(ApplicationLoader.applicationContext.getCacheDir(), "kyogram_liquid.attheme");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(AndroidUtilities.getStringBytes(sb.toString()));
            if (KyogramPrefs.chatWall()) {
                // * the glass colours as the chats' wallpaper, in the file as Telegram keeps a theme's own picture
                Bitmap wall = Style.wallBitmap(1080, 2340);
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                wall.compress(Bitmap.CompressFormat.JPEG, 90, jpg);
                out.write(new byte[]{'W', 'P', 'S', '\n'});
                out.write(jpg.toByteArray());
                out.write(new byte[]{'\n', 'W', 'P', 'E', '\n'});
            }
            out.close();
            Theme.ThemeInfo t = Theme.applyThemeFile(tmp, NAME, null, false);
            if (t != null) {
                Theme.refreshThemeColors();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, t, false, null, -1);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewWallpapper);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void setLite(int flag, boolean on) {
        if (LiteMode.isEnabledSetting(flag) != on) LiteMode.toggleFlag(flag, on);
    }

    /** Kyogram's colours over Telegram's: the glass at the set clearness, the violet accent, readable ink. */
    private static void glassColors(int[] c, boolean day) {
        float solid = KyogramPrefs.solid();
        int accent = 0xff8b74f2, accentHi = 0xffad9cff;
        int glassA = Math.round(40 + solid * 200);          // * panels and rows
        int pageA = Math.round(solid * 170);                 // * behind the panels: the colours show through
        int barA = Math.round(25 + solid * 150);             // * headers
        if (day) {
            int ink = 0xff1d1a26;
            put(c, Theme.key_windowBackgroundWhite, argb(glassA, 0xffffff));
            put(c, Theme.key_windowBackgroundGray, argb(pageA, 0xf6f1fb));
            put(c, Theme.key_graySection, argb(Math.max(30, pageA), 0xf1ecf6));
            put(c, Theme.key_actionBarDefault, argb(barA, 0xffffff));
            put(c, Theme.key_actionBarDefaultTitle, ink);
            put(c, Theme.key_actionBarDefaultIcon, ink);
            put(c, Theme.key_actionBarDefaultSubmenuBackground, 0xf7fbf9ff);
            put(c, Theme.key_dialogBackground, 0xfffbf9ff);
            put(c, Theme.key_dialogBackgroundGray, 0xfff2eef8);
            put(c, Theme.key_chats_menuBackground, 0xf7fbf9ff);
            put(c, Theme.key_chat_messagePanelBackground, argb(glassA, 0xffffff));
            put(c, Theme.key_chat_inBubble, argb(Math.max(150, glassA), 0xffffff));
            put(c, Theme.key_chat_inBubbleSelected, argb(Math.max(170, glassA), 0xece7f7));
            put(c, Theme.key_chat_messageTextIn, ink);
            put(c, Theme.key_windowBackgroundWhiteBlackText, ink);
            put(c, Theme.key_chats_name, ink);
        } else {
            put(c, Theme.key_windowBackgroundWhite, argb(glassA, 0x1e1a2e));
            put(c, Theme.key_windowBackgroundGray, argb(pageA, 0x100d16));
            put(c, Theme.key_graySection, argb(Math.max(30, pageA), 0x15121c));
            put(c, Theme.key_actionBarDefault, argb(barA, 0x15121c));
            put(c, Theme.key_actionBarDefaultTitle, 0xfff6f4ff);
            put(c, Theme.key_actionBarDefaultIcon, 0xfff6f4ff);
            put(c, Theme.key_actionBarDefaultSubmenuBackground, 0xf5201b2c);
            put(c, Theme.key_dialogBackground, 0xff1c1828);
            put(c, Theme.key_dialogBackgroundGray, 0xff15121c);
            put(c, Theme.key_chats_menuBackground, 0xf5171222);
            put(c, Theme.key_chat_messagePanelBackground, argb(glassA, 0x1b1626));
            put(c, Theme.key_chat_inBubble, argb(Math.max(120, glassA), 0x1e1b30));
            put(c, Theme.key_chat_inBubbleSelected, argb(Math.max(150, glassA), 0x2a2640));
            put(c, Theme.key_chat_messageTextIn, 0xfff6f4ff);
            put(c, Theme.key_windowBackgroundWhiteBlackText, 0xfff6f4ff);
            put(c, Theme.key_chats_name, 0xfff6f4ff);
        }
        put(c, Theme.key_chats_actionBackground, accent);
        put(c, Theme.key_chats_actionPressedBackground, accentHi);
        put(c, Theme.key_chats_actionIcon, 0xffffffff);
        put(c, Theme.key_chats_unreadCounter, accent);
        put(c, Theme.key_chats_verifiedBackground, accent);
        put(c, Theme.key_chat_outBubble, accentHi);
        put(c, Theme.key_chat_outBubbleGradient1, accent);
        put(c, Theme.key_chat_outBubbleSelected, accent);
        put(c, Theme.key_chat_messageTextOut, 0xffffffff);
        put(c, Theme.key_chat_outTimeText, 0xc8ffffff);
        put(c, Theme.key_chat_outTimeSelectedText, 0xc8ffffff);
        put(c, Theme.key_chat_messagePanelSend, accentHi);
        put(c, Theme.key_windowBackgroundWhiteBlueText, day ? accent : accentHi);
        put(c, Theme.key_windowBackgroundWhiteBlueHeader, day ? accent : accentHi);
        put(c, Theme.key_checkboxSquareBackground, accent);
        put(c, Theme.key_switchTrackChecked, accent);
    }

    private static void put(int[] c, int key, int color) {
        if (key >= 0 && key < c.length) c[key] = color;
    }

    private static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xffffff);
    }

    // ── the colours behind everything ──

    private static Bitmap wallCache;

    private static Bitmap wall() {
        if (wallCache == null) {
            Style.fromPrefs();
            int w = Math.max(540, Math.min(1440, AndroidUtilities.displaySize.x)), h = Math.max(960, Math.min(2560, AndroidUtilities.displaySize.y));
            wallCache = Style.wallBitmap(w / 2, h / 2); // * soft blobs: half size is plenty
        }
        return wallCache;
    }

    /** The glass colours behind a whole screen, stretched to it (the window, the chat list). */
    public static Drawable wallDrawable() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            private final Rect src = new Rect();

            @Override
            public void draw(Canvas canvas) {
                Bitmap b = wall();
                src.set(0, 0, b.getWidth(), b.getHeight());
                canvas.drawBitmap(b, src, getBounds(), paint);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.OPAQUE;
            }
        };
    }

    /** The glass colours behind the chat list. */
    public static Drawable chatListBackground(android.content.Context c) {
        return wallDrawable();
    }
}
