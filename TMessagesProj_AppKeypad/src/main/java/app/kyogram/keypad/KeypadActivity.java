package app.kyogram.keypad;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Kyogram Keypad: the whole app on one screen of springs, made for the docomo SH-02L's keys. It is the
 * motion prototype (claude.ai artifact "Kyogram Keypad") built natively on Telegram's own engine: the
 * chat list with its gliding pill, a chat that grows out of its row, the menu sheet, pages pushed in from
 * the side, search in the header, and the soft keys that say what they do.
 */
public class KeypadActivity extends Activity implements NotificationCenter.NotificationCenterDelegate {

    private enum Screen { LIST, SEARCH, CHAT, TYPING, MENU, PAGE }

    private static final int REQUEST_CAMERA = 41, REQUEST_PHOTO = 42;

    private int account;
    private MessagesController mc;
    private Screen screen = Screen.LIST;
    private Screen menuFrom = Screen.LIST;
    private int W, H, sb;

    // ── views ──
    private FrameLayout root;
    private FrameLayout listScreen;
    private HeaderView header;
    private TextView hint, empty;
    private ChatListView list;
    private RevealFrame chatScreen;
    private HeadView chatHead;
    private ThreadView thread;
    private GlassFrame reply, composer;
    private TextView replyWho, replyText, mode;
    private EditText input;
    private FrameLayout pageScreen;
    private HeadView pageHead;
    private ChatListView pageList;
    private View dim;
    private GlassFrame sheet;
    private MenuView menu;
    private SoftKeysView soft;

    // ── springs (the prototype's) ──
    private Spring openP, pushP, menuP, replyP;
    private float openTop, openBottom;

