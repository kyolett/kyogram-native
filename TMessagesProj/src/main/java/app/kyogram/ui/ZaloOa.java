package app.kyogram.ui;

import android.content.Context;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kyogram ↔ Zalo OA, as desktop Kyogram does it (desktop/zaloOa.js): the same calls the Zalo OA
 * website (oa.zalo.me/chat) makes for itself, with the sign-in you make in its page here (a WebView;
 * its cookies stay in this app). Calls are POSTs with ?platform=web and a form body params=<the call's
 * fields as JSON, AES-CBC encrypted with the key "ek" that /user/login-info hands the signed-in page,
 * and a fixed IV>; answers {error_code, error_message, data}, data encrypted the same way.
 * Nothing is sent to Zalo but what you ask for; the key is kept in memory only.
 */
public final class ZaloOa {

    public static final String ORIGIN = "https://oa.zalo.me";
    private static final String READ = "/chat/api/v3", SEND = "/chatv2";
    private static final byte[] IV = {11, 22, 33, 44, 99, 88, 77, 66, 11, 22, 33, 44, 99, 88, 77, 66};

    public static final class Chat {
        public String convId, customerId, name, avatar, last;
        public boolean lastFromOa;
        public long time;
        public int unread;
    }

    public static final class Message {
        public String id, text, type, staff;
        public boolean fromOa;
        public long time;
    }

    public interface Callback<T> {
        void done(T result, String error);
    }

    public static class SignedOut extends Exception {
        SignedOut() {
            super("Not signed in to Zalo OA");
        }
    }

    private static String oaId, ek, name;
    private static boolean encrypt;
    private static String userAgent;

    private ZaloOa() {
    }

    public static void init(Context c) {
        if (userAgent == null) {
            try {
                userAgent = WebSettings.getDefaultUserAgent(c);
            } catch (Throwable e) {
                userAgent = "Mozilla/5.0 (Linux; Android)";
            }
        }
    }

    public static String accountName() {
        return name;
    }

    // ── requests ──

    private static JSONObject post(String path, String body) throws Exception {
        URL url = new URL(ORIGIN + path + (path.contains("?") ? "&" : "?") + "platform=web");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setRequestProperty("Referer", ORIGIN + "/chat");
        conn.setRequestProperty("User-Agent", userAgent);
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
        OutputStream os = conn.getOutputStream();
        os.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        try {
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            if (code == 401 || code == 403) throw new SignedOut();
            throw new Exception("Zalo answered with an error (HTTP " + code + ")");
        }
    }

