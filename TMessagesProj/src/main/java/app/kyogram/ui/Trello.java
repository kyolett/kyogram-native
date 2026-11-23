package app.kyogram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;

/**
 * Kyogram ↔ Trello, as desktop Kyogram does it (src/kyogram/trello.ts): your API key and token, the
 * board, your shifts and which Trello column is each shift's to-do list. Kept on the phone only; anyone
 * with the token can read and change your boards. Requests run off the main thread and answer on it.
 */
public final class Trello {

    public static final class Shift {
        public String name = "", start = "", end = "", listId = "", listName = "";
    }

    public static final class Card {
        public String id, name, shortUrl, due, desc;
        public boolean done;
        public ArrayList<String> labels = new ArrayList<>();
        public ArrayList<String> labelColors = new ArrayList<>();
        public int checkItems, checkItemsChecked, comments, attachments;
    }

    public static final class TrelloList {
        public String id, name;
    }

    public interface Callback<T> {
        void done(T result, String error);
    }

    private Trello() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("kyogram_trello", Context.MODE_PRIVATE);
    }

    public static String key(Context c) { return prefs(c).getString("key", ""); }
    public static String token(Context c) { return prefs(c).getString("token", ""); }
    public static String boardId(Context c) { return prefs(c).getString("boardId", ""); }
    public static String boardName(Context c) { return prefs(c).getString("boardName", ""); }
    public static String fallbackListId(Context c) { return prefs(c).getString("fallbackListId", ""); }

    public static boolean connected(Context c) {
        return !key(c).isEmpty() && !token(c).isEmpty() && !boardId(c).isEmpty();
    }

    public static void save(Context c, String key, String token, String boardId, String boardName, ArrayList<Shift> shifts, String fallbackListId) {
        JSONArray a = new JSONArray();
        for (Shift s : shifts) {
            try {
                a.put(new JSONObject().put("name", s.name).put("start", s.start).put("end", s.end).put("listId", s.listId).put("listName", s.listName));
            } catch (Exception ignore) {
            }
        }
        prefs(c).edit().putString("key", key).putString("token", token).putString("boardId", boardId).putString("boardName", boardName)
                .putString("shifts", a.toString()).putString("fallbackListId", fallbackListId).apply();
    }

    public static void disconnect(Context c) {
        prefs(c).edit().clear().apply();
    }

    public static ArrayList<Shift> shifts(Context c) {
        ArrayList<Shift> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("shifts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Shift s = new Shift();
                s.name = o.optString("name");
                s.start = o.optString("start");
                s.end = o.optString("end");
                s.listId = o.optString("listId");
                s.listName = o.optString("listName");
                out.add(s);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** The page where you approve Kyogram and get a token, for your key. */
    public static String tokenUrl(String key) {
        return "https://trello.com/1/authorize?expiration=never&scope=read,write&response_type=token&name=Kyogram&key=" + Uri.encode(key);
    }

    // ── shifts ──

    static int minutesOf(String time) {
        if (time == null) return -1;
        String[] p = time.trim().split(":");
        if (p.length != 2) return -1;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean validTime(String time) {
        return minutesOf(time) >= 0;
    }

    /** The shift on now, or null between shifts (a shift past midnight counts). */
    public static Shift currentShift(Context c) {
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        for (Shift s : shifts(c)) {
            int a = minutesOf(s.start), b = minutesOf(s.end);
            if (a < 0 || b < 0 || a == b) continue;
            if (a < b ? now >= a && now < b : now >= a || now < b) return s;
        }
        return null;
    }

    /** The column whose cards are the to-do list now. */
    public static String tasksListId(Context c) {
        Shift s = currentShift(c);
        if (s != null && !s.listId.isEmpty()) return s.listId;
        return fallbackListId(c);
    }

    // ── requests ──

    private static String request(String method, String path, Map<String, String> query, String key, String token) throws Exception {
        StringBuilder url = new StringBuilder("https://api.trello.com").append(path).append("?key=").append(URLEncoder.encode(key, "UTF-8"))
                .append("&token=").append(URLEncoder.encode(token, "UTF-8"));
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                url.append('&').append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        if (method.equals("POST") || method.equals("PUT")) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(new byte[0]);
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        String body = out.toString("UTF-8");
        if (code == 401) throw new Exception("Trello didn't accept the key or token");
        if (code >= 400) throw new Exception(body.isEmpty() ? "Trello said " + code : body);
        return body;
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
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final T r = result;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> cb.done(r, err));
        });
    }

    private static java.util.HashMap<String, String> q(String... kv) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** A board from its link ("https://trello.com/b/AbC123/name") or id. */
    public static void board(String key, String token, String link, Callback<String[]> cb) {
        async(() -> {
            String id = link.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("trello\\.com/b/([A-Za-z0-9]+)").matcher(id);
            if (m.find()) id = m.group(1);
            JSONObject o = new JSONObject(request("GET", "/1/boards/" + Uri.encode(id), q("fields", "name"), key, token));
            return new String[]{o.getString("id"), o.optString("name")};
        }, cb);
    }

    public static void lists(String key, String token, String boardId, Callback<ArrayList<TrelloList>> cb) {
        async(() -> {
            JSONArray a = new JSONArray(request("GET", "/1/boards/" + Uri.encode(boardId) + "/lists", q("fields", "name", "filter", "open"), key, token));
            ArrayList<TrelloList> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                TrelloList l = new TrelloList();
                l.id = a.getJSONObject(i).getString("id");
                l.name = a.getJSONObject(i).optString("name");
                out.add(l);
            }
            return out;
        }, cb);
    }

    public static void cards(Context c, String listId, Callback<ArrayList<Card>> cb) {
        String key = key(c), token = token(c);
        async(() -> {
            JSONArray a = new JSONArray(request("GET", "/1/lists/" + Uri.encode(listId) + "/cards", q("fields", "name,shortUrl,due,dueComplete,labels,badges"), key, token));
            ArrayList<Card> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(card(a.getJSONObject(i)));
            return out;
        }, cb);
    }

    private static Card card(JSONObject o) {
        Card c = new Card();
        c.id = o.optString("id");
        c.name = o.optString("name");
        c.shortUrl = o.optString("shortUrl");
        c.due = o.isNull("due") ? null : o.optString("due", null);
        c.done = o.optBoolean("dueComplete");
        JSONArray labels = o.optJSONArray("labels");
        if (labels != null) {
            for (int i = 0; i < labels.length(); i++) {
                JSONObject l = labels.optJSONObject(i);
                if (l == null) continue;
                c.labels.add(l.optString("name"));
                c.labelColors.add(l.optString("color"));
            }
        }
        JSONObject b = o.optJSONObject("badges");
        if (b != null) {
            c.checkItems = b.optInt("checkItems");
            c.checkItemsChecked = b.optInt("checkItemsChecked");
            c.comments = b.optInt("comments");
            c.attachments = b.optInt("attachments");
        }
        return c;
    }

    /** Ticks a card off (or back on) with Trello's own "complete" mark, which the board shows too. */
    public static void setDone(Context c, String cardId, boolean done, Callback<Card> cb) {
        String key = key(c), token = token(c);
        async(() -> card(new JSONObject(request("PUT", "/1/cards/" + Uri.encode(cardId), q("dueComplete", done ? "true" : "false"), key, token))), cb);
    }

    public static void addTask(Context c, String listId, String name, Callback<Card> cb) {
        String key = key(c), token = token(c);
        async(() -> card(new JSONObject(request("POST", "/1/cards", q("idList", listId, "pos", "bottom", "name", name), key, token))), cb);
    }

    /** A card's description, for its details. */
    public static void details(Context c, String cardId, Callback<String> cb) {
        String key = key(c), token = token(c);
        async(() -> new JSONObject(request("GET", "/1/cards/" + Uri.encode(cardId), q("fields", "desc"), key, token)).optString("desc"), cb);
    }
}