    // ── state ──
    private final ArrayList<MessagesController.DialogFilter> folders = new ArrayList<>();
    private int folder;
    private String query = "";
    private long chatId;
    private MessageObject replyTo;
    private String page;
    private SharedPreferences prefs;
    private File photoFile;
    private final HashMap<Integer, Boolean> unknownKeys = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ApplicationLoader.postInitApplication();
        account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            // * signing in uses Telegram's own screens; they come back here once done (LoginActivity)
            startActivity(new Intent(this, LaunchActivity.class));
            finish();
            return;
        }
        mc = MessagesController.getInstance(account);
        prefs = getSharedPreferences("kyogram_keypad", MODE_PRIVATE);
        Spring.reduced = prefs.getBoolean("reducedMotion", false);
        Style.theme(prefs.getBoolean("day", false), prefs.getString("tint", "candy"));

        Window win = getWindow();
        win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        root = new FrameLayout(this) {
            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                if (w > 0 && h > 0 && (w != W || h != H)) post(KeypadActivity.this::build);
            }
        };
        root.setBackgroundColor(Style.BASE);
        setContentView(root);

        for (int e : events()) mc.getNotificationCenter().addObserver(this, e);
        mc.loadDialogs(0, 0, 100, true);
    }

    private int[] events() {
        return new int[]{NotificationCenter.dialogsNeedReload, NotificationCenter.updateInterfaces, NotificationCenter.didReceiveNewMessages,
                NotificationCenter.messageReceivedByServer, NotificationCenter.messageSendError, NotificationCenter.messagesRead, NotificationCenter.dialogFiltersUpdated};
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mc != null) for (int e : events()) mc.getNotificationCenter().removeObserver(this, e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ApplicationLoader.mainInterfacePaused = false;
        ApplicationLoader.mainInterfaceStopped = false;
        if (mc != null) ConnectionsManager.getInstance(account).setAppPaused(false, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ApplicationLoader.mainInterfacePaused = true;
        if (mc != null) ConnectionsManager.getInstance(account).setAppPaused(true, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent();
    }

    private void openFromIntent() {
        long did = getIntent() != null ? getIntent().getLongExtra("dialogId", 0) : 0;
        if (did == 0 || list == null) return;
        getIntent().removeExtra("dialogId");
        if (screen == Screen.CHAT || screen == Screen.TYPING) closeChat(true);
        if (screen == Screen.MENU) closeMenu();
        if (screen == Screen.PAGE) closePage();
        if (screen == Screen.SEARCH) closeSearch();
        ChatListView.Item it = null;
        for (ChatListView.Item i : list.items()) if (i.id == did) it = i;
        if (it != null) list.focusItem(it, true);
        openChat(did, it);
    }

    // ── building the screen (the prototype's positions, with the status bar for its 40px) ──

    private void build() {
        W = root.getWidth();
        H = root.getHeight();
        int res = getResources().getIdentifier("status_bar_height", "dimen", "android");
        sb = res > 0 ? getResources().getDimensionPixelSize(res) : (int) (24 * getResources().getDisplayMetrics().density);
        Style.init(this, W, H, sb);
        root.removeAllViews();
        root.setBackgroundColor(Style.BASE);
        int ui = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (Style.day) ui |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(ui);
        getWindow().setNavigationBarColor(Style.BASE);

        root.addView(new WallView(this), new FrameLayout.LayoutParams(-1, -1));

        // * the chat list
        listScreen = new FrameLayout(this);
        root.addView(listScreen, new FrameLayout.LayoutParams(-1, -1));
        header = new HeaderView(this);
        listScreen.addView(header, box(14, sb + px(6), 14, -1, px(64)));
        header.search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                if (screen != Screen.SEARCH) return;
                query = s.toString();
                reloadList(ChatListView.FROM_NONE, false, false);
            }
        });
        hint = label(15, Style.INK3, Style.regular);
        hint.setGravity(Gravity.CENTER);
        listScreen.addView(hint, box(0, sb + px(76), 0, -1, px(22)));
        list = new ChatListView(this, true);
        listScreen.addView(list, box(0, sb + px(102), 0, px(84), -1));
        empty = label(20, Style.INK3, Style.regular);
        empty.setGravity(Gravity.CENTER);
        empty.setText("No chats found");
        empty.setAlpha(0);
        listScreen.addView(empty, box(0, sb + px(162), 0, -1, px(30)));

        // * a chat
        chatScreen = new RevealFrame(this);
        chatScreen.setVisibility(View.GONE);
        root.addView(chatScreen, new FrameLayout.LayoutParams(-1, -1));
        chatScreen.addView(new WallView(this), new FrameLayout.LayoutParams(-1, -1));
        chatHead = new HeadView(this, true);
        chatScreen.addView(chatHead, box(14, sb + px(6), 14, -1, px(72)));
        thread = new ThreadView(this);
        chatScreen.addView(thread, box(0, sb + px(84), 0, px(162), -1));
        reply = new GlassFrame(this, 20);
        reply.setAlpha(0);
        View bar = new View(this);
        bar.setBackgroundColor(Style.ACCENT_HI);
        reply.addView(bar, new FrameLayout.LayoutParams(px(4), -1));
        replyWho = label(17, Style.INK, Style.bold);
        replyText = label(17, Style.INK2, Style.regular);
        replyText.setSingleLine(true);
        replyText.setEllipsize(TextUtils.TruncateAt.END);
        android.widget.LinearLayout rl = new android.widget.LinearLayout(this);
        rl.setGravity(Gravity.CENTER_VERTICAL);
        rl.setPadding(px(16), 0, px(16), 0);
        rl.addView(replyWho);
        android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(10);
        rl.addView(replyText, tlp);
        reply.addView(rl, new FrameLayout.LayoutParams(-1, -1));
        chatScreen.addView(reply, box(24, -1, 24, px(156), px(52)));
        composer = new GlassFrame(this, 31);
        input = new EditText(this);
        input.setBackground(null);
        input.setTextColor(Style.INK);
        input.setHintTextColor(Style.fg(140));
        input.setHint("Write a message…");
        input.setTypeface(Style.medium);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(20));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_SEND);
        input.setPadding(0, 0, 0, 0);
        input.setFocusable(false);
        input.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
        ilp.leftMargin = px(22);
        ilp.rightMargin = px(12 + 56);
        composer.addView(input, ilp);
        mode = label(15, Style.INK, Style.bold);
        mode.setText("abc");
        mode.setPadding(px(10), px(5), px(10), px(5));
        android.graphics.drawable.GradientDrawable modeBg = new android.graphics.drawable.GradientDrawable();
        modeBg.setColor(Style.fg(36));
        modeBg.setCornerRadius(px(10));
        mode.setBackground(modeBg);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        mlp.rightMargin = px(12);
        composer.addView(mode, mlp);
        chatScreen.addView(composer, box(12, -1, 12, px(92), px(62)));

        // * pages (Care, Tasks, Settings, New message)
        pageScreen = new FrameLayout(this);
        pageScreen.setVisibility(View.GONE);
        root.addView(pageScreen, new FrameLayout.LayoutParams(-1, -1));
        pageScreen.addView(new WallView(this), new FrameLayout.LayoutParams(-1, -1));
        pageHead = new HeadView(this, false);
        pageScreen.addView(pageHead, box(14, sb + px(6), 14, -1, px(72)));
        pageList = new ChatListView(this, false);
        pageScreen.addView(pageList, box(0, sb + px(88), 0, px(84), -1));

        // * the menu
        dim = new View(this);
        dim.setBackgroundColor(Color.argb(128, 10, 8, 18));
        dim.setAlpha(0);
        dim.setVisibility(View.GONE);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        sheet = new GlassFrame(this, 38);
        sheet.setVisibility(View.GONE);
        menu = new MenuView(this);
        FrameLayout.LayoutParams mvl = new FrameLayout.LayoutParams(-1, -1);
        mvl.setMargins(px(14), px(14), px(14), px(14));
        sheet.addView(menu, mvl);
        root.addView(sheet, box(14, -1, 14, px(96), px(28) + px(MenuView.TITLE_H) + px(MenuView.ITEM) * 4));

        soft = new SoftKeysView(this);
        root.addView(soft, box(12, -1, 12, px(12), px(68)));

        // * the springs that drive the screens
        openP = new Spring(0, 260, 27, (p, v) -> applyOpen(p));
        pushP = new Spring(0, 300, 30, (p, v) -> applyPush(p));
        menuP = new Spring(0, 330, 24, (p, v) -> applyMenu(p));
        replyP = new Spring(0, 340, 26, (p, v) -> {
            reply.setAlpha(Math.min(1, p * 1.5f));
            reply.setTranslationY((1 - p) * Style.px(40));
            float s = .94f + .06f * p;
            reply.setScaleX(s);
            reply.setScaleY(s);
        });

        screen = Screen.LIST;
        soft.set("Menu", "OPEN", "Search");
        reloadFolders(true);
        reloadList(ChatListView.FROM_NONE, false, true);
        openFromIntent();
    }

    private int px(float v) {
        return Math.round(Style.px(v));
    }

    /** Left, top, right, bottom (-1 = not set) and height (-1 = fill), as the prototype's CSS boxes. */
    private FrameLayout.LayoutParams box(int left, int top, int right, int bottom, int height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, height > 0 ? height : -1);
        lp.leftMargin = left > 0 && left < 100 ? px(left) : Math.max(0, left);
        lp.rightMargin = right > 0 && right < 100 ? px(right) : Math.max(0, right);
        if (top >= 0) {
            lp.topMargin = top;
            lp.gravity = Gravity.TOP;
        }
        if (bottom >= 0) {
            lp.bottomMargin = bottom;
            if (top < 0) lp.gravity = Gravity.BOTTOM;
        }
        return lp;
    }

    private TextView label(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(size));
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ── the list ──

    private void reloadFolders(boolean snap) {
        folders.clear();
        ArrayList<String> names = new ArrayList<>();
        names.add("All " + unreadCount(null));
        for (MessagesController.DialogFilter f : mc.dialogFilters) {
            if (f.isDefault()) continue;
            folders.add(f);
            names.add(f.name + " " + unreadCount(f));
        }
        if (folder > folders.size()) folder = 0;
        header.setTabs(names, folder, snap);
    }

    private int unreadCount(MessagesController.DialogFilter f) {
        int n = 0;
        AccountInstance ai = AccountInstance.getInstance(account);
        for (TLRPC.Dialog d : mc.getDialogs(0)) {
            if (d instanceof TLRPC.TL_dialogFolder) continue;
            if ((d.unread_count > 0 || d.unread_mark) && (f == null || f.includesDialog(ai, d.id))) n++;
        }
        return n;
    }

    private void reloadList(int from, boolean keepFocus, boolean snap) {
        if (list == null) return;
        ArrayList<ChatListView.Item> items = new ArrayList<>();
        AccountInstance ai = AccountInstance.getInstance(account);
        MessagesController.DialogFilter f = folder > 0 && folder <= folders.size() ? folders.get(folder - 1) : null;
        String q = query.trim().toLowerCase();
        int key = 0;
        for (TLRPC.Dialog d : mc.getDialogs(0)) {
            if (d instanceof TLRPC.TL_dialogFolder) continue;
            ChatListView.Item it = dialogItem(d);
            if (it == null) continue;
            if (!q.isEmpty()) {
                // * search looks in every folder
                if (!it.name.toLowerCase().contains(q)) continue;
            } else if (f != null && !f.includesDialog(ai, d.id)) {
                continue;
            }
            boolean pinned = f == null ? d.pinned : f.pinnedDialogs.indexOfKey(d.id) >= 0;
            if (pinned && q.isEmpty() && key < 9) it.key = ++key;
            items.add(it);
        }
        list.setItems(items, from, keepFocus, snap);
        empty.animate().alpha(items.isEmpty() ? 1 : 0).setDuration(250).start();
        hint.setText(key > 0 ? "◀ ▶ folders · " + (key == 1 ? "1 opens" : "1–" + key + " open") + " pinned chats" : "◀ ▶ folders");
    }

    private ChatListView.Item dialogItem(TLRPC.Dialog d) {
        ChatListView.Item it = new ChatListView.Item();
        it.id = d.id;
        boolean group = false;
        if (DialogObject.isUserDialog(d.id)) {
            TLRPC.User u = mc.getUser(d.id);
            if (u == null) return null;
            it.peer = u;
            it.name = UserObject.isUserSelf(u) ? "Saved Messages" : UserObject.getUserName(u);
        } else if (DialogObject.isChatDialog(d.id)) {
            TLRPC.Chat c = mc.getChat(-d.id);
            if (c == null) return null;
            it.peer = c;
            it.name = c.title;
            group = !org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup(c);
        } else {
            return null;
        }
        it.unread = d.unread_count > 0 ? d.unread_count : d.unread_mark ? 1 : 0;
        it.muted = mc.isDialogMuted(d.id, 0);
        ArrayList<MessageObject> last = mc.dialogMessage.get(d.id);
        MessageObject mo = last != null && !last.isEmpty() ? last.get(0) : null;
        TLRPC.DraftMessage draft = MediaDataController.getInstance(account).getDraft(d.id, 0);
        if (draft != null && !TextUtils.isEmpty(draft.message)) {
            it.preview = ChatListView.draft("Draft:", draft.message);
        } else if (mo != null) {
            it.preview = preview(mo, group);
        } else {
            it.preview = "";
        }
        int date = mo != null ? mo.messageOwner.date : d.last_message_date;
        it.time = date > 0 ? LocaleController.stringForMessageListDate(date) : "";
        return it;
    }

    private CharSequence preview(MessageObject mo, boolean group) {
        CharSequence body;
        if (mo.caption != null && mo.caption.length() > 0) body = mo.caption;
        else if (mo.isPhoto()) body = "Photo";
        else body = mo.messageText != null ? mo.messageText : "";
        String who = null;
        if (mo.isOutOwner()) {
            who = "You";
        } else if (group) {
            TLRPC.User u = mc.getUser(mo.getFromChatId());
            if (u != null) who = !TextUtils.isEmpty(u.first_name) ? u.first_name : u.last_name;
        }
        if (mo.messageOwner instanceof TLRPC.TL_messageService) who = null;
        return who != null ? who + ": " + body : body;
    }

    private void switchFolder(int d) {
        int n = header.tabCount();
        int next = Math.max(0, Math.min(n - 1, folder + d));
        if (next == folder) return;
        folder = next;
        header.select(folder, false);
        reloadList(d > 0 ? ChatListView.FROM_RIGHT : ChatListView.FROM_LEFT, false, false);
    }

    // ── a chat: grows out of its row ──

    private void openChat(long did, ChatListView.Item from) {
        chatId = did;
        float top = from != null ? list.getTop() + list.rowTop(from) : H / 2f - Style.px(47);
        openTop = Math.max(sb, top);
        openBottom = Math.min(H - Style.px(80), top + Style.px(ChatListView.ROW));
        boolean group = DialogObject.isChatDialog(did);
        TLObject peer = group ? mc.getChat(-did) : mc.getUser(did);
        chatHead.set(peer, from != null ? from.name : peerName(peer), status(did));
        thread.setGroup(group && !org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup(mc.getChat(-did)));
        thread.setMessages(new ArrayList<>());
        loadHistory(did);
        chatScreen.setVisibility(View.VISIBLE);
        screen = Screen.CHAT;
        soft.set("Back", "REPLY", "Options");
        openP.to(0, true);
        openP.to(1);
        thread.showRing(true);
    }

    private String peerName(TLObject peer) {
        if (peer instanceof TLRPC.User) return UserObject.getUserName((TLRPC.User) peer);
        if (peer instanceof TLRPC.Chat) return ((TLRPC.Chat) peer).title;
        return "";
    }

    private String status(long did) {
        CharSequence printing = mc.getPrintingString(did, 0, false);
        if (printing != null && printing.length() > 0) return printing.toString();
        if (DialogObject.isUserDialog(did)) {
            TLRPC.User u = mc.getUser(did);
            return u != null ? LocaleController.formatUserStatus(account, u) : "";
        }
        TLRPC.Chat c = mc.getChat(-did);
        return c != null && c.participants_count > 0 ? c.participants_count + (c.participants_count == 1 ? " member" : " members") : "";
    }

    private void closeChat(boolean snap) {
        ChatListView.Item it = null;
        for (ChatListView.Item i : list.items()) if (i.id == chatId) it = i;
        if (it != null) {
            list.focusItem(it, true);
            float top = list.getTop() + list.rowTop(it);
            openTop = Math.max(sb, top);
            openBottom = Math.min(H - Style.px(80), top + Style.px(ChatListView.ROW));
        }
        cancelTyping(true);
        hideKeyboard();
        screen = Screen.LIST;
        chatId = 0;
        soft.set("Menu", "OPEN", "Search");
        openP.to(0, snap);
    }

    private void applyOpen(float p) {
        float q = Math.max(0, Math.min(1.04f, p));
        float top = openTop * (1 - q), bottom = (H - openBottom) * (1 - q), side = Style.px(10) * (1 - q);
        float radius = Style.px(30) * (1 - q) + Style.px(6) * q;
        chatScreen.setClip(side, top, W - side, H - bottom, radius);
        chatScreen.setAlpha(Math.min(1, p * 3));
        thread.setTranslationY((1 - q) * Style.px(40));
        float s = 1 - .06f * q;
        listScreen.setScaleX(s);
        listScreen.setScaleY(s);
        listScreen.setAlpha(1 - .65f * Math.min(1, q));
        if (p <= .001f && screen != Screen.CHAT && screen != Screen.TYPING && !(screen == Screen.MENU && (menuFrom == Screen.CHAT))) {
            chatScreen.setVisibility(View.GONE);
        }
    }

    private void loadHistory(long did) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = mc.getInputPeer(did);
        req.limit = 40;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages) || chatId != did) return;
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            mc.putUsers(res.users, false);
            mc.putChats(res.chats, false);
            ArrayList<MessageObject> objs = new ArrayList<>();
            HashMap<Integer, MessageObject> byId = new HashMap<>();
            for (int i = res.messages.size() - 1; i >= 0; i--) {
                TLRPC.Message m = res.messages.get(i);
                MessageObject mo = new MessageObject(account, m, true, false);
                objs.add(mo);
                byId.put(m.id, mo);
            }
            for (MessageObject mo : objs) {
                if (mo.messageOwner.reply_to != null && mo.messageOwner.reply_to.reply_to_msg_id != 0) {
                    mo.replyMessageObject = byId.get(mo.messageOwner.reply_to.reply_to_msg_id);
                }
            }
            updateReadOutbox();
            thread.setMessages(objs);
            markRead(did, objs);
        }));
    }

    private void markRead(long did, ArrayList<MessageObject> objs) {
        int max = 0, date = 0;
        for (MessageObject mo : objs) {
            if (!mo.isOutOwner() && mo.getId() > max) {
                max = mo.getId();
                date = mo.messageOwner.date;
            }
        }
        if (max > 0) mc.markDialogAsRead(did, max, max, date, false, 0, 0, true, 0);
    }

    private void updateReadOutbox() {
        TLRPC.Dialog d = mc.dialogs_dict.get(chatId);
        thread.readOutboxMax = d != null ? d.read_outbox_max_id : 0;
    }

    // ── typing a message / a reply: the quote slides up out of the message box ──

    private void startTyping(MessageObject to) {
        replyTo = to;
        if (to != null) {
            String who = to.isOutOwner() ? "You" : chatHead.name;
            if (DialogObject.isChatDialog(chatId) && !to.isOutOwner()) {
                TLRPC.User u = mc.getUser(to.getFromChatId());
                if (u != null) who = UserObject.getFirstName(u);
            }
            replyWho.setText(who);
            replyText.setText(to.caption != null && to.caption.length() > 0 ? to.caption : to.isPhoto() ? "Photo" : to.messageText);
            replyP.to(1);
        }
        thread.showRing(false);
        screen = Screen.TYPING;
        soft.set("Cancel", "SEND", "");
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    private void cancelTyping(boolean snap) {
        replyTo = null;
        if (replyP != null) replyP.to(0, snap);
        input.setText("");
        input.clearFocus();
        input.setFocusable(false);
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void backToChat() {
        screen = Screen.CHAT;
        soft.set("Back", "REPLY", "Options");
        thread.showRing(true);
        thread.focusLast();
    }

    private void send() {
        String text = input.getText().toString().trim();
        MessageObject to = replyTo;
        cancelTyping(false);
        if (!text.isEmpty()) {
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, chatId, to, null, null, true, null, null, null, true, 0, 0, null, false));
        }
        backToChat();
    }

    // ── the menu: a glass sheet springs up ──

    private void openMenu() {
        menuFrom = screen;
        ArrayList<MenuView.Entry> e = new ArrayList<>();
        if (menuFrom == Screen.CHAT) {
            e.add(new MenuView.Entry("photo", "Take photo", "◉", "#ff8fb1", "#e0457a"));
        } else {
            e.add(new MenuView.Entry("new", "New message", "✎", "#a593ff", "#7a5ae6"));
        }
        e.add(new MenuView.Entry("care", "Care", "☺", "#5fd1a0", "#2a9a6a"));
        e.add(new MenuView.Entry("tasks", "Tasks", "✓", "#ffb46a", "#e07a2a"));
        e.add(new MenuView.Entry("settings", "Settings", "⚙", "#c0c0d0", "#80809a"));
        menu.setEntries(e);
        screen = Screen.MENU;
        thread.showRing(false);
        sheet.setVisibility(View.VISIBLE);
        dim.setVisibility(View.VISIBLE);
        sheet.setPivotX(sheet.getWidth() * .2f);
        sheet.setPivotY(sheet.getHeight());
        soft.set("Close", "SELECT", "");
        menuP.to(1);
    }

    private void closeMenu() {
        screen = menuFrom;
        menuP.to(0);
        if (screen == Screen.CHAT) {
            soft.set("Back", "REPLY", "Options");
            thread.showRing(true);
        } else if (screen == Screen.PAGE) {
            soft.set("Back", pageOkLabel(), "");
        } else {
            soft.set("Menu", "OPEN", "Search");
        }
    }

    private void applyMenu(float p) {
        float q = Math.max(0, p);
        sheet.setAlpha(Math.min(1, q * 2));
        sheet.setTranslationY((1 - q) * Style.px(70));
        float s = .86f + .14f * q;
        sheet.setScaleX(s);
        sheet.setScaleY(s);
        dim.setAlpha(Math.min(1, q));
        if (q <= .001f && screen != Screen.MENU) {
            sheet.setVisibility(View.GONE);
            dim.setVisibility(View.GONE);
        }
    }

    private void choose(MenuView.Entry e, boolean viaKey) {
        if (e == null) return;
        Runnable go = () -> {
            menuP.to(0);
            if (e.id.equals("photo")) {
                screen = Screen.CHAT;
                soft.set("Back", "REPLY", "Options");
                thread.showRing(true);
                takePhoto();
                return;
            }
            if (menuFrom == Screen.CHAT) {
                screen = Screen.CHAT;
                closeChat(false);
            }
            screen = Screen.LIST;
            openPage(e.id);
        };
        // * a number key: the pill glides to the item first (prototype: 140ms)
        if (viaKey) root.postDelayed(go, 140);
        else go.run();
    }

    // ── pages: pushed in from the side ──

    private void openPage(String id) {
        page = id;
        ArrayList<ChatListView.Item> items = new ArrayList<>();
        switch (id) {
            case "care":
                pageHead.setTile("☺", "#5fd1a0", "#2a9a6a", "Care", "DroidShop · examples");
                String[][] t = {
                        {"#1046 · iPhone 13 thay pin", "Anh Tuấn · 0912 ••• 431", "new", "New"},
                        {"#1044 · Galaxy S22 màn hình", "Chị Mai · chờ linh kiện", "warn", "Waiting"},
                        {"#1041 · iPad Air sạc", "Anh Long · đang sửa", "warn", "Repairing"},
                        {"#1038 · Pixel 7 camera", "Chị Hà · khách đã nhận", "ok", "Done"},
                        {"#1035 · iPhone 11 Face ID", "Anh Bảo · đã báo giá", "new", "Quoted"}};
                for (int i = 0; i < t.length; i++) {
                    ChatListView.Item it = new ChatListView.Item();
                    it.id = 1000 + i;
                    it.name = t[i][0];
                    it.preview = t[i][1];
                    it.chip = t[i][3];
                    chip(it, t[i][2]);
                    items.add(it);
                }
                break;
            case "tasks":
                pageHead.setTile("✓", "#ffb46a", "#e07a2a", "Tasks", "Morning shift");
                JSONArray tasks = tasks();
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject o = tasks.optJSONObject(i);
                    ChatListView.Item it = new ChatListView.Item();
                    it.id = 2000 + i;
                    it.name = o.optString("name");
                    it.checkable = true;
                    it.done = o.optBoolean("done");
                    items.add(it);
                }
                break;
            case "settings":
                pageHead.setTile("⚙", "#c0c0d0", "#80809a", "Settings", "Kyogram Keypad");
                TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
                items.add(setting(3000, "Motion", Spring.reduced ? "Reduced" : "Full"));
                items.add(setting(3001, "Theme", Style.day ? "Day" : "Night"));
                items.add(setting(3002, "Glass tint", Character.toUpperCase(Style.tint.charAt(0)) + Style.tint.substring(1)));
                items.add(setting(3003, "Account", me != null && !TextUtils.isEmpty(me.username) ? "@" + me.username : me != null ? UserObject.getUserName(me) : ""));
                items.add(setting(3004, "Version", "Keypad " + org.telegram.messenger.BuildVars.BUILD_VERSION_STRING));
                break;
            case "new":
                pageHead.setTile("✎", "#a593ff", "#7a5ae6", "New message", "Pick a contact");
                for (TLRPC.TL_contact c : ContactsController.getInstance(account).contacts) {
                    TLRPC.User u = mc.getUser(c.user_id);
                    if (u == null || UserObject.isUserSelf(u)) continue;
                    ChatListView.Item it = new ChatListView.Item();
                    it.id = u.id;
                    it.name = UserObject.getUserName(u);
                    it.preview = LocaleController.formatUserStatus(account, u);
                    items.add(it);
                }
                break;
        }
        pageList.clear();
        pageList.setItems(items, ChatListView.FROM_NONE, false, true);
        screen = Screen.PAGE;
        pageScreen.setVisibility(View.VISIBLE);
        soft.set("Back", pageOkLabel(), "");
        pushP.to(0, true);
        pushP.to(1);
    }

    private String pageOkLabel() {
        return "tasks".equals(page) ? "CHECK" : "OPEN";
    }

    private void chip(ChatListView.Item it, String kind) {
        switch (kind) {
            case "ok": it.chipColor = Style.OK; it.chipBg = Color.argb(46, 127, 224, 168); break;
            case "warn": it.chipColor = Style.WARN; it.chipBg = Color.argb(46, 255, 191, 106); break;
            default: it.chipColor = Style.ACCENT_HI; it.chipBg = Color.argb(56, 173, 156, 255); break;
        }
    }

    private ChatListView.Item setting(long id, String name, String value) {
        ChatListView.Item it = new ChatListView.Item();
        it.id = id;
        it.name = name;
        it.value = value;
        return it;
    }

    private JSONArray tasks() {
        try {
            String saved = prefs.getString("tasks", null);
            if (saved != null) return new JSONArray(saved);
        } catch (Exception ignore) {
        }
        JSONArray a = new JSONArray();
        String[] names = {"Open the shop", "Count the cash drawer", "Reply to Care tickets", "Check deliveries on Ahamove", "Post today's deals", "Clean the counter"};
        for (int i = 0; i < names.length; i++) {
            try {
                a.put(new JSONObject().put("name", names[i]).put("done", i < 2));
            } catch (Exception ignore) {
            }
        }
        return a;
    }

    private void pageOk() {
        ChatListView.Item it = pageList.focusedItem();
        if (it == null) return;
        if ("tasks".equals(page)) {
            JSONArray a = tasks();
            int i = (int) (it.id - 2000);
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                try {
                    o.put("done", !o.optBoolean("done"));
                } catch (Exception ignore) {
                }
                prefs.edit().putString("tasks", a.toString()).apply();
            }
            ArrayList<ChatListView.Item> items = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                ChatListView.Item n = new ChatListView.Item();
                n.id = 2000 + k;
                n.name = a.optJSONObject(k).optString("name");
                n.checkable = true;
                n.done = a.optJSONObject(k).optBoolean("done");
                items.add(n);
            }
            pageList.setItems(items, ChatListView.FROM_NONE, true, false);
        } else if ("settings".equals(page) && it.id == 3000) {
            Spring.reduced = !Spring.reduced;
            prefs.edit().putBoolean("reducedMotion", Spring.reduced).apply();
            openPageAgain();
        } else if ("settings".equals(page) && (it.id == 3001 || it.id == 3002)) {
            // * Theme flips Night/Day; Glass tint steps Candy → Ocean → Sunset → Mono; everything repaints
            String[] tints = {"candy", "ocean", "sunset", "mono"};
            boolean d = Style.day;
            String t = Style.tint;
            if (it.id == 3001) d = !d;
            else {
                int i = 0;
                for (int k = 0; k < tints.length; k++) if (tints[k].equals(t)) i = k;
                t = tints[(i + 1) % tints.length];
            }
            prefs.edit().putBoolean("day", d).putString("tint", t).apply();
            Style.theme(d, t);
            long focusId = it.id;
            build();
            openPage("settings");
            pushP.to(1, true);
            for (ChatListView.Item i : pageList.items()) if (i.id == focusId) pageList.focusItem(i, true);
        } else if ("new".equals(page)) {
            long did = it.id;
            closePage();
            ChatListView.Item row = null;
            for (ChatListView.Item i : list.items()) if (i.id == did) row = i;
            openChat(did, row);
        } else {
            pageList.bounce();
        }
    }

    private void openPageAgain() {
        ChatListView.Item f = pageList.focusedItem();
        String p = page;
        openPage(p);
        pushP.to(1, true);
        if (f != null) for (ChatListView.Item i : pageList.items()) if (i.id == f.id) pageList.focusItem(i, true);
    }

    private void closePage() {
        screen = Screen.LIST;
        soft.set("Menu", "OPEN", "Search");
        pushP.to(0);
    }

    private void applyPush(float p) {
        float q = Math.max(0, Math.min(1.02f, p));
        pageScreen.setAlpha(Math.min(1, p * 4));
        pageScreen.setTranslationX((1 - q) * W);
        listScreen.setTranslationX(-q * Style.px(120));
        float s = 1 - .04f * q;
        listScreen.setScaleX(s);
        listScreen.setScaleY(s);
        listScreen.setAlpha(1 - .6f * Math.min(1, q));
        if (p <= .001f && screen != Screen.PAGE) pageScreen.setVisibility(View.GONE);
    }

    // ── search: the header turns into a field ──

    private void openSearch() {
        screen = Screen.SEARCH;
        header.setSearching(true);
        hint.animate().alpha(0).setDuration(200).start();
        soft.set("Close", "OPEN", "");
        root.postDelayed(() -> {
            header.search.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(header.search, 0);
        }, 60);
    }

    private void closeSearch() {
        screen = Screen.LIST;
        header.setSearching(false);
        hint.animate().alpha(1).setDuration(200).start();
        header.search.setText("");
        header.search.clearFocus();
        hideKeyboard();
        query = "";
        reloadList(ChatListView.FROM_NONE, true, false);
        soft.set("Menu", "OPEN", "Search");
    }

    // ── a photo from the camera ──

    private void takePhoto() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        try {
            photoFile = AndroidUtilities.generatePicturePath();
            if (photoFile == null) return;
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            Uri uri = FileProvider.getUriForFile(this, ApplicationLoader.getApplicationId() + ".provider", photoFile);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_PHOTO);
        } catch (Exception e) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) takePhoto();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PHOTO && resultCode == RESULT_OK && photoFile != null && photoFile.exists() && chatId != 0) {
            AndroidUtilities.addMediaToGallery(photoFile.getAbsolutePath());
            SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(account), photoFile.getAbsolutePath(), null, chatId, null, null, null, null, null, null, null, 0, null, true, 0, 0, null);
            photoFile = null;
        }
    }

    // ── keys ──

    private static final int K_UP = 1, K_DOWN = 2, K_LEFT = 3, K_RIGHT = 4, K_OK = 5, K_SOFT_L = 6, K_SOFT_R = 7, K_BACK = 8;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (list == null) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean typing = screen == Screen.TYPING || screen == Screen.SEARCH;
        int k = 0, digit = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: k = K_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = K_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: k = typing ? 0 : K_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = typing ? 0 : K_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: k = K_OK; break;
            case KeyEvent.KEYCODE_SOFT_LEFT:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_BUTTON_L1: k = K_SOFT_L; break;
            case KeyEvent.KEYCODE_SOFT_RIGHT:
            case KeyEvent.KEYCODE_F2:
            case KeyEvent.KEYCODE_BUTTON_R1: k = K_SOFT_R; break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_CLEAR: k = K_BACK; break;
            case KeyEvent.KEYCODE_DEL:
                // * クリア deletes while typing; with nothing left, it goes back
                EditText field = screen == Screen.TYPING ? input : screen == Screen.SEARCH ? header.search : null;
                if (field == null || field.length() == 0) k = K_BACK;
                break;
            default:
                if (!typing && code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) digit = code - KeyEvent.KEYCODE_0;
                break;
        }
        if (k == 0 && digit == 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !typing && event.getRepeatCount() == 0 && code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
                    && code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_POWER && unknownKeys.put(code, true) == null) {
                // * an unmapped key: say which, so the phone's soft keys can be mapped if they differ
                Toast.makeText(this, "Key " + code + " (" + KeyEvent.keyCodeToString(code) + ")", Toast.LENGTH_SHORT).show();
            }
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == K_UP || k == K_DOWN || event.getRepeatCount() == 0) press(k, digit);
        }
        return true;
    }

    private void press(int k, int digit) {
        if (k == K_SOFT_L) soft.press(SoftKeysView.KEY_LEFT);
        else if (k == K_SOFT_R) soft.press(SoftKeysView.KEY_RIGHT);
        else if (k == K_OK) soft.press(SoftKeysView.KEY_OK);
        switch (screen) {
            case LIST:
                if (k == K_UP) list.moveFocus(-1, false);
                else if (k == K_DOWN) list.moveFocus(1, false);
                else if (k == K_LEFT) switchFolder(-1);
                else if (k == K_RIGHT) switchFolder(1);
                else if (k == K_OK) {
                    ChatListView.Item it = list.focusedItem();
                    if (it != null) openChat(it.id, it);
                } else if (k == K_SOFT_L) openMenu();
                else if (k == K_SOFT_R) openSearch();
                else if (k == K_BACK) moveTaskToBack(true);
                else if (digit > 0) {
                    ChatListView.Item it = list.itemWithKey(digit);
                    if (it != null) {
                        list.focusItem(it, false);
                        root.postDelayed(() -> openChat(it.id, it), 160);
                    }
                }
                break;
            case SEARCH:
                if (k == K_UP) list.moveFocus(-1, false);
                else if (k == K_DOWN) list.moveFocus(1, false);
                else if (k == K_OK) {
                    ChatListView.Item it = list.focusedItem();
                    if (it != null) {
                        long id = it.id;
                        closeSearch();
                        ChatListView.Item row = null;
                        for (ChatListView.Item i : list.items()) if (i.id == id) row = i;
                        if (row != null) list.focusItem(row, true);
                        openChat(id, row);
                    }
                } else if (k == K_SOFT_L || k == K_BACK) closeSearch();
                break;
            case CHAT:
                if (k == K_UP) thread.move(-1);
                else if (k == K_DOWN) {
                    if (!thread.atLast()) thread.move(1);
                    else startTyping(null);
                } else if (k == K_OK) startTyping(thread.focused());
                else if (k == K_SOFT_L || k == K_BACK) closeChat(false);
                else if (k == K_SOFT_R) openMenu();
                break;
            case TYPING:
                if (k == K_OK) send();
                else if (k == K_SOFT_L || k == K_BACK) {
                    cancelTyping(false);
                    backToChat();
                }
                break;
            case MENU:
                if (k == K_UP) menu.move(-1, false);
                else if (k == K_DOWN) menu.move(1, false);
                else if (k == K_OK) choose(menu.focused(), false);
                else if (digit > 0) choose(menu.byKey(digit), true);
                else if (k == K_SOFT_L || k == K_BACK) closeMenu();
                break;
            case PAGE:
                if (k == K_UP) pageList.moveFocus(-1, false);
                else if (k == K_DOWN) pageList.moveFocus(1, false);
                else if (k == K_OK) pageOk();
                else if (k == K_SOFT_L || k == K_BACK) closePage();
                break;
        }
    }

    // ── Telegram's updates ──

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (list == null) return;
        if (id == NotificationCenter.dialogsNeedReload || id == NotificationCenter.dialogFiltersUpdated) {
            reloadFolders(false);
            reloadList(ChatListView.FROM_NONE, true, false);
            if (chatId != 0) {
                updateReadOutbox();
                thread.refresh();
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            reloadFolders(false);
            reloadList(ChatListView.FROM_NONE, true, false);
            if (chatId != 0) {
                if ((mask & (MessagesController.UPDATE_MASK_USER_PRINT | MessagesController.UPDATE_MASK_STATUS)) != 0) {
                    CharSequence printing = mc.getPrintingString(chatId, 0, false);
                    thread.setTyping(printing != null && printing.length() > 0);
                    chatHead.setStatus(status(chatId));
                }
                if ((mask & MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE) != 0) {
                    updateReadOutbox();
                    thread.refresh();
                }
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long did = (Long) args[0];
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (did == chatId && !scheduled) {
                @SuppressWarnings("unchecked") ArrayList<MessageObject> objs = (ArrayList<MessageObject>) args[1];
                for (MessageObject mo : objs) thread.append(mo);
                if (screen == Screen.CHAT) thread.showRing(true);
                markRead(did, objs);
            }
        } else if (id == NotificationCenter.messageReceivedByServer || id == NotificationCenter.messageSendError || id == NotificationCenter.messagesRead) {
            if (chatId != 0) {
                updateReadOutbox();
                thread.refresh();
            }
        }
    }

    // ── small views ──

    /** The colours behind everything (painted once, Style.wall). */
    private static final class WallView extends View {
        WallView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (Style.wall != null) c.drawBitmap(Style.wall, 0, 0, null);
        }
    }

    /** A screen that can be revealed from a rounded rectangle (a chat growing out of its row). */
    private static final class RevealFrame extends FrameLayout {
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private float radius;
        private boolean clipping;

        RevealFrame(Context c) {
            super(c);
        }

        void setClip(float l, float t, float r, float b, float radius) {
            rect.set(l, t, r, b);
            this.radius = radius;
            clipping = l > .5f || t > .5f || b < getHeight() - .5f;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (clipping) {
                clip.reset();
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                super.dispatchDraw(canvas);
                canvas.restore();
            } else {
                super.dispatchDraw(canvas);
            }
        }
    }

    /** A chat's (or a page's) glass header: avatar or icon tile, name, and a line under it. */
    private final class HeadView extends GlassFrame {
        String name = "";
        private String status = "", tile;
        private final boolean chat;
        private final ImageReceiver avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextPaint namePaint = Style.text(Style.bold, 23, Style.INK);
        private final TextPaint statusPaint;
        private final TextPaint tilePaint = Style.text(Style.bold, 24, Color.WHITE);
        private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final View content;

        HeadView(Context context, boolean chat) {
            super(context, 36);
            this.chat = chat;
            statusPaint = Style.text(Style.regular, 16, chat ? Style.OK : Style.INK3);
            content = new View(context) {
                @Override
                protected void onDraw(Canvas c) {
                    drawContent(c);
                }

                @Override
                protected void onAttachedToWindow() {
                    super.onAttachedToWindow();
                    if (avatar != null) avatar.onAttachedToWindow();
                }

                @Override
                protected void onDetachedFromWindow() {
                    super.onDetachedFromWindow();
                    if (avatar != null) avatar.onDetachedFromWindow();
                }
            };
            avatar = chat ? new ImageReceiver(content) : null;
            if (avatar != null) avatar.setRoundRadius((int) Style.px(28));
            addView(content, new FrameLayout.LayoutParams(-1, -1));
        }

        void set(TLObject peer, String name, String status) {
            this.name = name != null ? name : "";
            this.status = status != null ? status : "";
            if (avatar != null && peer != null) {
                avatarDrawable.setInfo(peer);
                avatar.setForUserOrChat(peer, avatarDrawable);
            }
            content.invalidate();
        }

        void setStatus(String s) {
            status = s != null ? s : "";
            content.invalidate();
        }

        void setTile(String glyph, String from, String to, String name, String sub) {
            tile = glyph;
            tileFill.setShader(new LinearGradient(0, 0, Style.px(56), Style.px(56), Color.parseColor(from), Color.parseColor(to), Shader.TileMode.CLAMP));
            this.name = name;
            this.status = sub;
            content.invalidate();
        }

        private void drawContent(Canvas c) {
            float h = c.getHeight() > 0 ? content.getHeight() : 0, s = Style.px(56), x = Style.px(8), y = (h - s) / 2f;
            if (chat) {
                avatar.setImageCoords(x, y, s, s);
                avatar.draw(c);
            } else if (tile != null) {
                c.save();
                c.translate(x, y);
                c.drawRoundRect(0, 0, s, s, Style.px(18), Style.px(18), tileFill);
                Paint.FontMetrics fm = tilePaint.getFontMetrics();
                c.drawText(tile, (s - tilePaint.measureText(tile)) / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, tilePaint);
                c.restore();
            }
            float tx = x + s + Style.px(14), maxW = content.getWidth() - tx - Style.px(18);
            String n = TextUtils.ellipsize(name, namePaint, maxW, TextUtils.TruncateAt.END).toString();
            String st = TextUtils.ellipsize(status, statusPaint, maxW, TextUtils.TruncateAt.END).toString();
            Paint.FontMetrics nm = namePaint.getFontMetrics(), sm = statusPaint.getFontMetrics();
            float nh = nm.descent - nm.ascent, sh = sm.descent - sm.ascent, total = nh + Style.px(2) + sh;
            float top = (h - total) / 2f;
            c.drawText(n, tx, top - nm.ascent, namePaint);
            c.drawText(st, tx, top + nh + Style.px(2) - sm.ascent, statusPaint);
        }
    }
}