    private static Cipher cipher(int mode) throws Exception {
        byte[] key = ek.getBytes(StandardCharsets.UTF_8);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(IV));
        return c;
    }

    private static void account(boolean fresh) throws Exception {
        if (!fresh && oaId != null) return;
        JSONObject json = post(READ + "/user/login-info", "");
        JSONObject data = json.optJSONObject("data");
        if (data == null || !data.optBoolean("logged") || data.optString("oaId", "").isEmpty()) {
            oaId = null;
            throw new SignedOut();
        }
        oaId = data.optString("oaId");
        ek = data.optString("ek", null);
        encrypt = data.optBoolean("encrypt") && ek != null && !ek.isEmpty();
        name = data.optString("displayName", "");
    }

    private static final int[] SIGNED_OUT_CODES = {-201, -202, -1001, -14001};

    private static Object call(String base, String path, JSONObject params, boolean retried) throws Exception {
        account(retried);
        if (!params.has("oaId")) params.put("oaId", oaId);
        String json = params.toString();
        String packed = encrypt ? Base64.encodeToString(cipher(Cipher.ENCRYPT_MODE).doFinal(json.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP) : json;
        JSONObject res = post(base + path, "params=" + URLEncoder.encode(packed, "UTF-8"));
        int code = res.optInt("error_code", 0);
        if (code < 0) {
            for (int c : SIGNED_OUT_CODES) if (c == code && !retried) return call(base, path, params, true);
            throw new Exception(res.optString("error_message", "Zalo reported an error"));
        }
        Object data = res.opt("data");
        if (encrypt && data instanceof String && !((String) data).isEmpty()) {
            try {
                String plain = new String(cipher(Cipher.DECRYPT_MODE).doFinal(Base64.decode((String) data, Base64.DEFAULT)), StandardCharsets.UTF_8);
                data = plain.trim().startsWith("[") ? new JSONArray(plain) : new JSONObject(plain);
            } catch (Exception e) {
                if (!retried) return call(base, path, params, true);
                throw new Exception("Zalo's answer couldn't be read");
            }
        }
        return data;
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private static <T> void async(Work<T> work, Callback<T> cb) {
        Utilities.globalQueue.postRunnable(() -> {
            T result = null;
            String error = null;
            try {
                result = work.run();
            } catch (SignedOut e) {
                error = "signed-out";
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final T r = result;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> cb.done(r, err));
        });
    }

    // ── what the Zalo OA tab asks for ──

    public static void status(Callback<Boolean> cb) {
        async(() -> {
            try {
                account(true);
                return true;
            } catch (SignedOut e) {
                return false;
            }
        }, cb);
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !v.equals("null")) return v;
        }
        return null;
    }

    /** The site's message kinds: 0 sticker, 1 link, 2 text, 3 photo, 4 voice, 5 location, 6 GIF, 9 card, 10 video, 13 file. */
    public static String kind(int type) {
        switch (type) {
            case 2: return "text";
            case 3: case 33: return "photo";
            case 0: return "sticker";
            case 6: return "gif";
            case 4: return "voice";
            case 10: return "video";
            case 1: case 8: case 11: return "link";
            case 13: return "file";
            case 5: return "location";
            case 9: return "card";
            default: return "other";
        }
    }

    public static String label(String kind) {
        switch (kind) {
            case "photo": return "📷 Ảnh";
            case "sticker": return "Sticker";
            case "gif": return "GIF";
            case "voice": return "🎤 Tin nhắn thoại";
            case "video": return "🎬 Video";
            case "file": return "📎 Tệp";
            case "location": return "📍 Vị trí";
            case "card": return "Danh thiếp";
            default: return "";
        }
    }

    public static void chats(Callback<ArrayList<Chat>> cb) {
        async(() -> {
            JSONObject list = (JSONObject) call(READ, "/conversation/get-list", new JSONObject().put("limit", 40).put("offset", 0).put("type", 2), false);
            JSONArray convs = list != null ? list.optJSONArray("conversations") : null;
            ArrayList<Chat> out = new ArrayList<>();
            if (convs == null) return out;
            JSONArray ids = new JSONArray();
            for (int i = 0; i < convs.length(); i++) ids.put(convs.getJSONObject(i).optString("convId"));
            JSONObject previews = null;
            try {
                JSONObject p = (JSONObject) call(READ, "/conversation/get-preview-message", new JSONObject().put("convIds", ids), false);
                previews = p != null ? p.optJSONObject("previewMessage") : null;
            } catch (Exception ignore) {
            }
            for (int i = 0; i < convs.length(); i++) {
                JSONObject conv = convs.getJSONObject(i);
                Chat c = new Chat();
                c.convId = conv.optString("convId");
                if (c.convId.isEmpty()) continue;
                c.customerId = conv.optString("userId", "");
                c.name = str(conv, "aliasName", "displayName");
                if (c.name == null) c.name = "Khách";
                c.avatar = str(conv, "avatar");
                c.unread = conv.optInt("totalUnread");
                c.time = conv.optLong("timestamp");
                JSONObject pv = previews != null ? previews.optJSONObject(c.convId) : null;
                if (pv != null) {
                    c.lastFromOa = pv.optInt("srcType", 1) == 0;
                    String k = kind(pv.optInt("messageType", 2));
                    String t = str(pv, "message");
                    c.last = k.equals("text") ? (t != null ? t : "") : label(k);
                } else {
                    c.last = "";
                }
                out.add(c);
            }
            return out;
        }, cb);
    }

    public static void messages(String convId, Callback<ArrayList<Message>> cb) {
        async(() -> {
            JSONObject data = (JSONObject) call(READ, "/message/get-list", new JSONObject().put("convId", convId).put("limit", 30), false);
            JSONArray raws = data != null ? data.optJSONArray("messages") : null;
            ArrayList<Message> out = new ArrayList<>();
            if (raws == null) return out;
            for (int i = 0; i < raws.length(); i++) {
                JSONObject r = raws.getJSONObject(i);
                Message m = new Message();
                m.id = str(r, "id", "msgId", "messageId");
                m.fromOa = r.optBoolean("isAdmin");
                m.time = r.optLong("timestamp");
                m.type = kind(r.optInt("type", 2));
                String t = str(r, "message", "desc");
                m.text = m.type.equals("text") || m.type.equals("link") ? (t != null ? t : "") : label(m.type) + (t != null ? " " + t : "");
                m.staff = m.fromOa ? str(r, "owner") : null;
                out.add(m);
            }
            Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
            return out;
        }, cb);
    }

    public static void markRead(String convId) {
        async(() -> call(READ, "/conversation/reset-unread", new JSONObject().put("convId", convId), false), (r, e) -> {
        });
    }

    public static void sendText(String convId, String customerId, String text, Callback<Object> cb) {
        async(() -> call(SEND, "/message/send-text", new JSONObject().put("convId", convId).put("customerId", customerId).put("msg", text), false), cb);
    }

    public static void signOut() {
        CookieManager.getInstance().removeAllCookies(null);
        oaId = null;
        ek = null;
    }
}
