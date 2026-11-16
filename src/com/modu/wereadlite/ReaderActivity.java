package com.modu.wereadlite;

import android.app.Activity;
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
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 阅读页：点中间出工具条，目录抽屉 + 显示面板（字号/边距/行距），音量键翻页。 */
public class ReaderActivity extends Activity implements PageView.Callback {

    private Store store;
    private Api api;
    private PageView pageView;
    private TextView header;
    private TextView overlay;

    private View toolbar;
    private FrameLayout menuDot;
    private View displayPanel;
    private View progressPanel;
    private SeekRow progressRow;
    private TextView progressPercent;
    private TextView progressPages;
    private TextView progressChapter;
    private TextView progressHint;
    private View tocPanel;
    private View tocDim;
    private TextView tocAuthor;
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
    private final List<Integer> visible = new ArrayList<Integer>();
    private int chapterIndex;
    private String chapterTitle = "";
    private String currentText = "";
    private Progress progress;
    /** 页数/百分比按整本书算（默认）还是只看本章。 */
    private boolean wholeBook = true;
    private Api.Context ctx;
    /** 上次上报的时刻。rt 要报「距上次上报读了多久」，报开场至今云端时长会成倍增加。 */
    private long sinceReport;
    private int loadToken;
    private boolean demo;
    private final Handler ui = new Handler();

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

