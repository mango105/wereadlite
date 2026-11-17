package com.modu.wereadlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Random;

import javax.net.ssl.HttpsURLConnection;

/**
 * E-Ink 客户端接口（i.weread.qq.com）。
 * 与 Web 会话是两套登录态：Web 只读，E-Ink 才有 /shelf/delete、/shelf/add 等写能力。
 * 授权流程照搬觅阅 MiuRead：wxticket -> 微信开放平台扫码 -> /login 换 accessToken。
 */
public final class Eink {

    public static final String BASE = "https://i.weread.qq.com";
    private static final String UA = "WeRead/2.1.2 WRBrand/Onyx wr_eink Dalvik/2.1.0 "
            + "(Linux; U; Android 11; BOOX Build/onyx)";

    /** E-Ink 登录凭证。 */
    public static final class Cred {
        public String vid = "";
        public String accessToken = "";
        public String refreshToken = "";
        public String deviceId = "";

        public boolean ok() {
            return vid.length() > 0 && accessToken.length() > 0 && refreshToken.length() > 0;
        }
    }

    /** 扫码票据。 */
    public static final class Ticket {
        public String signature = "";
        public String timestamp = "";
    }

    /** 轮询结果。 */
    public static final class Poll {
        public static final int WAIT = 0;
        public static final int DONE = 1;
        public static final int TIMEOUT = 2;
        public static final int DECLINED = 3;
        public int state = WAIT;
        public String wxCode = "";
        public String last = "";
    }

    private Eink() {
    }

    // ---------------- 凭证存取 ----------------

    public static Cred load(Store store) {
        Cred c = new Cred();
        String raw = store.einkCred();
        if (raw.length() > 0) {
            try {
                Object root = Json.parse(raw);
                c.vid = Json.str(root, "vid");
                c.accessToken = Json.str(root, "accessToken");
                c.refreshToken = Json.str(root, "refreshToken");
                c.deviceId = Json.str(root, "deviceId");
            } catch (Exception ignore) {
            }
        }
        if (c.deviceId.length() == 0) c.deviceId = "eink334691225" + digits(19);
        return c;
    }

    public static void save(Store store, Cred c) {
        store.setEinkCred("{\"vid\":" + Json.quote(c.vid)
                + ",\"accessToken\":" + Json.quote(c.accessToken)
                + ",\"refreshToken\":" + Json.quote(c.refreshToken)
                + ",\"deviceId\":" + Json.quote(c.deviceId) + "}");
    }

    public static void clear(Store store) {
        store.setEinkCred("");
    }

    // ---------------- 授权流程 ----------------

    public static Ticket ticket(boolean compat) throws IOException {
        Object root = Json.parse(call(BASE + "/wxticket?nonceStr=weread", "GET", null, null, compat));
        Ticket t = new Ticket();
        t.signature = Json.str(root, "signature");
        t.timestamp = Json.str(root, "timeStamp");
        if (t.signature.length() == 0 || t.timestamp.length() == 0) {
            throw new IOException("客户端二维码票据无效");
        }
        return t;
    }

    public static String qrUuid(Ticket t, boolean compat) throws IOException {
        String url = "https://open.weixin.qq.com/connect/sdk/qrconnect"
                + "?appid=wxab9b71ad2b90ff34&noncestr=weread"
                + "&timestamp=" + Protocol.encode(t.timestamp)
                + "&scope=snsapi_userinfo%2Csnsapi_timeline%2Csnsapi_friend"
                + "&signature=" + Protocol.encode(t.signature);
        Object root = Json.parse(call(url, "GET", null, null, compat));
        int err = Json.num(root, "errcode");
        String uuid = Json.str(root, "uuid");
        if (err != 0 || uuid.length() == 0) throw new IOException("微信未返回授权二维码 errcode=" + err);
        return uuid;
    }

    public static String qrUrl(String uuid) {
        return "https://open.weixin.qq.com/connect/confirm?uuid=" + Protocol.encode(uuid);
    }

    public static Poll poll(String uuid, String last, boolean compat) throws IOException {
        String url = "https://long.open.weixin.qq.com/connect/l/qrconnect?f=json&uuid="
                + Protocol.encode(uuid);
        if (last.length() > 0) url = url + "&last=" + Protocol.encode(last);
        Object root = Json.parse(call(url, "GET", null, null, compat));
        int code = Json.num(root, "wx_errcode");
        Poll p = new Poll();
        if (code == 405) {
            p.wxCode = Json.str(root, "wx_code");
            p.state = p.wxCode.length() > 0 ? Poll.DONE : Poll.DECLINED;
        } else if (code == 402) {
            p.state = Poll.TIMEOUT;
        } else if (code == 404 || code == 408) {
            p.state = Poll.WAIT;
            p.last = String.valueOf(code);
        } else {
            p.state = Poll.DECLINED;
        }
        return p;
    }

