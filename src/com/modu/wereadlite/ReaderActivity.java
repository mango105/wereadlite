package com.modu.wereadlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 阅读页：点中间出工具条，目录抽屉 + 显示面板（字号/边距/行距），音量键翻页。 */
public class ReaderActivity extends Activity implements PageView.Callback,
        PageView.SelectListener {

    private Store store;
    private Api api;
    private PageView pageView;
    private TextView header;
    private TextView overlay;

    private View toolbar;
    private View topToolbar;
    private FrameLayout rootView;
    private View notesPanel;
    private View displayPanel;
    private View paraPanel;
    private TextView paraCardFlush;
    private TextView paraCardIndent;
    private TextView gapMinus;
    private TextView gapValue;
    private TextView gapPlus;
    private View progressPanel;
    private SeekRow progressRow;
    private TextView progressPercent;
    private TextView progressPages;
    private TextView progressChapter;
    private TextView progressHint;
    private TextView autoFlipBtn;
    private SeekRow intervalSeek;
    private TextView intervalValue;
    private boolean autoFlipping;
    private final Runnable autoFlipTick = new Runnable() {
        @Override
        public void run() {
            if (!autoFlipping) return;
            onNextChapter();
            ui.postDelayed(this, store.autoFlipSec() * 1000L);
        }
    };
    private View tocPanel;
    private View tocDim;
    private TextView tocAuthor;
    private TextView tocTitleView;
    private ListView tocList;
    private EditText tocSearch;
    private SeekRow fontRow;
    private SeekRow marginRow;
    private SeekRow lineRow;
    private SeekRow indentRow;
    private TextView fontValue;
    private TextView marginValue;
    private TextView lineValue;
    private TextView indentValue;
    private TocAdapter tocAdapter;
    private BookmarkAdapter bookmarkAdapter;
    private ListView markList;
    private TextView markEmpty;
    private TextView tocTab;
    private TextView bookmarkTab;
    /** 目录抽屉当前显示目录还是书签。 */
    private boolean bookmarkMode;
    private final List<Bookmarks.Item> marks = new ArrayList<Bookmarks.Item>();

    private String bookId;
    private String bookTitle = "";
    private String bookCover = "";
    private android.widget.ImageView tocCover;
    private String author = "";
    private final List<Map<String, Object>> chapters = new ArrayList<Map<String, Object>>();
    /** 连续自动跳过（封面/版权等无正文页）的次数，防止死循环。 */
    private int autoSkips;
    /** 目录抽屉的行模型：章节 / 当前读到 / 内联书签。 */
    private final List<TocRow> tocRows = new ArrayList<TocRow>();
    private int[] chapterStart;
    private int chapterIndex;
    private String chapterTitle = "";
    private String currentText = "";
    private Progress progress;
    /** 页数/百分比按整本书算（默认）还是只看本章。 */
    private boolean wholeBook = true;
    private Api.Context ctx;
    /** 上次上报的时刻。rt 要报「距上次上报读了多久」，报开场至今云端时长会成倍增加。 */
    private long sinceReport;
    /** 正在同步时避免开书/手动/关书三条链路重入。 */
    private volatile boolean syncing;
    private int loadToken;
    private boolean demo;
    private final Handler ui = new Handler();

    // ---------------- 划线与想法 ----------------
    /** 本章原始 HTML（写划线算 range 用，随章节切换更新；本地书/演示书为空）。 */
    private volatile String chapterHtml = "";
    /** 本章书友划线（webUnderlines，markText 原文）。 */
    private final List<Map<String, Object>> cloudMarks = new ArrayList<Map<String, Object>>();
    /** markText -> 书友想法数，点划线弹想法列表用。 */
    private final java.util.HashMap<String, Integer> thoughtCount =
            new java.util.HashMap<String, Integer>();
    /** 本地划线（全书），与 Store.hl_ 同源。 */
    private final List<Highlights.Item> localMarks = new ArrayList<Highlights.Item>();
    /** 长按选择后的浮动操作条。 */
    private android.widget.PopupWindow selMenu;
    /** 选区起点（章内偏移），菜单按钮共用。 */
    private int selAnchorStart;
    private int selAnchorEnd;
    /** 书籍版本号（写划线/想法必带），一次拉取整本复用。 */
    private volatile int bookVer;
    private volatile boolean verFetched;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            report();
            ui.postDelayed(this, 60000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        api = new Api(store);
        wholeBook = store.progressWholeBook();
        bookId = getIntent().getStringExtra("bookId");
        bookTitle = getIntent().getStringExtra("title");
        if (bookTitle == null) bookTitle = "";
        bookCover = getIntent().getStringExtra("cover");
        if (bookCover == null) bookCover = "";
        author = getIntent().getStringExtra("author");
        if (author == null) author = "";
        demo = getIntent().getBooleanExtra("demo", false);
        sinceReport = System.currentTimeMillis();
        if (store.keepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        openBook();
    }

    // ---------------- 布局 ----------------

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        header = Ui.text(this, "", 10.5f, 0xFF8A8A8A);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // 只显示章节名：书名底部工具栏已有，左上角返回箭头已去掉，不再需要左侧留白
        header.setPadding(dp(16), dp(2), dp(16), dp(2));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        pageView = new PageView(this);
        pageView.setCallback(this);
        pageView.setSelectListener(this);
        pageView.setFooter(new PageView.Footer() {
            @Override
            public String caption() {
                if (bookTitle.length() == 0) return author;
                return author.length() == 0 ? bookTitle : bookTitle + " · " + author;
            }

            @Override
            public String pageLabel(int localPage, int localTotal, int offset) {
                Progress p = progress();
                if (!wholeBook || p == null || p.size() == 0) {
                    return (localPage + 1) + "/" + localTotal;
                }
                int pn = p.pageNumber(chapterIndex, localPage, localTotal);
                int pt = p.totalPages(chapterIndex, localTotal);
                Trace.i("footer ch=" + chapterIndex + "/" + p.size()
                        + " local=" + localPage + "/" + localTotal + " -> " + pn + "/" + pt);
                return pn + "/" + pt;
            }

            @Override
            public String percentLabel(int localPage, int localTotal, int offset) {
                Progress p = progress();
                if (!wholeBook || p == null || p.size() == 0) {
                    if (currentText.length() == 0) return "";
                    return (offset * 100 / Math.max(1, currentText.length())) + "%";
                }
                return p.percent(chapterIndex, localPage, localTotal) + "%";
            }
        });
        pageView.setFontSize(store.fontSize());
        pageView.setLinePct(store.linePct());
        pageView.setMarginDp(store.marginDp());
        pageView.setIndentCells(store.indentCells());
        pageView.setParaGap(store.paraGapN());
        page.addView(pageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = Ui.text(this, "加载中…", 14.5f, Ui.INK);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.WHITE);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 左上角返回箭头已去掉：书名/返回在底部工具栏都有，页眉只留章节名。
        // 唤出/收起工具栏由点击正文（onMenu）承担，不再需要这颗悬浮按钮。

        toolbar = buildToolbar();
        root.addView(toolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50), Gravity.BOTTOM));

        topToolbar = buildTopToolbar();
        root.addView(topToolbar, new FrameLayout.LayoutParams(
                dp(44) * 4 + dp(6), dp(34), Gravity.TOP | Gravity.RIGHT));

        progressPanel = buildProgressPanel();
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        plp.bottomMargin = dp(44);
        root.addView(progressPanel, plp);

        displayPanel = buildDisplayPanel();
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        panelLp.bottomMargin = dp(44);
        root.addView(displayPanel, panelLp);

        paraPanel = buildParaPanel();
        FrameLayout.LayoutParams paraLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        paraLp.bottomMargin = dp(44);
        root.addView(paraPanel, paraLp);

        tocPanel = buildTocPanel();
        FrameLayout.LayoutParams tocLp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.89f),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        root.addView(tocPanel, tocLp);

        tocDim = new View(this);
        tocDim.setBackgroundColor(0x33000000);
        tocDim.setVisibility(View.GONE);
        tocDim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToc(false);
            }
        });
        root.addView(tocDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootView = root;
        return root;
    }

    private TextView flatButton(String label, float sp, int color) {
        TextView t = Ui.text(this, label, sp, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        return t;
    }

    /** 底部工具条：返回书架 / 目录 / 进度罗盘 / 字号；收起靠点屏幕中间。 */
    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setVisibility(View.GONE);
        bar.setPadding(dp(2), 0, dp(2), 0);

        bar.addView(barButton(NavIcon.BACK, "返回书架", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), slot());
        bar.addView(barButton(NavIcon.LIST, "目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToc(true);
            }
        }), slot());
        bar.addView(barButton(NavIcon.COMPASS, "阅读进度", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(true);
            }
        }), slot());
        // 收起菜单按钮去掉：点屏幕中间即可收起，单独做按钮属于多此一举。
        bar.addView(barButton(NavIcon.LETTER_A, "字体设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDisplay(true);
            }
        }), slot());
        return bar;
    }

    /** 顶部工具条：对齐官方——加书签 / 笔记 / 书单 / 云同步。 */
    private View buildTopToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bar.setVisibility(View.GONE);
        bar.setPadding(dp(2), 0, dp(6), 0);

        bar.addView(barButton(NavIcon.BOOKMARK_PLUS, "加书签", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBookmark();
                brief(currentHasBookmark() ? "已加书签" : "已取消书签");
            }
        }), topSlot());
        bar.addView(barButton(NavIcon.PEN, "笔记", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotes();
            }
        }), topSlot());
        bar.addView(barButton(NavIcon.BOOKLIST, "章节目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToc(true);
            }
        }), topSlot());
        bar.addView(barButton(NavIcon.CLOUD, "同步云端进度", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                brief("正在同步云端进度…");
                syncNow(Sync.TRIGGER_MANUAL);
            }
        }), topSlot());
        return bar;
    }

    private LinearLayout.LayoutParams topSlot() {
        return new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams slot() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private View barButton(int iconKind, String desc, View.OnClickListener onClick) {
        FrameLayout cell = new FrameLayout(this);
        cell.setContentDescription(desc);
        NavIcon icon = new NavIcon(this, iconKind);
        icon.setInk(Ui.INK);
        cell.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        cell.setOnClickListener(onClick);
        return cell;
    }

    /** 进度面板：三个数字 + 本章滑杆 + 上一章/下一章。 */
    private View buildProgressPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setVisibility(View.GONE);

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        panel.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(dp(10), dp(18), dp(10), dp(14));
        progressPercent = statCell(stats, "阅读进度");
        progressPages = statCell(stats, "阅读时长");
        progressChapter = statCell(stats, "笔记");
        panel.addView(stats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sep = new View(this);
        sep.setBackgroundColor(Ui.LINE);
        panel.addView(sep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout slider = new LinearLayout(this);
        slider.setOrientation(LinearLayout.HORIZONTAL);
        slider.setGravity(Gravity.CENTER_VERTICAL);
        slider.setPadding(dp(6), 0, dp(6), 0);
        slider.addView(arrowButton("‹", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(false);
                onPrevChapter();
            }
        }), new LinearLayout.LayoutParams(dp(44), dp(40)));

        progressRow = new SeekRow(this);
        progressRow.setRange(0, 100);
        progressRow.setEdges("章首", 9f, "章末", 9f);
        progressRow.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int value) {
                seekChapterPct(value);
                bindProgressStats();
            }

            @Override
            public void onCommit(int value) {
                seekChapterPct(value);
                savePos();
                bindProgressStats();
            }
        });
        slider.addView(progressRow, new LinearLayout.LayoutParams(0, dp(52), 1f));

        View next = arrowButton("›", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(false);
                onNextChapter();
            }
        });
        slider.addView(next, new LinearLayout.LayoutParams(dp(44), dp(40)));
        panel.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressHint = Ui.text(this, "", 10.5f, Ui.LIGHT);
        progressHint.setGravity(Gravity.CENTER);
        progressHint.setPadding(0, dp(2), 0, dp(4));
        panel.addView(progressHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 翻页间隔：10–120 秒，默认 30，对齐官方「翻页间隔 30秒」滑杆
        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalRow.setPadding(dp(18), dp(6), dp(18), 0);
        TextView il = Ui.text(this, "翻页间隔", 13.5f, Ui.SUB);
        intervalRow.addView(il, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View ispace = new View(this);
        // 高度必须给 0：裸 View 的默认 onMeasure 在 WRAP_CONTENT(AT_MOST) 下
        // 会返回整个可用高度，把这一行撑成 900+px 的巨型白板盖住全屏。
        intervalRow.addView(ispace, new LinearLayout.LayoutParams(0, 0, 1f));
        intervalSeek = new SeekRow(this);
        intervalSeek.setRange(10, 120);
        intervalSeek.setEdges("10", 8f, "120", 8f);
        intervalSeek.setValue(store.autoFlipSec());
        intervalValue = Ui.text(this, store.autoFlipSec() + " 秒", 13.5f, Ui.INK);
        intervalSeek.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int value) {
                setInterval(value);
            }

            @Override
            public void onCommit(int value) {
                setInterval(value);
                store.setAutoFlipSec(value);
            }
        });
        intervalRow.addView(intervalSeek, new LinearLayout.LayoutParams(
                0, dp(44), 1f));
        intervalRow.addView(intervalValue, new LinearLayout.LayoutParams(
                dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        intervalValue.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        panel.addView(intervalRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 自动翻页按钮，对齐官方「开启自动翻页」
        autoFlipBtn = flatButton("开启自动翻页", 13.5f, Ui.INK);
        autoFlipBtn.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(22)));
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        abp.setMargins(dp(20), dp(6), dp(20), dp(6));
        autoFlipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAutoFlip();
            }
        });
        panel.addView(autoFlipBtn, abp);

        TextView sync = flatButton("⇅  立即同步云端进度", 11f, Ui.LIGHT);
        sync.setPadding(dp(12), dp(8), dp(12), dp(8));
        sync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(false);
                syncNow(Sync.TRIGGER_MANUAL);
            }
        });
        panel.addView(sync, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /** 进度面板提示行：显示当前章节标题。 */
    private void bindReadTime() {
        if (progressHint == null) return;
        String ch = chapterTitle == null ? "" : chapterTitle;
        progressHint.setText(ch.length() == 0 ? "拖动滑杆定位本章" : ch);
        progressHint.setSingleLine(true);
        progressHint.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    }

    private void toggleAutoFlip() {
        autoFlipping = !autoFlipping;
        if (autoFlipping) {
            autoFlipBtn.setText("停止自动翻页");
            ui.removeCallbacks(autoFlipTick);
            ui.postDelayed(autoFlipTick, store.autoFlipSec() * 1000L);
            brief("已开启自动翻页（每 " + store.autoFlipSec() + " 秒）");
        } else {
            ui.removeCallbacks(autoFlipTick);
            autoFlipBtn.setText("开启自动翻页");
            brief("已停止自动翻页");
        }
    }

    private void setInterval(int sec) {
        if (intervalValue != null) intervalValue.setText(sec + " 秒");
        if (autoFlipping) {
            ui.removeCallbacks(autoFlipTick);
            ui.postDelayed(autoFlipTick, sec * 1000L);
        }
    }

    /** 进度面板顶部的一列：大数字 + 小注，返回数字那个 TextView。 */
    private TextView statCell(LinearLayout parent, String caption) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setText("—");
        col.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView c = Ui.text(this, caption, 9.5f, Ui.LIGHT);
        c.setGravity(Gravity.CENTER);
        col.addView(c, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    private View arrowButton(String text, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setText(text);
        t.setOnClickListener(onClick);
        return t;
    }

    /** 书友想法：只同步云端别人的想法，本地书没有这个数据。 */
    private void openReviews() {
        hideToolbar();
        if (LocalBooks.isLocal(bookId) || demo) {
            brief("本地书没有云端想法");
            return;
        }
        if (chapters.isEmpty()) return;
        int from = pageView.currentOffset();
        String excerpt = currentText.length() == 0 ? "" : currentText.substring(from,
                Math.min(currentText.length(), from + 120));
        Intent intent = new Intent(this, ReviewActivity.class);
        intent.putExtra("bookId", bookId);
        intent.putExtra("chapterUid", String.valueOf(chapters.get(chapterIndex).get("uid")));
        intent.putExtra("chapterTitle", chapterTitle);
        intent.putExtra("mark", excerpt);
        startActivity(intent);
    }

    private LinearLayout notesContent;

    /** 图3：笔记页——顶部返回 + 标题，书籍卡片 + 我的书签 + 书友想法。 */
    private View buildNotesPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setVisibility(View.GONE);

        // 顶部：← 返回 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = new FrameLayout(this);
        NavIcon arrow = new NavIcon(this, NavIcon.BACK);
        arrow.setInk(Ui.INK);
        back.addView(arrow, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setPadding(dp(14), dp(12), dp(10), dp(12));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                notesPanel.setVisibility(View.GONE);
            }
        });
        top.addView(back);
        TextView title = Ui.text(this, "笔记", 17, Ui.INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(4), 0, dp(20), 0);
        top.addView(title);
        panel.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        panel.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView notesScroll = new ScrollView(this);
        notesContent = new LinearLayout(this);
        notesContent.setOrientation(LinearLayout.VERTICAL);
        notesScroll.addView(notesContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(notesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return panel;
    }

    /** 分区小标题。 */
    private TextView noteSection(String text) {
        TextView t = Ui.text(this, text, 13, Ui.SUB);
        t.setPadding(dp(18), dp(14), dp(18), dp(6));
        return t;
    }

    /** 绑定笔记页：书籍卡片 + 我的书签（可跳转/删除）+ 书友想法入口。 */
    private void bindNotes() {
        if (notesContent == null) return;
        notesContent.removeAllViews();

        // 书籍卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(8)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        final android.widget.ImageView cover = new android.widget.ImageView(this);
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, dp(3)));
        if (bookCover.length() > 0) {
            Covers.load("notes-" + bookId, bookCover, dp(40), dp(54), new Covers.Callback() {
                @Override
                public void onCover(String key, android.graphics.Bitmap bitmap) {
                    if (bitmap != null) cover.setImageBitmap(bitmap);
                }
            });
        }
        card.addView(cover, new LinearLayout.LayoutParams(dp(40), dp(54)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView nt = Ui.text(this, bookTitle, 15, Ui.INK);
        nt.setMaxLines(1);
        nt.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(nt);
        int pct = overallProgress(pageView == null ? 0 : pageView.currentOffset());
        TextView meta = Ui.text(this, "已读到 " + pct + "% · " + marks.size() + " 书签 · "
                + store.highlightCount(bookId) + " 划线", 11.5f, Ui.SUB);
        meta.setPadding(0, dp(4), 0, 0);
        info.addView(meta);
        String spent = Ui.duration(store.readTime(bookId));
        TextView time = Ui.text(this, "时长 " + (spent.length() == 0 ? "0 分钟" : spent), 11.5f, Ui.SUB);
        time.setPadding(0, dp(4), 0, 0);
        info.addView(time);
        card.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccp.setMargins(dp(16), dp(4), dp(16), dp(6));
        notesContent.addView(card, ccp);

        // 我的书签
        notesContent.addView(noteSection("我的书签 · " + marks.size() + " 条"));
        if (marks.isEmpty()) {
            TextView empty = Ui.text(this, "还没有书签。阅读时点顶部的书签图标添加。",
                    12f, Ui.LIGHT);
            empty.setPadding(dp(18), dp(6), dp(18), dp(8));
            notesContent.addView(empty);
        } else {
            for (int i = 0; i < marks.size(); i++) {
                final Bookmarks.Item it = marks.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(9), dp(12), dp(9));
                row.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(6)));

                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView excerpt = Ui.text(this,
                        it.excerpt.length() > 0 ? it.excerpt : it.chapter, 13, Ui.INK);
                excerpt.setMaxLines(2);
                excerpt.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(excerpt);
                String chapter = it.chapter.length() > 0 ? it.chapter : "第 " + (it.idx + 1) + " 章";
                String when = Bookmarks.timeLabel(it.at);
                TextView sub = Ui.text(this,
                        when.length() == 0 ? chapter : chapter + " · " + when, 10.5f, Ui.LIGHT);
                sub.setPadding(0, dp(3), 0, 0);
                texts.addView(sub);
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView del = Ui.text(this, "×", 20, Ui.LIGHT);
                del.setPadding(dp(12), dp(4), dp(4), dp(4));
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        marks.remove(it);
                        saveMarks();
                        bindNotes();
                    }
                });
                row.addView(del);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        notesPanel.setVisibility(View.GONE);
                        gotoBookmark(it);
                    }
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(dp(16), 0, dp(16), dp(6));
                notesContent.addView(row, rlp);
            }
        }

        // 我的划线
        List<Highlights.Item> hls = synchronizedMarks();
        notesContent.addView(noteSection("我的划线 · " + hls.size() + " 条"));
        if (hls.isEmpty()) {
            TextView empty = Ui.text(this, "还没有划线。阅读时长按正文选中文字即可划线。",
                    12f, Ui.LIGHT);
            empty.setPadding(dp(18), dp(6), dp(18), dp(8));
            notesContent.addView(empty);
        } else {
            for (int i = 0; i < hls.size(); i++) {
                final Highlights.Item it = hls.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(9), dp(12), dp(9));
                row.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(6)));

                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView excerpt = Ui.text(this,
                        it.excerpt.length() > 0 ? it.excerpt : "（无摘录）", 13, Ui.INK);
                excerpt.setMaxLines(2);
                excerpt.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(excerpt);
                String chapter = chapterTitleOf(it.idx);
                String state = it.rid.length() > 0 ? "已同步" : "仅本机";
                TextView sub = Ui.text(this,
                        chapter + " · " + state + (it.range.length() > 0 ? "" : ""), 10.5f, Ui.LIGHT);
                sub.setPadding(0, dp(3), 0, 0);
                texts.addView(sub);
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView del = Ui.text(this, "×", 20, Ui.LIGHT);
                del.setPadding(dp(12), dp(4), dp(4), dp(4));
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteMark(it);
                        bindNotes();
                    }
                });
                row.addView(del);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        notesPanel.setVisibility(View.GONE);
                        loadChapter(it.idx, false, it.start);
                    }
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(dp(16), 0, dp(16), dp(6));
                notesContent.addView(row, rlp);
            }
        }

        // 书友想法
        notesContent.addView(noteSection("书友想法"));
        LinearLayout reviewCard = new LinearLayout(this);
        reviewCard.setOrientation(LinearLayout.VERTICAL);
        reviewCard.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(8)));
        reviewCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView rt = Ui.text(this, "看其他读者写的想法和划线", 14, Ui.INK);
        reviewCard.addView(rt);
        TextView rs = Ui.text(this, "来自云端，只读。需先完成 E-Ink 客户端授权。", 11f, Ui.LIGHT);
        rs.setPadding(0, dp(4), 0, 0);
        reviewCard.addView(rs);
        reviewCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openReviews();
            }
        });
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rcp.setMargins(dp(16), 0, dp(16), dp(16));
        notesContent.addView(reviewCard, rcp);

        TextView note = Ui.text(this, "划线同步到微信读书云端（手机 App 可见）；想法发布后默认仅自己可见。",
                10.5f, Ui.LIGHT);
        note.setPadding(dp(18), 0, dp(18), dp(12));
        notesContent.addView(note);
    }

    private String chapterTitleOf(int idx) {
        if (idx >= 0 && idx < chapters.size()) {
            String t = String.valueOf(chapters.get(idx).get("title"));
            if (t.length() > 0) return t;
        }
        return "第 " + (idx + 1) + " 章";
    }

    /** 笔记页：我的书签 + 书友想法。做成覆盖层，和官方「笔记」页一致。 */
    private void openNotes() {
        hideToolbar();
        loadMarks();
        if (notesPanel == null) {
            notesPanel = buildNotesPanel();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            rootView.addView(notesPanel, lp);
        }
        bindNotes();
        notesPanel.setVisibility(View.VISIBLE);
        notesPanel.bringToFront();
    }

    private void openDetail() {
        hideToolbar();
        if (LocalBooks.isLocal(bookId) || demo) {
            brief("本地书没有书籍详情");
            return;
        }
        Intent intent = new Intent(this, BookInfoActivity.class);
        intent.putExtra("bookId", bookId);
        intent.putExtra("title", bookTitle);
        intent.putExtra("author", author);
        intent.putExtra("cover", bookCover);
        intent.putExtra("inShelf", true);
        startActivity(intent);
    }

    private View buildDisplayPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setVisibility(View.GONE);

        // 图5：标题栏 + 右上「首行缩进 ›」入口
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(12), dp(12), dp(8));
        TextView h = Ui.text(this, "字体设置", 15, Ui.INK);
        head.addView(h, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView indentEntry = Ui.text(this, "首行缩进 ›", 12.5f, Ui.SUB);
        indentEntry.setPadding(dp(10), dp(5), dp(10), dp(5));
        indentEntry.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, dp(14)));
        indentEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showParaPanel(true);
            }
        });
        head.addView(indentEntry);
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        panel.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        fontRow = new SeekRow(this);
        fontRow.setRange(14, 34);
        fontRow.setStep(1);
        fontRow.setTapNudge(true);
        fontRow.setValue(store.fontSize());
        fontRow.setEdges("A", 11f, "A", 17f);
        fontRow.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int value) {
                fontValue.setText(String.valueOf(value));
                previewStyle(P_FONT, value);
            }

            @Override
            public void onCommit(int value) {
                commitStyle(P_FONT, value);
                store.setFontSize(pageView.fontSize());
                savePos();
            }
        });
        panel.addView(styleRow("字号", fontRow, String.valueOf(store.fontSize())));

        marginRow = new SeekRow(this);
        marginRow.setRange(4, 44);
        marginRow.setStep(2);
        marginRow.setTapNudge(true);
        marginRow.setValue(store.marginDp());
        marginRow.setEdges("窄", 9.5f, "宽", 9.5f);
        marginRow.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int value) {
                marginValue.setText(String.valueOf(value));
                previewStyle(P_MARGIN, value);
            }

            @Override
            public void onCommit(int value) {
                commitStyle(P_MARGIN, value);
                store.setMarginDp(pageView.marginDp());
                savePos();
            }
        });
        panel.addView(styleRow("边距", marginRow, String.valueOf(store.marginDp())));

        lineRow = new SeekRow(this);
        lineRow.setRange(108, 200);
        lineRow.setStep(4);
        lineRow.setTapNudge(true);
        lineRow.setValue(store.linePct());
        lineRow.setEdges("紧", 9.5f, "松", 9.5f);
        lineRow.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int value) {
                lineValue.setText(Ui.lineSpacing(value));
                previewStyle(P_LINE, value);
            }

            @Override
            public void onCommit(int value) {
                commitStyle(P_LINE, value);
                store.setLinePct(pageView.linePct());
                savePos();
            }
        });
        panel.addView(styleRow("行距", lineRow, Ui.lineSpacing(store.linePct())));

        indentRow = new SeekRow(this);
        indentRow.setRange(4, 8);
        indentRow.setStep(1);
        indentRow.setTapNudge(true);
        indentRow.setValue(pageView.indentCells());
        indentRow.setEdges("4 格", 9.5f, "8 格", 9.5f);
        indentRow.setListener(new SeekRow.Listener() {
            @Override
            public void onValue(int value) {
                indentValue.setText(value + " 格");
                previewStyle(P_INDENT, value);
            }

            @Override
            public void onCommit(int value) {
                commitStyle(P_INDENT, value);
                store.setIndentCells(pageView.indentCells());
                savePos();
            }
        });
        panel.addView(styleRow("缩进", indentRow, pageView.indentCells() + " 格"));

        TextView hint = Ui.text(this, "轻点走一档，按住拖动选档；松手就记住", 10.5f, Ui.LIGHT);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(2), 0, dp(12));
        panel.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /**
     * 左侧「名称 + 当前值」，右侧滑杆。
     * 数值放在名称下面而不是跟着手柄跑：小屏上跟随的两位数又细又糊，还一直跳。
     */
    private View styleRow(String label, final SeekRow row, String value) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(dp(14), dp(5), dp(14), dp(5));

        // 图5：轨道两端的圆形 − / + 按钮
        TextView minus = stepButton("−");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                row.nudge(-1);
            }
        });
        wrap.addView(minus, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, label, 10.5f, Ui.SUB);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView num = Ui.text(this, value, 14, Ui.INK);
        num.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        col.addView(num, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if ("字号".equals(label)) fontValue = num;
        else if ("边距".equals(label)) marginValue = num;
        else if ("缩进".equals(label)) indentValue = num;
        else lineValue = num;
        wrap.addView(col, new LinearLayout.LayoutParams(dp(44), dp(40)));

        wrap.addView(row, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView plus = stepButton("+");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                row.nudge(1);
            }
        });
        wrap.addView(plus, new LinearLayout.LayoutParams(dp(34), dp(34)));
        return wrap;
    }

    private TextView stepButton(String glyph) {
        TextView t = Ui.text(this, glyph, 17, Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundDrawable(Ui.oval(Ui.TILE));
        return t;
    }

    /** 图6：段落样式——首行顶格 / 首行缩进 卡片 + 段间距开关。 */
    private View buildParaPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setVisibility(View.GONE);

        TextView head = Ui.text(this, "段落样式", 15, Ui.INK);
        head.setPadding(dp(18), dp(14), dp(18), dp(10));
        panel.addView(head);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(dp(16), dp(2), dp(16), dp(10));
        paraCardFlush = styleCard("首行顶格");
        paraCardIndent = styleCard("首行缩进");
        paraCardFlush.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setIndentMode(false);
            }
        });
        paraCardIndent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setIndentMode(true);
            }
        });
        cards.addView(paraCardFlush, new LinearLayout.LayoutParams(0, dp(74), 1f));
        LinearLayout.LayoutParams im = new LinearLayout.LayoutParams(0, dp(74), 1f);
        im.setMargins(dp(10), 0, 0, 0);
        cards.addView(paraCardIndent, im);
        panel.addView(cards, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 段间距：−/档位/＋，0 关闭
        LinearLayout gapRow = new LinearLayout(this);
        gapRow.setOrientation(LinearLayout.HORIZONTAL);
        gapRow.setGravity(Gravity.CENTER_VERTICAL);
        gapRow.setPadding(dp(18), dp(8), dp(18), dp(12));
        TextView gl = Ui.text(this, "段间距", 14, Ui.INK);
        gapRow.addView(gl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gapMinus = stepButton("−");
        gapMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setParaGapN(store.paraGapN() - 1);
            }
        });
        gapRow.addView(gapMinus);
        gapValue = Ui.text(this, "", 13, Ui.INK);
        gapValue.setGravity(Gravity.CENTER);
        gapValue.setMinWidth(dp(58));
        gapRow.addView(gapValue);
        gapPlus = stepButton("+");
        gapPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setParaGapN(store.paraGapN() + 1);
            }
        });
        gapRow.addView(gapPlus);
        refreshGapRow();
        panel.addView(gapRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView done = flatButton("完成", 13.5f, Ui.INK);
        done.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, dp(20)));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        dp2.setMargins(dp(20), dp(2), dp(20), dp(12));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showParaPanel(false);
            }
        });
        panel.addView(done, dp2);
        return panel;
    }

    private TextView styleCard(String label) {
        TextView t = Ui.text(this, label, 14, Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, dp(8)));
        return t;
    }

    /** 记住上次的缩进格数，切「顶格」时暂存，切回「缩进」时恢复。 */
    private int lastIndentCells = 4;

    private void setIndentMode(boolean indent) {
        int cells = indent ? (lastIndentCells > 0 ? lastIndentCells : 4) : 0;
        if (indent) lastIndentCells = pageView.indentCells() > 0 ? pageView.indentCells() : lastIndentCells;
        pageView.setIndentCells(cells);
        store.setIndentCells(cells);
        refreshParaCards();
        savePos();
    }

    private void refreshParaCards() {
        if (paraCardFlush == null) return;
        boolean indent = pageView.indentCells() > 0;
        paintCard(paraCardFlush, !indent);
        paintCard(paraCardIndent, indent);
    }

    private void paintCard(TextView card, boolean selected) {
        card.setTextColor(selected ? Color.WHITE : Ui.INK);
        card.setBackgroundDrawable(Ui.box(selected ? Ui.INK : Ui.TILE,
                selected ? Ui.INK : Ui.LINE, 1, dp(8)));
    }

    /** 段间距档位（0–4）：0 关闭，1 档约 0.35 倍字高。 */
    private void setParaGapN(int n) {
        n = Math.max(0, Math.min(4, n));
        store.setParaGapN(n);
        pageView.setParaGap(n);
        refreshGapRow();
        savePos();
    }

    private void refreshGapRow() {
        if (gapValue == null) return;
        int n = store.paraGapN();
        gapValue.setText(n == 0 ? "关" : String.valueOf(n));
        gapMinus.setEnabled(n > 0);
        gapMinus.setTextColor(n > 0 ? Ui.INK : 0xFFBBBBBB);
        gapPlus.setEnabled(n < 4);
        gapPlus.setTextColor(n < 4 ? Ui.INK : 0xFFBBBBBB);
    }

    private void showParaPanel(boolean show) {
        if (paraPanel == null) return;
        if (show) {
            refreshParaCards();
            refreshGapRow();
            displayPanel.setVisibility(View.GONE);
        }
        paraPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        syncChrome();
    }

    // ---------------- 拖动预览：重排要按节奏，不能逐像素来 ----------------

    private static final int P_FONT = 1;
    private static final int P_MARGIN = 2;
    private static final int P_LINE = 3;
    private static final int P_INDENT = 4;
    /**
     * 拖动时最快多久重排一次。整章重排是几十到几百毫秒的活（长章更久），
     * 连续拖动时每个档位都排一次会把主线程压死——旧版就是这样在长章上触发过 5 秒输入超时的 ANR。
     * 所以按节奏排：手一直动也每 200ms 跟着刷新，而不是像以前那样非等手停下不可，
     * 等手停下才变的「实时预览」拖起来是僵的。
     */
    private static final long PREVIEW_STEP_MS = 200;
    /** 再慢也不能把拖动节奏拖到看不下去。 */
    private static final long PREVIEW_MAX_MS = 1200;

    private int pendingStyle;
    private int pendingStyleValue;
    private long lastPreviewAt;
    private long previewInterval = PREVIEW_STEP_MS;
    private final Runnable applyPreview = new Runnable() {
        @Override
        public void run() {
            long t0 = SystemClock.uptimeMillis();
            applyStyle(pendingStyle, pendingStyleValue);
            long cost = SystemClock.uptimeMillis() - t0;
            // 按上一次重排的耗时自适应：慢机器上把节奏拉开，主线程才有空收下一个手指事件。
            // 定死 200ms 在好机器上没问题，在电纸书上就变成排队几十次重排，直接顶到 5 秒输入超时。
            long wait = cost * 3;
            if (wait < PREVIEW_STEP_MS) wait = PREVIEW_STEP_MS;
            if (wait > PREVIEW_MAX_MS) wait = PREVIEW_MAX_MS;
            previewInterval = wait;
            lastPreviewAt = SystemClock.uptimeMillis();
        }
    };

    private void previewStyle(int what, int value) {
        pendingStyle = what;
        pendingStyleValue = value;
        ui.removeCallbacks(applyPreview);
        long since = SystemClock.uptimeMillis() - lastPreviewAt;
        ui.postDelayed(applyPreview, since >= previewInterval ? 0 : previewInterval - since);
    }

    /** 松手：取消排队，立刻按最终值排一次，保证正文和滑杆一致。 */
    private void commitStyle(int what, int value) {
        ui.removeCallbacks(applyPreview);
        lastPreviewAt = SystemClock.uptimeMillis();
        applyStyle(what, value);
    }

    private void applyStyle(int what, int value) {
        if (what == P_FONT) pageView.setFontSize(value);
        else if (what == P_MARGIN) pageView.setMarginDp(value);
        else if (what == P_LINE) pageView.setLinePct(value);
        else pageView.setIndentCells(value);
    }

    private void savePos() {
        if (bookId == null || pageView == null) return;
        store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
    }

    private View buildTocPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setVisibility(View.GONE);

        // 图2：顶部书名标题栏 + 「去底部」，大字号
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(16), dp(16), dp(4));
        TextView bookLabel = Ui.text(this, bookTitle, 18, Ui.INK);
        bookLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bookLabel.setMaxLines(1);
        bookLabel.setEllipsize(TextUtils.TruncateAt.END);
        tocTitleView = bookLabel;
        head.addView(bookLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView toBottom = Ui.text(this, "↓ 去底部", 12, Ui.SUB);
        toBottom.setPadding(dp(6), dp(4), dp(4), dp(4));
        toBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tocList != null && tocAdapter != null && tocAdapter.getCount() > 0) {
                    tocList.setSelection(tocAdapter.getCount() - 1);
                }
            }
        });
        head.addView(toBottom);
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索全书内容");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.LIGHT);
        search.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(20)));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterToc(s.toString().trim());
            }
        });
        tocSearch = search;
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(16), dp(14), dp(16), dp(10));
        panel.addView(search, slp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setPadding(dp(16), dp(4), dp(16), dp(12));

        FrameLayout thumb = new FrameLayout(this);
        thumb.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, 2));
        tocCover = new android.widget.ImageView(this);
        tocCover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        thumb.addView(tocCover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView mark = Ui.text(this, "书", 13, 0xFFB0B0B0);
        mark.setGravity(Gravity.CENTER);
        thumb.addView(mark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(52), dp(70));
        tlp.rightMargin = dp(12);
        info.addView(thumb, tlp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, bookTitle, 14.5f, Ui.INK);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tocAuthor = Ui.text(this, author.length() == 0 ? " " : author, 11, Ui.LIGHT);
        tocAuthor.setPadding(0, dp(5), 0, 0);
        texts.addView(t);
        texts.addView(tocAuthor);
        info.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(info);

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        panel.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        loadTocCover();

        tocList = new ListView(this);
        tocList.setBackgroundColor(Color.WHITE);
        tocList.setDivider(null);
        tocList.setCacheColorHint(Color.WHITE);
        tocList.setVerticalScrollBarEnabled(false);
        tocAdapter = new TocAdapter();
        tocList.setAdapter(tocAdapter);
        panel.addView(tocList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 书签用独立 ListView，避免和目录行共用 convertView 复用池导致 tag 串味
        markList = new ListView(this);
        markList.setBackgroundColor(Color.WHITE);
        markList.setDivider(null);
        markList.setCacheColorHint(Color.WHITE);
        markList.setVerticalScrollBarEnabled(false);
        bookmarkAdapter = new BookmarkAdapter();
        markList.setAdapter(bookmarkAdapter);
        markList.setVisibility(View.GONE);
        panel.addView(markList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        markEmpty = Ui.text(this, "还没有书签\n\n读到这里时点底栏的书签图标就能记下来", 13f, Ui.LIGHT);
        markEmpty.setGravity(Gravity.CENTER);
        markEmpty.setPadding(dp(24), dp(36), dp(24), dp(36));
        markEmpty.setVisibility(View.GONE);
        panel.addView(markEmpty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line2 = new View(this);
        line2.setBackgroundColor(Ui.LINE);
        panel.addView(line2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(10), 0, dp(10), 0);

        // 图2：左右翻页箭头 + 中间「书签」胶囊按钮
        TextView prev = Ui.text(this, "⌃", 20, Ui.SUB);
        prev.setGravity(Gravity.CENTER);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPrevChapter();
            }
        });
        bottom.addView(prev, new LinearLayout.LayoutParams(dp(48), dp(44)));

        bookmarkTab = Ui.text(this, "书签", 14, Ui.INK);
        bookmarkTab.setGravity(Gravity.CENTER);
        bookmarkTab.setPadding(dp(20), dp(9), dp(20), dp(9));
        bookmarkTab.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(20)));
        bookmarkTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setBookmarkMode(!bookmarkMode);
            }
        });
        bottom.addView(bookmarkTab, new LinearLayout.LayoutParams(0,
                dp(38), 1f));

        TextView next = Ui.text(this, "⌄", 20, Ui.SUB);
        next.setGravity(Gravity.CENTER);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onNextChapter();
            }
        });
        bottom.addView(next, new LinearLayout.LayoutParams(dp(48), dp(44)));
        panel.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        return panel;
    }

    private void loadTocCover() {
        if (tocCover == null || bookCover.length() == 0) return;
        Covers.load("toc-" + bookId, bookCover, dp(52), dp(70), new Covers.Callback() {
            @Override
            public void onCover(String key, android.graphics.Bitmap bitmap) {
                if (tocCover != null) tocCover.setImageBitmap(bitmap);
            }
        });
    }

    // ---------------- 目录数据 ----------------

    /** 目录行：章节 / 当前读到 / 内联书签。 */
    private static final class TocRow {
        static final int CHAPTER = 0;
        static final int CURRENT = 1;
        static final int MARK = 2;
        final int type;
        final int chapterIndex;
        final Bookmarks.Item mark;
        int pos;   // 全局累计位置，显示在右侧

        TocRow(int type, int chapterIndex, Bookmarks.Item mark) {
            this.type = type;
            this.chapterIndex = chapterIndex;
            this.mark = mark;
        }
    }

    /** 算每章起始的累计字数，用来把章内偏移换算成全书位置数字。 */
    private void computeChapterStart() {
        chapterStart = new int[chapters.size() + 1];
        for (int i = 0; i < chapters.size(); i++) {
            int wc = chapters.get(i).get("wordCount") instanceof Number
                    ? ((Number) chapters.get(i).get("wordCount")).intValue() : 0;
            chapterStart[i + 1] = chapterStart[i] + Math.max(0, wc);
        }
    }

    /** 重建目录行：每个章节下面挂它的书签，当前章额外插一行「当前读到」。 */
    private void buildTocRows(String query) {
        loadMarks();
        computeChapterStart();
        tocRows.clear();
        boolean searching = query != null && query.length() > 0;
        for (int i = 0; i < chapters.size(); i++) {
            Map<String, Object> chapter = chapters.get(i);
            String title = String.valueOf(chapter.get("title"));

            // 本章的书签（uid 优先定位，退回存的下标）
            java.util.List<Bookmarks.Item> here = new ArrayList<Bookmarks.Item>();
            for (Bookmarks.Item m : marks) {
                int mi = Resume.indexOf(chapters, m.uid);
                if (mi < 0) mi = m.idx;
                if (Resume.clamp(mi, chapters.size()) == i) here.add(m);
            }
            Collections.sort(here, new java.util.Comparator<Bookmarks.Item>() {
                @Override
                public int compare(Bookmarks.Item a, Bookmarks.Item b) {
                    return a.off - b.off;
                }
            });

            boolean titleMatch = !searching || title.contains(query);
            boolean anyMarkMatch = false;
            if (searching) {
                for (Bookmarks.Item m : here) {
                    if (m.chapter.contains(query) || m.excerpt.contains(query)) { anyMarkMatch = true; break; }
                }
            }
            if (!titleMatch && !anyMarkMatch) continue;

            TocRow chapterRow = new TocRow(TocRow.CHAPTER, i, null);
            chapterRow.pos = chapterStart[i];
            tocRows.add(chapterRow);

            if (i == chapterIndex) {
                TocRow cur = new TocRow(TocRow.CURRENT, i, null);
                cur.pos = chapterStart[i] + Math.max(0, pageView.currentOffset());
                tocRows.add(cur);
            }
            for (Bookmarks.Item m : here) {
                if (searching && !titleMatch
                        && !m.chapter.contains(query) && !m.excerpt.contains(query)) continue;
                TocRow mr = new TocRow(TocRow.MARK, i, m);
                mr.pos = chapterStart[i] + m.off;
                tocRows.add(mr);
            }
        }
    }

    private void bindToc() {
        buildTocRows("");
        if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
        if (tocList != null) tocList.setSelection(currentRowSelection());
    }

    private void filterToc(String query) {
        buildTocRows(query);
        tocAdapter.notifyDataSetChanged();
        if (query.length() > 0 && !tocRows.isEmpty()) tocList.setSelection(0);
    }

    /** 打开抽屉时滚到「当前读到」那一行。 */
    private int currentRowSelection() {
        for (int i = 0; i < tocRows.size(); i++) {
            if (tocRows.get(i).type == TocRow.CURRENT) return i;
        }
        for (int i = 0; i < tocRows.size(); i++) {
            if (tocRows.get(i).type == TocRow.CHAPTER
                    && tocRows.get(i).chapterIndex == chapterIndex) return i;
        }
        return 0;
    }

    private class TocAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tocRows.size();
        }

        @Override
        public Object getItem(int position) {
            return tocRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TocRow row = tocRows.get(position);
            if (row.type == TocRow.CURRENT) return currentRow(row, convertView);
            if (row.type == TocRow.MARK) return markRow(row, convertView);
            return chapterRow(row, convertView);
        }

        private View chapterRow(final TocRow row, View convertView) {
            LinearLayout v = (LinearLayout) convertView;
            TextView title, words;
            if (v == null || !Integer.valueOf(TocRow.CHAPTER).equals(v.getTag())) {
                v = new LinearLayout(ReaderActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(18), dp(13), dp(16), dp(13));
                v.setBackgroundColor(Color.WHITE);
                title = Ui.text(ReaderActivity.this, "", 14, Ui.SUB);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                v.addView(title, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                words = Ui.text(ReaderActivity.this, "", 10.5f, 0xFFB0B0B0);
                words.setPadding(dp(8), 0, 0, 0);
                v.addView(words);
                v.setTag(Integer.valueOf(TocRow.CHAPTER));
                v.setTag(0x7F000001, new TextView[]{title, words});
            } else {
                TextView[] refs = (TextView[]) v.getTag(0x7F000001);
                title = refs[0];
                words = refs[1];
            }
            final int index = row.chapterIndex;
            title.setText(String.valueOf(chapters.get(index).get("title")));
            int level = chapters.get(index).get("level") instanceof Number
                    ? ((Number) chapters.get(index).get("level")).intValue() : 1;
            boolean isVolume = level <= 1;
            title.setPadding(dp(20 * (level - 1)), 0, 0, 0);
            boolean isCurrent = index == chapterIndex;
            if (isCurrent) {
                // 图2：当前章整行黑底白字高亮，页码也是白色
                v.setBackgroundColor(Ui.INK);
                title.setTextColor(Color.WHITE);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                words.setTextColor(Color.WHITE);
            } else {
                // 卷级加粗深色，子级浅色缩进——像官方目录的层级
                v.setBackgroundColor(Color.WHITE);
                title.setTextColor(isVolume ? Ui.INK : Ui.SUB);
                title.setTypeface(isVolume ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                words.setTextColor(0xFFB0B0B0);
            }
            words.setText(row.pos > 0 ? String.valueOf(row.pos) : "");
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    hideToolbar();
                    if (index != chapterIndex) loadChapter(index, false, 0);
                }
            });
            return v;
        }

        /** 「当前读到 + 添加书签」：点亮当前章，书签入口就在目录里。 */
        private View currentRow(final TocRow row, View convertView) {
            LinearLayout v = (LinearLayout) convertView;
            TextView label, pos;
            if (v == null || !Integer.valueOf(TocRow.CURRENT).equals(v.getTag())) {
                v = new LinearLayout(ReaderActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(18), dp(11), dp(16), dp(11));
                v.setBackgroundColor(0xFFF6F2E8);

                NavIcon icon = new NavIcon(ReaderActivity.this, NavIcon.OPENBOOK);
                icon.setInk(0xFFA8782C);
                v.addView(icon, new LinearLayout.LayoutParams(dp(17), dp(17)));

                label = Ui.text(ReaderActivity.this, "", 12.5f, 0xFFA8782C);
                label.setPadding(dp(9), 0, dp(8), 0);
                v.addView(label, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                pos = Ui.text(ReaderActivity.this, "", 10.5f, 0xFFB0A48C);
                v.addView(pos);
                v.setTag(Integer.valueOf(TocRow.CURRENT));
                v.setTag(0x7F000001, new TextView[]{label, pos});
            } else {
                TextView[] refs = (TextView[]) v.getTag(0x7F000001);
                label = refs[0];
                pos = refs[1];
            }
            boolean hasHere = currentHasBookmark();
            label.setText(hasHere ? "当前读到 · 取消书签" : "当前读到 · 添加书签");
            pos.setText(row.pos > 0 ? String.valueOf(row.pos) : "");
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggleBookmark();
                    buildTocRows("");
                    tocAdapter.notifyDataSetChanged();
                }
            });
            return v;
        }

        /** 内联书签行：图标 + 摘录 + 章名/时间 + 位置，右侧可删。 */
        private View markRow(final TocRow row, View convertView) {
            LinearLayout v = (LinearLayout) convertView;
            TextView excerpt, meta, pos, del;
            if (v == null || !Integer.valueOf(TocRow.MARK).equals(v.getTag())) {
                v = new LinearLayout(ReaderActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(18), dp(11), dp(12), dp(11));
                v.setBackgroundColor(Color.WHITE);

                NavIcon icon = new NavIcon(ReaderActivity.this, NavIcon.BOOKMARK);
                icon.setInk(0xFF8A8A8A);
                v.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));

                LinearLayout texts = new LinearLayout(ReaderActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                excerpt = Ui.text(ReaderActivity.this, "", 12.5f, Ui.INK);
                excerpt.setMaxLines(1);
                excerpt.setEllipsize(TextUtils.TruncateAt.END);
                meta = Ui.text(ReaderActivity.this, "", 10f, Ui.LIGHT);
                meta.setPadding(0, dp(3), 0, 0);
                meta.setMaxLines(1);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(excerpt);
                texts.addView(meta);
                v.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                pos = Ui.text(ReaderActivity.this, "", 10.5f, 0xFFB0A48C);
                pos.setPadding(dp(6), 0, dp(4), 0);
                v.addView(pos);
                del = Ui.text(ReaderActivity.this, "×", 19, 0xFFBBBBBB);
                del.setPadding(dp(8), dp(2), dp(2), dp(2));
                v.addView(del);
                v.setTag(Integer.valueOf(TocRow.MARK));
                v.setTag(0x7F000001, new TextView[]{excerpt, meta, pos, del});
            } else {
                TextView[] refs = (TextView[]) v.getTag(0x7F000001);
                excerpt = refs[0];
                meta = refs[1];
                pos = refs[2];
                del = refs[3];
            }
            final Bookmarks.Item m = row.mark;
            excerpt.setText(m.excerpt.length() > 0 ? m.excerpt : m.chapter);
            String when = Bookmarks.timeLabel(m.at);
            meta.setText(when.length() == 0 ? m.chapter : m.chapter + " · " + when);
            pos.setText(row.pos > 0 ? String.valueOf(row.pos) : "");
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Bookmarks.remove(marks, m);
                    saveMarks();
                    buildTocRows("");
                    tocAdapter.notifyDataSetChanged();
                    refreshBookmarkList();
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    gotoBookmark(m);
                }
            });
            return v;
        }
    }

    // ---------------- 书签 ----------------

    private void loadMarks() {
        marks.clear();
        if (bookId != null) marks.addAll(Bookmarks.decode(store.bookmarks(bookId)));
    }

    private void saveMarks() {
        if (bookId == null) return;
        // merge 顺带排序、限量，导入来的乱序书签写回去也是有序的
        store.setBookmarks(bookId, Bookmarks.encode(Bookmarks.merge(marks, null)));
    }

    /** 当前页这个位置是否已有书签，决定「当前读到」行显示添加还是取消。 */
    private boolean currentHasBookmark() {
        if (bookId == null || chapterIndex >= chapters.size()) return false;
        Object u = chapters.get(chapterIndex).get("uid");
        String uid = u == null ? "" : String.valueOf(u);
        return Bookmarks.indexOf(marks, uid, chapterIndex, pageView.currentOffset()) >= 0;
    }

    /** 工具条上的书签按钮：同一位置再点一次就是取消。 */
    private void toggleBookmark() {
        if (bookId == null || currentText.length() == 0) return;
        Object u = chapterIndex < chapters.size() ? chapters.get(chapterIndex).get("uid") : null;
        String uid = u == null ? "" : String.valueOf(u);
        int off = pageView.currentOffset();
        Bookmarks.Item it = new Bookmarks.Item(uid, chapterTitle, chapterIndex, off,
                Bookmarks.excerptOf(currentText, off), System.currentTimeMillis());
        loadMarks();
        boolean added = Bookmarks.toggle(marks, it);
        saveMarks();
        Toast.makeText(this, added ? "已添加书签 · 目录里可直接看到" : "已取消书签",
                Toast.LENGTH_SHORT).show();
        // 工具条添加时目录抽屉没开也要保证 marks 最新；开着就重建内联行
        if (bookmarkMode) refreshBookmarkList();
        if (tocPanel != null && tocPanel.getVisibility() == View.VISIBLE) {
            buildTocRows("");
            if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
        }
    }

    private void refreshBookmarkList() {
        if (bookmarkAdapter != null) bookmarkAdapter.notifyDataSetChanged();
        boolean empty = marks.isEmpty();
        if (markEmpty != null) {
            markEmpty.setVisibility(bookmarkMode && empty ? View.VISIBLE : View.GONE);
        }
        if (bookmarkTab != null) bookmarkTab.setText(empty ? "书签" : "书签 " + marks.size());
    }

    private void setBookmarkMode(boolean bm) {
        bookmarkMode = bm;
        if (bookmarkTab != null) {
            bookmarkTab.setTextColor(bm ? Color.WHITE : Ui.INK);
            bookmarkTab.setBackgroundDrawable(Ui.box(bm ? Ui.INK : Color.WHITE,
                    bm ? Ui.INK : Ui.LINE, 1, dp(20)));
        }
        if (tocSearch != null) tocSearch.setVisibility(bm ? View.GONE : View.VISIBLE);
        if (tocList != null) tocList.setVisibility(bm ? View.GONE : View.VISIBLE);
        if (markList != null) markList.setVisibility(bm ? View.VISIBLE : View.GONE);
        if (bm) {
            loadMarks();
            refreshBookmarkList();
        } else {
            if (markEmpty != null) markEmpty.setVisibility(View.GONE);
            bindToc();
        }
    }

    /** 跳到书签：uid 优先（目录增删也能对上），找不到再退回存的下标。 */
    private void gotoBookmark(Bookmarks.Item it) {
        int index = Resume.indexOf(chapters, it.uid);
        if (index < 0) index = it.idx;
        index = Resume.clamp(index, chapters.size());
        hideToolbar();
        loadChapter(index, false, Math.max(0, it.off));
    }

    private class BookmarkAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return marks.size();
        }

        @Override
        public Object getItem(int position) {
            return marks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView[] refs;
            if (convertView == null) {
                row = new LinearLayout(ReaderActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(12), dp(10), dp(12));
                row.setBackgroundColor(Color.WHITE);

                LinearLayout texts = new LinearLayout(ReaderActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView title = Ui.text(ReaderActivity.this, "", 13.5f, Ui.INK);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                TextView meta = Ui.text(ReaderActivity.this, "", 10.5f, Ui.LIGHT);
                meta.setPadding(0, dp(4), 0, 0);
                texts.addView(title);
                texts.addView(meta);
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView del = Ui.text(ReaderActivity.this, "×", 20, Ui.LIGHT);
                del.setPadding(dp(12), dp(6), dp(2), dp(6));
                row.addView(del);
                refs = new TextView[]{title, meta, del};
                row.setTag(refs);
            } else {
                row = (LinearLayout) convertView;
                refs = (TextView[]) row.getTag();
            }
            Bookmarks.Item it = marks.get(position);
            refs[0].setText(it.excerpt.length() > 0 ? it.excerpt : it.chapter);
            String chapter = it.chapter.length() > 0 ? it.chapter : "第 " + (it.idx + 1) + " 章";
            String when = Bookmarks.timeLabel(it.at);
            refs[1].setText(when.length() == 0 ? chapter : chapter + " · " + when);
            // 复用行时 position 会变，删除按钮的监听每次都要重绑
            refs[2].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (position < marks.size()) {
                        marks.remove(position);
                        saveMarks();
                        refreshBookmarkList();
                    }
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (position < marks.size()) gotoBookmark(marks.get(position));
                }
            });
            return row;
        }
    }

    // ---------------- 面板显隐 ----------------

    private void toggleToolbar() {
        if (toolbar.getVisibility() == View.VISIBLE) {
            hideToolbar();
        } else {
            toolbar.setVisibility(View.VISIBLE);
            if (topToolbar != null) topToolbar.setVisibility(View.VISIBLE);
            syncChrome();
        }
    }

    /**
     * 左上角那颗按钮按退栈顺序干活：
     * 开着抽屉/面板先收掉，只剩底栏时就是「回书架」，都没开时才是展开菜单。
     */
    private void backOrMenu() {
        if (notesPanel != null && notesPanel.getVisibility() == View.VISIBLE) {
            notesPanel.setVisibility(View.GONE);
            return;
        }
        if (tocPanel.getVisibility() == View.VISIBLE) {
            showToc(false);
            return;
        }
        if (paraPanel != null && paraPanel.getVisibility() == View.VISIBLE) {
            showParaPanel(false);
            return;
        }
        if (displayPanel.getVisibility() == View.VISIBLE) {
            showDisplay(false);
            return;
        }
        if (progressPanel.getVisibility() == View.VISIBLE) {
            showProgress(false);
            return;
        }
        if (toolbar.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }
        toggleToolbar();
    }

    private void hideToolbar() {
        toolbar.setVisibility(View.GONE);
        if (topToolbar != null) topToolbar.setVisibility(View.GONE);
        displayPanel.setVisibility(View.GONE);
        if (paraPanel != null) paraPanel.setVisibility(View.GONE);
        progressPanel.setVisibility(View.GONE);
        showToc(false);
        syncChrome();
    }

    /**
     * 菜单开合只动可见性和页码绘制。
     * 以前这里会改正文区域高度（footerInset），每开一次菜单整页重排，看起来就是「跳一下」。
     */
    private void syncChrome() {
        boolean open = anyPanelOpen();
        if (pageView != null) pageView.setFooterShown(!open);
    }

    private void showToc(boolean show) {
        tocPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (tocDim != null) tocDim.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            tocPanel.bringToFront();
            showDisplay(false);
            showProgress(false);
            if (bookmarkMode) {
                loadMarks();
                refreshBookmarkList();
            } else {
                bindToc();
            }
        } else {
            hideKeyboard();
        }
        syncChrome();
    }

    private void showDisplay(boolean show) {
        displayPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            showToc(false);
            showProgress(false);
            fontRow.setValue(pageView.fontSize());
            marginRow.setValue(pageView.marginDp());
            lineRow.setValue(pageView.linePct());
            indentRow.setValue(pageView.indentCells());
        }
        syncChrome();
    }

    private void showProgress(boolean show) {
        progressPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            showToc(false);
            showDisplay(false);
            bindProgressStats();
            bindReadTime();
        }
        syncChrome();
    }

    /** 进度面板的三个数字 + 滑杆当前位置。 */
    private void bindProgressStats() {
        if (progressPercent == null) return;
        Progress p = progress();
        int page = pageView.currentPage();
        int total = pageView.totalPages();
        int len = currentText.length();
        int at = pageView.currentOffset();
        int pct;
        if (wholeBook && p != null && p.size() > 0) {
            pct = p.percent(chapterIndex, page, total);
        } else {
            pct = len == 0 ? 0 : at * 100 / Math.max(1, len);
        }
        progressPercent.setText(pct + "%");
        String spent = Ui.duration(store.readTime(bookId));
        progressPages.setText(spent.length() == 0 ? "0 分钟" : spent);
        progressChapter.setText(String.valueOf(marks.size() + store.highlightCount(bookId)));
        progressRow.setValue(len == 0 ? 0 : at * 100 / Math.max(1, len));
    }

    /** 滑杆按比例定位本章，立刻跳页。 */
    private void seekChapterPct(int pct) {
        if (currentText.length() == 0) return;
        pageView.setToOffset(currentText.length() * pct / 100);
    }

    private boolean anyPanelOpen() {
        return tocPanel.getVisibility() == View.VISIBLE
                || displayPanel.getVisibility() == View.VISIBLE
                || (paraPanel != null && paraPanel.getVisibility() == View.VISIBLE)
                || progressPanel.getVisibility() == View.VISIBLE
                || (notesPanel != null && notesPanel.getVisibility() == View.VISIBLE)
                || toolbar.getVisibility() == View.VISIBLE;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && tocSearch != null) imm.hideSoftInputFromWindow(tocSearch.getWindowToken(), 0);
    }

    // ---------------- 加载 ----------------

    private void openBook() {
        showOverlay("加载目录…");
        if (LocalBooks.isLocal(bookId)) {
            try {
                chapters.clear();
                chapters.addAll(LocalBooks.catalog(this, bookId));
            } catch (Exception e) {
                fail(e);
                return;
            }
            Map<String, Object> meta = LocalBooks.meta(this, bookId);
            author = meta == null ? "本地导入"
                    : String.valueOf(meta.get("author"));
            if (author == null || author.length() == 0 || "null".equals(author)) author = "本地导入";
            if (tocAuthor != null) tocAuthor.setText(author);
            int index = store.lastIndex(bookId);
            if (index < 0 || index >= chapters.size()) index = 0;
            loadChapter(index, false, store.lastOffset(bookId));
            return;
        }
        if (demo) {
            chapters.clear();
            chapters.addAll(Demo.catalog());
            author = "演示作者";
            if (tocAuthor != null) tocAuthor.setText(author);
            int index = store.lastIndex(bookId);
            if (index < 0 || index >= chapters.size()) index = 0;
            loadChapter(index, false, store.lastOffset(bookId));
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Map<String, Object>> list = api.catalog(bookId);
                    // 云端位置来自书架同步的 bookProgress[]：按 bookId 索引，比 reader 页里
                    // 那份 progress 可靠（页面上同一块的 bookId 会指到最近读过的另一本书）
                    final String remote = store.mark(bookId);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            chapters.clear();
                            chapters.addAll(list);
                            int index = store.lastIndex(bookId);
                            int offset = store.lastOffset(bookId);
                            Resume.Mark cloud = Resume.parse(remote);
                            int at = -9;
                            if (cloud != null) {
                                // 按 uid 找章节，目录对不上（章节增删、uid 变更）才退回下标
                                at = Resume.indexOf(chapters, cloud.uid);
                                if (at < 0) at = Resume.clamp(cloud.idx, chapters.size());
                                if (store.lastPosAt(bookId) <= 0L
                                        || Resume.ahead(at, cloud.offset, index, offset)) {
                                    index = at;
                                    offset = cloud.offset;
                                }
                            }
                            Trace.i("resume cloud=" + remote + " at=" + at
                                    + " use=" + index + "/" + offset);
                            if (index < 0 || index >= chapters.size()) index = 0;
                            if (!readable(index)) index = nextReadable(index, 1);
                            if (index < 0) index = 0;
                            loadChapter(index, false, offset);
                            // 开书拉取：开关打开时，等正文落地后再和云端对齐一次
                            if (store.syncOnOpen()) {
                                ui.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        syncNow(Sync.TRIGGER_OPEN);
                                    }
                                }, 400);
                            }
                        }
                    });
                } catch (final Exception e) {
                    fail(e);
                }
            }
        }).start();
    }

    private void loadChapter(final int index, final boolean startAtEnd, final int offset) {
        loadChapter(index, startAtEnd, offset, false);
    }

    private void loadChapter(final int index, final boolean startAtEnd, final int offset,
                             final boolean auto) {
        if (index < 0 || index >= chapters.size()) return;
        if (!auto) autoSkips = 0;
        chapterIndex = index;
        final Map<String, Object> chapter = chapters.get(index);
        chapterTitle = String.valueOf(chapter.get("title"));
        header.setText(chapterTitle);
        showOverlay("加载中…");
        if (LocalBooks.isLocal(bookId)) {
            final int token = ++loadToken;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String body = LocalBooks.chapterText(
                                ReaderActivity.this, bookId, String.valueOf(chapter.get("uid")));
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (token != loadToken) return;
                                ctx = null;
                                currentText = body;
                                chapterHtml = "";
                                synchronized (localMarks) {
                                    localMarks.clear();
                                    localMarks.addAll(Highlights.decode(store.highlights(bookId)));
                                }
                                autoSkips = 0;
                                hideOverlay();
                                pageView.setText(body, startAtEnd, offset);
                                store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
                                if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
                                loadCloudAnnotations(String.valueOf(chapter.get("uid")));
                            }
                        });
                    } catch (final Exception e) {
                        if (token == loadToken) fail(e);
                    }
                }
            }).start();
            return;
        }
        if (demo) {
            final String body = Demo.text(chapterTitle);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    ctx = null;
                    currentText = body;
                    hideOverlay();
                    pageView.setText(body, startAtEnd, offset);
                }
            });
            return;
        }
        final int token = ++loadToken;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String uid = String.valueOf(chapter.get("uid"));
                    Api.Context c = api.context(bookId, uid, false);
                    Api.ChapterData cd;
                    try {
                        cd = api.chapter(bookId, uid, c);
                    } catch (Exception first) {
                        c = api.context(bookId, uid, true);
                        cd = api.chapter(bookId, uid, c);
                    }
                    String text = cd.text;
                    if (text.trim().length() == 0) {
                        // 封面页/版权页只有图片，没有可排版的文字：直接略过
                        final int next = nextReadable(index + 1, 1);
                        if (next >= 0 && autoSkips < 6) {
                            autoSkips++;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    loadChapter(next, false, 0, true);
                                }
                            });
                            return;
                        }
                        throw new Exception("本章为图片页，没有可显示的文字");
                    }
                    final String body = text;
                    final String rawHtml = cd.html;
                    final Api.Context context = c;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != loadToken) return;
                            ctx = context;
                            currentText = body;
                            chapterHtml = rawHtml;
                            synchronized (localMarks) {
                                localMarks.clear();
                                localMarks.addAll(Highlights.decode(store.highlights(bookId)));
                            }
                            autoSkips = 0;
                            hideOverlay();
                            pageView.setText(body, startAtEnd, offset);
                            store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
                            if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
                            loadCloudAnnotations(String.valueOf(chapter.get("uid")));
                            report();
                        }
                    });
                } catch (final Exception e) {
                    if (token == loadToken) fail(e);
                }
            }
        }).start();
    }

    // ---------------- 划线与想法 ----------------

    @Override
    public void onSelection(int start, int end) {
        selAnchorStart = start;
        selAnchorEnd = end;
        showSelMenu();
    }

    @Override
    public void onHighlightTap(int start, int end, int kind) {
        if (start < 0) {   // 点了选区外，菜单随选区一起消失
            dismissSelMenu();
            return;
        }
        if (kind == PageView.HL_CLOUD) {
            showThoughts(markTextAt(start, end));
        } else {
            showMarkActions(start, end);
        }
    }

    private String markTextAt(int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(currentText.length(), end);
        return from < to ? currentText.substring(from, to) : "";
    }

    /** 长按选择后的浮动操作条：划线 / 写想法 / 复制 / 查询，贴在选区上方。 */
    private void showSelMenu() {
        dismissSelMenu();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundDrawable(Ui.box(0xEE1F1F1F, 0x00000000, 0, dp(8)));
        String[] labels = {"划线", "写想法", "复制", "查询"};
        for (int i = 0; i < labels.length; i++) {
            TextView b = Ui.text(this, labels[i], 14, Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(16), dp(10), dp(16), dp(10));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String l = ((TextView) v).getText().toString();
                    dismissSelMenu();
                    if (l.equals("划线")) createHighlight();
                    else if (l.equals("写想法")) writeThought();
                    else if (l.equals("复制")) copySelection();
                    else if (l.equals("查询")) querySelection();
                }
            });
            bar.addView(b);
        }
        selMenu = new android.widget.PopupWindow(bar,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        selMenu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        selMenu.setOutsideTouchable(true);
        int top = pageView.selectionTop();
        int y = top > dp(90) ? top - dp(52) : dp(60);
        selMenu.showAtLocation(pageView, Gravity.NO_GRAVITY, dp(40), y);
    }

    private void dismissSelMenu() {
        if (selMenu != null && selMenu.isShowing()) selMenu.dismiss();
        selMenu = null;
        pageView.clearSelection();
    }

    /** 划线：先落本地，range 能映射就推云端（addBookmark），映射失败只留本地。 */
    private void createHighlight() {
        final int s = selAnchorStart;
        final int e = selAnchorEnd;
        if (e <= s) return;
        String chapterUid = String.valueOf(chapters.get(chapterIndex).get("uid"));
        Highlights.Item it = new Highlights.Item(chapterUid, chapterIndex, s, e,
                Highlights.excerptOf(currentText, s, e), System.currentTimeMillis());
        synchronized (localMarks) {
            Highlights.add(localMarks, it);
            persistMarks();
        }
        applyHighlights();
        // 不再弹「已划线」提示：灰底高亮本身就是反馈，
        // 而且提示层是全屏白底，在墨水屏上就是一次白屏闪。
        final Highlights.Item item = it;
        new Thread(new Runnable() {
            @Override
            public void run() {
                uploadMark(item);
            }
        }).start();
    }

    /** 推云端：range 映射失败时静默跳过（仅本地），成功后回写 bookmarkId。 */
    private void uploadMark(Highlights.Item item) {
        if (chapterHtml.length() == 0 || item.rid.length() > 0) return;
        try {
            int[] rg = item.range.length() > 0 ? null
                    : Protocol.htmlRange(chapterHtml, currentText, item.start, item.end);
            if (rg == null && item.range.length() == 0) return;
            String range = item.range.length() > 0 ? item.range
                    : rg[0] + "-" + rg[1];
            String rid = api.addBookmark(bookId, item.uid, chapterIndex, fetchVer(),
                    range, markTextAt(item.start, item.end));
            item.rid = rid;
            item.range = range;
            synchronized (localMarks) {
                persistMarks();
            }
        } catch (Exception e) {
            Trace.i("addBookmark failed: " + e);
        }
    }

    /** 写想法：弹输入框，发布默认私密（isPrivate=1），走 /web/review/add。 */
    private void writeThought() {
        final String mark = markTextAt(selAnchorStart, selAnchorEnd);
        pageView.clearSelection();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView quote = Ui.text(this, mark.length() > 0 ? "「" + mark + "」" : "整章想法",
                12.5f, Ui.SUB);
        quote.setPadding(0, 0, 0, dp(10));
        box.addView(quote);
        final EditText input = new EditText(this);
        input.setHint("写下你的想法（发布后仅自己可见）");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tip = Ui.text(this, "想法同步到微信读书云端，官方 App 的「热门想法」不展示私密内容",
                10.5f, Ui.LIGHT);
        tip.setPadding(0, dp(8), 0, dp(4));
        box.addView(tip);
        new AlertDialog.Builder(this)
                .setTitle("写想法")
                .setView(box)
                .setPositiveButton("发布", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String content = input.getText().toString().trim();
                        if (content.length() == 0) return;
                        final String chapterUid =
                                String.valueOf(chapters.get(chapterIndex).get("uid"));
                        final int s = selAnchorStart;
                        final int e = selAnchorEnd;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String range = "0-0";
                                    int[] rg = Protocol.htmlRange(
                                            chapterHtml, currentText, s, e);
                                    if (rg != null) range = rg[0] + "-" + rg[1];
                                    api.addReview(bookId, chapterUid, chapterIndex,
                                            fetchVer(), range, mark, content);
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            brief("想法已同步");
                                        }
                                    });
                                } catch (final Exception ex) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            brief("想法同步失败：" + ex.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copySelection() {
        String text = pageView.selectionText();
        pageView.clearSelection();
        if (text.length() == 0) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
        brief("已复制");
    }

    /** 查询：把选词丢给百科（系统浏览器打开，不内置 WebView）。 */
    private void querySelection() {
        String word = pageView.selectionText().trim();
        pageView.clearSelection();
        if (word.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://baike.baidu.com/item/" + java.net.URLEncoder.encode(word, "UTF-8"))));
        } catch (Exception e) {
            brief("没有可用的浏览器");
        }
    }

    /** 点自己的划线：复制 / 删除（本地 + 云端）。 */
    private void showMarkActions(final int s, final int e) {
        final Highlights.Item it = Highlights.at(synchronizedMarks(), chapterIndex, s);
        if (it == null) return;
        String[] items = {"复制原文", "删除划线"};
        new AlertDialog.Builder(this)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(
                                            Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "text", markTextAt(s, e)));
                            brief("已复制");
                        } else {
                            deleteMark(it);
                        }
                    }
                }).show();
    }

    private List<Highlights.Item> synchronizedMarks() {
        synchronized (localMarks) {
            return new ArrayList<Highlights.Item>(localMarks);
        }
    }

    private void deleteMark(final Highlights.Item it) {
        synchronized (localMarks) {
            Highlights.remove(localMarks, it.uid, it.start, it.end);
            persistMarks();
        }
        applyHighlights();
        brief("已删除划线");
        if (it.rid.length() > 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        api.removeBookmark(it.rid, bookId, it.uid);
                    } catch (Exception e) {
                        Trace.i("removeBookmark failed: " + e);
                    }
                }
            }).start();
        }
    }

    /** 点书友划线：弹该段的想法列表（含点赞数）。 */
    private void showThoughts(final String mark) {
        if (mark.length() == 0) return;
        Integer n = thoughtCount.get(mark);
        if (n == null || n.intValue() == 0) {
            brief("这一段还没有书友想法");
            return;
        }
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : cloudReviews()) {
            String m = String.valueOf(r.get("mark"));
            if (m.equals(mark)) rows.add(r);
        }
        if (rows.isEmpty()) {
            brief("这一段还没有书友想法");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Map<String, Object> r : rows) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(10), dp(16), dp(10));
            TextView who = Ui.text(this, String.valueOf(r.get("author")), 12.5f, Ui.INK);
            who.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(who);
            TextView body = Ui.text(this, String.valueOf(r.get("content")), 13.5f, Ui.SUB);
            body.setPadding(0, dp(4), 0, dp(4));
            card.addView(body);
            TextView meta = Ui.text(this, "♥ " + r.get("like") + " · 💬 "
                    + r.get("comment"), 11, Ui.LIGHT);
            card.addView(meta);
            list.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        sc.addView(list);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        new AlertDialog.Builder(this)
                .setTitle("「" + ellipsis(mark, 18) + "」的书友想法")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String ellipsis(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private List<Map<String, Object>> cloudReviews() {
        synchronized (cloudMarks) {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> m : cloudMarks) out.add(m);
            return out;
        }
    }

    private int fetchVer() {
        if (verFetched) return bookVer;
        try {
            bookVer = api.bookVersion(bookId);
        } catch (Exception e) {
            bookVer = 0;
        }
        verFetched = true;
        return bookVer;
    }

    private void persistMarks() {
        synchronized (localMarks) {
            store.setHighlights(bookId, Highlights.encode(localMarks));
        }
    }

    /** 高亮应用到 PageView：本地深灰、书友浅灰；云端异步到达后重算一次。 */
    private void applyHighlights() {
        List<Integer> ranges = new ArrayList<Integer>();
        List<Integer> kinds = new ArrayList<Integer>();
        synchronized (localMarks) {
            for (Highlights.Item it : localMarks) {
                if (it.idx != chapterIndex) continue;
                ranges.add(Integer.valueOf(it.start));
                ranges.add(Integer.valueOf(it.end));
                kinds.add(Integer.valueOf(PageView.HL_LOCAL));
            }
        }
        synchronized (cloudMarks) {
            for (Map<String, Object> m : cloudMarks) {
                String mark = String.valueOf(m.get("markText"));
                if (mark.length() == 0) continue;
                int at = currentText.indexOf(mark);
                if (at < 0) continue;
                ranges.add(Integer.valueOf(at));
                ranges.add(Integer.valueOf(at + mark.length()));
                kinds.add(Integer.valueOf(PageView.HL_CLOUD));
            }
        }
        int[] r = new int[ranges.size()];
        int[] k = new int[kinds.size()];
        // r 是扁平 pairs（每个划线占 2 个），k 按对存——两者长度不同，分开拷
        for (int i = 0; i < r.length; i++) {
            r[i] = ranges.get(i).intValue();
        }
        for (int i = 0; i < k.length; i++) {
            k[i] = kinds.get(i).intValue();
        }
        pageView.setHighlightRanges(r, k);
    }

    /** 章节加载完成后调用：本地立即生效，云端划线+想法数异步补。 */
    private void loadCloudAnnotations(final String uid) {
        synchronized (cloudMarks) {
            cloudMarks.clear();
        }
        thoughtCount.clear();
        applyHighlights();
        if (demo || LocalBooks.isLocal(bookId)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Map<String, Object>> ul = api.webUnderlines(bookId, uid);
                    synchronized (cloudMarks) {
                        cloudMarks.clear();
                        cloudMarks.addAll(ul);
                    }
                } catch (Exception first) {
                    // 网页通道失败退 E-Ink 通道
                    Eink.Cred cred = Eink.load(store);
                    if (cred == null) return;
                    try {
                        List<Map<String, Object>> ul =
                                Eink.underlines(store, cred, bookId, uid);
                        synchronized (cloudMarks) {
                            cloudMarks.clear();
                            cloudMarks.addAll(ul);
                        }
                    } catch (Exception e) {
                        Trace.i("underlines failed: " + e);
                        return;
                    }
                }
                // 想法数：按 markText 聚合现有想法列表
                List<Map<String, Object>> revs = fetchReviews();
                final java.util.HashMap<String, Integer> cnt =
                        new java.util.HashMap<String, Integer>();
                for (Map<String, Object> r : revs) {
                    String m = String.valueOf(r.get("mark"));
                    if (m.length() == 0) continue;
                    Integer v = cnt.get(m);
                    cnt.put(m, Integer.valueOf(v == null ? 1 : v.intValue() + 1));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        thoughtCount.clear();
                        thoughtCount.putAll(cnt);
                        applyHighlights();
                    }
                });
            }
        }).start();
    }

    private List<Map<String, Object>> fetchReviews() {
        try {
            return Eink.reviews(store, Eink.load(store), bookId,
                    String.valueOf(chapters.get(chapterIndex).get("uid")), 0);
        } catch (Exception e) {
            try {
                return api.reviews(bookId,
                        String.valueOf(chapters.get(chapterIndex).get("uid")), 0);
            } catch (Exception e2) {
                return new ArrayList<Map<String, Object>>();
            }
        }
    }

    private void showOverlay(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                overlay.setText(text);
                overlay.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private void fail(final Exception e) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                overlay.setVisibility(View.VISIBLE);
                overlay.setText("加载失败\n" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    // ---------------- 章节可读性 ----------------

    /** wordCount 为 0 的是封面/版权/目录这类纯图片页，没有可排版的文字。 */
    private boolean readable(int i) {
        if (i < 0 || i >= chapters.size()) return false;
        Object w = chapters.get(i).get("wordCount");
        if (w instanceof Number) return ((Number) w).intValue() > 0;
        return true;
    }

    /** 沿 dir 方向找下一个有正文的章节，找不到返回 -1。 */
    private int nextReadable(int from, int dir) {
        for (int i = from; i >= 0 && i < chapters.size(); i += dir) {
            if (readable(i)) return i;
        }
        return -1;
    }

    // ---------------- 翻页回调 ----------------

    @Override
    public void onNextChapter() {
        int next = nextReadable(chapterIndex + 1, 1);
        if (next >= 0) {
            loadChapter(next, false, 0);
        } else {
            brief("已经是最后一章了");
        }
    }

    @Override
    public void onPrevChapter() {
        int prev = nextReadable(chapterIndex - 1, -1);
        if (prev >= 0) {
            loadChapter(prev, true, 0);
        } else {
            brief("已经是第一章了");
        }
    }

    @Override
    public void onMenu() {
        toggleToolbar();
    }

    @Override
    public void onPageChanged(int page, int total, int offset) {
        if (chapters.isEmpty()) return;
        store.setLastPos(bookId, chapterIndex, offset);
        Progress p = progress();
        if (p != null && pageView.getWidth() > 0 && currentText.length() > 0 && total > 0) {
            // 排版参数变了之前记的页数就作废，这里用签名同步
            p.setLayout(pageView.fontSize() + "/" + pageView.marginDp()
                    + "/" + pageView.linePct());
            p.setActual(chapterIndex, total, currentText.length());
            // 页数变了，页脚的全书页码要跟着重画
            pageView.invalidate();
        }
    }

    /** 目录就绪后才有意义；章数变了就重建。 */
    private Progress progress() {
        if (chapters.isEmpty()) return null;
        if (progress == null || progress.size() != chapters.size()) {
            progress = new Progress(chapters);
        }
        return progress;
    }

    private void brief(String text) {
        showOverlay(text);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideOverlay();
            }
        }, 900);
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = event.getRepeatCount() > 0;

        if (code == KeyEvent.KEYCODE_BACK) {
            if (anyPanelOpen()) {
                hideToolbar();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (code == KeyEvent.KEYCODE_MENU && down && !repeat) {
            toggleToolbar();
            return true;
        }

        boolean volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (volume) {
            if (!down) return true;
            if (displayPanel.getVisibility() == View.VISIBLE) {
                if (!repeat) nudgeFont(code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
                return true;
            }
            if (!store.volumePaging()) return super.dispatchKeyEvent(event);
            if (!repeat) {
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) pageView.next();
                else pageView.prev();
            }
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_SPACE:
                if (down && !repeat && !anyPanelOpen()) pageView.next();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down && !repeat && !anyPanelOpen()) pageView.prev();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void nudgeFont(int delta) {
        pageView.setFontSize(pageView.fontSize() + delta);
        store.setFontSize(pageView.fontSize());
        fontRow.setValue(pageView.fontSize());
        store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
    }

    // ---------------- 进度双向同步 ----------------

    /**
     * 拉取云端进度并与本地对比，按 Sync 决策执行。trigger 决定开关与上传行为。
     * 双源：书架同步存下的 bookProgress（web）+ E-Ink getProgress（gateway，需授权）。
     */
    private void syncNow(final int trigger) {
        if (bookId == null || LocalBooks.isLocal(bookId) || demo || chapters.isEmpty()) return;
        if (syncing) return;
        syncing = true;
        brief(trigger == Sync.TRIGGER_MANUAL ? "同步中…" : "正在对齐云端进度");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Sync.Catalog cat = new Sync.Catalog(chapters);
                final Sync.Pos local = localPos(cat);
                Sync.Pos web = posFromMark(cat, store.mark(bookId));
                Sync.Pos gateway = posFromEink(cat);
                Sync.RemoteChoice choice = Sync.chooseRemote(web, gateway);
                final Sync.Pos remote = choice.pos == null ? null
                        : Sync.normalizeRemote(cat, choice.pos);
                final int cmp = Sync.compare(local, remote);
                final boolean conflict = choice.conflict;
                final String pulled = remote == null ? "" : String.valueOf(remote.percent);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        syncing = false;
                        if (pulled.length() > 0) {
                            store.setSyncState(bookId, System.currentTimeMillis(), 0, "");
                        }
                        applyDecision(trigger, cmp, conflict, local, remote, cat);
                    }
                });
            }
        }).start();
    }

    /** 本地当前位置 → 统一坐标。用字数权重算全书百分比，和云端同一把尺子。 */
    private Sync.Pos localPos(Sync.Catalog cat) {
        if (chapterIndex < 0 || chapterIndex >= chapters.size()) {
            return new Sync.Pos(0, "", 0, 0L, "local");
        }
        String uid = String.valueOf(chapters.get(chapterIndex).get("uid"));
        int offset = pageView == null ? 0 : pageView.currentOffset();
        int chLen = currentText.length();
        if (chLen <= 0) {
            Object wc = chapters.get(chapterIndex).get("wordCount");
            chLen = wc instanceof Number ? ((Number) wc).intValue() : 0;
        }
        return new Sync.Pos(cat.localPercent(chapterIndex, offset, chLen), uid, offset, 0L, "local");
    }

    /** 书架同步存下的云端位置（Resume 编码串）→ 统一坐标。 */
    private Sync.Pos posFromMark(Sync.Catalog cat, String raw) {
        Resume.Mark m = Resume.parse(raw);
        if (m == null) return null;
        double pct = cat.remotePercent(m.uid, m.offset);
        if (pct < 0) {
            // uid 对不上：退回下标兜底，只用一个粗略百分比
            int idx = Resume.clamp(m.idx, cat.words.length);
            pct = cat.localPercent(idx, m.offset, 0);
        }
        return new Sync.Pos(pct, m.uid, m.offset, m.updated, "web");
    }

    /** E-Ink 精确进度 → 统一坐标。未授权或失败返回 null，自动降级为只有 web 单源。 */
    private Sync.Pos posFromEink(Sync.Catalog cat) {
        Eink.Cred cred = Eink.load(store);
        if (!cred.ok()) return null;
        try {
            Eink.Progress ep = Eink.getProgress(store, cred, bookId, store.compat());
            if (!ep.ok()) return null;
            return new Sync.Pos(ep.percent, ep.chapterUid, ep.chapterOffset, ep.updated, "gateway");
        } catch (Exception e) {
            Trace.i("Eink getProgress failed: " + (e.getMessage() == null ? e : e.getMessage()));
            return null;
        }
    }

    /** 执行决策：跳转 / 保留 / 上传 / 弹窗。 */
    private void applyDecision(int trigger, int cmp, boolean conflict,
                               Sync.Pos local, Sync.Pos remote, Sync.Catalog cat) {
        Sync.Action a = Sync.decide(cmp, conflict, trigger,
                store.syncOnOpen(), store.syncOnClose());
        Trace.i("sync trigger=" + trigger + " cmp=" + cmp + " conflict=" + conflict
                + " -> " + a.what + " " + a.reason
                + " local=" + (local == null ? "-" : local.percent)
                + " remote=" + (remote == null ? "-" : remote.percent));
        switch (a.what) {
            case Sync.ACT_JUMP_REMOTE:
                if (remote != null && jumpToRemote(remote, cat)) {
                    brief("已跳到云端进度 " + (int) Math.round(remote.percent) + "%");
                } else {
                    brief("云端位置对不上本地目录，保留当前位置");
                }
                break;
            case Sync.ACT_UPLOAD_LOCAL:
                uploadLocal();
                brief("本地进度已上传云端");
                break;
            case Sync.ACT_ASK:
                showConflictDialog(local, remote, cat);
                break;
            case Sync.ACT_KEEP_LOCAL:
                if (trigger == Sync.TRIGGER_MANUAL) brief("本地进度更新，保留本地");
                break;
            default:
                if (trigger == Sync.TRIGGER_MANUAL) brief("两端进度一致");
                break;
        }
    }

    /** 跳到云端记录的位置：按 uid 找章，章内用字数偏移（≈字符偏移）定位。 */
    private boolean jumpToRemote(Sync.Pos remote, Sync.Catalog cat) {
        int idx = cat.indexOf(remote.chapterUid);
        if (idx < 0 || idx >= chapters.size()) return false;
        Object wc = chapters.get(idx).get("wordCount");
        int words = wc instanceof Number ? ((Number) wc).intValue() : 0;
        int offset = words > 0 ? Math.min(remote.chapterOffset, words) : remote.chapterOffset;
        // 目录变了字数可能对不上，用章内比例兜底换算成字符位置
        double frac = cat.remoteChapterFraction(remote.chapterUid, remote.chapterOffset);
        if (frac >= 0 && words > 0) offset = (int) (frac * words);
        loadChapter(idx, false, Math.max(0, offset));
        return true;
    }

    /** 冲突弹窗：两侧百分比 + 归属，由用户裁决。 */
    private void showConflictDialog(final Sync.Pos local, final Sync.Pos remote,
                                    final Sync.Catalog cat) {
        String lp = local == null ? "—" : (int) Math.round(local.percent) + "%";
        String rp = remote == null ? "—" : (int) Math.round(remote.percent) + "%"
                + (remote.source.length() > 0 ? "（" + srcLabel(remote.source) + "）" : "");
        new AlertDialog.Builder(this)
                .setTitle("两端进度不一致")
                .setMessage("本地读到 " + lp + "\n云端读到 " + rp + "\n\n选择以哪一端为准？")
                .setPositiveButton("用云端进度", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (remote != null && jumpToRemote(remote, cat)) {
                            brief("已跳到云端进度");
                        } else {
                            brief("云端位置对不上，保留本地");
                        }
                    }
                })
                .setNegativeButton("保留本地并上传", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        uploadLocal();
                        brief("本地进度已上传云端");
                    }
                })
                .show();
    }

    private static String srcLabel(String source) {
        if ("web".equals(source)) return "网页";
        if ("gateway".equals(source)) return "客户端";
        return source;
    }

    /** 上传本地进度：先落 pending 快照（断网不丢），复用已验证的 report 通道，成功再清。 */
    private void uploadLocal() {
        if (bookId == null || chapterIndex < 0 || chapterIndex >= chapters.size()) return;
        String uid = String.valueOf(chapters.get(chapterIndex).get("uid"));
        int offset = pageView == null ? 0 : pageView.currentOffset();
        int percent = overallProgress(offset);
        String summary = currentText.length() == 0 ? ""
                : currentText.substring(offset, Math.min(currentText.length(), offset + 20));
        // pending 快照格式：uid|offset|idx|percent|summary
        String snap = uid + "|" + offset + "|" + chapterIndex + "|" + percent + "|" + summary;
        store.setPendingPos(bookId, snap);
        final String pending = snap;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                // 遇「忙」最多重发 10 次、每次间隔 2 秒（对齐 koplugin 的弱网策略）
                for (int attempt = 0; attempt < Sync.PUSH_RETRY_MAX && !ok; attempt++) {
                    if (attempt > 0) {
                        try {
                            Thread.sleep(Sync.PUSH_RETRY_MS);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                    ok = reportSilently(pending);
                }
                final boolean done = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            store.clearPendingPos(bookId);
                            store.setSyncState(bookId, 0, System.currentTimeMillis(), "");
                        } else {
                            // 没上传成功也要把失败原因记下，pending 快照保留待下次
                            store.setSyncState(bookId, 0, 0, "上传失败，位置已暂存");
                        }
                    }
                });
            }
        }).start();
    }

    /** 从 pending 快照上报一次，不碰 UI、不弹提示。true 表示落库成功。 */
    private boolean reportSilently(String snap) {
        if (ctx == null || demo) return false;
        String[] p = snap.split("\\|", -1);
        if (p.length < 4) return false;
        String uid = p[0];
        int offset = Store.parseInt(p[1]);
        int idx = Store.parseInt(p[2]);
        int percent = Store.parseInt(p[3]);
        String summary = p.length > 4 ? p[4] : "";
        int elapsed = 0;
        try {
            return api.report(bookId, uid, idx, offset, percent, elapsed, summary, ctx);
        } catch (Exception e) {
            Trace.i("reportSilently failed: " + e);
            return false;
        }
    }

    private void report() {
        final long now = System.currentTimeMillis();
        final int elapsed = (int) Math.max(0L, (now - sinceReport) / 1000L);
        sinceReport = now;
        // 本 App 阅读时长与是否联网/是否本地书无关，只要真的在读就记（统计卡片口径）。
        if (!demo && elapsed > 0) store.noteRead(elapsed);
        if (LocalBooks.isLocal(bookId)) return;   // 本地书没有云端进度
        if (demo || chapters.isEmpty() || ctx == null || currentText.length() == 0) return;
        final String uid = String.valueOf(chapters.get(chapterIndex).get("uid"));
        final int offset = pageView.currentOffset();
        final int progress = overallProgress(offset);
        final String summary = currentText.substring(offset,
                Math.min(currentText.length(), offset + 20));
        final Api.Context c = ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (api.report(bookId, uid, chapterIndex, offset, progress, elapsed, summary, c)) {
                    store.addReadTime(bookId, elapsed);
                }
            }
        }).start();
    }

    private int overallProgress(int offset) {
        Progress p = progress();
        if (p != null) return p.charPercent(chapterIndex, offset, currentText.length());
        if (chapters.isEmpty()) return 0;
        double chapterPart = currentText.length() == 0 ? 0
                : (double) offset / currentText.length();
        double v = (chapterIndex + chapterPart) / chapters.size();
        int r = (int) Math.round(v * 100);
        return Math.max(0, Math.min(100, r));
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.postDelayed(ticker, 60000);
        boolean whole = store.progressWholeBook();
        if (whole != wholeBook) {
            wholeBook = whole;
            if (pageView != null) pageView.invalidate();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(applyPreview);
        ui.removeCallbacks(autoFlipTick);
        autoFlipping = false;
        if (autoFlipBtn != null) autoFlipBtn.setText("开启自动翻页");
        report();
        if (!chapters.isEmpty()) {
            store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
        }
        // 关书上传：带 pending 快照 + 弱网重试，确保本地进度不丢。
        // report() 仍保留，负责阅读时长这条基础链路。
        if (store.syncOnClose() && bookId != null && !LocalBooks.isLocal(bookId)
                && !demo && !chapters.isEmpty()) {
            uploadLocal();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