        header = Ui.text(this, bookTitle, 10.5f, 0xFF8A8A8A);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // 页眉定高，左上角按钮整个落在这一行里，不会再压到下面的正文首行
        header.setPadding(dp(42), dp(2), dp(16), dp(2));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        pageView = new PageView(this);
        pageView.setCallback(this);
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
        page.addView(pageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = Ui.text(this, "加载中…", 14.5f, Ui.INK);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.WHITE);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 原来那颗 38dp 圆形悬浮按钮跨过页眉压住了正文首行。
        // 现在去掉圆形描边，只留一个细箭头，并把它整个收进页眉行内。
        menuDot = new FrameLayout(this);
        NavIcon menuGlyph = new NavIcon(this, NavIcon.BACK);
        menuGlyph.setInk(Ui.INK);
        menuDot.addView(menuGlyph, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        menuDot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backOrMenu();
            }
        });
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.LEFT | Gravity.TOP);
        dlp.setMargins(dp(8), dp(2), 0, 0);
        root.addView(menuDot, dlp);

        toolbar = buildToolbar();
        root.addView(toolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.BOTTOM));

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
        return root;
    }

    private TextView flatButton(String label, float sp, int color) {
        TextView t = Ui.text(this, label, sp, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        return t;
    }

    /** 底部工具条：纯图标一行，和微读客户端一致，文字留给面板本身。 */
    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setVisibility(View.GONE);
        bar.setPadding(dp(6), 0, dp(6), 0);

        bar.addView(barButton(NavIcon.LIST, "目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToc(true);
            }
        }), slot());
        bar.addView(barButton(NavIcon.BOOKMARK, "加/取消书签", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBookmark();
            }
        }), slot());
        bar.addView(barButton(NavIcon.THOUGHT, "书友想法", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openReviews();
            }
        }), slot());
        bar.addView(barButton(NavIcon.PROGRESS, "阅读进度", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(true);
            }
        }), slot());
        bar.addView(barButton(NavIcon.INFO, "书籍详情", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail();
            }
        }), slot());
        bar.addView(barButton(NavIcon.LETTER_A, "显示样式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDisplay(true);
            }
        }), slot());
        return bar;
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
        stats.setPadding(dp(16), dp(12), dp(16), dp(2));
        progressPercent = statCell(stats, "全书进度");
        progressPages = statCell(stats, "本页 / 全书");
        progressChapter = statCell(stats, "章节");
        panel.addView(stats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout slider = new LinearLayout(this);
        slider.setOrientation(LinearLayout.HORIZONTAL);
        slider.setGravity(Gravity.CENTER_VERTICAL);
        slider.setPadding(dp(6), 0, dp(6), 0);
        slider.addView(arrowButton("‹ 上一章", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(false);
                onPrevChapter();
            }
        }), new LinearLayout.LayoutParams(dp(62), dp(40)));

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

        View next = arrowButton("下一章 ›", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgress(false);
                onNextChapter();
            }
        });
        slider.addView(next, new LinearLayout.LayoutParams(dp(62), dp(40)));
        panel.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressHint = Ui.text(this, "拖动看在本章的位置，箭头直接切章", 10.5f, Ui.LIGHT);
        progressHint.setGravity(Gravity.CENTER);
        progressHint.setPadding(0, dp(2), 0, dp(12));
        panel.addView(progressHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /** 已读时长来自书架同步，本地导入的书没有，就不显示这半句。 */
    private void bindReadTime() {
        if (progressHint == null) return;
        String spent = Ui.duration(store.readTime(bookId));
        progressHint.setText(spent.length() == 0
                ? "拖动看在本章的位置，箭头直接切章"
                : "本书已读 " + spent + " · 拖动看在本章的位置");
    }

    /** 进度面板顶部的一列：大数字 + 小注，返回数字那个 TextView。 */
    private TextView statCell(LinearLayout parent, String caption) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
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
    private View styleRow(String label, SeekRow row, String value) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(dp(16), dp(4), dp(10), dp(4));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, label, 10.5f, Ui.SUB);
        col.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView num = Ui.text(this, value, 15, Ui.INK);
        num.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        col.addView(num, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if ("字号".equals(label)) fontValue = num;
        else if ("边距".equals(label)) marginValue = num;
        else if ("缩进".equals(label)) indentValue = num;
        else lineValue = num;
        wrap.addView(col, new LinearLayout.LayoutParams(dp(44), dp(40)));
        wrap.addView(row, new LinearLayout.LayoutParams(0, dp(48), 1f));
        return wrap;
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

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.LIGHT);
        search.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, 3));
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
        bottom.setPadding(dp(16), 0, dp(16), 0);

        tocTab = Ui.text(this, "目录", 13.5f, Ui.INK);
        tocTab.setGravity(Gravity.CENTER);
        tocTab.setPadding(dp(6), dp(10), dp(6), dp(10));
        tocTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setBookmarkMode(false);
            }
        });
        bottom.addView(tocTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bookmarkTab = Ui.text(this, "书签", 13.5f, Ui.LIGHT);
        bookmarkTab.setGravity(Gravity.CENTER);
        bookmarkTab.setPadding(dp(6), dp(10), dp(6), dp(10));
        bookmarkTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setBookmarkMode(true);
            }
        });
        bottom.addView(bookmarkTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = flatButton("关闭", 13, Ui.SUB);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToc(false);
            }
        });
        bottom.addView(close);
        panel.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

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

    private void bindToc() {
        visible.clear();
        for (int i = 0; i < chapters.size(); i++) visible.add(Integer.valueOf(i));
        if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
        if (tocList != null) tocList.setSelection(Math.max(0, Math.min(chapterIndex, visible.size() - 1)));
    }

    private void filterToc(String query) {
        visible.clear();
        for (int i = 0; i < chapters.size(); i++) {
            String title = String.valueOf(chapters.get(i).get("title"));
            if (query.length() == 0 || title.contains(query)) visible.add(Integer.valueOf(i));
        }
        tocAdapter.notifyDataSetChanged();
        if (query.length() > 0 && !visible.isEmpty()) tocList.setSelection(0);
    }

    private class TocAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView words;
            if (convertView == null) {
                row = new LinearLayout(ReaderActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(14), dp(16), dp(14));
                row.setBackgroundColor(Color.WHITE);

                title = Ui.text(ReaderActivity.this, "", 14, Ui.SUB);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                words = Ui.text(ReaderActivity.this, "", 10.5f, 0xFFB0B0B0);
                words.setPadding(dp(8), 0, 0, 0);
                row.addView(words);
                row.setTag(new TextView[]{title, words});
            } else {
                row = (LinearLayout) convertView;
                TextView[] refs = (TextView[]) row.getTag();
                title = refs[0];
                words = refs[1];
            }
            final int index = visible.get(position);
            Map<String, Object> chapter = chapters.get(index);
            title.setText(String.valueOf(chapter.get("title")));
            if (index == chapterIndex) {
                title.setTextColor(Ui.INK);
                title.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                title.setTextColor(Ui.SUB);
                title.setTypeface(Typeface.DEFAULT);
            }
            int level = chapter.get("level") instanceof Number
                    ? ((Number) chapter.get("level")).intValue() : 0;
            title.setPadding(dp(level > 1 ? 14 : 0), 0, 0, 0);
            int count = chapter.get("wordCount") instanceof Number
                    ? ((Number) chapter.get("wordCount")).intValue() : 0;
            words.setText(count > 0 ? String.valueOf(count) : "");

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideToolbar();
                    if (index != chapterIndex) loadChapter(index, false, 0);
                }
            });
            return row;
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
        Toast.makeText(this, added ? "已添加书签 · 在 目录 › 书签 里查看" : "已取消书签",
                Toast.LENGTH_SHORT).show();
        if (bookmarkMode) refreshBookmarkList();
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
        if (tocTab != null) tocTab.setTextColor(bm ? Ui.LIGHT : Ui.INK);
        if (bookmarkTab != null) bookmarkTab.setTextColor(bm ? Ui.INK : Ui.LIGHT);
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
            syncChrome();
        }
    }

    /**
     * 左上角那颗按钮按退栈顺序干活：
     * 开着抽屉/面板先收掉，只剩底栏时就是「回书架」，都没开时才是展开菜单。
     */
    private void backOrMenu() {
        if (tocPanel.getVisibility() == View.VISIBLE) {
            showToc(false);
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
        displayPanel.setVisibility(View.GONE);
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
        if (wholeBook && p != null && p.size() > 0) {
            progressPercent.setText(p.percent(chapterIndex, page, total) + "%");
            progressPages.setText(p.pageNumber(chapterIndex, page, total) + " / "
                    + p.totalPages(chapterIndex, total));
        } else {
            progressPercent.setText((len == 0 ? 0 : at * 100 / Math.max(1, len)) + "%");
            progressPages.setText((page + 1) + " / " + total);
        }
        progressChapter.setText((chapterIndex + 1) + " / " + chapters.size());
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
                || progressPanel.getVisibility() == View.VISIBLE
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
            author = "本地导入";
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
        header.setText(bookTitle + " · " + chapterTitle);
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
                                autoSkips = 0;
                                hideOverlay();
                                pageView.setText(body, startAtEnd, offset);
                                store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
                                if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
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
                    String text;
                    try {
                        text = api.chapterText(bookId, uid, c);
                    } catch (Exception first) {
                        c = api.context(bookId, uid, true);
                        text = api.chapterText(bookId, uid, c);
                    }
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
                    final Api.Context context = c;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != loadToken) return;
                            ctx = context;
                            currentText = body;
                            autoSkips = 0;
                            hideOverlay();
                            pageView.setText(body, startAtEnd, offset);
                            store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
                            if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
                            report();
                        }
                    });
                } catch (final Exception e) {
                    if (token == loadToken) fail(e);
                }
            }
        }).start();
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

    // ---------------- 进度 ----------------

    private void report() {
        if (LocalBooks.isLocal(bookId)) return;   // 本地书没有云端进度
        if (demo || chapters.isEmpty() || ctx == null || currentText.length() == 0) return;
        final String uid = String.valueOf(chapters.get(chapterIndex).get("uid"));
        final int offset = pageView.currentOffset();
        final int progress = overallProgress(offset);
        final long now = System.currentTimeMillis();
        final int elapsed = (int) Math.max(0L, (now - sinceReport) / 1000L);
        sinceReport = now;
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
        report();
        if (!chapters.isEmpty()) {
            store.setLastPos(bookId, chapterIndex, pageView.currentOffset());
        }
        super.onPause();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