    public static Cred login(String wxCode, String deviceId, boolean compat) throws IOException {
        Cred c = postLogin(wxCode, deviceId, "", compat);
        c.deviceId = deviceId;
        if (!c.ok()) throw new IOException("客户端授权未返回完整凭证");
        return c;
    }

    /** accessToken 过期后用 refreshToken 续期。 */
    public static Cred refresh(Cred old, boolean compat) throws IOException {
        Cred c = postLogin("", old.deviceId, old.refreshToken, compat);
        c.deviceId = old.deviceId;
        if (!c.ok()) throw new IOException("客户端续期失败");
        return c;
    }

    private static Cred postLogin(String wxCode, String deviceId, String refreshToken,
                                  boolean compat) throws IOException {
        long ts = System.currentTimeMillis();
        int random = 1 + new Random().nextInt(999);
        String signature = sha256(String.valueOf(ts) + deviceId + random);
        StringBuilder b = new StringBuilder();
        b.append("{\"deviceId\":").append(Json.quote(deviceId))
                .append(",\"deviceName\":\"BOOX\",\"deviceType\":3")
                .append(",\"timestamp\":").append(ts)
                .append(",\"random\":").append(random)
                .append(",\"trackId\":\"\"")
                .append(",\"signature\":").append(Json.quote(signature));
        if (refreshToken.length() > 0) {
            b.append(",\"refreshToken\":").append(Json.quote(refreshToken))
                    .append(",\"inBackground\":0,\"kickType\":1,\"refCgi\":\"\"");
        } else {
            b.append(",\"code\":").append(Json.quote(wxCode))
                    .append(",\"appFirstInstall\":1")
                    .append(",\"installId\":").append(Json.quote("eink31" + digits(26)))
                    .append(",\"isAutoLogout\":0,\"isFromQrcode\":1");
        }
        b.append("}");
        Object root = Json.parse(call(BASE + "/login", "POST", b.toString(), null, compat));
        Cred c = new Cred();
        c.vid = Json.str(root, "vid");
        c.accessToken = Json.str(root, "accessToken");
        c.refreshToken = Json.str(root, "refreshToken");
        return c;
    }

    // ---------------- 书架写操作 ----------------

    /** 真正从账号书架移除（不可撤销，需要在微信读书里重新加入）。 */
    public static void removeFromShelf(Store store, Cred cred, String bookId) throws IOException {
        if (bookId.length() == 0 || bookId.startsWith("local:")) {
            throw new IOException("无效的书籍 ID");
        }
        String body = "{\"bookIds\":[" + Json.quote(bookId) + "]}";
        Cred c = cred;
        try {
            String r = call(BASE + "/shelf/delete", "POST", body, c, store.compat());
            Trace.i("EINK shelf/delete " + bookId + " -> " + r);
            int err = Json.num(Json.parse(r), "errcode");
            if (err != 0) throw new IOException("移出失败 errcode=" + err);
        } catch (IOException first) {
            // accessToken 过期：续期后只重试一次
            if (!isAuthError(first)) throw first;
            Cred renewed = refresh(c, store.compat());
            save(store, renewed);
            String r = call(BASE + "/shelf/delete", "POST", body, renewed, store.compat());
            Trace.i("EINK shelf/delete(retry) " + bookId + " -> " + r);
            int err = Json.num(Json.parse(r), "errcode");
            if (err != 0) throw new IOException("移出失败 errcode=" + err);
        }
    }

    /** 只读校验：E-Ink 侧书架是否可访问（授权后用一次）。 */
    public static void verify(Store store, Cred cred) throws IOException {
        String r = call(BASE + "/shelf/sync", "GET", null, cred, store.compat());
        Object root = Json.parse(r);
        if (!(root instanceof java.util.Map)
                || !((java.util.Map<?, ?>) root).containsKey("books")) {
            throw new IOException("客户端书架验证失败");
        }
    }

    // ---------------- 想法（只读） ----------------
    /**
     * 某一章的书友想法。网页会话取不到这块数据，只有 E-Ink 授权后才有，
     * accessToken 过期时自动续期重试一次。
     */
    public static java.util.List<java.util.Map<String, Object>> reviews(
            Store store, Cred cred, String bookId, String chapterUid, int maxIdx)
            throws IOException {
        String url = BASE + "/review/list?bookId=" + Protocol.encode(bookId)
                + "&chapterUid=" + Protocol.encode(chapterUid)
                + "&range=1&maxIdx=" + maxIdx + "&count=20&synckey=0";
        Cred c = cred;
        String body;
        try {
            body = call(url, "GET", null, c, store.compat());
        } catch (IOException first) {
            if (!isAuthError(first)) throw first;
            c = refresh(c, store.compat());
            save(store, c);
            body = call(url, "GET", null, c, store.compat());
        }
        Object root = Json.parse(body);
        int err = Json.num(root, "errcode");
        if (err != 0) throw new IOException("想法拉取失败 errcode=" + err);
        return Api.reviewRows(body);
    }

