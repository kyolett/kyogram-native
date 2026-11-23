package app.kyogram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kyogram ↔ care.droidshop.vn, the shop's warranty site, as desktop Kyogram does it
 * (src/kyogram/droidshop.ts): it has no API, so this signs in with the site's own form and reads
 * its pages (the ticket table, .invoice-header, .invoice-meta, the "Tiến độ" timeline), and sends
 * the ticket page's own forms with their fresh hidden fields. The sign-in cookies are kept on the
 * phone; the password only if "remember" is on, to sign in again when the site's session ends.
 */
public final class Care {

    public static final String SITE = "https://care.droidshop.vn";

    /** The site's statuses: value, name, tone. */
    public static final String[][] STATUSES = {
            {"tiep_nhan", "Tiếp nhận", "blue"},
            {"dang_kiem_tra", "Đang kiểm tra", "amber"},
            {"cho_khach_xac_nhan", "Chờ khách xác nhận", "amber"},
            {"dang_xu_ly", "Đang xử lý", "amber"},
            {"dang_sua_cho_linh_kien", "Đang sửa / chờ linh kiện", "amber"},
            {"da_xu_ly_chua_tra_khach", "Đã xử lý - chưa trả khách", "teal"},
            {"khong_sua_duoc", "Không sửa được / Chờ trả khách", "red"},
            {"hoan_thanh", "Hoàn thành", "green"},
            {"tu_choi", "Từ chối bảo hành", "red"}};

    public static String tone(String label) {
        for (String[] s : STATUSES) if (s[1].equalsIgnoreCase(label == null ? "" : label.trim())) return s[2];
        return "gray";
    }

    public static final class Ticket {
        public String code, customer, product, serial, status, assignee, date;
    }

    public static final class Entry {
        public boolean note;
        public String title, when, who, body;
    }

    public static final class Detail {
        public String code, status, header, customer, phone, issue, statusValue = "";
        public ArrayList<String[]> meta = new ArrayList<>();
        public ArrayList<String> accessories = new ArrayList<>();
        public ArrayList<Entry> timeline = new ArrayList<>();
        public ArrayList<String[]> statusOptions = new ArrayList<>();
        public boolean canChangeStatus, canAddNote;
    }

    public interface Callback<T> {
        void done(T result, String error);
    }

    public static class SignedOut extends Exception {
        SignedOut() {
            super("Signed out of DroidShop");
        }
    }

    private Care() {
    }

