package com.modu.wereadlite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 本地整包备份：把本地书文件 + Store 里的进度/书签/设置打成一个 ZIP。
 *
 * 走 SAF 导出到用户能取到的地方；重装或换设备后导入恢复。
 * 不备份登录会话（cookies/vid/skey/einkCred）——换设备本就要重新登录，
 * 也避免把凭据写进一个可能被分享出去的文件。
 *
 * 纯 Java 字节流，不碰 Context，便于 JVM 回归。
 *
 * ZIP 结构：
 *   meta.json    {"format":1,"app":"0.2.8","at":<毫秒>,"books":<本数>,"prefs":<项数>}
 *   prefs.json   {"fontSize":22,"bm_local:xxx":"[...]", ...}  值都以字符串保存
 *   books/<key>/meta.json、ch0.txt ...
 */
public final class Backup {

    static final String META = "meta.json";
    static final String PREFS = "prefs.json";
    static final String BOOKS = "books/";
    static final int FORMAT = 1;

    /** 只备份这些前缀/键；登录会话、授权凭证一律不进包。 */
    static boolean wantedPref(String k) {
        if (k == null) return false;
        return k.startsWith("lp_") || k.startsWith("mk_") || k.startsWith("rt_")
                || k.startsWith("bm_") || k.startsWith("rd_") || k.equals("einkRead")
                || k.startsWith("pd_") || k.startsWith("pl_at_")
                || k.startsWith("ps_at_") || k.startsWith("se_")
                || k.equals("fontSize") || k.equals("indentCells") || k.equals("linePct")
                || k.equals("marginDp") || k.equals("volumePaging")
                || k.equals("progressWholeBook") || k.equals("keepAwake") || k.equals("compat")
                || k.equals("syncOnOpen") || k.equals("syncOnClose")
                || k.equals("paraGap") || k.equals("paraGapN");
    }

    private Backup() {
    }

    /** 打包。booksDir 为 null 或不存在时只打 prefs。返回写入的字节数。 */
    static long write(OutputStream out, File booksDir, Map<String, String> prefs,
                      String appVersion, long at) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            List<File> bookFiles = collectBookFiles(booksDir);

            StringBuilder meta = new StringBuilder("{");
            meta.append("\"format\":").append(FORMAT).append(',');
            meta.append("\"app\":").append(Json.quote(appVersion == null ? "" : appVersion)).append(',');
            meta.append("\"at\":").append(at).append(',');
            meta.append("\"books\":").append(countBookDirs(booksDir)).append(',');
            meta.append("\"prefs\":").append(prefs == null ? 0 : prefs.size());
            meta.append('}');
            put(zip, META, meta.toString().getBytes("UTF-8"));

            StringBuilder p = new StringBuilder("{");
            boolean first = true;
            if (prefs != null) {
                // 排序，产出稳定、便于 diff 和回归比对
                List<String> keys = new ArrayList<String>(prefs.keySet());
                java.util.Collections.sort(keys);
                for (String k : keys) {
                    if (!wantedPref(k)) continue;
                    String v = prefs.get(k);
                    if (v == null) continue;
                    if (!first) p.append(',');
                    first = false;
                    p.append(Json.quote(k)).append(':').append(Json.quote(v));
                }
            }
            p.append('}');
            put(zip, PREFS, p.toString().getBytes("UTF-8"));

            String root = booksDir == null ? "" : booksDir.getAbsolutePath();
            for (File f : bookFiles) {
                String rel = f.getAbsolutePath();
                if (root.length() > 0 && rel.startsWith(root)) rel = rel.substring(root.length());
                while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
                rel = rel.replace(File.separatorChar, '/');
                put(zip, BOOKS + rel, readAll(f));
            }
        } finally {
            zip.finish();
            // 不 close：交给调用方管理流（SAF 的 OutputStream 由系统管理生命周期）
        }
        return 0;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } finally {
            in.close();
        }
    }

    private static List<File> collectBookFiles(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            if (f.isDirectory()) collectRecursive(f, out);
            else if (f.isFile() && !f.getName().startsWith(".")) out.add(f);
        }
        return out;
    }

    private static void collectRecursive(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collectRecursive(f, out);
            else if (f.isFile() && !f.getName().startsWith(".")) out.add(f);
        }
    }

    private static int countBookDirs(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) if (f.isDirectory() && new File(f, "meta.json").exists()) n++;
        return n;
    }

    /** 读 ZIP 头里的 meta 做校验，不落地任何数据。 */
    static Map<String, Object> readMeta(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (META.equals(e.getName())) {
                    String s = new String(readZipAll(zip), "UTF-8");
                    return Json.asMap(Json.parse(s));
                }
            }
        } finally {
            // 不 close 外层流
        }
        throw new IOException("不是有效的备份文件（缺少 meta）");
    }

    /**
     * 只恢复书文件。books/<key>/... 按 key 判断：本地已有这本就整本跳过，
     * 不覆盖正在读的书（进度/书签在 prefs 里单独合并，和书文件无关）。
     * existingKeys 是恢复前本地已有的目录名集合。返回实际写入的文件数。
     */
    static int restoreBooks(InputStream in, File booksDir, java.util.Set<String> existingKeys)
            throws IOException {
        int written = 0;
        ZipInputStream zip = new ZipInputStream(in);
        byte[] buf = new byte[8192];
        while (true) {
            ZipEntry e = zip.getNextEntry();
            if (e == null) break;
            String name = e.getName();
            if (name.equals(META) || name.equals(PREFS) || !name.startsWith(BOOKS)) continue;
            String rel = name.substring(BOOKS.length());
            if (rel.length() == 0 || rel.indexOf("..") >= 0) continue; // 防目录穿越
            int slash = rel.indexOf('/');
            String key = slash < 0 ? "" : rel.substring(0, slash);
            if (key.length() == 0 || booksDir == null) continue;
            if (existingKeys != null && existingKeys.contains(key)) continue;
            File target = new File(booksDir, rel.replace('/', File.separatorChar));
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream fout = new FileOutputStream(target);
            try {
                int n;
                while ((n = zip.read(buf)) > 0) fout.write(buf, 0, n);
            } finally {
                fout.close();
            }
            written++;
        }
        return written;
    }

    /** 从备份里列出所有书的 key（meta.json 所在目录），用于预览和去重计数。 */
    static java.util.Set<String> readBookKeys(InputStream in) throws IOException {
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith(BOOKS) || !name.endsWith("/meta.json")) continue;
                String rel = name.substring(BOOKS.length(), name.length() - "/meta.json".length());
                if (rel.indexOf('/') < 0 && rel.length() > 0) keys.add(rel);
            }
        } finally {
        }
        return keys;
    }

    /** 从 ZIP 里取出 prefs.json 解析成 Map。要和 restore 分开调用（各自独立打开流）。 */
    static Map<String, String> readPrefs(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (PREFS.equals(e.getName())) {
                    Map<String, Object> m = Json.asMap(Json.parse(new String(readZipAll(zip), "UTF-8")));
                    Map<String, String> out = new LinkedHashMap<String, String>();
                    for (Map.Entry<String, Object> kv : m.entrySet()) {
                        if (kv.getValue() != null) out.put(kv.getKey(), String.valueOf(kv.getValue()));
                    }
                    return out;
                }
            }
        } finally {
        }
        throw new IOException("备份文件缺少 prefs");
    }

    private static byte[] readZipAll(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = zip.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