    // ---------------- 阅读进度 ----------------

    /** E-Ink 侧的单书精确进度（GET /book/getProgress）。 */
    public static final class Progress {
        public String chapterUid = "";
        public int chapterOffset;
        public int percent;          // 0~100
        public long updated;         // 云端更新时间（秒）
        public int readSeconds;      // recordReadingTime
        public boolean finished;

        public boolean ok() {
            return chapterUid.length() > 0 || percent > 0 || updated > 0;
        }
    }

    /**
     * 拉取一本书在云端的精确进度。只带 bookId、走 vid/accessToken，
     * 和 /shelf/sync 同一套鉴权，accessToken 过期自动续期重试一次。
     */
    public static Progress getProgress(Store store, Cred cred, String bookId, boolean compat)
            throws IOException {
        if (bookId == null || bookId.length() == 0) throw new IOException("缺少 bookId");
        String url = BASE + "/book/getProgress?bookId=" + Protocol.encode(bookId);
        Cred c = cred;
        String body;
        try {
            body = call(url, "GET", null, c, compat);
        } catch (IOException first) {
            if (!isAuthError(first)) throw first;
            c = refresh(c, compat);
            save(store, c);
            body = call(url, "GET", null, c, compat);
        }
        int err = Json.num(Json.parse(body), "errcode");
        if (err != 0) throw new IOException("进度拉取失败 errcode=" + err);
        return parseProgress(body);
    }

    /**
     * 解析 getProgress 返回体，兼容顶层与 book 套一层两种结构。纯函数，SelfTest 直接跑。
     */
    static Progress parseProgress(String body) {
        Progress p = new Progress();
        if (body == null || body.length() == 0) return p;
        Object root;
        try {
            root = Json.parse(body);
        } catch (Exception e) {
            return p;
        }
        Object book = Json.map(root, "book");
        Object src = ((java.util.Map<?, ?>) book).isEmpty() ? root : book;
        // uid 也接受数字：str 会把 Number 转成字符串
        p.chapterUid = strOf(src, "chapterUid", "chapterId", "chapter_uid");
        if (p.chapterUid.length() == 0) p.chapterUid = strOf(root, "chapterUid", "chapterId");
        p.chapterOffset = numOf(src, "chapterOffset", "chapterPos", "offset");
        p.percent = numOf(src, "progress", "readingProgress");
        p.updated = Sync.normalizeTimestamp(longOf(src, "updateTime", "updatedAt", "update_time"));
        p.readSeconds = numOf(src, "recordReadingTime", "readingTime");
        p.finished = numOf(src, "finishTime", "finish") > 0;
        return p;
    }

    private static String strOf(Object o, String... keys) {
        return Json.str(o, keys);
    }

    /** 依次尝试多个 key，返回第一个非零整数（0 也视为缺省，继续找下一个）。 */
    private static int numOf(Object o, String... keys) {
        for (String k : keys) {
            int v = Json.num(o, k);
            if (v != 0) return v;
        }
        return 0;
    }

    private static long longOf(Object o, String... keys) {
        for (String k : keys) {
            long v = Json.longNum(o, k);
            if (v != 0L) return v;
        }
        return 0L;
    }

    private static boolean isAuthError(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("-2012") || m.contains("401") || m.contains("登录");
    }

    // ---------------- HTTP ----------------

    private static String call(String url, String method, String body, Cred cred, boolean compat)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(Tls.get(compat));
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("baseapi", "30");
            conn.setRequestProperty("appver", "2.1.2.10245900");
            conn.setRequestProperty("basever", "2.1.2.10245900");
            conn.setRequestProperty("osver", "11");
            conn.setRequestProperty("channelId", "900");
            conn.setRequestProperty("User-Agent", UA);
            if (cred != null && cred.accessToken.length() > 0) {
                conn.setRequestProperty("vid", cred.vid);
                conn.setRequestProperty("accessToken", cred.accessToken);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                byte[] data = Protocol.utf8(body);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IOException("HTTP " + code + " " + clip(text));
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return Protocol.utf8Decode(out.toByteArray());
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    // ---------------- 工具 ----------------

    private static String digits(int count) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? 1 + r.nextInt(8) : r.nextInt(10));
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Protocol.utf8(s));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
