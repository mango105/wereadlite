package com.modu.wereadlite;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

/** 微信读书 Web 接口。所有请求统一走 Tls 工厂。 */
public final class Api {

    private static final String TAG = "WereadLite";

    public static final class Resp {
        public final int code;
        public final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    public static final class Context {
        public String psvts = "";
        public String pclts = "";
        public String token = "";
        public long fetchedAt;
    }

    private final Store store;
    private final Map<String, String> cookies = new LinkedHashMap<String, String>();
    private boolean compat;
    private String cachedBook;
    private Context cachedContext;

    public Api(Store store) {
        this.store = store;
        this.compat = store.compat();
        for (String pair : store.cookies().split(";")) {
            int i = pair.indexOf('=');
            if (i > 0) cookies.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
    }

    public void setCompat(boolean value) {
        compat = value;
        store.setCompat(value);
    }

    public boolean compat() {
        return compat;
    }

    public String vid() {
        String v = cookies.get("wr_vid");
        return v == null ? "" : v;
    }

    public String skey() {
        String v = cookies.get("wr_skey");
        return v == null ? "" : v;
    }

    public boolean loggedIn() {
        return cookieHeader().length() > 0 && vid().length() > 0;
    }

    public String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void persist() {
        store.saveSession(cookieHeader(), vid(), skey());
    }

    private void absorb(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String raw : e.getValue()) {
                if (raw == null) continue;
                String first = raw.split(";")[0];
                int i = first.indexOf('=');
                if (i <= 0) continue;
                String k = first.substring(0, i).trim();
                String v = first.substring(i + 1).trim();
                if (k.length() == 0 || "deleted".equalsIgnoreCase(v)) continue;
                cookies.put(k, v);
            }
        }
    }

    // ---------- 基础请求 ----------

