package com.modu.wereadlite;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信读书 Web 协议：摘要、_e() 混淆、请求签名、章节分片解码。
 * 算法与 KOReader 插件 finlater/weread.koplugin、觅阅 MiuRead 的实现逐位一致。
 */
public final class Protocol {

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    public static final String BASE = "https://weread.qq.com";
    /** reader 页面取不到 token 时的兜底值。 */
    public static final String READER_TOKEN = "3c5c8717f3daf09iop3423zafeqoi";

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final Random RAND = new Random();

    private Protocol() {
    }

    // ---------- 摘要 ----------

    public static byte[] digest(String alg, byte[] data) {
        try {
            return MessageDigest.getInstance(alg).digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    public static String md5hex(byte[] data) {
        return hex(digest("MD5", data));
    }

    public static String md5hex(String s) {
        return md5hex(utf8(s));
    }

    public static String sha256hex(String s) {
        return hex(digest("SHA-256", utf8(s)));
    }

    public static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    // ---------- _e() ----------

    /** 微信读书的 _e()：数字走 9 位分块，字符串走逐字节 hex。 */
    public static String obf(Object value) {
        String s = String.valueOf(value);
        String d = md5hex(s);
        List<String> chunks = new ArrayList<String>();
        String kind;
        boolean digits = s.length() > 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                digits = false;
                break;
            }
        }
        if (digits) {
            kind = "3";
            for (int i = 0; i < s.length(); i += 9) {
                String part = s.substring(i, Math.min(i + 9, s.length()));
                chunks.add(Long.toString(Long.parseLong(part), 16));
            }
        } else {
            kind = "4";
            StringBuilder sb = new StringBuilder();
            for (byte b : utf8(s)) {
                sb.append(Integer.toHexString(b & 0xff));
            }
            chunks.add(sb.toString());
        }
        StringBuilder out = new StringBuilder();
        out.append(d, 0, 3).append(kind).append('2').append(d.substring(d.length() - 2));
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            out.append(String.format(Locale.US, "%02x", c.length())).append(c);
            if (i < chunks.size() - 1) out.append('g');
        }
        if (out.length() < 20) out.append(d, 0, 20 - out.length());
        out.append(md5hex(out.toString()), 0, 3);
        return out.toString();
    }

    // ---------- 签名 ----------

    public static String encode(String v) {
        StringBuilder sb = new StringBuilder();
        for (byte b : utf8(v)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** 按 key 字典序拼接（不含 s），再做 0x15051505 双向位运算。 */
    public static String sign(Map<String, ?> params) {
        List<String> keys = new ArrayList<String>();
        for (String k : params.keySet()) {
            if (!"s".equals(k)) keys.add(k);
        }
        Collections.sort(keys);
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) q.append('&');
            q.append(keys.get(i)).append('=').append(encode(scalar(params.get(keys.get(i)))));
        }
        return webSign(q.toString());
    }

    private static String scalar(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        return String.valueOf(v);
    }

    public static String webSign(String q) {
        byte[] b = utf8(q);
        int n = b.length;
        int a = 0x15051505;
        int c = 0x15051505;
        int i = n;
        while (i > 1) {
            a = (a ^ ((b[i - 1] & 0xff) << ((n - i + 1) % 30))) & 0x7fffffff;
            c = (c ^ ((b[i - 2] & 0xff) << ((i - 1) % 30))) & 0x7fffffff;
            i -= 2;
        }
        long sum = (a & 0xffffffffL) + (c & 0xffffffffL);
        return Long.toHexString(sum);
    }

    public static String readerUrl(String bookId, String chapterUid) {
        String u = BASE + "/web/reader/" + obf(bookId);
        if (chapterUid != null) u = u + "k" + obf(chapterUid);
        return u;
    }

    // ---------- 章节分片 ----------

    /** 分片体：前 32 字符是剩余内容的 MD5。 */
    public static String shardBody(String raw) {
        if (raw == null || raw.length() <= 32) return "";
        String sum = raw.substring(0, 32);
        String body = raw.substring(32);
        if (!md5hex(body).equalsIgnoreCase(sum)) {
            throw new IllegalStateException("chapter checksum mismatch");
        }
        return body;
    }

    public static String decodeParts(String[] parts) {
        StringBuilder all = new StringBuilder();
        if (parts != null) {
            for (String p : parts) all.append(shardBody(p));
        }
        String s = all.toString();
        if (s.length() == 0) return "";
        s = s.substring(1);
        int[] pos = positions(s);
        char[] c = s.toCharArray();
        for (int i = pos.length - 1; i >= 1; i -= 2) {
            int a = pos[i] + 1;
            int b = pos[i - 1] + 1;
            char t = c[a];
            c[a] = c[b];
            c[b] = t;
            a--;
            b--;
            t = c[a];
            c[a] = c[b];
            c[b] = t;
        }
        return utf8Decode(b64decode(new String(c)));
    }

    static int[] positions(String s) {
        int n = s.length();
        if (n < 4) return new int[0];
        if (n < 11) return new int[]{0, 2};
        int take = Math.min(4, (n + 9) / 10);
        StringBuilder pieces = new StringBuilder();
        for (int i = n; i >= n - take + 1; i--) {
            // 尾字符取二进制串，再把这个串按 4 进制解析（与 Lua tonumber(bin,4) 一致）
            pieces.append(Long.toString(Long.parseLong(Integer.toBinaryString(s.charAt(i - 1)), 4)));
        }
        String t = pieces.toString();
        int mod = n - take - 2;
        int step = String.valueOf(mod).length();
        List<Integer> out = new ArrayList<Integer>();
        int i = 1;
        while (out.size() < 10 && i + step - 1 < t.length()) {
            out.add(parse(t.substring(i - 1, i - 1 + step)) % mod);
            if (i + 1 <= t.length()) {
                out.add(parse(t.substring(i, Math.min(i + step, t.length()))) % mod);
            }
            i += step;
        }
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    private static int parse(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    static byte[] b64decode(String data) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == '-') ch = '+';
            else if (ch == '_') ch = '/';
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '+' || ch == '/' || ch == '=') {
                clean.append(ch);
            }
        }
        int pad = clean.length() % 4;
        if (pad != 0) {
            for (int i = 0; i < 4 - pad; i++) clean.append('=');
        }
        String s = clean.toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i += 4) {
            int n = (val(s.charAt(i)) << 18) | (val(s.charAt(i + 1)) << 12)
                    | (val(s.charAt(i + 2)) << 6) | val(s.charAt(i + 3));
            out.write((n >> 16) & 0xff);
            if (s.charAt(i + 2) != '=') out.write((n >> 8) & 0xff);
            if (s.charAt(i + 3) != '=') out.write(n & 0xff);
        }
        return out.toByteArray();
    }

    private static int val(char ch) {
        if (ch == '=') return 0;
        for (int i = 0; i < B64.length; i++) {
            if (B64[i] == ch) return i;
        }
        return 0;
    }

    static String utf8Decode(byte[] data) {
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return new String(data);
        }
    }

    // ---------- XHTML -> 纯文本 ----------

    public static String htmlToText(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<\\s*br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</\\s*(p|div|h[1-6]|li|tr|blockquote|section)\\s*>", "\n");
        s = s.replaceAll("(?i)<\\s*(p|div|h[1-6]|li|tr|blockquote|section)[^>]*>", "\n");
        s = s.replaceAll("<[^>]*>", "");
        s = s.replace("&nbsp;", " ").replace("&ldquo;", "\u201c").replace("&rdquo;", "\u201d")
                .replace("&hellip;", "\u2026").replace("&mdash;", "\u2014")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
        s = decodeNumeric(s).replaceAll("&[a-zA-Z]+;", "");
        StringBuilder out = new StringBuilder(s.length());
        boolean lastBlank = true;
        for (String line : s.split("\n")) {
            String t = line.trim().replace('\u00a0', ' ');
            if (t.length() == 0) {
                if (!lastBlank) {
                    out.append('\n');
                    lastBlank = true;
                }
            } else {
                if (out.length() > 0 && !lastBlank) out.append('\n');
                out.append(t);
                lastBlank = false;
            }
        }
        return out.toString().trim();
    }

    /**
     * 去掉正文开头重复的章节标题行。
     *
     * 老版本导入的本地 EPUB，<title> 与 <h1> 的章节名都会被抽进正文，
     * 于是正文开头连续出现两行一样的标题（顶部标题栏已经显示一次，正文里
     * 就成了两遍）。这里按行去掉开头所有与 title 相同的行，忽略首尾空白和
     * 全角/半角空格差异。仅在「至少还剩一行正文」时才去，避免把只有标题的
     * 空章节清空。title 为空或正文不以标题开头时原样返回。
     */
    public static String dropRepeatTitle(String text, String title) {
        if (text == null || title == null) return text;
        String want = normTitle(title);
        if (want.length() == 0) return text;
        String[] lines = text.split("\n", -1);
        int drop = 0;
        while (drop < lines.length && normTitle(lines[drop]).equals(want)) drop++;
        if (drop == 0 || drop >= lines.length) return text; // 一行都别全删光
        StringBuilder out = new StringBuilder(text.length());
        for (int i = drop; i < lines.length; i++) {
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    /** 标题比较用的归一化：trim + 全角空格 / 不间断空格统一成普通空格。 */
    private static String normTitle(String s) {
        return s.replace('\u3000', ' ').replace('\u00a0', ' ').trim();
    }

    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");

    /**
     * 章节 HTML ↔ 纯文本坐标映射（写划线上传用）。
     *
     * 微信读书的划线 range 是「章节原始 HTML 的字符索引」（如 "383-415"，0 起、
     * 不含 BOM），官方阅读器靠这个把划线画回正文。我们是纯文本排版，所以加载
     * 章节时保留原始 HTML，选中后用这里的对齐算法把纯文本区间映射回 HTML 索引。
     *
     * 算法与 MiuRead 的 posmap 同思路：跳过标签字符、实体解码后映射到实体起点；
     * 空白差异（换行折叠、行尾 trim）在对齐时跳过。正文里偶发非 BMP 字符会差
     * 一两位，可接受。映射失败返回 null，调用方降级为仅本地划线。
     *
     * @return int[2] = {start, end}，HTML 0 起字符索引（BMP 内容即 rune 索引）
     */
    public static int[] htmlRange(String html, String plainText, int textStart, int textEnd) {
        if (html == null || html.length() == 0 || plainText == null) return null;
        if (html.startsWith("\ufeff")) html = html.substring(1);   // 官方 range 不含 BOM
        // 第一步：HTML → 可见文本流（跳过标签，实体解码），记录每个字符的 HTML 起止索引
        int[] runeIdx = new int[html.length()];
        int[] runeEnd = new int[html.length()];
        StringBuilder plain = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<') {
                int close = html.indexOf('>', i);
                if (close < 0) break;
                i = close + 1;
                continue;
            }
            if (c == '&') {
                int semi = html.indexOf(';', i);
                char dec = semi > i && semi - i <= 10
                        ? decodeEntity(html.substring(i + 1, semi)) : 0;
                if (dec != 0) {
                    runeIdx[plain.length()] = i;
                    runeEnd[plain.length()] = semi + 1;
                    plain.append(dec);
                    i = semi + 1;
                    continue;
                }
            }
            runeIdx[plain.length()] = i;
            runeEnd[plain.length()] = i + 1;
            plain.append(c);
            i++;
        }
        // 第二步：正文区间逐字符对齐到文本流（跳过空白差异）。
        // 对齐必须从 t=0 走起：选区之前的正文也要先消耗掉，否则中后段选区
        // 第一个字符就和流开头对不上，直接误判失败。
        int p = 0;
        int startIdx = -1;
        int endIdx = -1;
        int n = Math.min(textEnd, plainText.length());
        for (int t = 0; t < n; t++) {
            char tc = plainText.charAt(t);
            if (Character.isWhitespace(tc)) continue;
            while (p < plain.length() && plain.charAt(p) != tc) {
                if (!Character.isWhitespace(plain.charAt(p))
                        && plain.charAt(p) != '\u00a0') {
                    // 两边都是实字符却不相等：对齐失败，返回失败让调用方降级
                    return null;
                }
                p++;
            }
            if (p >= plain.length()) return null;
            if (t >= textStart) {
                if (startIdx < 0) startIdx = runeIdx[p];
                endIdx = runeEnd[p];
            }
            p++;
        }
        if (startIdx < 0 || endIdx <= startIdx) return null;
        return new int[]{startIdx, endIdx};
    }

    private static final String B64C =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /**
     * 标准 Base64（纯 Java，JVM 回归可直接跑；android.util.Base64 在测试桩上会炸）。
     * addBookmark 的 markText 走 UTF-8 字节的 Base64，这是协议要求。
     */
    public static String b64(String s) {
        byte[] b;
        try {
            b = s.getBytes("UTF-8");
        } catch (Exception e) {
            b = s.getBytes();
        }
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int v = (b[i] & 0xFF) << 16;
            int pad = 0;
            if (i + 1 < b.length) v |= (b[i + 1] & 0xFF) << 8;
            else pad++;
            if (i + 2 < b.length) v |= b[i + 2] & 0xFF;
            else pad++;
            sb.append(B64C.charAt((v >> 18) & 63)).append(B64C.charAt((v >> 12) & 63));
            sb.append(pad > 1 ? '=' : B64C.charAt((v >> 6) & 63));
            sb.append(pad > 0 ? '=' : B64C.charAt(v & 63));
        }
        return sb.toString();
    }

    private static char decodeEntity(String ent) {        if (ent == null || ent.length() == 0) return 0;
        if (ent.charAt(0) == '#') {
            try {
                int cp = ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                return cp > 0 && cp <= 0xFFFF ? (char) cp : 0;
            } catch (Exception e) {
                return 0;
            }
        }
        switch (ent) {
            case "nbsp": return ' ';
            case "ldquo": return '\u201c';
            case "rdquo": return '\u201d';
            case "hellip": return '\u2026';
            case "mdash": return '\u2014';
            case "amp": return '&';
            case "lt": return '<';
            case "gt": return '>';
            case "quot": return '"';
            case "apos": return '\'';
            default: return 0;
        }
    }

    /** 数字实体（&#10; / &#x4e2d;）必须还原成字符，否则正文里会冒出「10」「4e2d」这种数字。 */
    private static String decodeNumeric(String s) {
        Matcher m = NUMERIC.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = "";
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).length() > 0 ? 16 : 10);
                if (cp > 0 && cp <= 0xFFFF) rep = String.valueOf((char) cp);
            } catch (Exception ignored) {
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------- 进度上报 ----------

    public static String appId(String ua) {
        String[] parts = ua.trim().split("\\s+");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length && i < 12; i++) {
            prefix.append(parts[i].length() % 10);
        }
        int h = 0;
        for (byte b : utf8(ua)) {
            h = (131 * h + (b & 0xff)) & 0x7fffffff;
        }
        return "wb" + prefix + "h" + h;
    }

    public static int rand(int max) {
        return RAND.nextInt(max + 1);
    }

    private static long square(int v) {
        return (long) v * v;
    }

    public static Map<String, String> contentFields(String bookId, String chapterUid,
                                                    String psvts, boolean style) {
        long now = System.currentTimeMillis() / 1000L;
        if (obf(now).equals(String.valueOf(psvts))) now++;
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("b", obf(bookId));
        m.put("c", obf(chapterUid));
        m.put("r", String.valueOf(square(rand(9999))));
        m.put("ct", String.valueOf(now));
        m.put("ps", psvts == null ? "" : psvts);
        m.put("pc", obf(now));
        m.put("sc", "1");
        m.put("prevChapter", "false");
        m.put("st", style ? "1" : "0");
        m.put("s", sign(m));
        return m;
    }

    public static Map<String, String> readFields(String bookId, String chapterUid, int chapterIndex,
                                                 int offset, int progress, int elapsed,
                                                 String summary, String psvts, String pclts,
                                                 String token, String appId) {
        long now = System.currentTimeMillis() / 1000L;
        long ts = now * 1000L + rand(999);
        int rn = rand(999);
        String tk = (token == null || token.length() == 0) ? READER_TOKEN : token;
        String ps = psvts == null ? "" : psvts;
        // 阅读页里的 pclts 实测就是数字 0；照原样上报服务端只会回 {} 并丢弃进度，
        // 必须和网页端一样在缺省时改用 _e(ct)。
        String pc = (pclts == null || pclts.length() == 0 || "0".equals(pclts)) ? obf(now) : pclts;
        String sg = sha256hex(String.valueOf(ts) + rn + tk);
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("appId", appId);
        m.put("b", obf(bookId));
        m.put("c", obf(chapterUid));
        m.put("ci", String.valueOf(chapterIndex));
        m.put("co", String.valueOf(offset));
        m.put("sm", summary == null ? "" : (summary.length() > 20 ? summary.substring(0, 20) : summary));
        m.put("pr", String.valueOf(progress));
        m.put("rt", String.valueOf(elapsed));
        m.put("ts", String.valueOf(ts));
        m.put("rn", String.valueOf(rn));
        m.put("sg", sg);
        m.put("ct", String.valueOf(now));
        m.put("ps", ps);
        m.put("pc", pc);
        m.put("s", sign(m));
        return m;
    }

    // ---------- 请求体（类型必须与网页端一致：数字/布尔不能包成字符串） ----------

    /** 章节分片请求体：sc/st 为数字，prevChapter 为布尔。 */
    public static String contentJson(Map<String, String> f) {
        StringBuilder sb = new StringBuilder("{");
        sb.append(kv("b", f.get("b"))).append(',')
                .append(kv("c", f.get("c"))).append(',')
                .append(kv("r", f.get("r"))).append(',')
                .append(kv("ct", f.get("ct"))).append(',')
                .append(kv("ps", f.get("ps"))).append(',')
                .append(kv("pc", f.get("pc"))).append(',')
                .append(kn("sc", "1")).append(',')
                .append(kn("prevChapter", "false")).append(',')
                .append(kn("st", f.get("st"))).append(',')
                .append(kv("s", f.get("s")));
        return sb.append('}').toString();
    }

    /** 进度上报体：ci/co/pr/rt/rn/ct 为数字，ts 与签名类字段为字符串。 */
    public static String readJson(Map<String, String> f) {
        StringBuilder sb = new StringBuilder("{");
        sb.append(kv("appId", f.get("appId"))).append(',')
                .append(kv("b", f.get("b"))).append(',')
                .append(kv("c", f.get("c"))).append(',')
                .append(kn("ci", f.get("ci"))).append(',')
                .append(kn("co", f.get("co"))).append(',')
                .append(kv("sm", f.get("sm"))).append(',')
                .append(kn("pr", f.get("pr"))).append(',')
                .append(kn("rt", f.get("rt"))).append(',')
                .append(kv("ts", f.get("ts"))).append(',')
                .append(kn("rn", f.get("rn"))).append(',')
                .append(kv("sg", f.get("sg"))).append(',')
                .append(kn("ct", f.get("ct"))).append(',')
                .append(kv("ps", f.get("ps"))).append(',')
                .append(kv("pc", f.get("pc"))).append(',')
                .append(kv("s", f.get("s")));
        return sb.append('}').toString();
    }

    private static String kv(String k, String v) {
        return Json.quote(k) + ":" + Json.quote(v == null ? "" : v);
    }

    private static String kn(String k, String v) {
        return Json.quote(k) + ":" + (v == null || v.length() == 0 ? "0" : v);
    }
}
