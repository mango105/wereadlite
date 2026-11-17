package com.modu.wereadlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 设置页：分页展示，音量键左右（上下）翻页。 */
public class SettingsActivity extends Activity {

    private static final String[] PAGE_TITLES = {"显示", "阅读", "账号"};

    private Store store;
    private LinearLayout box;
    private LinearLayout tabsBar;
    private TextView pageLabel;
    private int page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        render();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

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

        TextView title = Ui.text(this, "设置", 17, Ui.INK);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pageLabel = Ui.text(this, "1/3", 12, Ui.LIGHT);
        pageLabel.setPadding(dp(18), 0, dp(18), 0);
        bar.addView(pageLabel);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(10), dp(8), dp(10), dp(4));
        for (int i = 0; i < PAGE_TITLES.length; i++) {
            final int index = i;
            final TextView tab = Ui.text(this, PAGE_TITLES[i], 12.5f, Ui.LIGHT);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(6), 0, dp(6));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    page = index;
                    render();
                }
            });
            tab.setTag(Integer.valueOf(i));
            tabs.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        tabsBar = tabs;
        root.addView(tabs);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setVerticalScrollBarEnabled(false);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = Ui.text(this, "音量键翻页 · 也可点上方标签切换", 10.5f, Ui.LIGHT);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(8), 0, dp(10));
        root.addView(foot);
        return root;
    }

    private void render() {
        pageLabel.setText((page + 1) + "/" + PAGE_TITLES.length);
        if (tabsBar != null) {
            for (int i = 0; i < tabsBar.getChildCount(); i++) {
                TextView t = (TextView) tabsBar.getChildAt(i);
                boolean on = i == page;
                t.setTextColor(on ? Ui.INK : Ui.LIGHT);
                t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }

        box.removeAllViews();
        if (page == 0) {
            renderDisplay();
        } else if (page == 1) {
            renderReading();
        } else {
            renderAccount();
        }
    }

    private void renderDisplay() {
        box.addView(fontRow());
        box.addView(slider("行距", "紧", "松", 108, 200, store.linePct(), true, 4));
        box.addView(slider("边距", "窄", "宽", 4, 44, store.marginDp(), false, 2));
        box.addView(note("拖动滑杆即时生效；阅读时点中间唤出「显示」面板也能调。"));
    }

    /** 连续滑杆，边距 4~44dp（一档 2dp），行距 1.08~2.00 倍（一档 0.04）。左侧名称下面跟着当前值。 */
    private View slider(String caption, String leftEdge, String rightEdge, int min, int max,
                        int value, final boolean lineSpacing, int step) {
        final TextView num = Ui.text(this, shown(lineSpacing, value), 14, Ui.INK);
        num.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        num.setGravity(Gravity.CENTER_VERTICAL);

        SeekRow row = new SeekRow(this);
        row.setRange(min, max);
        row.setStep(step);
        row.setTapNudge(true);
        row.setValue(value);
        row.setEdges(leftEdge, 9.5f, rightEdge, 9.5f);
        row.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int v) {
                num.setText(shown(lineSpacing, v));
            }

            @Override
            public void onCommit(int v) {
                if (lineSpacing) store.setLinePct(v);
                else store.setMarginDp(v);
            }
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(Ui.text(this, caption, 10.5f, Ui.LIGHT), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(num, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(dp(20), dp(6), dp(12), dp(6));
        wrap.addView(col, new LinearLayout.LayoutParams(dp(44), dp(40)));
        wrap.addView(row, new LinearLayout.LayoutParams(0, dp(48), 1f));
        return wrap;
    }

    private static String shown(boolean lineSpacing, int value) {
        return lineSpacing ? Ui.lineSpacing(value) : String.valueOf(value);
    }

    private void renderReading() {
        box.addView(section("进度显示"));
        box.addView(choiceRow("页数与百分比", new String[]{"整本书", "按章"},
                store.progressWholeBook() ? 0 : 1, new Pick() {
                    @Override
                    public void onPick(int index) {
                        store.setProgressWholeBook(index == 0);
                        render();
                    }
                }));
        box.addView(note("整本书：页码和百分比按全书累计，读到最后一页正好 100%。"
                + "未读章节的页数按字数估算，读到时自动校正。\n"
                + "按章：只显示本章第几页、本章内进度。"));

        box.addView(section("云端进度同步"));
        box.addView(toggleRow("开书时拉取云端进度", store.syncOnOpen(), new Toggle.OnChange() {
            @Override
            public void onChange(boolean checked) {
                store.setSyncOnOpen(checked);
            }
        }));
        box.addView(toggleRow("关书时上传本地进度", store.syncOnClose(), new Toggle.OnChange() {
            @Override
            public void onChange(boolean checked) {
                store.setSyncOnClose(checked);
            }
        }));
        box.addView(note("两项默认关闭。开启后：开书先对齐云端进度，关书把本地进度回传，"
                + "多设备交替阅读不丢位置。差异 ≤2% 视为同一位置；两端真的冲突时会在阅读页弹窗，"
                + "由你决定用哪一端。阅读页底部工具条也有「立即同步」按钮，随时手动对比一次。\n"
                + "只对本 App 内打开的在线书籍生效；本地导入的书不参与云端同步。"));

        box.addView(section("翻页"));
        box.addView(toggleRow("使用音量键翻页", store.volumePaging(), new Toggle.OnChange() {
            @Override
            public void onChange(boolean checked) {
                store.setVolumePaging(checked);
            }
        }));
        box.addView(toggleRow("阅读时不自动锁屏", store.keepAwake(), new Toggle.OnChange() {
            @Override
            public void onChange(boolean checked) {
                store.setKeepAwake(checked);
            }
        }));
        box.addView(note("音量键翻页关闭后，音量键恢复为系统音量调节。"));
    }

    private void renderAccount() {
        box.addView(toggleRow("兼容模式（证书容错）", new Store(this).compat(),
                new Toggle.OnChange() {
                    @Override
                    public void onChange(boolean checked) {
                        store.setCompat(checked);
                    }
                }));
        box.addView(actionRow("重新登录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.clearSession();
                Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }
        }));
        box.addView(actionRow("关于", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(Ui.version(SettingsActivity.this))
                        .setMessage("纯 framework 实现，无 AndroidX / WebView。\n"
                                + "协议参考 KOReader 微信读书插件与觅阅 MiuRead。")
                        .setPositiveButton("好", null)
                        .show();
            }
        }));
        box.addView(note("兼容模式会跳过证书校验，仅在证书链异常、书架同步失败时临时打开。"));
    }

    // ---------------- 行 ----------------

    private View section(String label) {
        TextView t = Ui.text(this, label, 12, Ui.LIGHT);
        t.setPadding(dp(20), dp(18), dp(20), dp(6));
        return t;
    }

    private View note(String text) {
        TextView t = Ui.text(this, text, 10.5f, Ui.LIGHT);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(dp(20), dp(18), dp(20), dp(20));
        return t;
    }

    private View fontRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(18), dp(20), dp(6));

        TextView label = Ui.text(this, "阅读字号", 14.5f, Ui.INK);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(stepButton("−", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setFontSize(Math.max(14, store.fontSize() - 1));
                render();
            }
        }));
        TextView value = Ui.text(this, store.fontSize() + " pt", 14, Ui.INK);
        value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(value, vlp);
        row.addView(stepButton("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setFontSize(Math.min(34, store.fontSize() + 1));
                render();
            }
        }));
        return row;
    }

    private View stepButton(String label, View.OnClickListener click) {
        TextView t = Ui.text(this, label, 15, Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, 2));
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setOnClickListener(click);
        return t;
    }

    private interface Pick {
        void onPick(int index);
    }

    /** 二选一：右侧两个小胶囊按钮。 */
    private View choiceRow(String label, String[] options, int selected, final Pick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));

        TextView title = Ui.text(this, label, 14.5f, Ui.INK);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        for (int i = 0; i < options.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView chip = Ui.text(this, options[i], 13, on ? Ui.INK : Ui.LIGHT);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setBackgroundDrawable(Ui.box(Color.WHITE, on ? Ui.INK : Ui.LINE, 1, dp(3)));
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.onPick(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), 0, 0, 0);
            row.addView(chip, lp);
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0xFFEFEFEF);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    private View toggleRow(String label, boolean checked, Toggle.OnChange change) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = Ui.text(this, label, 14.5f, Ui.INK);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Toggle toggle = new Toggle(this);
        toggle.setChecked(checked);
        toggle.setOnChange(change);
        row.addView(toggle);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0xFFEFEFEF);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    private View actionRow(String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setOnClickListener(click);

        TextView title = Ui.text(this, label, 14.5f, Ui.INK);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(this, "›", 17, 0xFFBBBBBB));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0xFFEFEFEF);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    // ---------------- 音量键翻页 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (page < PAGE_TITLES.length - 1) {
                    page++;
                    render();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (page > 0) {
                    page--;
                    render();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (page < PAGE_TITLES.length - 1) {
                    page++;
                    render();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (page > 0) {
                    page--;
                    render();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