    private Resp send(String url, String method, String body, String referer, int readTimeout)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(Tls.get(compat));
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Protocol.UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (cookieHeader().length() > 0) conn.setRequestProperty("Cookie", cookieHeader());
            if (referer != null) conn.setRequestProperty("Referer", referer);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                conn.setRequestProperty("Origin", Protocol.BASE);
                byte[] data = Protocol.utf8(body);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            absorb(conn.getHeaderFields());
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = read(in, conn.getContentEncoding());
            Trace.i("HTTP " + method + " " + url + " -> " + code + " len=" + text.length()
                    + " clen=" + conn.getContentLength() + " ct=" + conn.getContentType()
                    + " ce=" + conn.getContentEncoding()
                    + " hdrs=" + mask(String.valueOf(conn.getHeaderFields())));
            Trace.i("  cookie=" + mask(cookieHeader()));
            if (body != null) Trace.i("  body=" + body);
            if (cookieHeader().length() > 0) persist();
            return new Resp(code, text);
        } finally {
            conn.disconnect();
        }
    }

    private String read(InputStream in, String encoding) throws IOException {
        if (in == null) return "";
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return Protocol.utf8Decode(out.toByteArray());
    }

    private Resp get(String url, String referer, int timeout) throws IOException {
        return send(url, "GET", null, referer, timeout);
    }

    private Resp post(String url, String body, String referer, int timeout) throws IOException {
        return send(url, "POST", body, referer, timeout);
    }

    // ---------- 扫码登录 ----------

    public String loginUid() throws IOException {
        Resp r = get(Protocol.BASE + "/api/auth/getLoginUid", Protocol.BASE + "/", 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        String uid = Json.str(Json.parse(r.body), "uid");
        if (uid.length() == 0) throw new IOException("未取到 uid");
        return uid;
    }

    /** 长轮询：没有扫码时服务端约 30 秒断一次，返回空 body 属正常。 */
    public Resp loginInfo(String uid, String otp) throws IOException {
        String url = Protocol.BASE + "/api/auth/getLoginInfo?uid=" + Protocol.encode(uid) + "&otp";
        if (otp != null && otp.length() > 0) url = url + "=" + Protocol.encode(otp);
        return get(url, Protocol.BASE + "/", 40000);
    }

    public boolean finishLogin(Object data) {
        String token = Json.str(data, "accessToken");
        String vid = Json.str(data, "webLoginVid");
        if (token.length() == 0 || vid.length() == 0) return false;
        cookies.put("wr_skey", token);
        cookies.put("wr_vid", vid);
        persist();
        return true;
    }

    public void renewal() {
        try {
            post(Protocol.BASE + "/web/login/renewal",
                    "{\"rq\":\"%2Fweb%2Fbook%2Fread\",\"ql\":false}",
                    Protocol.BASE + "/", 15000);
        } catch (Exception e) {
            Log.w(TAG, "renewal failed", e);
        }
    }

    // ---------- 书架 ----------

    /**
     * marks / times 非空时顺手把每本书的云端阅读位置和累计时长交给调用方：
     * bookProgress[] 和书架在同一次返回里，续读和「读过多久」都不必再发一个请求。
     */
    public List<Map<String, Object>> shelf(Map<String, String> marks, Map<String, Integer> times)
            throws IOException {
        String url = Protocol.BASE + "/web/shelf/sync?synckey=0&teenmode=0&album=1&showStaticBook=1";
        Resp r = get(url, Protocol.BASE + "/web/shelf", 20000);
        Trace.dump("shelf.json", r.body);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        String fault = shelfFault(r.body);
        if (fault != null) throw new IOException(fault);
        Object root = Json.parse(r.body);
        bookProgress(r.body, marks, times);
        List<Map<String, Object>> books = new ArrayList<Map<String, Object>>();
        collect(Json.list(root, "books"), books);
        collect(Json.list(root, "archive"), books);
        return books;
    }

    /**
     * bookProgress[]：每本书上次读到哪、一共读了多久。返回体看不懂就当没有。
     * 云端给没读过的书记的是 0 秒，这种行不进 times，免得盖掉本地攒下的时长。
     */
    static void bookProgress(String body, Map<String, String> marks, Map<String, Integer> times) {
        List<Object> rows;
        try {
            rows = Json.list(Json.parse(body), "bookProgress");
        } catch (Exception ignore) {
            return;
        }
        for (Object o : rows) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = Json.asMap(o);
            String id = Json.str(m, "bookId");
            if (id.length() == 0) continue;
            if (marks != null) {
                marks.put(id, Resume.encode(Json.str(m, "chapterUid"), Json.num(m, "chapterOffset"),
                        Json.num(m, "chapterIdx"), Json.num(m, "updateTime")));
            }
            if (times != null) {
                int secs = Json.num(m, "readingTime");
                if (secs > 0) times.put(id, Integer.valueOf(secs));
            }
        }
    }

    /**
     * 书架返回体里的错误。登录过期时 books 是空的，只按「有没有书」判断会把
     * 「登录超时」看成「书架是空的」，所以 errCode 非 0 必须单独报出来。
     */
    static String shelfFault(String body) {
        Object root = Json.parse(body);
        int err = Json.num(root, "errCode");
        if (err == 0) err = Json.num(root, "errcode");
        if (err == 0) return null;
        if (err == -2010 || err == -2012) return "登录已过期，请重新登录";
        String msg = Json.str(root, "errMsg");
        return msg.length() == 0 ? "书架同步失败 errCode=" + err : "书架同步失败：" + msg;
    }

    private void collect(List<Object> items, List<Map<String, Object>> out) {
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = Json.asMap(item);
            String id = Json.str(m, "bookId");
            if (id.length() == 0 || id.startsWith("MP_WXS_")) continue;
            Map<String, Object> info = Json.map(m, "bookInfo");
            String title = Json.str(m, "title");
            if (title.length() == 0) title = Json.str(info, "title");
            String author = Json.str(m, "author");
            if (author.length() == 0) author = Json.str(info, "author");
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("bookId", id);
            row.put("title", title.length() == 0 ? id : title);
            row.put("author", author);
            row.put("progress", Json.num(m, "progress"));
            String cover = Json.str(m, "cover");
            if (cover.length() == 0) cover = Json.str(info, "cover");
            if (cover.length() == 0) cover = Json.str(info, "coverUrl");
            row.put("cover", cover);
            int secret = Json.num(m, "secret");
            if (secret == 0) secret = Json.num(m, "isSecret");
            row.put("secret", secret);
            out.add(row);
        }
    }

    /** 书架条目缺封面时按 bookId 补查一次（失败返回空串，不影响列表）。 */
    public String cover(String bookId) {
        if (bookId == null || bookId.length() == 0) return "";
        try {
            Resp r = get(Protocol.BASE + "/web/book/info?bookId=" + Protocol.encode(bookId),
                    Protocol.BASE + "/web/shelf", 12000);
            if (r.code != 200) return "";
            Object root = Json.parse(r.body);
            String c = Json.str(root, "cover");
            if (c.length() == 0) c = Json.str(Json.map(root, "bookInfo"), "cover");
            if (c.length() == 0) c = Json.str(Json.map(root, "info"), "cover");
            return c;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 目录 ----------

    public List<Map<String, Object>> catalog(String bookId) throws IOException {
        Resp r = post(Protocol.BASE + "/web/book/chapterInfos",
                "{\"bookIds\":[" + Json.quote(bookId) + "]}",
                Protocol.readerUrl(bookId, null), 20000);
        Trace.dump("chapterinfos.json", r.body);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        Object root = Json.parse(r.body);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<Object> records = Json.list(root, "data");
        if (records.isEmpty()) records = Json.list(root, "books");
        for (Object rec : records) {
            Map<String, Object> book = Json.asMap(rec);
            String id = Json.str(book, "bookId");
            if (id.length() > 0 && !id.equals(bookId)) continue;
            List<Object> chapters = Json.list(book, "updated");
            if (chapters.isEmpty()) chapters = Json.list(book, "chapters");
            for (Object c : chapters) {
                Map<String, Object> ch = Json.asMap(c);
                String uid = Json.str(ch, "chapterUid");
                String title = Json.str(ch, "title");
                if (uid.length() == 0) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("uid", uid);
                row.put("title", title.length() == 0 ? ("第 " + (out.size() + 1) + " 章") : title);
                row.put("level", Json.num(ch, "level"));
                row.put("wordCount", ch.containsKey("wordCount")
                        ? (Object) Integer.valueOf(Json.num(ch, "wordCount")) : null);
                out.add(row);
            }
            if (!out.isEmpty()) break;
        }
        if (out.isEmpty()) throw new IOException("目录为空，可能该书需要购买或会员");
        return out;
    }

    // ---------- 章节正文 ----------

    /** reader 页里的 psvts/pclts/token，按书缓存（服务端 240 秒内有效）。 */
    public Context context(String bookId, String chapterUid, boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && cachedContext != null && bookId.equals(cachedBook)
                && now - cachedContext.fetchedAt < 200000L) {
            return cachedContext;
        }
        Resp r = get(Protocol.readerUrl(bookId, chapterUid), Protocol.BASE + "/web/shelf", 25000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        Context ctx = new Context();
        ctx.psvts = pick(r.body, "psvts");
        ctx.pclts = pick(r.body, "pclts");
        ctx.token = pick(r.body, "token");
        ctx.fetchedAt = now;
        Trace.i("ctx html len=" + r.body.length() + " psvts=" + ctx.psvts
                + " pclts=" + ctx.pclts + " token=" + (ctx.token.length() > 0 ? "yes" : "no"));
        Trace.dump("ctx.html", r.body);
        if (ctx.psvts.length() == 0) throw new IOException("未取到 psvts（可能未登录或该书无权限）");
        cachedBook = bookId;
        cachedContext = ctx;
        return ctx;
    }

    private static String pick(String html, String key) {
        String[] patterns = {
                "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"",
                "\"" + key + "\"\\s*:\\s*(\\d+)",
        };
        for (String p : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(html);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private String shard(String path, String bookId, String chapterUid, String psvts, boolean style)
            throws IOException {
        Map<String, String> fields = Protocol.contentFields(bookId, chapterUid, psvts, style);
        String body = Protocol.contentJson(fields);
        Resp r = post(Protocol.BASE + path, body,
                Protocol.readerUrl(bookId, chapterUid), 30000);
        Trace.i("shard " + path + " code=" + r.code + " len=" + r.body.length()
                + " head=" + trim(r.body));
        Trace.dump("raw" + path.replace('/', '_') + ".txt", r.body);
        if (r.code != 200) throw new IOException(path + " HTTP " + r.code);
        if (r.body.length() < 8) throw new IOException(path + " 返回空内容");
        if (r.body.trim().startsWith("{") && r.body.contains("\"errcode\"")) {
            throw new IOException(path + " 被拒绝：" + r.body);
        }
        return r.body;
    }

    /** 返回章节纯文本（已去标签）。 */
    public String chapterText(String bookId, String chapterUid, Context ctx) throws IOException {
        return chapter(bookId, chapterUid, ctx).text;
    }

    /** 章节正文 + 原始 HTML（写划线要拿 HTML 算 range）。 */
    public static final class ChapterData {
        public String text = "";
        public String html = "";
    }

    public ChapterData chapter(String bookId, String chapterUid, Context ctx) throws IOException {
        String a = shard("/web/book/chapter/e_0", bookId, chapterUid, ctx.psvts, false);
        String[] parts;
        if (a.trim().startsWith("{") && a.contains("\"bookId\"")) {
            // 网文类：改走 t_0 / t_1
            String t0 = shard("/web/book/chapter/t_0", bookId, chapterUid, ctx.psvts, false);
            String t1 = "";
            try {
                t1 = shard("/web/book/chapter/t_1", bookId, chapterUid, ctx.psvts, false);
            } catch (IOException e) {
                Log.w(TAG, "t_1 empty: " + e.getMessage());
            }
            parts = new String[]{t0, t1};
        } else {
            String b = shard("/web/book/chapter/e_1", bookId, chapterUid, ctx.psvts, false);
            String c = shard("/web/book/chapter/e_3", bookId, chapterUid, ctx.psvts, false);
            parts = new String[]{a, b, c};
        }
        ChapterData d = new ChapterData();
        d.html = Protocol.decodeParts(parts);
        d.text = Protocol.htmlToText(d.html);
        return d;
    }

    // ---------- 划线 / 想法（云端写，协议同 MiuRead 开源实现） ----------

    /** 书籍版本号，addBookmark / addReview 必带；取不到返回 0（部分书仍能写成功）。 */
    public int bookVersion(String bookId) throws IOException {
        Resp r = get(Protocol.BASE + "/web/book/info?bookId=" + Protocol.encode(bookId),
                Protocol.BASE + "/web/shelf", 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return (int) pickLong(Json.asMap(Json.parse(r.body)), "version", "bookVersion");
    }

    /**
     * 上传一条划线。markText 走 Base64（协议要求），range 为章节 HTML 字符索引。
     * 成功返回服务端 bookmarkId（删除时要用），失败抛 IOException。
     */
    public String addBookmark(String bookId, String chapterUid, int chapterIdx,
                              int version, String range, String markText)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("bookId", bookId);
        m.put("chapterUid", chapterUid);
        m.put("chapterIdx", Integer.valueOf(chapterIdx));
        m.put("bookVersion", Integer.valueOf(version));
        m.put("type", Integer.valueOf(1));
        m.put("style", Integer.valueOf(1));
        m.put("colorStyle", Integer.valueOf(0));
        m.put("range", range);
        m.put("markText", Protocol.b64(markText));
        Resp r = post(Protocol.BASE + "/web/book/addBookmark", Json.write(m),
                Protocol.readerUrl(bookId, chapterUid), 20000);
        Object root = Json.parse(r.body);
        if (r.code != 200 || Json.num(root, "errcode") != 0 && Json.num(root, "succ") == 0
                && Json.str(root, "bookmarkId").length() == 0) {
            throw new IOException("addBookmark 被拒绝：" + trim(r.body));
        }
        return Json.str(root, "bookmarkId", "bookmarkID");
    }

    /** 删除一条云端划线。 */
    public void removeBookmark(String bookmarkId, String bookId, String chapterUid)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("bookmarkId", bookmarkId);
        Resp r = post(Protocol.BASE + "/web/book/removeBookmark", Json.write(m),
                Protocol.readerUrl(bookId, chapterUid), 20000);
        if (r.code != 200) throw new IOException("removeBookmark HTTP " + r.code);
    }

    /**
     * 上传一条想法（默认私密 isPrivate=1，不会打扰书友，之后可在官方 App 改公开）。
     * 返回服务端 reviewId。
     */
    public String addReview(String bookId, String chapterUid, int chapterIdx,
                            int version, String range, String abstractText, String content)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", Integer.valueOf(1));
        m.put("bookId", bookId);
        m.put("chapterUid", chapterUid);
        m.put("chapterIdx", Integer.valueOf(chapterIdx));
        m.put("bookVersion", Integer.valueOf(version));
        m.put("range", range);
        m.put("abstract", abstractText);
        m.put("content", content);
        m.put("isPrivate", Integer.valueOf(1));
        Resp r = post(Protocol.BASE + "/web/review/add", Json.write(m),
                Protocol.readerUrl(bookId, chapterUid), 20000);
        Object root = Json.parse(r.body);
        // 成功响应不一定带 reviewId（参考 MiuRead：想法写入不强制要 id），
        // 只要 HTTP 200 且 errcode/errCode 为 0（或无该字段）就算成功。
        int err = Json.num(root, "errcode");
        if (err == 0) err = Json.num(root, "errCode");
        if (r.code != 200 || err != 0) {
            String msg = errMsg(root, "服务器拒绝了想法");
            throw new IOException(msg);
        }
        return Json.str(root, "reviewId", "reviewID");
    }

    /** 网页会话直接拉某章书友划线（含 markText 原文），E-Ink 通道之外的第二条读通道。 */
    public List<Map<String, Object>> webUnderlines(String bookId, String chapterUid)
            throws IOException {
        Resp r = get(Protocol.BASE + "/web/book/underlines?bookId=" + Protocol.encode(bookId)
                        + "&chapterUid=" + Protocol.encode(chapterUid),
                Protocol.readerUrl(bookId, chapterUid), 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return Eink.parseUnderlines(r.body);
    }

    // ---------- 进度上报 ----------

    public boolean report(String bookId, String chapterUid, int chapterIndex, int offset,
                          int progress, int elapsed, String summary, Context ctx) {
        try {
            Map<String, String> f = Protocol.readFields(bookId, chapterUid, chapterIndex, offset,
                    progress, elapsed, summary,
                    ctx == null ? "" : ctx.psvts, ctx == null ? "" : ctx.pclts,
                    ctx == null ? "" : ctx.token, Protocol.appId(Protocol.UA));
            Resp r = post(Protocol.BASE + "/web/book/read", Protocol.readJson(f),
                    Protocol.readerUrl(bookId, chapterUid), 20000);
            // 落库了才回 {"succ":1,…}；签名或 pc 字段不对时服务端只回一个空对象，进度静默丢弃
            boolean ok = r.code == 200 && Json.num(Json.parse(r.body), "succ") == 1;
            Log.i(TAG, "report " + r.code + " ok=" + ok + " " + trim(r.body));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "report failed", e);
            return false;
        }
    }

    // ---------- 搜索 / 书城 ----------

    /** 全站搜索：keyword 为空时服务端返回热门。maxIdx 每页 20。 */
    public List<Map<String, Object>> search(String keyword, int maxIdx) throws IOException {
        String url = Protocol.BASE + "/web/search/global?keyword=" + Protocol.encode(keyword == null ? "" : keyword)
                + "&maxIdx=" + maxIdx + "&count=20&fragmentSize=120";
        Resp r = get(url, Protocol.BASE + "/web/search/books", 20000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        Object root = ok(r.body, "搜索");
        return rows(listAt(root, "books", "results", "list"));
    }

    /** 热门书：书城「猜你喜欢」和搜索页空关键词都用它。 */
    public List<Map<String, Object>> hotBooks() throws IOException {
        Resp r = get(Protocol.BASE + "/web/search/hot", Protocol.BASE + "/web/search/books", 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        Object root = ok(r.body, "热门书籍");
        List<Object> items = listAt(root, "items", "books", "results");
        if (items.isEmpty()) items = listAt(Json.map(root, "data"), "items", "books");
        return rows(items);
    }

    /** 搜索建议词，失败返回空表（不弹错误，输入框每敲一次都会调）。 */
    public List<String> suggest(String keyword) {
        List<String> out = new ArrayList<String>();
        if (keyword == null || keyword.trim().length() == 0) return out;
        try {
            Resp r = get(Protocol.BASE + "/web/search/search_suggest?keyword="
                    + Protocol.encode(keyword.trim()), Protocol.BASE + "/web/search/books", 8000);
            if (r.code != 200) return out;
            List<Object> records = Json.list(Json.parse(r.body), "records");
            Set<String> seen = new LinkedHashSet<String>();
            for (Object o : records) {
                String w = Json.str(Json.asMap(o), "word", "title");
                if (w.length() == 0 || seen.contains(w)) continue;
                seen.add(w);
                out.add(w);
                if (out.size() >= 8) break;
            }
        } catch (Exception e) {
            Trace.i("suggest failed " + e);
        }
        return out;
    }

    /** 详情页数据：网页态取不到时退回搜索接口按书名查一次。 */
    public Map<String, Object> bookDetail(String bookId, String hint) throws IOException {
        Resp r = get(Protocol.BASE + "/web/book/info?bookId=" + Protocol.encode(bookId),
                Protocol.BASE + "/web/shelf", 15000);
        if (r.code == 200) {
            Object root = Json.parse(r.body);
            if (errOf(root) == 0) {
                Map<String, Object> row = bookRow(root);
                if (row != null) {
                    row.put("bookId", bookId);
                    mergeRating(row, root);
                    if (hint != null && hint.length() > 0 && isEmptyRow(row)) {
                        row.put("title", hint);
                    }
                    return row;
                }
            }
        }
        if (hint != null && hint.trim().length() > 0) {
            List<Map<String, Object>> found = search(hint.trim(), 0);
            for (Map<String, Object> row : found) {
                if (bookId.equals(row.get("bookId"))) return row;
            }
            if (!found.isEmpty()) return found.get(0);
        }
        Map<String, Object> bare = new LinkedHashMap<String, Object>();
        bare.put("bookId", bookId);
        bare.put("title", hint == null ? bookId : hint);
        bare.put("author", "");
        bare.put("cover", "");
        return bare;
    }

    /** 加入书架：网页端是支持的，返回 succ==1。 */
    public boolean addToShelf(String bookId) throws IOException {
        if (bookId == null || bookId.length() == 0 || bookId.startsWith("local:")) {
            throw new IOException("无效的书籍 ID");
        }
        Resp r = post(Protocol.BASE + "/web/shelf/add",
                "{\"bookIds\":[" + Json.quote(bookId) + "]}", Protocol.BASE + "/web/search/books", 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        Object root = Json.parse(r.body);
        int err = errOf(root);
        if (err != 0) throw new IOException(errMsg(root, "加入失败"));
        Map<String, Object> data = Json.map(root, "data");
        int succ = data.isEmpty() ? Json.num(root, "succ") : Json.num(data, "succ");
        if (succ == 0) throw new IOException("书架已满或该书不可加入");
        Trace.i("shelf/add " + bookId + " succ=" + succ);
        return true;
    }

    // ---------- 想法（云端只读） ----------

    /** 某一章的书友想法。网页会话一般拿不到，调用方失败时要能退到 E-Ink 会话。 */
    public List<Map<String, Object>> reviews(String bookId, String chapterUid, int maxIdx)
            throws IOException {
        String url = Protocol.BASE + "/web/review/list?bookId=" + Protocol.encode(bookId)
                + "&chapterUid=" + Protocol.encode(chapterUid)
                + "&range=1&maxIdx=" + maxIdx + "&count=20&synckey=0";
        Resp r = get(url, Protocol.readerUrl(bookId, chapterUid), 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return reviewRows(r.body);
    }

    static List<Map<String, Object>> reviewRows(String body) {
        Object root = Json.parse(body);
        List<Object> list = listAt(root, "reviews", "reviewList");
        if (list.isEmpty()) list = listAt(Json.map(root, "data"), "reviews", "reviewList");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object o : list) {
            Map<String, Object> m = Json.asMap(o);
            Map<String, Object> inner = Json.map(m, "review");
            if (!inner.isEmpty()) m = inner;
            String content = Protocol.htmlToText(Json.str(m, "content", "text", "htmlContent"));
            String mark = Protocol.htmlToText(Json.str(m, "abstract", "mark", "chapterName"));
            if (content.length() == 0 && mark.length() == 0) continue;
            String name = Json.str(Json.map(m, "author"), "name", "nick");
            if (name.length() == 0) name = Json.str(m, "authorName", "nickName", "nick");
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("content", content);
            row.put("mark", mark);
            row.put("author", name);
            row.put("like", Integer.valueOf(pick(m, "likeCount", "likes")));
            row.put("comment", Integer.valueOf(pick(m, "commentCount", "comments")));
            row.put("time", Long.valueOf(pickLong(m, "createTime", "time")));
            row.put("reviewId", Json.str(m, "reviewId", "review_id", "id"));
            out.add(row);
        }
        return out;
    }

    /**
     * 单条想法的评论列表（含点赞的人）。走网页接口 /web/review/single，
     * 只读；没有登录态或接口不给数据时调用方显示空态即可。
     */
    public List<Map<String, Object>> reviewDetail(String reviewId) throws IOException {
        String url = Protocol.BASE + "/web/review/single?reviewId=" + Protocol.encode(reviewId)
                + "&commentsCount=20&commentsDirection=0&likesCount=0&listType=0&maxIdx=0&count=20&synckey=0";
        Resp r = get(url, "https://weread.qq.com/", 15000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        Object root = Json.parse(r.body);
        Map<String, Object> review = Json.map(root, "review");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<Object> comments = Json.list(root, "comments");
        if (comments.isEmpty()) comments = Json.list(review, "comments");
        for (Object o : comments) {
            Map<String, Object> m = Json.asMap(o);
            if (m.isEmpty()) continue;
            Map<String, Object> inner = Json.map(m, "comment");
            if (!inner.isEmpty()) m = inner;
            String c = Protocol.htmlToText(Json.str(m, "content", "text"));
            if (c.length() == 0) continue;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("content", c);
            row.put("author", Json.str(Json.map(m, "author"), "name", "nick"));
            row.put("time", Long.valueOf(pickLong(m, "createTime", "time")));
            out.add(row);
        }
        return out;
    }

    /**
     * 诊断日志会同时进 logcat 和外部私有目录，别的 app 或 adb 都能翻到，
     * 所以会话密钥、刷新令牌、accessToken 一律只留字段名。
     */
    static String mask(String s) {
        if (s == null) return "";
        return s.replaceAll("(wr_skey|wr_vid|wr_rt|wr_kd|wr_fp|accessToken|access_token|vid|key)"
                + "=[^;,\\]&\\s]+", "$1=**");
    }

    private static int pick(Map<String, Object> m, String a, String b) {
        int v = Json.num(m, a);
        return v != 0 ? v : Json.num(m, b);
    }

    /** 同一组字段名先在内层找，再退到外层；网页端几种包装结构都见过。 */
    private static int pick3(Map<String, Object> first, Map<String, Object> second,
                             String a, String b, String c) {
        int v = pick(first, a, b);
        return v != 0 ? v : pick(second, c, a);
    }

    private static long pickLong(Map<String, Object> m, String a, String b) {
        Object v = m.get(a);
        if (v == null) v = m.get(b);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    // ---------- 解析工具 ----------

    private Object ok(String body, String what) throws IOException {
        Object root = Json.parse(body);
        int err = errOf(root);
        if (err == -2010) throw new IOException("登录已失效，请重新扫码");
        if (err != 0) throw new IOException(what + "失败：" + errMsg(root, what + "失败"));
        return root;
    }

    private static int errOf(Object root) {
        int err = Json.num(root, "errCode");
        return err != 0 ? err : Json.num(root, "errcode");
    }

    /** 同一个对象里依次找几个 key，返回第一个非空数组。 */
    private static List<Object> listAt(Object root, String... keys) {
        for (String k : keys) {
            List<Object> l = Json.list(root, k);
            if (!l.isEmpty()) return l;
        }
        return new ArrayList<Object>();
    }

    private static String errMsg(Object root, String fallback) {
        String m = Json.str(root, "errMsg", "errmsg", "msg");
        return m.length() > 0 ? m : fallback;
    }

    private List<Map<String, Object>> rows(List<Object> items) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Object item : items) {
            Map<String, Object> row = bookRow(item);
            if (row == null) continue;
            String id = String.valueOf(row.get("bookId"));
            if (!seen.add(id)) continue;
            out.add(row);
        }
        return out;
    }

    /** 一条书籍记录 -> 界面行；两种包装（裸对象 / bookInfo 套一层）都吃。 */
    static Map<String, Object> bookRow(Object item) {
        Map<String, Object> outer = Json.asMap(item);
        Map<String, Object> info = Json.map(outer, "bookInfo");
        if (info.isEmpty()) info = Json.map(outer, "book");
        Map<String, Object> src = info.isEmpty() ? outer : info;
        String id = Json.str(src, "bookId");
        if (id.length() == 0 || id.startsWith("MP_WXS_")) return null;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("bookId", id);
        String title = Json.str(src, "title");
        row.put("title", title.length() == 0 ? id : title);
        row.put("author", Json.str(src, "author", "authorName"));
        row.put("cover", Json.str(src, "cover", "coverUrl", "cover2"));
        row.put("intro", Json.str(src, "intro", "abstract", "description"));
        row.put("publisher", Json.str(src, "publisher"));
        row.put("format", Json.str(src, "format"));
        // /web/book/info 是平铺结构且字数写在 totalWords，列表接口才是 wordCount/wordsCount
        row.put("wordCount", Integer.valueOf(pick3(src, outer, "wordCount", "wordsCount", "totalWords")));
        row.put("readCount", Integer.valueOf(pick3(outer, src, "readingCount", "readCount", "readNum")));
        row.put("payType", Integer.valueOf(Json.num(src, "payType")));
        row.put("secret", Integer.valueOf(0));
        mergeRating(row, src);
        return row;
    }

    /** 推荐值：newRating 是千分制（930 = 93.0%），档位名和三档占比在 newRatingDetail 里。 */
    private static void mergeRating(Map<String, Object> row, Map<String, Object> src) {
        int rating = Json.num(src, "newRating");
        if (rating > 0) row.put("rating", Integer.valueOf(rating));
        int count = Json.num(src, "newRatingCount");
        if (count > 0) row.put("ratingCount", Integer.valueOf(count));
        Map<String, Object> detail = Json.map(src, "newRatingDetail");
        if (!detail.isEmpty()) {
            String t = Json.str(detail, "title");
            if (t.length() > 0) row.put("ratingTitle", t);
            putIf(detail, row, "good", "good");
            putIf(detail, row, "mixed", "mixed");
            putIf(detail, row, "poor", "poor");
        }
    }

    private static void mergeRating(Map<String, Object> row, Object root) {
        Map<String, Object> m = Json.asMap(root);
        mergeRating(row, m);
        Map<String, Object> info = Json.map(m, "bookInfo");
        if (!info.isEmpty()) mergeRating(row, info);
    }

    private static void putIf(Map<String, Object> from, Map<String, Object> to, String key, String as) {
        int v = Json.num(from, key);
        if (v > 0) to.put(as, Integer.valueOf(v));
    }

    private static boolean isEmptyRow(Map<String, Object> row) {
        return String.valueOf(row.get("title")).equals(row.get("bookId"));
    }

    private static String trim(String s) {
        return s == null ? "" : (s.length() > 120 ? s.substring(0, 120) : s);
    }

}
