package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * 封面加载：内存 LRU(60) + 磁盘缓存(cache/cover) + 3 线程串行下载。
 * 回调统一回到主线程，调用方用 tag 判断是否已复用。
 */
final class Covers {

    interface Callback {
        void onCover(String key, Bitmap bitmap);
    }

    private static final String TAG = "WereadLite";
    private static final int MEM_MAX = 60;

    private static final Map<String, Bitmap> MEM = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MEM_MAX;
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static File dir;
    private static boolean compat;

    private Covers() {
    }

    static void init(Context ctx, boolean compatMode) {
        compat = compatMode;
        if (dir == null) {
            dir = new File(ctx.getCacheDir(), "cover");
            if (!dir.exists()) dir.mkdirs();
        }
    }

    static void clearMemory() {
        synchronized (MEM) {
            MEM.clear();
        }
    }

    static void load(final String key, final String rawUrl, final int w, final int h,
                     final Callback cb) {
        Bitmap hit;
        synchronized (MEM) {
            hit = MEM.get(key);
        }
        if (hit != null) {
            cb.onCover(key, hit);
            return;
        }
        final String url = normalize(rawUrl);
        if (url.length() == 0 || w <= 0 || h <= 0) return;

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                File file = new File(dir, name(key) + ".png");
                try {
                    if (file.exists()) bmp = decode(file, w, h);
                } catch (Throwable ignore) {
                }
                if (bmp == null) {
                    try {
                        bmp = fetch(url, w, h);
                        if (bmp != null) write(bmp, file);
                    } catch (Throwable t) {
                        Log.w(TAG, "cover download failed: " + t);
                    }
                }
                if (bmp == null) return;
                synchronized (MEM) {
                    MEM.put(key, bmp);
                }
                final Bitmap out = bmp;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onCover(key, out);
                    }
                });
            }
        });
    }

    private static String normalize(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        return u;
    }

    private static String name(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static Bitmap fetch(String url, int w, int h) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(Tls.get(compat));
            }
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Protocol.UA);
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            conn.setRequestProperty("Referer", Protocol.BASE + "/");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] data = out.toByteArray();
            if (data.length < 512) return null;
            return decode(data, w, h);
        } finally {
            conn.disconnect();
        }
    }

    private static Bitmap decode(File file, int w, int h) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), probe);
        probe.inSampleSize = sample(probe.outWidth, probe.outHeight, w, h);
        probe.inJustDecodeBounds = false;
        probe.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), probe);
        return scale(bmp, w, h);
    }

    private static Bitmap decode(byte[] data, int w, int h) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, probe);
        probe.inSampleSize = sample(probe.outWidth, probe.outHeight, w, h);
        probe.inJustDecodeBounds = false;
        probe.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, probe);
        return scale(bmp, w, h);
    }

    private static int sample(int srcW, int srcH, int w, int h) {
        if (srcW <= 0 || srcH <= 0) return 1;
        int ratio = Math.min(srcW / Math.max(1, w), srcH / Math.max(1, h));
        int s = 1;
        while (s * 2 <= ratio && s < 8) s *= 2;
        return s;
    }

    private static Bitmap scale(Bitmap bmp, int w, int h) {
        if (bmp == null) return null;
        if (bmp.getWidth() == w && bmp.getHeight() == h) return bmp;
        try {
            Bitmap out = Bitmap.createScaledBitmap(bmp, w, h, true);
            if (out != bmp) bmp.recycle();
            return out;
        } catch (Throwable t) {
            return bmp;
        }
    }

    private static void write(Bitmap bmp, File file) {
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, os);
        } catch (Throwable ignore) {
        } finally {
            try {
                if (os != null) os.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
