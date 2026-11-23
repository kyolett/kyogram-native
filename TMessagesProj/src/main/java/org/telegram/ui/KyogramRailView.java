package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AvatarDrawable;

import java.util.ArrayList;

import app.kyogram.ui.Spring;
import app.kyogram.ui.Style;

/**
 * Kyogram's server rail, unfolded, as desktop Kyogram's Discord layout has it: a glass capsule down the
 * left with your pinned chats as round avatars (unread dots and counts), then your folders as tiles with
 * their names; the folder you're in has a glass pill that glides to it. Tap an avatar to open that chat,
 * a folder to show its chats in the channel list beside the rail.
 */
public class KyogramRailView extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final class Slot {
        long dialogId;
        int folderId = -1;
        String label;
        TLObject peer;
        ImageReceiver avatar;
        AvatarDrawable drawable;
        int unread;
        float y;
    }

    private final LaunchActivity activity;
    private final ArrayList<Slot> slots = new ArrayList<>();
    private int account = UserConfig.selectedAccount;
    private int selectedFolder;
    private final Spring pillY;
    private float stretch;
    private final Style.Glass glass = new Style.Glass();
    private final Style.Pill pill = new Style.Pill(true);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tileText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badge = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private float downY;

    public KyogramRailView(LaunchActivity activity) {
        super(activity);
        this.activity = activity;
        pillY = new Spring(0, 420, 30, (v, vel) -> {
            stretch = Math.min(.14f, Math.abs(vel) / (5000f * AndroidUtilities.density));
            invalidate();
        });
        label.setTextSize(AndroidUtilities.dp(10.5f));
        label.setColor(Color.argb(200, 255, 255, 255));
        label.setTypeface(AndroidUtilities.bold());
        tileText.setTextSize(AndroidUtilities.dp(17));
        tileText.setColor(Color.WHITE);
        tileText.setTypeface(AndroidUtilities.bold());
        badge.setTextSize(AndroidUtilities.dp(10));
        badge.setColor(Color.WHITE);
        badge.setTypeface(AndroidUtilities.bold());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        account = UserConfig.selectedAccount;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogFiltersUpdated);
        reload();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.dialogFiltersUpdated);
        for (Slot s : slots) if (s.avatar != null) s.avatar.onDetachedFromWindow();
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        reload();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            Style.init(getContext(), getRootView().getWidth(), getRootView().getHeight(), AndroidUtilities.statusBarHeight, AndroidUtilities.density * 360f / 540f);
            reload();
        }
    }

    private void reload() {
        MessagesController mc = MessagesController.getInstance(account);
        for (Slot s : slots) if (s.avatar != null) s.avatar.onDetachedFromWindow();
        slots.clear();
        float y = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(20);
        float step = AndroidUtilities.dp(60);
        // * pinned chats: the "people" at the top of the rail
        int n = 0;
        for (TLRPC.Dialog d : mc.getDialogs(0)) {
            if (!d.pinned || d instanceof TLRPC.TL_dialogFolder || n >= 8) continue;
            Slot s = new Slot();
            s.dialogId = d.id;
            s.peer = DialogObject.isUserDialog(d.id) ? mc.getUser(d.id) : mc.getChat(-d.id);
            if (s.peer == null) continue;
            s.unread = d.unread_count;
            s.avatar = new ImageReceiver(this);
            s.avatar.setRoundRadius(AndroidUtilities.dp(23));
            s.drawable = new AvatarDrawable();
            s.drawable.setInfo(s.peer);
            s.avatar.setForUserOrChat(s.peer, s.drawable);
            if (isAttachedToWindow()) s.avatar.onAttachedToWindow();
            s.y = y;
            y += step;
            slots.add(s);
            n++;
        }
        y += AndroidUtilities.dp(14);
        // * folders: All, then yours
        ArrayList<MessagesController.DialogFilter> filters = mc.getDialogFilters();
        for (MessagesController.DialogFilter f : filters) {
            Slot s = new Slot();
            s.folderId = f.id;
            s.label = f.isDefault() ? "All" : f.name;
            s.y = y;
            y += step + AndroidUtilities.dp(10);
            slots.add(s);
        }
        if (filters.isEmpty()) {
            Slot s = new Slot();
            s.folderId = 0;
            s.label = "All";
            s.y = y;
            slots.add(s);
        }
        placePill(true);
        invalidate();
    }

    private void placePill(boolean snap) {
        for (Slot s : slots) {
            if (s.folderId >= 0 && (s.folderId == selectedFolder || (selectedFolder == 0 && "All".equals(s.label)))) {
                pillY.to(s.y - AndroidUtilities.dp(6), snap);
                return;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            downY = e.getY();
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP && Math.abs(e.getY() - downY) < AndroidUtilities.dp(10)) {
            for (Slot s : slots) {
                if (e.getY() >= s.y - AndroidUtilities.dp(6) && e.getY() < s.y + AndroidUtilities.dp(54)) {
                    tap(s);
                    return true;
                }
            }
        }
        return true;
    }

    private void tap(Slot s) {
        if (s.folderId >= 0) {
            selectedFolder = s.folderId;
            placePill(false);
            DialogsActivity dialogs = dialogs();
            if (dialogs != null) dialogs.scrollToFolder(s.folderId);
            return;
        }
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(s.dialogId)) args.putLong("user_id", s.dialogId);
        else args.putLong("chat_id", -s.dialogId);
        activity.presentFragment(new ChatActivity(args));
    }

    private DialogsActivity dialogs() {
        if (activity.getActionBarLayout() == null) return null;
        for (BaseFragment f : activity.getActionBarLayout().getFragmentStack()) {
            if (f instanceof DialogsActivity) return (DialogsActivity) f;
            if (f instanceof MainTabsActivity) return ((MainTabsActivity) f).getDialogsActivity();
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), inset = AndroidUtilities.dp(8);
        getLocationInWindow(loc);
        c.save();
        c.translate(inset, AndroidUtilities.statusBarHeight + inset);
        float gw = w - inset * 2, gh = h - AndroidUtilities.statusBarHeight - inset * 2;
        glass.draw(c, gw, gh, gw / 2f, loc[0] + inset, loc[1] + AndroidUtilities.statusBarHeight + inset);
        c.restore();

        // * the pill behind the folder you're in
        float size = AndroidUtilities.dp(46), cx = w / 2f;
        c.save();
        c.translate(cx - AndroidUtilities.dp(29), pillY.v);
        c.scale(1 - stretch * .25f, 1 + stretch, AndroidUtilities.dp(29), AndroidUtilities.dp(36));
        pill.draw(c, AndroidUtilities.dp(58), AndroidUtilities.dp(72), AndroidUtilities.dp(22));
        c.restore();

        boolean dividerDrawn = false;
        for (Slot s : slots) {
            if (s.folderId >= 0 && !dividerDrawn) {
                fill.setColor(Color.argb(46, 255, 255, 255));
                c.drawRect(cx - AndroidUtilities.dp(16), s.y - AndroidUtilities.dp(14), cx + AndroidUtilities.dp(16), s.y - AndroidUtilities.dp(12), fill);
                dividerDrawn = true;
            }
            if (s.avatar != null) {
                s.avatar.setImageCoords(cx - size / 2f, s.y, size, size);
                s.avatar.draw(c);
                if (s.unread > 0) {
                    String t = s.unread > 99 ? "99+" : String.valueOf(s.unread);
                    float bw = Math.max(AndroidUtilities.dp(18), badge.measureText(t) + AndroidUtilities.dp(10));
                    r.set(cx + size / 2f - bw + AndroidUtilities.dp(4), s.y + size - AndroidUtilities.dp(16), cx + size / 2f + AndroidUtilities.dp(4), s.y + size + AndroidUtilities.dp(2));
                    fill.setColor(Color.parseColor("#e5484d"));
                    c.drawRoundRect(r, AndroidUtilities.dp(9), AndroidUtilities.dp(9), fill);
                    c.drawText(t, r.centerX() - badge.measureText(t) / 2f, r.centerY() + AndroidUtilities.dp(3.5f), badge);
                }
            } else {
                r.set(cx - size / 2f, s.y, cx + size / 2f, s.y + size);
                fill.setColor(s.folderId == selectedFolder ? Style.ACCENT : Color.argb(36, 255, 255, 255));
                c.drawRoundRect(r, AndroidUtilities.dp(16), AndroidUtilities.dp(16), fill);
                String letter = s.label.isEmpty() ? "?" : s.label.substring(0, Math.min(2, s.label.length()));
                c.drawText(letter, cx - tileText.measureText(letter) / 2f, s.y + size / 2f + AndroidUtilities.dp(6), tileText);
                String name = TextUtils.ellipsize(s.label, label, w - AndroidUtilities.dp(16), TextUtils.TruncateAt.END).toString();
                c.drawText(name, cx - label.measureText(name) / 2f, s.y + size + AndroidUtilities.dp(14), label);
            }
        }
    }
}
