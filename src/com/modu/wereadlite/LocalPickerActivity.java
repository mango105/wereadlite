package com.modu.wereadlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自带文件选择器。很多墨水屏 ROM 没装 DocumentsUI，
 * ACTION_GET_CONTENT 会直接提示「没有应用可执行此操作」，所以自己列目录。
 *
 * 两个原则（踩过坑）：
 * 1) 目录里的文件全部列出来，哪怕格式不支持也列，只灰掉并标注原因——
 *    否则用户看到空目录会以为文件没读到；
 * 2) 存储根目录不写死，逐个探测 /storage、/mnt 下的可读目录，探不到的列出来给用户看。
 */
public class LocalPickerActivity extends Activity {

    private static final String TAG = "WRPick";

    /** 常见存储挂载点，按墨水屏 ROM 的实际情况排。 */
    private static final String[] CANDIDATES = {
            "/mnt/sdcard", "/sdcard", "/storage/sdcard0", "/storage/sdcard1",
            "/mnt/sdcard2", "/mnt/extsd", "/mnt/ext_sd", "/mnt/external_sd",
            "/mnt/udisk", "/mnt/usb_storage", "/mnt/media_rw/sdcard0",
            "/storage/emulated/0", "/storage/emulated/legacy", "/data/media/0"
    };

    private final List<File> roots = new ArrayList<File>();
    private final List<File> items = new ArrayList<File>();
    private final List<String> probes = new ArrayList<String>();
    private File current;
    private boolean rootMode;
    private int supportedCount;
    private boolean unreadable;

