package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Arrays;

import app.kyogram.ui.AutoReply;
import app.kyogram.ui.GlassFrame;
import app.kyogram.ui.KyogramLook;
import app.kyogram.ui.KyogramPrefs;
import app.kyogram.ui.Presence;
import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;

/**
 * Kyogram: Settings → Customize Kyogram, the desktop's (kyogramSettings.tsx) on the phone: the glass look and
 * its glass (Night or Day, colours, clear, frost, shine, liquid), the chats' wallpaper, the unfolded layout,
 * the font, your status, and the doors to Hidden chats, auto-reply, DroidShop, Trello and the wallpaper wipe.
 * Every change shows at once, here and on Telegram's screens.
 */
public class KyogramSettingsActivity extends BaseFragment {

    private FrameLayout root;
    private ScrollView scroll;
    private int keptScroll;

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
        fragmentView = root;
        return root;
    }

    private void build() {
        Context c = getContext();
        if (c == null || root.getWidth() == 0) return;
        if (scroll != null) keptScroll = scroll.getScrollY();
        Style.fromPrefs();
        Style.init(c, root.getWidth(), root.getHeight(), AndroidUtilities.statusBarHeight, Style.phoneUnit());
        root.removeAllViews();
        root.setBackgroundColor(Style.BASE);
        root.addView(new View(c) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (Style.wall != null) canvas.drawBitmap(Style.wall, 0, 0, null);
            }
        }, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, AndroidUtilities.statusBarHeight + px(92), 0, px(40) + AndroidUtilities.navigationBarHeight);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(14), 0, px(14), 0);
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // ── Glass ──
        LinearLayout glass = section(c, col, "Glass", "Kyogram's Liquid glass on every screen: the colours behind, the glass over them.");
        toggle(c, glass, "Glass look", "The whole app in glass. Off: Telegram's own Night or Day.", KyogramPrefs.glass(), on -> {
            KyogramPrefs.put("glass", on);
            if (on) KyogramLook.apply();
            else KyogramLook.turnOff();
            refreshWindow();
            build();
        });
        choice(c, glass, "Theme", new String[]{"Night", "Day"}, KyogramPrefs.day() ? 1 : 0, i -> {
            KyogramPrefs.put("day", i == 1);
            lookChanged();
        });
        swatches(c, glass);
        slider(c, glass, "Clear", "how solid the glass is", KyogramPrefs.solid(), 0f, 1f, v -> Math.round((1 - v) * 100) + "% clear", v -> {
            KyogramPrefs.put("solid", v);
            lookChanged();
        });
        slider(c, glass, "Frost", "the blur behind the glass", KyogramPrefs.blur(), 0f, 60f, v -> Math.round(v) + " px", v -> {
            KyogramPrefs.put("blur", v);
            lookChanged();
        });
        slider(c, glass, "Shine", "the light on the glass's edge", KyogramPrefs.shine(), 0f, 1f, v -> Math.round(v * 100) + "%", v -> {
            KyogramPrefs.put("shine", v);
            lookChanged();
        });
        toggle(c, glass, "Liquid glass", "Bars that bend the light under them, as on the iPhone (Telegram's own; Android 13 and newer).", KyogramPrefs.liquid(), on -> {
            KyogramPrefs.put("liquid", on);
            lookChanged();
        });
        toggle(c, glass, "Glass colours in chats", "Kyogram's colours as the chats' wallpaper.", KyogramPrefs.chatWall(), on -> {
            KyogramPrefs.put("chatWall", on);
            lookChanged();
        });
        action(c, glass, "Reset the glass", null, () -> {
            KyogramPrefs.reset();
            lookChanged();
        });

        // ── Layout ──
        LinearLayout layout = section(c, col, "Layout", null);
        toggle(c, layout, "Discord layout when unfolded", "Your chats and folders in a rail, the chat list, and the chat side by side on the big screen.", KyogramPrefs.discord(), on -> {
            KyogramPrefs.put("discord", on);
            AndroidUtilities.resetTabletFlag();
            Activity a = getParentActivity();
            if (a != null) a.recreate();
        });
        choice(c, layout, "Font", new String[]{"Inter", "Roboto"}, "inter".equals(KyogramPrefs.font()) ? 0 : 1, i -> {
            KyogramPrefs.put("font", i == 0 ? "inter" : "roboto");
            Toast.makeText(c, "Close and open Kyogram to see the font everywhere", Toast.LENGTH_LONG).show();
        });

        // ── Status ──
        LinearLayout status = section(c, col, "Status", "Idle comes on by itself when you're away. Do not disturb mutes notifications; Invisible shows you offline and hides your typing and reading.");
        for (int i = 0; i < Presence.STATUSES.length; i++) {
            final String s = Presence.STATUSES[i];
            final int color = Presence.COLORS[i];
            boolean on = Presence.index() == i;
            row(c, status, Presence.NAMES[i], null, dot(c, color, on), () -> {
                Presence.set(s);
                build();
            });
        }

        // ── Kyogram ──
        LinearLayout more = section(c, col, "Kyogram", null);
        action(c, more, "Hidden chats", Hidden() ? "Behind a PIN" : null, () -> presentFragment(new KyogramHiddenActivity()));
        action(c, more, "Auto-reply messages", AutoReply.inOrder() ? "In order" : "Random", () -> editAutoReplyMessages(this, null));
        action(c, more, "DroidShop Care", "Tickets, lookup, warranty", () -> presentFragment(new KyogramCareActivity(null)));
        action(c, more, "Trello shift tasks", "Key, token, board and shifts", () -> {
            android.os.Bundle b = new android.os.Bundle();
            b.putBoolean("hasMainTabs", false);
            presentFragment(new KyogramTasksActivity(b));
        });

        LinearLayout wipe = section(c, col, "Wallpapers", "Removes every chat wallpaper you uploaded, from your account and this phone.");
        action(c, wipe, "Delete all wallpapers", null, () -> confirmWipeWallpapers(this));

        // * the header over it all
        GlassFrame header = new GlassFrame(c, 36);
        TextView title = label(c, 23, Style.INK, Style.bold);
        title.setText("Customize Kyogram");
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(px(64), 0, px(20), 0);
        header.addView(title, new FrameLayout.LayoutParams(-1, -1));
        ImageView back = new ImageView(c);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new PorterDuffColorFilter(Style.INK, PorterDuff.Mode.SRC_IN));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setOnClickListener(v -> finishFragment());
        header.addView(back, new FrameLayout.LayoutParams(px(64), -1, Gravity.LEFT));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, px(72));
        hlp.setMargins(px(14), AndroidUtilities.statusBarHeight + px(8), px(14), 0);
        root.addView(header, hlp);

        final int y = keptScroll;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private static boolean Hidden() {
        return app.kyogram.ui.Hidden.hasPin();
    }

    /** A setting of the look changed: Telegram's screens and this one, again. */
    private void lookChanged() {
        if (KyogramPrefs.glass()) KyogramLook.apply();
        refreshWindow();
        build();
    }

    private void refreshWindow() {
        Activity a = getParentActivity();
        if (a == null) return;
        if (KyogramPrefs.glass()) a.getWindow().setBackgroundDrawable(KyogramLook.wallDrawable());
        a.getWindow().getDecorView().invalidate();
    }

    // ── pieces ──

    private interface OnToggle {
        void on(boolean on);
    }

    private interface OnPick {
        void pick(int index);
    }

    private interface OnValue {
        void value(float v);
    }

    private interface Format {
        String text(float v);
    }

    private TextView label(Context c, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(size));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout section(Context c, LinearLayout col, String name, String caption) {
        TextView h = label(c, 15, Style.INK2, Style.semibold);
        h.setText(name.toUpperCase());
        h.setLetterSpacing(.06f);
        h.setPadding(px(18), px(20), px(18), px(10));
        col.addView(h);
        GlassFrame frame = new GlassFrame(c, 26);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(6), 0, px(6));
        frame.addView(box, new FrameLayout.LayoutParams(-1, -2));
        col.addView(frame, new LinearLayout.LayoutParams(-1, -2));
        if (caption != null) {
            TextView cap = label(c, 14, Style.INK3, Style.regular);
            cap.setText(caption);
            cap.setLineSpacing(0, 1.2f);
            cap.setPadding(px(18), px(8), px(18), 0);
            col.addView(cap);
        }
        return box;
    }

    private LinearLayout textBlock(Context c, String title, String sub) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = label(c, 19, Style.INK, Style.semibold);
        a.setText(title);
        t.addView(a);
        if (!TextUtils.isEmpty(sub)) {
            TextView b = label(c, 15, Style.INK3, Style.regular);
            b.setText(sub);
            b.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = px(5);
            t.addView(b, lp);
        }
        return t;
    }

    private void row(Context c, LinearLayout box, String title, String sub, View end, Runnable onClick) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(70));
        r.setPadding(px(20), px(14), px(18), px(14));
        r.addView(textBlock(c, title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        if (end != null) {
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
            ep.leftMargin = px(14);
            r.addView(end, ep);
        }
        if (onClick != null) {
            r.setBackground(Theme.createSelectorDrawable(Style.fg(24), Theme.RIPPLE_MASK_ALL));
            r.setOnClickListener(v -> onClick.run());
        }
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void action(Context c, LinearLayout box, String title, String value, Runnable onClick) {
        TextView end = label(c, 17, Style.INK3, Style.regular);
        end.setText((value != null ? value + "  " : "") + "›");
        row(c, box, title, null, end, onClick);
    }

    private void toggle(Context c, LinearLayout box, String title, String sub, boolean on, OnToggle cb) {
        Toggle t = new Toggle(c, on);
        row(c, box, title, sub, t, () -> {
            t.set(!t.on);
            t.postDelayed(() -> cb.on(t.on), 220);
        });
    }

    private void choice(Context c, LinearLayout box, String title, String[] options, int picked, OnPick cb) {
        app.kyogram.ui.SegmentView seg = new app.kyogram.ui.SegmentView(c, options);
        seg.pick(picked, true);
        seg.setListener(i -> seg.postDelayed(() -> cb.pick(i), 260));
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(20), px(12), px(14), px(12));
        r.addView(textBlock(c, title, null), new LinearLayout.LayoutParams(0, -2, 1));
        float w = 0;
        Paint p = Style.text(Style.semibold, 18, 0);
        for (String o : options) w = Math.max(w, p.measureText(o));
        r.addView(seg, new LinearLayout.LayoutParams(Math.round((w + Style.px(44)) * options.length), px(44)));
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void slider(Context c, LinearLayout box, String title, String sub, float value, float min, float max, Format fmt, OnValue cb) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(px(20), px(14), px(18), px(8));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(textBlock(c, title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = label(c, 17, Style.INK2, Style.medium);
        val.setText(fmt.text(value));
        top.addView(val);
        r.addView(top);
        SeekBar bar = new SeekBar(c);
        bar.setMax(1000);
        bar.setProgress(Math.round((value - min) / (max - min) * 1000));
        bar.setProgressTintList(ColorStateList.valueOf(Style.ACCENT_HI));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Style.fg(90)));
        bar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setPadding(px(10), px(10), px(10), px(10));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                val.setText(fmt.text(min + (max - min) * progress / 1000f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                cb.value(min + (max - min) * s.getProgress() / 1000f);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = px(6);
        r.addView(bar, bp);
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The glass colours, as the desktop's tint swatches: each a little picture of its colours. */
    private void swatches(Context c, LinearLayout box) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(px(20), px(14), px(18), px(16));
        r.addView(textBlock(c, "Colours", null));
        LinearLayout line = new LinearLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(12);
        r.addView(line, lp);
        String current = KyogramPrefs.tint();
        String keepTint = Style.tint;
        for (int i = 0; i < KyogramPrefs.TINTS.length; i++) {
            final String t = KyogramPrefs.TINTS[i];
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            Style.theme(Style.day, t);
            final android.graphics.Bitmap pic = Style.wallBitmap(96, 96);
            final boolean on = t.equals(current);
            View sw = new View(c) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    float r = getWidth() / 2f;
                    paint.setShader(new android.graphics.BitmapShader(pic, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setScale(getWidth() / 96f, getHeight() / 96f);
                    paint.getShader().setLocalMatrix(m);
                    canvas.drawCircle(r, r, r - Style.px(5), paint);
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(Style.px(on ? 3 : 1.5f));
                    ring.setColor(on ? Style.ACCENT_HI : Style.fg(60));
                    canvas.drawCircle(r, r, r - Style.px(on ? 1.5f : 4.25f), ring);
                }
            };
            cell.addView(sw, new LinearLayout.LayoutParams(px(58), px(58)));
            TextView name = label(c, 15, on ? Style.INK : Style.INK2, on ? Style.semibold : Style.regular);
            name.setText(KyogramPrefs.TINT_NAMES[i]);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
            np.topMargin = px(6);
            cell.addView(name, np);
            cell.setOnClickListener(v -> {
                KyogramPrefs.put("tint", t);
                lookChanged();
            });
            line.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        Style.theme(Style.day, keepTint);
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private View dot(Context c, int color, boolean on) {
        View v = new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float r = getWidth() / 2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                canvas.drawCircle(r, r, Style.px(8), p);
                if (on) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Style.px(2.5f));
                    p.setColor(Style.ACCENT_HI);
                    canvas.drawCircle(r, r, r - Style.px(2), p);
                }
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        v.setMinimumWidth(px(30));
        v.setMinimumHeight(px(30));
        return v;
    }

    /** A glass switch: the knob glides on a spring, the track fills with the accent. */
    private static class Toggle extends View {
        boolean on;
        private float t;
        private final Spring spring;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            t = on ? 1 : 0;
            spring = new Spring(t, 420, 28, (v, vel) -> {
                t = v;
                invalidate();
            });
            setMinimumWidth(Math.round(Style.px(64)));
            setMinimumHeight(Math.round(Style.px(38)));
        }

        void set(boolean value) {
            on = value;
            spring.to(on ? 1 : 0);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(Style.px(64)), Math.round(Style.px(38)));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            float k = Math.max(0, Math.min(1, t));
            track.setColor(blend(Style.fg(46), Style.ACCENT, k));
            c.drawRoundRect(0, 0, w, h, r, r, track);
            float pad = Style.px(4), kr = r - pad;
            float x = pad + kr + (w - 2 * (pad + kr)) * t;
            knob.setColor(Color.WHITE);
            knob.setShadowLayer(Style.px(4), 0, Style.px(1.5f), 0x40000000);
            c.drawCircle(x, r, kr * (1 + .08f * (float) Math.sin(Math.PI * k)), knob);
        }

        private static int blend(int a, int b, float k) {
            return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
        }
    }

    // ── shared with the chat's menu and the Chats tab ──

    /** Auto-reply's messages, one per line, and whether they go in order or at random. */
    public static void editAutoReplyMessages(BaseFragment f, Theme.ResourcesProvider rp) {
        if (f.getParentActivity() == null) return;
        EditText box = new EditText(f.getParentActivity());
        box.setText(TextUtils.join("\n", AutoReply.messages()));
        box.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        box.setMinLines(4);
        AlertDialog.Builder b = new AlertDialog.Builder(f.getParentActivity(), rp);
        b.setTitle("Auto-reply messages (one per line)");
        b.setView(box);
        b.setPositiveButton("Save", (d, w) -> AutoReply.setMessages(new ArrayList<>(Arrays.asList(box.getText().toString().split("\n")))));
        b.setNeutralButton(AutoReply.inOrder() ? "In order" : "Random", (d, w) -> AutoReply.setInOrder(!AutoReply.inOrder()));
        b.setNegativeButton("Cancel", null);
        f.showDialog(b.create());
    }

    /** Delete all wallpapers (wallpaperWipe.ts): your uploaded ones, off your account and this phone. */
    public static void confirmWipeWallpapers(BaseFragment f) {
        if (f.getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(f.getParentActivity());
        b.setTitle("Delete all wallpapers?");
        b.setMessage("Your uploaded chat wallpapers come off your account and this phone. Chats go back to the default background.");
        b.setPositiveButton("Delete", (d, w) -> f.getConnectionsManager().sendRequest(new org.telegram.tgnet.tl.TL_account.resetWallPapers(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            try {
                java.io.File dir = new java.io.File(ApplicationLoader.getFilesDirFixed(), "wallpapers");
                java.io.File[] files = dir.listFiles();
                if (files != null) for (java.io.File file : files) file.delete();
            } catch (Throwable ignore) {
            }
            if (f.getParentActivity() != null) Toast.makeText(f.getParentActivity(), error == null ? "Wallpapers deleted" : "Couldn't reach Telegram", Toast.LENGTH_SHORT).show();
        })));
        b.setNegativeButton("Cancel", null);
        f.showDialog(b.create());
    }
}
