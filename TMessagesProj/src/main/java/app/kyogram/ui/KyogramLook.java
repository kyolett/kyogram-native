package app.kyogram.ui;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Kyogram's Liquid glass look on Telegram's own screens (the phone app): a theme made once from the
 * night theme, with Kyogram's colours — the glass colours as the chats' wallpaper, see-through dark
 * glass for others' messages, yours in the accent gradient, the violet accent everywhere — and
 * applied. Yours to change after, like any theme (Settings → Chat settings).
 */
public final class KyogramLook {

    private static final String DONE = "kyogram_liquid_v1";

    private KyogramLook() {
    }

    public static void applyOnce() {
        SharedPreferences p = MessagesController.getGlobalMainSettings();
        if (p.getBoolean(DONE, false)) return;
        p.edit().putBoolean(DONE, true).apply();
        try {
            Theme.ThemeInfo night = Theme.getTheme("Night");
            if (night != null) Theme.applyTheme(night, true);
            Theme.ThemeInfo made = Theme.createNewTheme("Kyogram Liquid");
            Theme.ThemeInfo t = Theme.applyThemeFile(new File(made.pathToFile), "Kyogram Liquid.attheme", null, false);
            if (t == null) return;
            int accent = 0xff8b74f2, accentHi = 0xffad9cff;
            set(Theme.key_windowBackgroundWhite, 0xff17131f);
            set(Theme.key_windowBackgroundGray, 0xff100d16);
            set(Theme.key_actionBarDefault, 0xff1b1626);
            set(Theme.key_chats_actionBackground, accent);
            set(Theme.key_chats_actionPressedBackground, accentHi);
            set(Theme.key_chats_unreadCounter, accent);
            set(Theme.key_chats_verifiedBackground, accent);
            set(Theme.key_chat_inBubble, 0xb01e1b30);
            set(Theme.key_chat_inBubbleSelected, 0xc02a2640);
            set(Theme.key_chat_outBubble, accentHi);
            set(Theme.key_chat_outBubbleGradient1, accent);
            set(Theme.key_chat_outBubbleSelected, accent);
            set(Theme.key_chat_messageTextIn, 0xfff6f4ff);
            set(Theme.key_chat_messageTextOut, 0xffffffff);
            set(Theme.key_chat_outTimeText, 0xc8ffffff);
            set(Theme.key_chat_messagePanelBackground, 0xff1b1626);
            set(Theme.key_chat_messagePanelSend, accentHi);
            set(Theme.key_windowBackgroundWhiteBlueText, accentHi);
            set(Theme.key_windowBackgroundWhiteBlueHeader, accentHi);
            set(Theme.key_checkboxSquareBackground, accent);
            set(Theme.key_switchTrackChecked, accent);
            // * the glass colours as the chats' wallpaper
            int w = Math.max(720, AndroidUtilities.displaySize.x), h = Math.max(1280, AndroidUtilities.displaySize.y);
            Bitmap wall = Style.wallBitmap(Math.min(w, 1440), Math.min(h, 2560));
            File f = new File(ApplicationLoader.getFilesDirFixed(), "kyogram_liquid.jpg");
            FileOutputStream out = new FileOutputStream(f);
            wall.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            Theme.setThemeWallpaper(t, wall, f);
            Theme.saveCurrentTheme(t, true, false, false);
            Theme.refreshThemeColors();
        } catch (Throwable ignore) {
        }
    }

    private static void set(int key, int color) {
        Theme.setColor(key, color, false);
    }
}
