package com.modu.wereadlite;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 本地书：导入 TXT / EPUB，按章切好存到应用私有目录，书架里跟云端书混排。
 *
 * 目录结构：files/books/l<时间戳>/meta.json + ch0.txt、ch1.txt…
 * 一章一个文件，墨水屏内存小，读一章只加载一章。
 */
public final class LocalBooks {

    public static final String PREFIX = "local:";

    /** 「第X章 / 第X回 / 卷…」这类标题行。注意很多 TXT 章首缩进用的是全角空格，要一起吃掉。 */
    private static final Pattern CHAPTER = Pattern.compile(
            "^[ \\t\\u00A0\\u3000]*(第[0-9一二三四五六七八九十百千零〇两]{1,10}[章回节卷篇集部][^\\n]{0,30})[ \\t\\u00A0\\u3000]*$",
            Pattern.MULTILINE);

    private LocalBooks() {
    }

    public static boolean isLocal(String bookId) {
        return bookId != null && bookId.startsWith(PREFIX);
    }

    /** 认不认这个文件名（选择器和批量导入共用一套判断，避免两处不一致）。 */
    public static boolean supported(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();   // 有些下载工具会在扩展名后留空格
        return n.endsWith(".txt") || n.endsWith(".epub");
    }

    /**
     * 递归收集目录下可导入的文件，depth 为还能往下几层。
     * 有的人习惯一本书一个文件夹，所以默认往里找三层。
     */
    public static List<File> scanSupported(File dir, int depth) {
        List<File> out = new ArrayList<File>();
        if (dir == null || depth < 0) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        List<File> files = new ArrayList<File>();
        List<File> dirs = new ArrayList<File>();
        for (File f : kids) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) dirs.add(f);
            else if (supported(f.getName())) files.add(f);
        }
        java.util.Collections.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        java.util.Collections.sort(dirs, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        out.addAll(files);
        if (depth > 0) {
            for (File d : dirs) out.addAll(scanSupported(d, depth - 1));
        }
        return out;
    }

    private static File root(Context c) {
        File dir = new File(c.getFilesDir(), "books");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File dirOf(Context c, String bookId) {
        String key = bookId.startsWith(PREFIX) ? bookId.substring(PREFIX.length()) : bookId;
        return new File(root(c), key);
    }

    // ---------------- 书架条目 ----------------

    /** 所有本地书，按导入时间倒序。 */
    public static List<Map<String, Object>> list(Context c) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        List<File> sorted = new ArrayList<File>();
        for (File d : dirs) if (d.isDirectory()) sorted.add(d);
        // 目录名带时间戳，倒序即最近导入在前
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (sorted.get(j).getName().compareTo(sorted.get(i).getName()) > 0) {
                    File t = sorted.get(i);
                    sorted.set(i, sorted.get(j));
                    sorted.set(j, t);
                }
            }
        }
        for (File d : sorted) {
            Map<String, Object> meta = readMeta(d);
            if (meta == null) continue;
            // 封面是本地文件，转成 file: 绝对路径给 Covers.load
            String cover = Json.str(meta, "cover");
            if (cover != null && cover.length() > 0) {
                File img = new File(d, cover);
                if (img.exists()) meta.put("cover", "file:" + img.getAbsolutePath());
            }
            out.add(toShelfRow(d.getName(), meta));
        }
        return out;
    }

    /** 单本的 meta（书架、阅读器都会用）；cover 字段补成本地绝对路径。 */
    public static Map<String, Object> meta(Context c, String bookId) {
        Map<String, Object> meta = readMeta(dirOf(c, bookId));
        if (meta != null) {
            String cover = Json.str(meta, "cover");
            if (cover != null && cover.length() > 0) {
                File img = new File(dirOf(c, bookId), cover);
                if (img.exists()) meta.put("cover", "file:" + img.getAbsolutePath());
            }
        }
        return meta;
    }

    private static Map<String, Object> toShelfRow(String key, Map<String, Object> meta) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("bookId", PREFIX + key);
        row.put("title", Json.str(meta, "title"));
        row.put("author", Json.str(meta, "author"));
        row.put("cover", Json.str(meta, "cover"));
        row.put("secret", Integer.valueOf(0));
        row.put("local", Boolean.TRUE);
        row.put("format", Json.str(meta, "format"));
        row.put("chapterCount", Integer.valueOf(Json.num(meta, "chapters")));
        return row;
    }

    private static Map<String, Object> readMeta(File dir) {
        File f = new File(dir, "meta.json");
        if (!f.exists()) return null;
        try {
            String s = new String(readFile(f), "UTF-8");
            return Json.asMap(Json.parse(s));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 阅读器接口 ----------------

    /** 与 api.catalog 同结构，供目录抽屉直接使用；EPUB 优先用 toc 的层级目录。 */
    public static List<Map<String, Object>> catalog(Context c, String bookId) throws IOException {
        Map<String, Object> meta = meta(c, bookId);
        if (meta == null) throw new IOException("本地书已丢失");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<Object> toc = Json.list(meta, "toc");
        List<Object> titles = Json.list(meta, "titles");
        List<Object> words = Json.list(meta, "words");
        for (int i = 0; i < toc.size(); i++) {
            Map<String, Object> t = Json.asMap(toc.get(i));
            int ci = (int) Json.num(t, "c");
            if (ci < 0 || ci >= titles.size()) continue;
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("uid", String.valueOf(ci));
            row.put("title", Json.str(t, "t"));
            row.put("level", Integer.valueOf((int) Math.max(1, Json.num(t, "l"))));
            row.put("wordCount", words.size() > ci ? words.get(ci) : Integer.valueOf(0));
            out.add(row);
        }
        if (out.isEmpty()) {
            for (int i = 0; i < titles.size(); i++) {
                Map<String, Object> row = new HashMap<String, Object>();
                row.put("uid", String.valueOf(i));
                row.put("title", String.valueOf(titles.get(i)));
                row.put("level", Integer.valueOf(1));
                row.put("wordCount", words.size() > i ? words.get(i) : Integer.valueOf(0));
                out.add(row);
            }
        }
        if (out.isEmpty()) throw new IOException("本地书没有内容");
        // toc 顺序基本等于 spine 顺序，但万一不齐，按章节下标排一次保证阅读顺序
        java.util.Collections.sort(out, new java.util.Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Store.parseInt(String.valueOf(a.get("uid")))
                        - Store.parseInt(String.valueOf(b.get("uid")));
            }
        });
        return out;
    }

    public static String chapterText(Context c, String bookId, String uid) throws IOException {
        int index = Store.parseInt(uid);
        File f = new File(dirOf(c, bookId), "ch" + index + ".txt");
        if (!f.exists()) throw new IOException("章节缺失");
        return new String(readFile(f), "UTF-8");
    }

    public static void delete(Context c, String bookId) {
        deleteDir(dirOf(c, bookId));
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }

    // ---------------- 导入 ----------------

    /** 导入一个文件，返回 bookId；解析失败抛异常（消息可直接给用户看）。 */
    public static String importFile(Context c, Uri uri, String displayName) throws Exception {
        File tmp = new File(c.getCacheDir(), "import.tmp");
        copy(c, uri, tmp);
        try {
            return importCore(c, tmp, displayName);
        } finally {
            tmp.delete();
        }
    }

    /** 直接按路径导入（自带文件选择器用）。 */
    public static String importPath(Context c, String path) throws Exception {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) throw new IOException("文件不可读，或没有存储权限");
        return importCore(c, f, f.getName());
    }

    private static String importCore(Context c, File tmp, String displayName) throws Exception {
        String name = displayName == null ? "" : displayName.trim();
        List<String[]> chapters;
        List<String[]> nav = null;      // [title, chapterIndex, level]
        String author = null;
        String bookTitle = null;
        byte[] cover = null;
        String coverExt = "jpg";
        String format;
        byte[] head = readHead(tmp, 4);
        boolean zip = head.length >= 2 && (head[0] & 0xFF) == 'P' && (head[1] & 0xFF) == 'K';
        if (zip) {
            EpubBook epub = parseEpub(tmp);
            chapters = epub.chapters;
            nav = epub.nav.isEmpty() ? null : epub.nav;
            author = epub.author;
            bookTitle = epub.title;
            cover = epub.cover;
            coverExt = epub.coverExt;
            format = "epub";
        } else {
            chapters = parseTxt(tmp);
            format = "txt";
        }
        if (chapters.isEmpty()) {
            throw new IOException("没有解析到正文，可能是不支持的加密格式");
        }

        // 「书名 (作者) .epub」这种名字要顺手去空格，否则书架上多一个尾空格
        String base = name.length() == 0 ? "本地书" : name.trim();
        String low = base.toLowerCase();
        if (low.endsWith(".txt")) base = base.substring(0, base.length() - 4).trim();
        else if (low.endsWith(".epub")) base = base.substring(0, base.length() - 5).trim();
        if (base.length() == 0) base = "本地书";
        // EPUB 元数据里的书名/作者更准，优先用
        if (bookTitle != null && bookTitle.length() > 0) base = bookTitle;
        if (author == null || author.length() == 0) author = "本地导入";

        String key = "l" + Long.toString(System.currentTimeMillis(), 36);
        File dir = new File(root(c), key);
        if (!dir.mkdirs()) throw new IOException("无法创建书目录");

        // 封面图落盘（书架 / 目录标题栏显示用）
        if (cover != null) {
            FileOutputStream co = null;
            try {
                co = new FileOutputStream(new File(dir, "cover." + coverExt));
                co.write(cover);
            } finally {
                if (co != null) try { co.close(); } catch (IOException ignored) { }
            }
        }

        List<String> titles = new ArrayList<String>();
        List<Integer> words = new ArrayList<Integer>();
        for (int i = 0; i < chapters.size(); i++) {
            String[] ch = chapters.get(i);
            String title = ch[0] == null || ch[0].length() == 0 ? ("第 " + (i + 1) + " 节") : ch[0];
            titles.add(title);
            words.add(Integer.valueOf(ch[1].length()));
            Writer w = null;
            try {
                w = new OutputStreamWriter(new FileOutputStream(new File(dir, "ch" + i + ".txt")),
                        "UTF-8");
                w.write(ch[1]);
            } finally {
                if (w != null) try { w.close(); } catch (IOException ignored) { }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"title\":").append(Json.quote(base));
        sb.append(",\"author\":").append(Json.quote(author));
        sb.append(",\"format\":").append(Json.quote(format));
        sb.append(",\"chapters\":").append(chapters.size());
        sb.append(",\"bytes\":").append(tmp.length());
        sb.append(",\"at\":").append(System.currentTimeMillis());
        sb.append(",\"cover\":").append(Json.quote(
                cover == null ? "" : ("cover." + coverExt)));
        sb.append(",\"titles\":[");
        for (int i = 0; i < titles.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(titles.get(i)));
        }
        sb.append("],\"words\":[");
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(words.get(i).intValue());
        }
        if (nav != null) {
            sb.append("],\"toc\":[");
            for (int i = 0; i < nav.size(); i++) {
                String[] n = nav.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"t\":").append(Json.quote(n[0]));
                sb.append(",\"c\":").append(Store.parseInt(n[1]));
                sb.append(",\"l\":").append(Math.max(1, Store.parseInt(n[2]))).append('}');
            }
        }
        sb.append("]}");

        Writer mw = null;
        try {
            mw = new OutputStreamWriter(new FileOutputStream(new File(dir, "meta.json")), "UTF-8");
            mw.write(sb.toString());
        } finally {
            if (mw != null) try { mw.close(); } catch (IOException ignored) { }
        }
        return PREFIX + key;
    }

    private static void copy(Context c, Uri uri, File dest) throws IOException {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("无法打开文件");
            out = new FileOutputStream(dest);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    // ---------------- TXT ----------------

    private static List<String[]> parseTxt(File f) throws IOException {
        String raw = decode(readFile(f));
        raw = raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String[]> out = new ArrayList<String[]>();
        Matcher m = CHAPTER.matcher(raw);
        List<int[]> marks = new ArrayList<int[]>();
        List<String> names = new ArrayList<String>();
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            names.add(m.group(1).trim());
        }
        if (marks.size() >= 2) {
            // 第一章之前的内容（序、前言）也保留
            String preface = raw.substring(0, marks.get(0)[0]).trim();
            if (preface.length() > 200) out.add(new String[]{"前言", preface});
            for (int i = 0; i < marks.size(); i++) {
                int from = marks.get(i)[1];
                int to = i + 1 < marks.size() ? marks.get(i + 1)[0] : raw.length();
                String body = raw.substring(from, to).trim();
                if (body.length() == 0) continue;
                out.add(new String[]{names.get(i), body});
            }
        } else {
            // 没有章节标题：按 8000 字一段切开，避免单文件过大
            int step = 8000;
            int total = raw.length();
            if (total == 0) return out;
            int index = 0;
            for (int p = 0; p < total; p += step) {
                String body = raw.substring(p, Math.min(total, p + step)).trim();
                if (body.length() == 0) continue;
                out.add(new String[]{total > step ? ("第 " + (index + 1) + " 节") : "正文", body});
                index++;
            }
        }
        return out;
    }

    // ---------------- EPUB ----------------

    /** EPUB 解析产物：章节正文 + 元数据 + 封面 + 带层级的目录。 */
    static final class EpubBook {
        final List<String[]> chapters = new ArrayList<String[]>();   // [title, text]
        final List<String[]> nav = new ArrayList<String[]>();        // [title, chapterIndex, level]
        String author = "";
        String title = "";
        byte[] cover;          // 封面图原始字节，可能为 null
        String coverExt = "jpg";
    }

    private static EpubBook parseEpub(File f) throws Exception {
        EpubBook book = new EpubBook();
        ZipFile zip = new ZipFile(f);
        try {
            String container = new String(readEntry(zip, "META-INF/container.xml"), "UTF-8");
            String opfPath = attr(container, "rootfile", "full-path");
            if (opfPath == null) throw new IOException("EPUB 缺少 OPF");
            String opf = new String(readEntry(zip, opfPath), "UTF-8");
            String baseDir = parent(opfPath);

            // opf 元数据：书名 / 作者 / 封面
            book.title = firstTag(opf, "title");
            book.author = firstTag(opf, "creator");
            String coverHref = null;
            // 方式一：manifest 里 properties="cover-image"（EPUB3）
            Matcher item = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
            Map<String, String> hrefById = new HashMap<String, String>();
            Map<String, String> typeById = new HashMap<String, String>();
            String ncxHref = null;
            while (item.find()) {
                String tag = item.group();
                String id = attr(tag, null, "id");
                String href = attr(tag, null, "href");
                String type = attr(tag, null, "media-type");
                String props = attr(tag, null, "properties");
                if (id == null || href == null) continue;
                hrefById.put(id, href);
                if (type != null) typeById.put(id, type);
                if (props != null && props.contains("cover-image")) coverHref = href;
                if (type != null && type.contains("dtbncx")) ncxHref = href;
            }
            // 方式二：<meta name="cover" content="图片id">（EPUB2 惯例）
            if (coverHref == null) {
                Matcher meta = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
                while (meta.find()) {
                    String tag = meta.group();
                    if (!"cover".equalsIgnoreCase(attr(tag, null, "name"))) continue;
                    String id = attr(tag, null, "content");
                    String href = id == null ? null : hrefById.get(id);
                    String type = id == null ? null : typeById.get(id);
                    if (href != null && type != null && type.startsWith("image/")) {
                        coverHref = href;
                        break;
                    }
                }
            }
            if (coverHref != null) {
                byte[] img = readEntry(zip, join(baseDir, coverHref));
                if (img != null && img.length > 128) {
                    book.cover = img;
                    String low = coverHref.toLowerCase();
                    book.coverExt = low.endsWith(".png") ? "png"
                            : (low.endsWith(".gif") ? "gif" : "jpg");
                }
            }

            // 目录（toc.ncx）：递归 navPoint 记层级；EPUB3 的 nav.xhtml 也兜一把
            List<String[]> navRaw = new ArrayList<String[]>();   // [href, title, level]
            if (ncxHref != null) {
                String ncx = new String(readEntry(zip, join(baseDir, ncxHref)), "UTF-8");
                Matcher np = Pattern.compile("<navPoint\\b[^>]*>|</navPoint>",
                        Pattern.CASE_INSENSITIVE).matcher(ncx);
                int depth = 0;
                while (np.find()) {
                    if (np.group().startsWith("</")) {
                        if (depth > 0) depth--;
                        continue;
                    }
                    depth++;
                    String block = ncx.substring(np.end(),
                            Math.min(ncx.length(), np.end() + 1500));
                    String src = attr(block, "content", "src");
                    Matcher t = Pattern.compile("<text>(.*?)</text>",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(block);
                    if (src != null && t.find()) {
                        String title = Protocol.htmlToText(t.group(1)).trim();
                        if (title.length() > 0) {
                            // 和 spine 页一样按规范化路径存，后面才能对上
                            navRaw.add(new String[]{strip(join(baseDir, src)), title,
                                    String.valueOf(depth)});
                        }
                    }
                }
            }

            // spine 顺序取正文：扉页 / 版权页这类短页也保留（官方目录里它们是独立条目），
            // 只跳过纯封面页和近乎空白的页——封面自己有图展示，留着反而重复。
            Matcher ref = Pattern.compile("<itemref\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
            List<String[]> pages = new ArrayList<String[]>();    // [zip路径, title, text]
            List<Integer> pageSp = new ArrayList<Integer>();     // 与 pages 对齐：spine 序号
            Map<String, Integer> spinePos = new HashMap<String, Integer>();  // zip路径 -> spine 序号
            int sp = 0;
            while (ref.find()) {
                String idref = attr(ref.group(), null, "idref");
                if (idref == null) continue;
                String href = hrefById.get(idref);
                if (href == null) continue;
                sp++;
                String path = join(baseDir, href);
                if (dupPage(pages, path)) continue;              // 同一文件多次进 spine
                byte[] bytes = readEntry(zip, path);
                if (bytes == null) continue;
                String low = strip(href).toLowerCase();
                String text = Protocol.htmlToText(decode(bytes)).trim();
                if (low.contains("cover") || text.length() < 8) {
                    spinePos.put(path, Integer.valueOf(sp));     // 封面 / 空白页：只记位置不进正文
                    continue;
                }
                spinePos.put(path, Integer.valueOf(sp));
                String title = null;
                for (String[] n : navRaw) {
                    if (n[0].equals(path)) { title = n[1]; break; }
                }
                if (title == null || title.length() == 0) {
                    Matcher tm = Pattern.compile("<title>(.*?)</title>",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(
                            new String(bytes, "UTF-8"));
                    title = tm.find() ? Protocol.htmlToText(tm.group(1)).trim() : "";
                }
                if (title == null || title.length() == 0) {
                    title = "第 " + (pages.size() + 1) + " 节";
                }
                pages.add(new String[]{path, title, dropLeading(text, title)});
                pageSp.add(Integer.valueOf(sp));
            }

            // 目录树：nav 指向的页可能被跳过（封面），归到它后面第一个保留章节
            for (String[] n : navRaw) {
                Integer s = spinePos.get(n[0]);
                if (s == null) continue;
                int ci = -1;
                for (int i = 0; i < pageSp.size(); i++) {
                    if (pageSp.get(i).intValue() >= s.intValue()) {
                        ci = i;
                        break;
                    }
                }
                if (ci >= 0 && ci < pages.size()) {
                    book.nav.add(new String[]{n[1], String.valueOf(ci), n[2]});
                }
            }

            for (String[] p : pages) {
                book.chapters.add(new String[]{p[1], p[2]});
            }
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }
        return book;
    }

    /** 同一文件在 spine 里出现多次（EPUB 偶见），只收第一次。 */
    private static boolean dupPage(List<String[]> pages, String path) {
        for (String[] p : pages) {
            if (p[0].equals(path)) return true;
        }
        return false;
    }

    /** 取 <tag> 文本；兼容 dc:title / dc:creator 这类带命名空间前缀的标签。 */
    private static String firstTag(String src, String tag) {
        Matcher m = Pattern.compile("<(?:[\\w.-]+:)?(?:" + tag + ")\\b[^>]*>([\\s\\S]{0,400}?)</(?:[\\w.-]+:)?(?:" + tag + ")>",
                Pattern.CASE_INSENSITIVE).matcher(src);
        if (!m.find()) return "";
        return Protocol.htmlToText(m.group(1)).trim();
    }

    /** 去掉首尾空白，包括全角空格和不间断空格（String.trim 只认 <= 0x20）。 */
    private static String trimU(String s) {
        if (s == null) return "";
        int a = 0;
        int b = s.length();
        while (a < b && isSpaceU(s.charAt(a))) a++;
        while (b > a && isSpaceU(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isSpaceU(char ch) {
        return ch <= ' ' || ch == '\u00A0' || ch == '\u3000';
    }

    /** 去掉正文开头重复的章节标题（EPUB 里 <title> 和 <h1> 都会被抽出来）。 */
    private static String dropLeading(String text, String title) {
        if (text == null || title == null || title.length() == 0) return text;
        String body = text;
        for (int i = 0; i < 2; i++) {
            String t = body.trim();
            if (!t.startsWith(title)) break;
            body = t.substring(title.length()).trim();
        }
        return body.length() == 0 ? text : body;
    }

    private static String strip(String href) {
        int i = href.indexOf('#');
        return i >= 0 ? href.substring(0, i) : href;
    }

    private static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i + 1) : "";
    }

    private static String join(String baseDir, String href) {
        String path = href;
        if (baseDir.length() > 0 && !path.startsWith("/")) path = baseDir + path;
        // 处理 ../ 与 ./
        List<String> parts = new ArrayList<String>();
        String[] seg = path.split("/");
        for (String s : seg) {
            if (s.length() == 0 || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        String out = sb.toString();
        try {
            out = java.net.URLDecoder.decode(out, "UTF-8");
        } catch (Exception ignored) { }
        return out;
    }

    /** 取标签里某个属性的值；tagName 为 null 表示直接在字符串里找。 */
    private static String attr(String src, String tagName, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*([\"'])(.*?)\\1",
                Pattern.CASE_INSENSITIVE).matcher(src);
        return m.find() ? m.group(2) : null;
    }

    private static byte[] readEntry(ZipFile zip, String path) throws IOException {
        ZipEntry e = zip.getEntry(path);
        if (e == null) return null;
        InputStream in = zip.getInputStream(e);
        try {
            return readAll(in);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ---------------- 编解码 ----------------

    /** UTF-8 优先，失败退回 GB18030（兼容 GBK/GB2312 的 txt）。 */
    private static String decode(byte[] b) {
        int off = 0;
        if (b.length > 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB
                && (b[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        String s = tryDecode(b, off, "UTF-8");
        if (s != null) return s;
        s = tryDecode(b, off, "GB18030");
        if (s != null) return s;
        return new String(b, off, b.length - off, Charset.forName("UTF-8"));
    }

    private static String tryDecode(byte[] b, int off, String charset) {
        try {
            CharsetDecoder d = Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = d.decode(ByteBuffer.wrap(b, off, b.length - off));
            return cb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readHead(File f, int n) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[n];
            int read = in.read(buf);
            if (read <= 0) return new byte[0];
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        } catch (Exception e) {
            return new byte[0];
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