    private BaseAdapter adapter;
    private TextView pathLabel;
    private TextView countLabel;
    private TextView emptyLabel;
    private TextView upBtn;
    private TextView allBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        discoverRoots();
        if (roots.isEmpty()) {
            rootMode = true;
            refresh();
        } else if (roots.size() == 1) {
            current = roots.get(0);
            refresh();
        } else {
            // 多个存储（内置 + TF 卡）时先让用户选一个
            rootMode = true;
            refresh();
        }
    }

    // ---------------- 存储探测 ----------------

    private void discoverRoots() {
        Map<String, File> map = new LinkedHashMap<String, File>();
        try {
            addRoot(map, Environment.getExternalStorageDirectory());
        } catch (Exception e) {
            Log.w(TAG, "getExternalStorageDirectory: " + e);
        }
        for (String p : CANDIDATES) addRoot(map, new File(p));
        for (String base : new String[]{"/storage", "/mnt"}) {
            File[] kids = new File(base).listFiles();
            if (kids == null) continue;
            for (File f : kids) addRoot(map, f);
        }
        roots.addAll(map.values());
        Log.i(TAG, "roots=" + roots.size() + " " + map.keySet());
    }

    private void addRoot(Map<String, File> map, File f) {
        if (f == null) return;
        String name = f.getName();
        if ("asec".equals(name) || "obb".equals(name) || "secure".equals(name)
                || "shell".equals(name) || "media".equals(name)) return;
        if (!f.isDirectory()) {
            probes.add(f.getAbsolutePath() + "　不存在");
            return;
        }
        // canRead() 在 4.x 上会骗人，直接试着列一次
        String[] probe = f.list();
        if (probe == null) {
            Log.i(TAG, "skip unreadable " + f.getAbsolutePath());
            probes.add(f.getAbsolutePath() + "　读不到（未挂载 / 无权限）");
            return;
        }
        String key;
        try {
            key = f.getCanonicalPath();
        } catch (Exception e) {
            key = f.getAbsolutePath();
        }
        if (!map.containsKey(key)) map.put(key, f);
    }

    // ---------------- 界面 ----------------

    private View buildUi() {
        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = Ui.text(this, "‹", 26, Ui.INK);
        back.setPadding(dp(18), dp(6), dp(18), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "选择文件", 16, Ui.INK);
        titleRow.addView(title);
        countLabel = Ui.text(this, "", 10.5f, Ui.LIGHT);
        countLabel.setPadding(dp(8), dp(2), 0, 0);
        titleRow.addView(countLabel);
        titles.addView(titleRow);
        pathLabel = Ui.text(this, "", 10.5f, Ui.LIGHT);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        titles.addView(pathLabel);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        allBtn = Ui.text(this, "全导入", 12.5f, Ui.INK);
        allBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        allBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importAll();
            }
        });
        bar.addView(allBtn);

        upBtn = Ui.text(this, "上一级", 12.5f, Ui.INK);
        upBtn.setPadding(dp(10), dp(10), dp(16), dp(10));
        upBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        bar.addView(upBtn);

        rootView.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        rootView.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ListView list = new ListView(this);
        list.setBackgroundColor(Color.WHITE);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return rowOf(items.get(position));
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(items.get(position));
            }
        });
        rootView.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyLabel = Ui.text(this, "", 12, Ui.LIGHT);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setPadding(dp(24), dp(40), dp(24), dp(40));
        rootView.addView(emptyLabel);
        return rootView;
    }

    private View rowOf(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));

        boolean dir = f.isDirectory();
        boolean ok = !dir && LocalBooks.supported(f.getName());
        CharSequence name = dir ? "「" + f.getName() + "」" : f.getName();
        TextView nameView = Ui.text(this, name, 14.5f,
                dir ? Ui.SUB : (ok ? Ui.INK : Ui.LIGHT));
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(nameView);

        String meta;
        if (dir) {
            meta = pathOf(f);
        } else if (ok) {
            meta = sizeText(f.length()) + " · " + extOf(f.getName()).toUpperCase();
        } else {
            String ext = extOf(f.getName());
            meta = sizeText(f.length()) + " · "
                    + (ext.length() == 0 ? "无扩展名" : ext.toUpperCase())
                    + "　暂不支持，只认 TXT / EPUB";
        }
        TextView metaView = Ui.text(this, meta, 10.5f, Ui.LIGHT);
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        metaView.setPadding(0, dp(3), 0, 0);
        row.addView(metaView);
        return row;
    }

    private void open(File f) {
        if (f.isDirectory()) {
            if (rootMode) rootMode = false;
            current = f;
            refresh();
            return;
        }
        if (!LocalBooks.supported(f.getName())) {
            toast("这个格式还不支持，目前只认 TXT / EPUB");
            return;
        }
        Intent data = new Intent();
        data.putExtra("path", f.getAbsolutePath());
        setResult(RESULT_OK, data);
        finish();
    }

    private void goUp() {
        if (rootMode || current == null) return;
        // 站在存储根上就别再往 /mnt、/storage 走
        for (File r : roots) {
            if (sameFile(r, current)) {
                if (roots.size() > 1) {
                    rootMode = true;
                    current = null;
                    refresh();
                }
                return;
            }
        }
        File parent = current.getParentFile();
        if (parent == null || parent.list() == null) return;
        current = parent;
        refresh();
    }

    private void importAll() {
        if (rootMode || current == null) {
            toast("先进入放书的文件夹");
            return;
        }
        final List<File> all = LocalBooks.scanSupported(current, 3);
        if (all.isEmpty()) {
            toast("这个文件夹里没有 TXT / EPUB");
            return;
        }
        Intent data = new Intent();
        data.putExtra("dir", current.getAbsolutePath());
        setResult(RESULT_OK, data);
        finish();
    }

    // ---------------- 列表刷新 ----------------

    private void refresh() {
        items.clear();
        unreadable = false;
        supportedCount = 0;

        pathLabel.setText(rootMode ? "选择存储位置"
                : (current == null ? "" : current.getAbsolutePath()));
        allBtn.setVisibility(rootMode ? View.GONE : View.VISIBLE);
        upBtn.setVisibility(rootMode ? View.GONE : View.VISIBLE);

        if (rootMode) {
            items.addAll(roots);
            countLabel.setText(roots.isEmpty() ? "" : roots.size() + " 处");
            adapter.notifyDataSetChanged();
            StringBuilder sb = new StringBuilder("没检测到可读的存储目录。\n\n"
                    + "1) 插好 TF 卡或确认系统存储已挂载；\n"
                    + "2) 用数据线把 TXT / EPUB 拷到卡里；\n"
                    + "3) 也可以用系统文件管理器「打开方式」直接打开这本书。\n\n探测结果：");
            int shown = 0;
            for (String p : probes) {
                if (shown++ >= 6) break;
                sb.append("\n").append(p);
            }
            emptyLabel.setText(sb.toString());
            emptyLabel.setVisibility(roots.isEmpty() ? View.VISIBLE : View.GONE);
            return;
        }

        File[] files = current == null ? null : current.listFiles();
        if (files == null) {
            unreadable = true;
        } else {
            for (File f : files) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    items.add(f);
                } else if (LocalBooks.supported(f.getName())) {
                    items.add(f);
                    supportedCount++;
                } else {
                    items.add(f);   // 不支持的也列出来，灰掉并有说明
                }
            }
        }
        Collections.sort(items, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        adapter.notifyDataSetChanged();

        int deep = (current == null || unreadable) ? 0
                : LocalBooks.scanSupported(current, 3).size();
        if (unreadable) {
            countLabel.setText("读不到");
            emptyLabel.setText("这个目录读不出内容（可能需要存储权限，或者存储没挂载）。\n\n"
                    + current.getAbsolutePath());
            emptyLabel.setVisibility(View.VISIBLE);
        } else {
            countLabel.setText(items.size() + " 项 · " + deep + " 本书");
            if (items.isEmpty()) {
                emptyLabel.setText("这个文件夹是空的");
                emptyLabel.setVisibility(View.VISIBLE);
            } else if (supportedCount == 0 && deep == 0) {
                emptyLabel.setText("这里没有 TXT / EPUB。\n灰色的是其他格式，暂时不支持。");
                emptyLabel.setVisibility(View.VISIBLE);
            } else {
                emptyLabel.setVisibility(View.GONE);
            }
        }
    }

    // ---------------- 小工具 ----------------

    private static boolean sameFile(File a, File b) {
        String x = a.getAbsolutePath();
        String y = b.getAbsolutePath();
        if (x.equals(y)) return true;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private String pathOf(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            return kids == null ? "无权访问" : kids.length + " 项";
        }
        return sizeText(f.length());
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1);
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
