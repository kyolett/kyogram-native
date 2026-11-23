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

    // ── looking a customer up: purchases and invoices ──

    public static final class Purchase {
        public String source, code, path, date, customer, phone, note, total;
    }

    public static final class Item {
        public String code, name, serial, term, quantity, total, ticketStart;
    }

    public static final class Invoice {
        public String code, source, status, customer, phone, total, date, address, description, ticketStart;
        public ArrayList<String[]> meta = new ArrayList<>();
        public ArrayList<Item> items = new ArrayList<>();
    }

    /** A Vietnamese phone number in a message or field, as the site stores it (0xxxxxxxxx). */
    public static String findPhone(String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:\\+?84|0)(\\d{9})(?!\\d)").matcher(value == null ? "" : value.replaceAll("[.\\s-]", ""));
        return m.find() ? "0" + m.group(1) : null;
    }

    private static ArrayList<Map<String, Element>> table(Document doc, String mustHave) {
        ArrayList<Map<String, Element>> rows = new ArrayList<>();
        Element table = null;
        for (Element t : doc.select("table")) {
            for (Element th : t.select("th")) if (text(th).toUpperCase().contains(mustHave)) table = t;
            if (table != null) break;
        }
        if (table == null) return rows;
        ArrayList<String> headers = new ArrayList<>();
        for (Element th : table.select("thead th, tr:first-child th")) headers.add(text(th).toUpperCase().replaceAll("[▼▲]", "").trim());
        for (Element row : table.select("tbody tr")) {
            Elements cells = row.select("td");
            if (cells.isEmpty()) continue;
            HashMap<String, Element> m = new HashMap<>();
            for (int i = 0; i < headers.size() && i < cells.size(); i++) m.put(headers.get(i), cells.get(i));
            rows.add(m);
        }
        return rows;
    }

    private static Element col(Map<String, Element> row, String header) {
        for (Map.Entry<String, Element> e : row.entrySet()) if (e.getKey().contains(header)) return e.getValue();
        return null;
    }

    private static String link(Element a) {
        if (a == null) return null;
        String href = a.absUrl("href");
        if (href.isEmpty() || href.startsWith("javascript:")) return null;
        return href;
    }

    /** Purchases, newest first. type: phone, name, product, imei or all. */
    public static void purchases(String q, String type, Callback<ArrayList<Purchase>> cb) {
        async(() -> {
            HashMap<String, String> query = new HashMap<>();
            query.put("type", type);
            query.put("q", q);
            ArrayList<Purchase> out = new ArrayList<>();
            for (Map<String, Element> row : table(page("/search.php", query, null, false), "MÃ HÓA ĐƠN")) {
                Purchase p = new Purchase();
                Element src = col(row, "NGUỒN");
                Element tag = src != null ? src.selectFirst(".tag") : null;
                java.util.regex.Matcher m = tag != null ? java.util.regex.Pattern.compile("tag-(\\w+)").matcher(tag.className()) : null;
                p.source = (m != null && m.find() ? m.group(1) : text(src)).toLowerCase();
                Element code = col(row, "MÃ HÓA ĐƠN");
                p.code = text(code);
                p.path = code != null ? link(code.selectFirst("a")) : null;
                p.date = text(col(row, "NGÀY"));
                p.customer = text(col(row, "KHÁCH HÀNG"));
                p.phone = text(col(row, "SĐT"));
                p.note = text(col(row, "NỘI DUNG"));
                p.total = text(col(row, "THÀNH TIỀN"));
                out.add(p);
            }
            return out;
        }, cb);
    }

    public static void invoice(Purchase purchase, Callback<Invoice> cb) {
        async(() -> {
            if (purchase.path == null) throw new Exception("This purchase has no page");
            Document doc = page(purchase.path, null, null, false);
            Invoice inv = new Invoice();
            inv.code = purchase.code;
            inv.source = purchase.source;
            Element header = doc.selectFirst(".invoice-header .code");
            StringBuilder ht = new StringBuilder();
            if (header != null) for (TextNode t : header.textNodes()) ht.append(t.text()).append(' ');
            String[] parts = ht.toString().replaceAll("\\s+", " ").trim().split(" - ", 2);
            inv.phone = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : purchase.phone;
            inv.customer = parts.length > 1 ? parts[1] : purchase.customer;
            Element status = doc.selectFirst(".invoice-header .tag:not(.tag-misa):not(.tag-kiotviet)");
            inv.status = status != null ? text(status) : null;
            Element total = doc.selectFirst(".invoice-header .right > div:last-child");
            inv.total = total != null ? text(total) : purchase.total;
            for (Element pair : doc.select(".invoice-meta > div")) {
                String label = text(pair.selectFirst(".label"));
                if (label.isEmpty()) continue;
                String value = text(pair.selectFirst(".value"));
                inv.meta.add(new String[]{label, value});
                if (inv.date == null && (label.contains("Ngày bán") || label.contains("Ngày chứng từ") || label.contains("Ngày hạch toán"))) inv.date = value;
                if (label.contains("Địa chỉ")) inv.address = value;
            }
            if (inv.date == null) inv.date = purchase.date;
            for (Map<String, Element> row : table(doc, "MÃ HÀNG")) {
                Item it = new Item();
                it.code = text(col(row, "MÃ HÀNG"));
                Element name = col(row, "TÊN HÀNG");
                String n = "";
                if (name != null) for (TextNode t : name.textNodes()) if (!t.text().trim().isEmpty()) { n = t.text().trim(); break; }
                it.name = n.isEmpty() ? text(name) : n;
                it.serial = name != null && name.selectFirst(".imei-badge") != null ? text(name.selectFirst(".imei-badge")) : null;
                it.term = name != null && name.selectFirst(".warranty-note") != null ? text(name.selectFirst(".warranty-note")).replaceFirst("(?i)^Bảo hành:\\s*", "") : null;
                it.quantity = text(col(row, "SỐ LƯỢNG"));
                it.total = text(col(row, "THÀNH TIỀN"));
                it.ticketStart = name != null ? link(name.selectFirst("a[href*=warranty_ticket_new]")) : null;
                inv.items.add(it);
            }
            if ("misa".equals(purchase.source)) {
                for (Element label : doc.select(".label")) if (text(label).matches("(?i).*Nội dung mua hàng.*")) inv.description = text(label.nextElementSibling());
                if (inv.description == null) inv.description = purchase.note;
            }
            if (inv.items.isEmpty()) inv.ticketStart = link(doc.selectFirst("a[href*=warranty_ticket_new]"));
            return inv;
        }, cb);
    }

    /** Months in "6 tháng", "1 năm", "Toàn bộ sản phẩm:6 tháng". */
    public static int termMonths(String term) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*(tháng|năm)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(term == null ? "" : term);
        if (!m.find()) return 0;
        return Integer.parseInt(m.group(1)) * (m.group(2).toLowerCase().contains("năm") ? 12 : 1);
    }

    /** Days of warranty left (negative: ended), or null when unknown. */
    public static Integer warrantyDaysLeft(String date, String term) {
        int months = termMonths(term);
        if (months == 0 || date == null || date.length() < 10) return null;
        try {
            String d = date.substring(0, 10);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            if (d.matches("\\d{4}-\\d{2}-\\d{2}")) cal.set(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(5, 7)) - 1, Integer.parseInt(d.substring(8, 10)));
            else if (d.matches("\\d{2}/\\d{2}/\\d{4}")) cal.set(Integer.parseInt(d.substring(6, 10)), Integer.parseInt(d.substring(3, 5)) - 1, Integer.parseInt(d.substring(0, 2)));
            else return null;
            cal.add(java.util.Calendar.MONTH, months);
            return (int) Math.ceil((cal.getTimeInMillis() - System.currentTimeMillis()) / 86400e3);
        } catch (Exception e) {
            return null;
        }
    }

    // ── a new warranty ticket: the site's own form, its values with yours over them ──

    public static final String[][] COVERAGE = {{"covered", "Thuộc diện bảo hành"}, {"not_covered", "Không thuộc diện bảo hành"}, {"needs_inspection", "Cần kiểm tra thêm"}};
    public static final String[][] HANDLING = {{"intake_check", "Tiếp nhận kiểm tra"}, {"onsite_repair", "Sửa chữa tại chỗ"}, {"send_to_manufacturer", "Gửi hãng bảo hành"}, {"replace_product", "Đổi sản phẩm mới"}, {"reject", "Từ chối tiếp nhận"}};
    public static final String[][] ACCESSORIES = {{"hop_san_pham", "Hộp sản phẩm"}, {"hop_sac", "Hộp sạc"}, {"hop_bao_ve_kinh", "Hộp bảo vệ kính"}, {"tay_cam", "Tay cầm"}, {"sac", "Sạc"}, {"cap", "Cáp"}, {"phieu_bao_hanh", "Phiếu bảo hành"}, {"sach_huong_dan", "Sách hướng dẫn"}, {"kinh", "Kính"}, {"tui_bao_ve", "Túi bảo vệ"}};

    /**
     * Saves a new ticket (only from the "Tạo phiếu" button): the form at {@code start} (an invoice's
     * "Tiếp nhận" link, or the blank form), with these fields and photos; returns the ticket's code.
     */
    public static void newTicket(String start, Map<String, Object> fields, ArrayList<java.io.File> photos, Callback<String> cb) {
        async(() -> {
            Document doc = page(start != null ? start : "/warranty_ticket_new.php", null, null, false);
            Element form = null;
            for (Element f : doc.select("form")) if (f.selectFirst("[name=customer_name]") != null) form = f;
            if (form == null) throw new SignedOut();
            String action = form.absUrl("action");
            if (action.isEmpty()) action = doc.location();
            Connection c = Jsoup.connect(action).cookies(cookies()).timeout(60000).followRedirects(true).ignoreHttpErrors(true)
                    .userAgent("Mozilla/5.0 (Linux; Android) Kyogram").method(Connection.Method.POST);
            java.util.HashSet<String> overridden = new java.util.HashSet<>(fields.keySet());
            String fileField = "images[]";
            boolean hasCreatedAt = false;
            for (Element el : form.select("input, select, textarea")) {
                String name = el.attr("name"), type = el.attr("type").toLowerCase();
                if (name.isEmpty() || el.hasAttr("disabled")) continue;
                if (type.equals("file")) { fileField = name; continue; }
                if (type.equals("submit") || type.equals("button") || type.equals("reset") || type.equals("image")) continue;
                if (overridden.contains(name)) continue;
                // * the preset condition rows not asked for aren't sent, as when removed on the site
                if (name.matches("^conditions\\[\\w+\\]\\[.*")) continue;
                if ((type.equals("checkbox") || type.equals("radio")) && !el.hasAttr("checked")) continue;
                String value;
                if (el.tagName().equals("select")) {
                    Element sel = el.selectFirst("option[selected]");
                    if (sel == null) sel = el.selectFirst("option");
                    value = sel != null ? sel.attr("value") : "";
                } else if (el.tagName().equals("textarea")) {
                    value = el.wholeText();
                } else {
                    value = el.attr("value");
                }
                if (name.equals("created_at")) hasCreatedAt = true;
                if (name.equals("created_at") && value.isEmpty()) {
                    value = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(new java.util.Date());
                }
                c.data(name, value);
            }
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (e.getValue() instanceof java.util.List) {
                    for (Object v : (java.util.List<?>) e.getValue()) c.data(e.getKey(), String.valueOf(v));
                } else if (e.getValue() != null) {
                    c.data(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            if (photos != null) {
                for (java.io.File f : photos) c.data(fileField, f.getName(), new java.io.FileInputStream(f), "image/jpeg");
            }
            Connection.Response r = c.execute();
            keepCookies(r.cookies());
            Document after = r.parse();
            String path = r.url().getPath();
            if (path.endsWith("warranty_ticket_new.php")) {
                StringBuilder errors = new StringBuilder();
                for (Element e : after.select(".alert, .error, .errors, .alert-danger, .flash-error")) errors.append(text(e)).append('\n');
                throw new Exception(errors.length() > 0 ? errors.toString().trim() : "DroidShop didn't save the ticket");
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("BH\\d{4,}").matcher(after.title() + " " + after.text());
            return m.find() ? m.group() : "";
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