    private static Context app;

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("kyogram_care", Context.MODE_PRIVATE);
    }

    public static void init(Context c) {
        app = c.getApplicationContext();
    }

    public static String accountName() {
        return prefs().getString("name", "");
    }

    public static boolean hasSession() {
        return !prefs().getString("cookies", "").isEmpty();
    }

    private static Map<String, String> cookies() {
        HashMap<String, String> m = new HashMap<>();
        for (String part : prefs().getString("cookies", "").split("\n")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i), part.substring(i + 1));
        }
        return m;
    }

    private static void keepCookies(Map<String, String> fresh) {
        Map<String, String> all = cookies();
        all.putAll(fresh);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        prefs().edit().putString("cookies", sb.toString()).apply();
    }

    // ── pages ──

    private static boolean isSignIn(Document doc, String url) {
        return !doc.select("input[type=password]").isEmpty() || url.matches("(?i).*(login|dang-?nhap|signin).*");
    }

    private static Document page(String path, Map<String, String> query, Map<String, String> form, boolean allowSignIn) throws Exception {
        StringBuilder url = new StringBuilder(path.startsWith("http") ? path : SITE + path);
        if (query != null && !query.isEmpty()) {
            url.append(url.indexOf("?") >= 0 ? '&' : '?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(java.net.URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        }
        Connection c = Jsoup.connect(url.toString()).cookies(cookies()).timeout(20000).followRedirects(true).ignoreHttpErrors(true)
                .userAgent("Mozilla/5.0 (Linux; Android) Kyogram");
        if (form != null) c.data(form).method(Connection.Method.POST);
        else c.method(Connection.Method.GET);
        Connection.Response r = c.execute();
        keepCookies(r.cookies());
        Document doc = r.parse();
        String at = r.url().toString();
        if (!allowSignIn && isSignIn(doc, at)) {
            if (signInWithSaved()) return page(path, query, form, true);
            throw new SignedOut();
        }
        if (r.statusCode() >= 400) throw new Exception("DroidShop answered " + r.statusCode());
        readAccountName(doc);
        return doc;
    }

    private static String text(Element e) {
        return e == null ? "" : e.text().replaceAll("\\s+", " ").trim();
    }

    private static void readAccountName(Document doc) {
        Element box = doc.selectFirst(".userbox");
        if (box == null) return;
        Element clone = box.clone();
        clone.select("a, button").remove();
        prefs().edit().putString("name", text(clone)).apply();
    }

    // ── signing in ──

    /** Fills the site's own sign-in form (whatever its fields are called) and sends it. */
    private static void signInNow(String username, String password, boolean remember) throws Exception {
        Document doc = page("/", null, null, true);
        if (!isSignIn(doc, doc.location())) return;
        Element form = null;
        for (Element f : doc.select("form")) if (!f.select("input[type=password]").isEmpty()) form = f;
        if (form == null) throw new Exception("Couldn't find the sign-in form on care.droidshop.vn");
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        boolean userSet = false;
        for (Element el : form.select("input, select, textarea")) {
            String name = el.attr("name"), type = el.attr("type").toLowerCase();
            if (name.isEmpty() || el.hasAttr("disabled")) continue;
            if (type.equals("password")) pairs.put(name, password);
            else if (!userSet && el.tagName().equals("input") && (type.isEmpty() || type.equals("text") || type.equals("email") || type.equals("tel"))) {
                pairs.put(name, username);
                userSet = true;
            } else if (type.equals("checkbox")) {
                if (name.matches("(?i).*(remember|nho).*")) pairs.put(name, el.attr("value").isEmpty() ? "on" : el.attr("value"));
            } else if (!type.equals("button") && !type.equals("file")) {
                pairs.put(name, el.attr("value"));
            }
        }
        String action = form.absUrl("action");
        if (action.isEmpty()) action = doc.location();
        Document result = page(action, null, pairs, true);
        if (isSignIn(result, result.location())) {
            String msg = text(result.selectFirst(".alert, .error, .alert-danger, .flash-error"));
            throw new Exception(msg.isEmpty() ? "Wrong username or password" : msg);
        }
        if (remember) prefs().edit().putString("user", username).putString("pass", password).apply();
        else prefs().edit().remove("user").remove("pass").apply();
    }

    private static boolean signInWithSaved() {
        String u = prefs().getString("user", ""), p = prefs().getString("pass", "");
        if (u.isEmpty()) return false;
        try {
            signInNow(u, p, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void signIn(String username, String password, boolean remember, Callback<String> cb) {
        async(() -> {
            signInNow(username, password, remember);
            page("/warranty_dashboard.php", null, null, false);
            return accountName();
        }, cb);
    }

    public static void signOut(Callback<Boolean> cb) {
        async(() -> {
            try {
                page("/logout.php", null, null, true);
            } catch (Exception ignore) {
            }
            prefs().edit().clear().apply();
            return true;
        }, cb);
    }

    // ── tickets ──

    /** Tickets as the site lists them (unfinished first); a search looks back to 2015. */
    public static void tickets(String q, String status, Callback<ArrayList<Ticket>> cb) {
        async(() -> {
            HashMap<String, String> query = new HashMap<>();
            query.put("q", q == null ? "" : q);
            query.put("status", status == null ? "" : status);
            if (q != null && !q.isEmpty()) {
                query.put("date_from", "2015-01-01");
                query.put("date_to", "");
            }
            Document doc = page("/warranty_tickets.php", query, null, false);
            ArrayList<Ticket> out = new ArrayList<>();
            Element table = null;
            for (Element t : doc.select("table")) {
                for (Element th : t.select("th")) if (text(th).toUpperCase().contains("MÃ PHIẾU")) table = t;
                if (table != null) break;
            }
            if (table == null) return out;
            ArrayList<String> headers = new ArrayList<>();
            for (Element th : table.select("thead th, tr:first-child th")) headers.add(text(th).toUpperCase().replaceAll("[▼▲]", "").trim());
            for (Element row : table.select("tbody tr")) {
                Elements cells = row.select("td");
                if (cells.isEmpty()) continue;
                Ticket t = new Ticket();
                t.code = text(cell(cells, headers, "MÃ PHIẾU"));
                Element customer = cell(cells, headers, "KHÁCH HÀNG");
                t.customer = customer != null && !customer.textNodes().isEmpty() && !customer.textNodes().get(0).text().trim().isEmpty()
                        ? customer.textNodes().get(0).text().trim() : customer != null && customer.children().size() > 0 ? text(customer.child(0)) : text(customer);
                t.product = text(cell(cells, headers, "SẢN PHẨM"));
                t.serial = text(cell(cells, headers, "SERIAL"));
                Element st = cell(cells, headers, "TRẠNG THÁI");
                t.status = st != null && st.selectFirst(".tag") != null ? text(st.selectFirst(".tag")) : text(st);
                t.assignee = text(cell(cells, headers, "NGƯỜI PHỤ TRÁCH"));
                t.date = text(cell(cells, headers, "NGÀY TIẾP NHẬN"));
                if (!t.code.isEmpty()) out.add(t);
            }
            return out;
        }, cb);
    }

    private static Element cell(Elements cells, ArrayList<String> headers, String header) {
        for (int i = 0; i < headers.size(); i++) if (headers.get(i).contains(header)) return i < cells.size() ? cells.get(i) : null;
        return null;
    }

    private static String multiline(Element e) {
        if (e == null) return "";
        Element clone = e.clone();
        for (Element br : clone.select("br")) br.replaceWith(new TextNode("\n"));
        StringBuilder sb = new StringBuilder();
        for (String line : clone.wholeText().split("\n")) {
            String l = line.replaceAll("\\s+", " ").trim();
            sb.append(l).append('\n');
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private static ArrayList<Element> sectionAfter(Document doc, String regex) {
        ArrayList<Element> parts = new ArrayList<>();
        Element heading = null;
        for (Element h : doc.select("h2, h3")) if (text(h).matches("(?is).*" + regex + ".*")) { heading = h; break; }
        if (heading == null) return parts;
        for (Element n = heading.nextElementSibling(); n != null && !n.tagName().matches("h[23]"); n = n.nextElementSibling()) parts.add(n);
        return parts;
    }

    private static Element findForm(Document doc, String action) {
        for (Element f : doc.select("form")) {
            Element a = f.selectFirst("[name=action]");
            if (a != null && action.equals(a.attr("value"))) return f;
        }
        return null;
    }

    private static Detail read(Document doc, String code) {
        Detail d = new Detail();
        d.code = code;
        Element header = doc.selectFirst(".invoice-header .code");
        StringBuilder ht = new StringBuilder();
        if (header != null) for (TextNode t : header.textNodes()) ht.append(t.text()).append(' ');
        d.header = ht.toString().replaceAll("\\s+", " ").trim();
        String[] parts = d.header.split(" - ", 2);
        d.phone = parts.length > 0 ? parts[0] : "";
        d.customer = parts.length > 1 ? parts[1] : "";
        for (Element pair : doc.select(".invoice-meta > div")) {
            String label = text(pair.selectFirst(".label"));
            if (!label.isEmpty()) d.meta.add(new String[]{label, multiline(pair.selectFirst(".value"))});
        }
        for (Element label : doc.select(".label")) {
            if (text(label).matches("(?i).*Mô tả lỗi.*")) {
                d.issue = multiline(label.nextElementSibling());
                break;
            }
        }
        if (d.issue == null) d.issue = "";
        d.status = text(doc.selectFirst(".invoice-header .tag"));
        Element statusForm = findForm(doc, "change_status");
        d.canChangeStatus = statusForm != null;
        d.canAddNote = findForm(doc, "add_note") != null;
        if (statusForm != null) {
            Element select = statusForm.selectFirst("select[name=to_status]");
            if (select != null) {
                for (Element o : select.select("option")) {
                    if (o.attr("value").isEmpty() || o.hasAttr("disabled")) continue;
                    d.statusOptions.add(new String[]{o.attr("value"), text(o)});
                    if (o.hasAttr("selected")) d.statusValue = o.attr("value");
                }
            }
        }
        for (Element part : sectionAfter(doc, "Phụ kiện")) for (Element tag : part.select(".tag")) d.accessories.add(text(tag));
        for (Element part : sectionAfter(doc, "Tiến độ")) {
            for (Element li : part.select("li")) {
                Entry e = new Entry();
                e.note = li.hasClass("note-entry");
                String meta = text(li.selectFirst(".muted")).replaceFirst("^—\\s*", "");
                String[] m = meta.split(" · ");
                e.when = m.length > 0 ? m[0] : "";
                e.who = m.length > 1 ? m[1] : "";
                e.title = e.note ? text(li.selectFirst(".note-entry-title")) : text(li.selectFirst("strong"));
                if (e.note) e.body = multiline(li.selectFirst(".note-entry-body"));
                else if (m.length > 2) e.body = meta.substring(meta.indexOf(m[2]));
                d.timeline.add(e);
            }
        }
        return d;
    }

    private static Document ticketPage(String code) throws Exception {
        HashMap<String, String> q = new HashMap<>();
        q.put("code", code);
        Document doc = page("/warranty_ticket.php", q, null, false);
        if (doc.selectFirst(".invoice-header") == null) throw new Exception("Ticket " + code + " not found");
        return doc;
    }

    public static void ticket(String code, Callback<Detail> cb) {
        async(() -> read(ticketPage(code), code), cb);
    }

    /** Sends one of the ticket page's own forms, with the page's fresh hidden fields (its CSRF token). */
    private static Detail sendForm(String code, String action, Map<String, String> fields) throws Exception {
        Document doc = ticketPage(code);
        Element form = findForm(doc, action);
        if (form == null) throw new Exception("DroidShop doesn't let this account do that");
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        for (Element el : form.select("input[type=hidden]")) if (!el.attr("name").isEmpty()) pairs.put(el.attr("name"), el.attr("value"));
        pairs.putAll(fields);
        String target = form.absUrl("action");
        if (target.isEmpty()) target = doc.location();
        Document result = page(target, null, pairs, false);
        StringBuilder errors = new StringBuilder();
        for (Element e : result.select(".alert-danger, .alert-error, .error, .flash-error")) errors.append(text(e)).append(' ');
        if (errors.toString().trim().length() > 0) throw new Exception(errors.toString().trim());
        return result.selectFirst(".invoice-header") != null ? read(result, code) : read(ticketPage(code), code);
    }

    public static void changeStatus(String code, String toStatus, String note, Callback<Detail> cb) {
        async(() -> {
            HashMap<String, String> f = new HashMap<>();
            f.put("to_status", toStatus);
            f.put("status_note", note);
            return sendForm(code, "change_status", f);
        }, cb);
    }

    public static void addNote(String code, String note, Callback<Detail> cb) {
        async(() -> {
            HashMap<String, String> f = new HashMap<>();
            f.put("quick_note", note);
            return sendForm(code, "add_note", f);
        }, cb);
    }

    // ── off the main thread ──

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
}
