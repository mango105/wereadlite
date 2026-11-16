package com.modu.wereadlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页：顶部搜索框 + 「发现 / 书架 / 我」三个标签，右下角 ^ v 整屏翻动。
 * 书架列数按屏宽自适应（3~5 列），长按或点「选择」进入多选。
 * 全部用纯 framework 控件拼，无 XML、无动画（墨水屏刷新成本高）。
 */
public class MainActivity extends Activity {

    private Store store;
    private Api api;
    private final Handler ui = new Handler();

    private final List<Map<String, Object>> books = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> cloudBooks = new ArrayList<Map<String, Object>>();
    private final List<Cell> cells = new ArrayList<Cell>();
    private final Set<String> selected = new LinkedHashSet<String>();

    private LinearLayout grid;
    private TextView headerTitle;
    private TextView headerSub;
    private TextView headerAction;
    private TextView headerImport;
    private View headerImportWrap;
    private View headerActionWrap;
    private View headerSettings;
    private TextView shelfStats;
    private TextView loadingView;
    private TextView emptyView;

    private View shelfPage;
    private View discoverPage;
    private View mePage;
    private View navBar;
    private View actionBar;
    private NavIcon iconDiscover;
    private NavIcon iconShelf;
    private NavIcon iconMe;
    private TextView labelDiscover;
    private TextView labelShelf;
    private TextView labelMe;
    private LinearLayout meRows;
    private ScrollView shelfScroll;
    private ScrollView discoverScroll;
    private LinearLayout recentRow;
    private LinearLayout guessBox;
    private TextView guessStatus;
    private final List<Map<String, Object>> guessPool = new ArrayList<Map<String, Object>>();
    private int guessPage;
    private boolean guessLoading;
    private boolean guessLoaded;

    private boolean editMode;
    private boolean loading;
    private boolean shelfLoaded;
    private boolean demo;
    /** 未登录的离线模式：只显示本地导入的书，不去拉云端书架。 */
    private boolean offline;
    private String pendingExport;
    private int tab = TAB_SHELF;

    private static final int TAB_DISCOVER = 0;
    private static final int TAB_SHELF = 1;
    private static final int TAB_ME = 2;

    private int cellW;
    private int cellH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        api = new Api(store);
        Covers.init(this, api.compat());
        demo = Demo.enabled(getIntent());
        offline = getIntent() != null && getIntent().getBooleanExtra("offline", false);
        if (!api.loggedIn() && !demo && !offline) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        switchTab(TAB_SHELF);
        if (demo) {
            books.addAll(Demo.books());
            rebuildGrid();
        } else {
            loadShelf(false);
        }
        // 从文件管理器「打开方式」进来时，直接导入这个文件
        android.net.Uri incoming = getIntent() == null ? null : getIntent().getData();
        if (incoming != null) doImport(incoming);
    }

    // ---------------- 布局 ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        root.addView(buildSearchBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        root.addView(hline());

        FrameLayout content = new FrameLayout(this);
        discoverPage = buildDiscoverPage();
        shelfPage = buildShelfPage();
        mePage = buildMePage();
        discoverPage.setVisibility(View.GONE);
        mePage.setVisibility(View.GONE);
        content.addView(discoverPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(shelfPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(mePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(hline());

        FrameLayout bottom = new FrameLayout(this);
        navBar = buildNavBar();
        actionBar = buildActionBar();
        actionBar.setVisibility(View.GONE);
        bottom.addView(navBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bottom.addView(actionBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return root;
    }

    /** 顶部搜索框：点一下进搜索页，墨水屏上不直接放输入法。 */
    private View buildSearchBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(8));

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(18)));
        box.setPadding(dp(14), dp(9), dp(14), dp(9));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch("");
            }
        });

        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        NavIcon icon = new NavIcon(this, NavIcon.SEARCH);
        icon.setInk(Ui.LIGHT);
        hint.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));
        TextView text = Ui.text(this, "搜索书名 / 作者", 13.5f, Ui.LIGHT);
        text.setPadding(dp(9), 0, 0, 0);
        hint.addView(text);
        box.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.LEFT));

        row.addView(box, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View hline() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(18), dp(4), dp(12), dp(4));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        headerTitle = Ui.text(this, "书架", 18, Ui.INK);
        headerSub = Ui.text(this, "已选书籍，点下方操作", 10.5f, Ui.LIGHT);
        headerSub.setPadding(0, dp(3), 0, 0);
        headerSub.setVisibility(View.GONE);
        center.addView(headerTitle);
        center.addView(headerSub);
        bar.addView(center, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        headerImport = Ui.text(this, "导入", 14.5f, Ui.INK);
        headerImportWrap = headerButton(headerImport, new NavIcon(this, NavIcon.PLUS), dp(19),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickBook();
                    }
                });
        bar.addView(headerImportWrap);

        headerAction = Ui.text(this, "选择", 14.5f, Ui.INK);
        CheckMark tick = new CheckMark(this);
        tick.setChecked(true);
        headerActionWrap = headerButton(headerAction, tick, dp(18), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (editMode) {
                    exitEdit();
                } else {
                    enterEdit(null);
                }
            }
        });
        bar.addView(headerActionWrap);

        FrameLayout settings = new FrameLayout(this);
        NavIcon icon = new NavIcon(this, NavIcon.SLIDERS);
        icon.setInk(Ui.INK);
        settings.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        settings.setPadding(dp(12), dp(8), dp(12), dp(8));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        settings.setVisibility(View.GONE);
        bar.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerSettings = settings;
        return bar;
    }

    /**
     * 图标 + 文字合成一个按钮：整块都能点。
     * 以前只给文字挂了监听，左边那个小图标点了没反应，触点也只有十来个 dp，墨水屏上很难瞄得准。
     */
    private View headerButton(TextView label, View icon, int iconSize, View.OnClickListener cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        // 顶到标题栏满高，触点高度就不止那几行字
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (icon instanceof NavIcon) ((NavIcon) icon).setInk(Ui.INK);
        row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        label.setPadding(dp(6), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setOnClickListener(cb);
        return row;
    }

    private View buildShelfPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        shelfScroll = sv;

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(14), dp(14), dp(14), dp(6));

        sv.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        loadingView = Ui.text(this, "正在同步书架…", 14, Ui.LIGHT);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setPadding(0, dp(60), 0, dp(20));

        emptyView = Ui.text(this, "书架是空的\n去手机上的微信读书加几本吧", 13.5f, Ui.LIGHT);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setLineSpacing(dp(5), 1f);
        emptyView.setPadding(0, dp(60), 0, dp(20));
        emptyView.setVisibility(View.GONE);

        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        shelfStats = Ui.text(this, statsText(), 12, Ui.LIGHT);
        shelfStats.setGravity(Gravity.CENTER);
        shelfStats.setPadding(0, dp(8), 0, dp(14));
        shelfStats.setVisibility(View.GONE);
        page.addView(shelfStats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    /** 发现页：最近阅读 + 猜你喜欢（换一批），数据来自书架和热门接口。 */
    private View buildDiscoverPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setVerticalScrollBarEnabled(false);
        discoverScroll = sv;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(16));

        box.addView(section("最近阅读"));
        HorizontalScrollView hv = new HorizontalScrollView(this);
        hv.setHorizontalScrollBarEnabled(false);
        hv.setBackgroundColor(Color.WHITE);
        recentRow = new LinearLayout(this);
        recentRow.setOrientation(LinearLayout.HORIZONTAL);
        recentRow.setPadding(dp(2), 0, dp(2), dp(6));
        hv.addView(recentRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hv);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(14), dp(2), 0);
        TextView t = Ui.text(this, "猜你喜欢", 16, Ui.INK);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView shuffle = Ui.text(this, "换一批", 12.5f, Ui.INK);
        shuffle.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(15)));
        shuffle.setPadding(dp(14), dp(5), dp(14), dp(5));
        shuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shuffleGuess();
            }
        });
        head.addView(shuffle);
        box.addView(head);
        box.addView(sectionLine());

        guessBox = new LinearLayout(this);
        guessBox.setOrientation(LinearLayout.VERTICAL);
        guessBox.setPadding(dp(2), dp(14), dp(2), 0);
        box.addView(guessBox);

        guessStatus = Ui.text(this, "", 12, Ui.LIGHT);
        guessStatus.setGravity(Gravity.CENTER);
        guessStatus.setPadding(0, dp(16), 0, dp(8));
        box.addView(guessStatus);

        sv.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View section(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, label, 16, Ui.INK);
        t.setPadding(dp(2), dp(14), dp(2), dp(8));
        box.addView(t);
        box.addView(sectionLine());
        return box;
    }

    private View sectionLine() {
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        line.setLayoutParams(lp);
        return line;
    }

    /** 最近阅读：读过的排前面，最多 8 本。 */
    private void rebuildRecent() {
        recentRow.removeAllViews();
        if (books.isEmpty()) {
            TextView empty = Ui.text(this, "还没有读过的书", 12.5f, Ui.LIGHT);
            empty.setPadding(dp(4), dp(14), dp(4), dp(18));
            recentRow.addView(empty);
            return;
        }
        List<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> b : books) {
            if (BookRow.num(b.get("progress")) > 0) ordered.add(b);
        }
        for (Map<String, Object> b : books) {
            if (BookRow.num(b.get("progress")) == 0) ordered.add(b);
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        int w = (int) ((width - dp(16) * 2 - dp(9) * 4) / 5.0f);
        int h = w * 4 / 3;
        for (int i = 0; i < ordered.size() && i < 8; i++) {
            Map<String, Object> b = ordered.get(i);
            // 单元格内部是 MATCH_PARENT，不给固定尺寸的话整行会塌成一条文字
            LinearLayout.LayoutParams lp = margin(dp(9), dp(14));
            lp.width = w;
            lp.height = h;
            recentRow.addView(recentCell(b, w, h), lp);
        }
    }

    private View recentCell(Map<String, Object> data, int w, int h) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, 2));
        final String bookId = BookRow.str(data.get("bookId"));
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView mark = Ui.text(this, BookRow.str(data.get("title")), 9.5f, 0xFF9A9A9A);
        mark.setGravity(Gravity.CENTER);
        mark.setMaxLines(4);
        mark.setEllipsize(TextUtils.TruncateAt.END);
        mark.setLineSpacing(dp(3), 1f);
        mark.setPadding(dp(6), dp(6), dp(6), dp(6));
        frame.addView(mark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        String cover = BookRow.str(data.get("cover"));
        if (cover.length() > 0) {
            Covers.load("recent-" + bookId, cover, w, h, new Covers.Callback() {
                @Override
                public void onCover(String key, Bitmap bitmap) {
                    if (!key.equals("recent-" + bookId)) return;
                    image.setImageBitmap(bitmap);
                    mark.setVisibility(View.GONE);
                }
            });
        }
        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBook(findBook(bookId));
            }
        });
        return frame;
    }

    private Map<String, Object> findBook(String bookId) {
        for (Map<String, Object> b : books) {
            if (bookId.equals(BookRow.str(b.get("bookId")))) return b;
        }
        return null;
    }

    private LinearLayout.LayoutParams margin(int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = right;
        lp.bottomMargin = bottom;
        return lp;
    }

    private void shuffleGuess() {
        if (guessPool.isEmpty()) {
            loadGuess(true);
            return;
        }
        guessPage++;
        renderGuess();
    }

    private void loadGuess(final boolean force) {
        if (guessLoading) return;
        if (guessLoaded && !force) {
            renderGuess();
            return;
        }
        guessLoading = true;
        guessStatus.setText("正在取推荐书目…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> found = new ArrayList<Map<String, Object>>();
                String err = null;
                try {
                    found = api.hotBooks();
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final List<Map<String, Object>> hot = found;
                final String fail = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        guessLoading = false;
                        guessLoaded = true;
                        guessPool.clear();
                        guessPool.addAll(hot);
                        if (guessPool.isEmpty()) {
                            guessStatus.setText(fail == null ? "暂时没有推荐书目" : fail);
                            guessBox.removeAllViews();
                            return;
                        }
                        guessStatus.setText("");
                        renderGuess();
                    }
                });
            }
        }).start();
    }

    /** 两列网格，一次 6 本；换一批在热门池里滚动窗口。 */
    private void renderGuess() {
        guessBox.removeAllViews();
        if (guessPool.isEmpty()) return;
        int per = 6;
        int start = (guessPage * per) % guessPool.size();
        for (int r = 0; r < per / 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int i = (start + r * 2 + c) % guessPool.size();
                View cell = new BookRow(this, guessPool.get(i), dp(52), dp(70),
                        guessOpen, guessPress);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c == 1) lp.leftMargin = dp(10);
                lp.bottomMargin = dp(16);
                row.addView(cell, lp);
            }
            guessBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        guessStatus.setText("共 " + guessPool.size() + " 本备选");
    }

    private final BookRow.Open guessOpen = new BookRow.Open() {
        @Override
        public void onOpen(Map<String, Object> book) {
            Intent intent = new Intent(MainActivity.this, BookInfoActivity.class);
            intent.putExtra("bookId", BookRow.str(book.get("bookId")));
            intent.putExtra("title", BookRow.str(book.get("title")));
            intent.putExtra("author", BookRow.str(book.get("author")));
            intent.putExtra("cover", BookRow.str(book.get("cover")));
            intent.putExtra("inShelf", findBook(BookRow.str(book.get("bookId"))) != null);
            startActivity(intent);
        }
    };

    private final BookRow.Press guessPress = new BookRow.Press() {
        @Override
        public void onLongPress(Map<String, Object> book) {
            addGuess(book);
        }
    };

    private void addGuess(Map<String, Object> book) {
        final String id = BookRow.str(book.get("bookId"));
        if (id.length() == 0) return;
        if (findBook(id) != null) {
            toast("《" + BookRow.str(book.get("title")) + "》已经在书架上");
            return;
        }
        toast("正在加入书架…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    api.addToShelf(id);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fail = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fail == null) {
                            toast("已加入书架");
                            loadShelf(true);
                        } else {
                            toast("加入失败：" + fail);
                        }
                    }
                });
            }
        }).start();
    }

    private View buildMePage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(26), dp(20), dp(20));

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackgroundDrawable(Ui.oval(0xFFF1F1F1));
        NavIcon person = new NavIcon(this, NavIcon.ME);
        person.setInk(Ui.SUB);
        avatar.addView(person, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(68), dp(68));
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(avatar, alp);

        TextView name = Ui.text(this, api.loggedIn() ? "微信读书用户" : "本地读者", 16.5f, Ui.INK);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(12), 0, 0);
        box.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = Ui.text(this, "ID " + maskVid(api.vid()), 10.5f, Ui.LIGHT);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, 0);
        box.addView(sub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View gap = new View(this);
        box.addView(gap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        box.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        meRows = new LinearLayout(this);
        meRows.setOrientation(LinearLayout.VERTICAL);
        box.addView(meRows);

        TextView ver = Ui.text(this, Ui.version(this), 10.5f, Ui.LIGHT);
        ver.setGravity(Gravity.CENTER);
        ver.setLineSpacing(dp(3), 1f);
        ver.setPadding(0, dp(30), 0, dp(10));
        box.addView(ver);

        sv.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshMeRows();
        return sv;
    }

    private void refreshMeRows() {
        meRows.removeAllViews();
        meRows.addView(meRow("书架", books.size() + " 本", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadShelf(false);
                switchTab(TAB_SHELF);
            }
        }));
        meRows.addView(meRow("同步书架", syncedText(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (offline && !api.loggedIn()) {
                    toast("还没登录，先点下面的「登录微信读书」");
                    return;
                }
                loadShelf(false);
                toast("正在同步书架…");
            }
        }));
        TextView tip = Ui.text(this,
                "想在别的设备读自己的书？用微信读书 App「传书」到云端，"
                        + "回来点上面「同步书架」即可。"
                        + "本机导入的 TXT / EPUB 只存于本设备。",
                11f, Ui.LIGHT);
        tip.setLineSpacing(dp(4), 1f);
        tip.setPadding(0, dp(8), 0, dp(6));
        meRows.addView(tip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meRows.addView(meRow("导出书签", store.bookmarkCount() + " 条",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        exportBookmarks();
                    }
                }));
        meRows.addView(meRow("导入书签", "合并，不覆盖", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBookmarkImport();
            }
        }));
        if (offline && !api.loggedIn()) {
            meRows.addView(meRow("登录微信读书", "同步云端书架", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, LoginActivity.class));
                    finish();
                }
            }));
        }
        meRows.addView(meRow("云端功能授权", Eink.load(store).ok() ? "已授权" : "未授权",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivityForResult(
                                new Intent(MainActivity.this, EinkLoginActivity.class), REQ_EINK);
                    }
                }));
        meRows.addView(meRow("阅读设置", store.fontSize() + " pt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        }));
        meRows.addView(meRow("设置", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        }));
        meRows.addView(meRow("关于", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(Ui.version(MainActivity.this))
                        .setMessage("协议实现参考 KOReader 微信读书插件与觅阅 MiuRead。\n"
                                + "TLS 强制 1.2，内置 DigiCert Global Root G2。")
                        .setPositiveButton("好", null)
                        .show();
            }
        }));
    }

    private View meRow(String label, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(50));
        row.setOnClickListener(click);

        TextView title = Ui.text(this, label, 15, Ui.INK);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(value)) {
            TextView v = Ui.text(this, value, 12.5f, Ui.LIGHT);
            v.setPadding(dp(8), 0, dp(8), 0);
            row.addView(v);
        }
        TextView arrow = Ui.text(this, "›", 18, 0xFFBBBBBB);
        row.addView(arrow);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0xFFEFEFEF);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    private View buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(12), 0);

        LinearLayout discover = navItem(NavIcon.COMPASS, "发现");
        LinearLayout shelf = navItem(NavIcon.SHELF, "书架");
        LinearLayout me = navItem(NavIcon.ME, "我");
        bar.addView(discover, new LinearLayout.LayoutParams(dp(74),
                ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(shelf, new LinearLayout.LayoutParams(dp(74),
                ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(me, new LinearLayout.LayoutParams(dp(74),
                ViewGroup.LayoutParams.MATCH_PARENT));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(buildScrollPair());

        discover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_DISCOVER);
            }
        });
        shelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_SHELF);
            }
        });
        me.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_ME);
            }
        });
        return bar;
    }

    /** 右下角 ^ v：整屏翻动当前标签页。 */
    private View buildScrollPair() {
        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER_VERTICAL);
        pair.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(18)));

        pair.addView(scrollButton(NavIcon.UP, -1));
        View divider = new View(this);
        divider.setBackgroundColor(Ui.INK);
        pair.addView(divider, new LinearLayout.LayoutParams(1, dp(26)));
        pair.addView(scrollButton(NavIcon.DOWN, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pair.setLayoutParams(lp);
        return pair;
    }

    private View scrollButton(int kind, final int dir) {
        FrameLayout btn = new FrameLayout(this);
        NavIcon icon = new NavIcon(this, kind);
        icon.setInk(Ui.INK);
        btn.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        btn.setPadding(dp(20), dp(11), dp(20), dp(11));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nudge(dir);
            }
        });
        return btn;
    }

    /** 墨水屏不做滚动动画，直接按整屏跳；阅读进度那一页由阅读页自己处理。 */
    private void nudge(int dir) {
        ScrollView sv = tab == TAB_SHELF ? shelfScroll : tab == TAB_DISCOVER ? discoverScroll : null;
        if (sv == null) return;
        int dy = Math.max(dp(120), sv.getHeight() - dp(60));
        sv.scrollBy(0, dir * dy);
    }

    private LinearLayout navItem(int kind, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        NavIcon icon = new NavIcon(this, kind);
        TextView text = Ui.text(this, label, 10.5f, Ui.LIGHT);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(2), 0, 0);
        item.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        item.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (kind == NavIcon.COMPASS) {
            iconDiscover = icon;
            labelDiscover = text;
        } else if (kind == NavIcon.SHELF) {
            iconShelf = icon;
            labelShelf = text;
        } else {
            iconMe = icon;
            labelMe = text;
        }
        return item;
    }

    private View buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        bar.addView(actionItem(NavIcon.LOCK, "关闭私密阅读", 0));
        bar.addView(actionItem(NavIcon.DOWNLOAD, "离线完成", 1));
        bar.addView(actionItem(NavIcon.FOLDER, "移动", 2));
        bar.addView(actionItem(NavIcon.TRASH, "移出书架", 3));
        return bar;
    }

    private View actionItem(int kind, String label, final int which) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        NavIcon icon = new NavIcon(this, kind);
        icon.setInk(Ui.SUB);
        TextView text = Ui.text(this, label, 10, Ui.SUB);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(2), 0, 0);
        item.addView(icon, new LinearLayout.LayoutParams(dp(21), dp(21)));
        item.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShelfAction(which);
            }
        });
        item.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return item;
    }

    // ---------------- 标签切换 ----------------

    private void switchTab(int which) {
        tab = which;
        boolean discover = which == TAB_DISCOVER;
        boolean shelf = which == TAB_SHELF;
        boolean me = which == TAB_ME;
        discoverPage.setVisibility(discover ? View.VISIBLE : View.GONE);
        shelfPage.setVisibility(shelf ? View.VISIBLE : View.GONE);
        mePage.setVisibility(me ? View.VISIBLE : View.GONE);
        headerTitle.setText(discover ? "发现" : shelf ? "书架" : "我");
        headerSub.setVisibility(shelf && editMode ? View.VISIBLE : View.GONE);
        if (headerImportWrap != null) headerImportWrap.setVisibility(shelf ? View.VISIBLE : View.GONE);
        if (headerActionWrap != null) headerActionWrap.setVisibility(shelf ? View.VISIBLE : View.GONE);
        if (headerSettings != null) headerSettings.setVisibility(me ? View.VISIBLE : View.GONE);
        paintNav(iconDiscover, labelDiscover, discover);
        paintNav(iconShelf, labelShelf, shelf);
        paintNav(iconMe, labelMe, me);
        if (!shelf && editMode) exitEdit();
        if (me) refreshMeRows();
        if (discover) {
            rebuildRecent();
            loadGuess(false);
        }
    }

    private void paintNav(NavIcon icon, TextView label, boolean on) {
        if (icon == null || label == null) return;
        icon.setInk(on ? Ui.INK : Ui.LIGHT);
        label.setTextColor(on ? Ui.INK : Ui.LIGHT);
    }

    // ---------------- 书架数据 ----------------

    private void loadShelf(final boolean silent) {
        if (loading) return;
        // 未登录的离线模式：只摆本地导入的书，不发网络请求，也不会误弹「登录过期」
        if (offline && !api.loggedIn()) {
            mergeLocal();
            rebuildGrid();
            if (books.isEmpty()) toast("点右上角「导入」读本地 TXT / EPUB");
            return;
        }
        loading = true;
        if (!silent) {
            // 先把本地导入的书摆出来：云端拉不动也不该让用户以为书没了
            mergeLocal();
            if (books.isEmpty()) {
                loadingView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(View.GONE);
                if (grid.getChildCount() == 0) {
                    grid.addView(loadingView, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            } else {
                rebuildGrid();
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 续读位置和阅读时长都在书架这一次返回里，不必再单独发请求
                final Map<String, String> marks = new java.util.LinkedHashMap<String, String>();
                final Map<String, Integer> times = new java.util.LinkedHashMap<String, Integer>();
                try {
                    final List<Map<String, Object>> result = api.shelf(marks, times);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            shelfLoaded = true;
                            store.setSyncedAt(System.currentTimeMillis());
                            applyReadTimes(times);
                            store.setMarks(marks);
                            cloudBooks.clear();
                            cloudBooks.addAll(result);
                            mergeLocal();
                            // 多选态下同步书架只刷新格子，勾了哪些还是用户说了算
                            rebuildGrid();
                            enrichCovers();
                            if (recentRow != null) rebuildRecent();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            String hint = friendly(e);
                            mergeLocal();
                            if (books.isEmpty()) {
                                grid.removeAllViews();
                                emptyView.setText("书架同步失败\n" + hint);
                                emptyView.setVisibility(View.VISIBLE);
                                grid.addView(emptyView, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                            } else {
                                rebuildGrid();
                                if (!isLoginFault(e)) toast(hint);
                            }
                            if (isLoginFault(e)) askLogin();
                        }
                    });
                }
            }
        }).start();
    }

    /** 本地导入的书排在云端书前面。 */
    private void mergeLocal() {
        books.clear();
        books.addAll(LocalBooks.list(this));
        books.addAll(cloudBooks);
    }

    /** 云端读到的每本书累计阅读秒数落到本地，详情页和阅读页都从这里取。 */
    private void applyReadTimes(Map<String, Integer> times) {
        store.setReadTimes(times);
    }

    private String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        String hint;
        if (m.contains("-2010") || m.contains("-2012") || m.contains("登录")) {
            hint = "登录已过期，重新登录一次就好";
        } else if (m.contains("UnknownHost") || m.contains("Unable to resolve")) {
            hint = "网络不可用，请检查 Wi-Fi";
        } else if (m.contains("SSL") || m.contains("handshake") || m.contains("Cert")) {
            hint = "证书校验失败，可到「我」里打开兼容模式";
        } else {
            hint = "同步失败：" + m;
        }
        return hint;
    }

    /**
     * 登录过期时书架接口回的是空的 books，页面只会显示「书架是空的」，
     * 看起来像书被删了。这里直接把用户带回登录页。
     */
    private boolean isLoginFault(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("登录");
    }

    private void askLogin() {
        new AlertDialog.Builder(this)
                .setTitle("登录已过期")
                .setMessage("微信读书的登录状态过期了，书架暂时取不到。\n\n"
                        + "重新扫码登录一次即可，本地导入的书不受影响。")
                .setPositiveButton("去登录", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void rebuildGrid() {
        grid.removeAllViews();
        cells.clear();
        loadingView.setVisibility(View.GONE);

        // 计数放在空书架判断之前，否则删完最后一本时底部还留着旧数字
        if (shelfStats != null) {
            shelfStats.setText(statsText());
            shelfStats.setVisibility(View.VISIBLE);
        }

        if (books.isEmpty()) {
            emptyView.setText("书架是空的\n去手机上的微信读书加几本吧");
            emptyView.setVisibility(View.VISIBLE);
            grid.addView(emptyView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        emptyView.setVisibility(View.GONE);

        int width = getResources().getDisplayMetrics().widthPixels;
        int pad = dp(14);
        int gap = dp(9);
        // 目标每格约 104dp 宽：小屏 3 列，6 寸以上 4~5 列
        int cols = (width - pad * 2 + gap) / (dp(104) + gap);
        if (cols < 3) cols = 3;
        if (cols > 5) cols = 5;
        cellW = (width - pad * 2 - gap * (cols - 1)) / cols;
        cellH = cellW * 4 / 3;

        for (int i = 0; i < books.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                int index = i + j;
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (j < cols - 1) lp.rightMargin = gap;
                if (index < books.size()) {
                    Cell cell = new Cell(books.get(index));
                    row.addView(cell, lp);
                    cells.add(cell);
                } else {
                    View stub = new View(this);
                    row.addView(stub, lp);
                }
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(18);
            grid.addView(row, rowLp);
        }
    }

    private String statsText() {
        int secret = 0;
        for (Map<String, Object> b : books) {
            if (num(b.get("secret")) != 0) secret++;
        }
        int open = books.size() - secret;
        if (secret == 0) return open + " 本公开阅读";
        if (open == 0) return secret + " 本私密阅读";
        return open + " 本公开阅读 · " + secret + " 本私密阅读";
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    /** 书架接口不一定带封面，缺失时按 bookId 补一次。 */
    private void enrichCovers() {
        final List<Cell> missing = new ArrayList<Cell>();
        for (Cell c : cells) {
            if (c.coverUrl.length() == 0) missing.add(c);
        }
        if (missing.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (final Cell c : missing) {
                    final String url = api.cover(c.bookId);
                    if (url.length() == 0) continue;
                    c.coverUrl = url;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            c.loadCover();
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------- 编辑态 ----------------

    /** 进入多选：点「选择」默认一个都不勾，长按某本则只勾那一本。 */
    private void enterEdit(String preselect) {
        if (editMode) return;
        editMode = true;
        selected.clear();
        if (preselect != null) selected.add(preselect);
        headerSub.setVisibility(View.VISIBLE);
        headerAction.setText("取消");
        navBar.setVisibility(View.GONE);
        actionBar.setVisibility(View.VISIBLE);
        for (Cell c : cells) c.applySelection();
    }

    private void exitEdit() {
        if (!editMode) return;
        editMode = false;
        selected.clear();
        headerSub.setVisibility(View.GONE);
        headerAction.setText("选择");
        navBar.setVisibility(tab == TAB_SHELF ? View.VISIBLE : View.GONE);
        actionBar.setVisibility(View.GONE);
        for (Cell c : cells) c.applySelection();
    }

    private void toggleSelect(Cell cell) {
        if (selected.contains(cell.bookId)) {
            selected.remove(cell.bookId);
        } else {
            selected.add(cell.bookId);
        }
        cell.applySelection();
    }

    private int selectedPrivate() {
        int n = 0;
        for (Cell c : cells) {
            if (selected.contains(c.bookId) && num(c.book.get("secret")) != 0) n++;
        }
        return n;
    }

    private void onShelfAction(int which) {
        if (selected.isEmpty()) {
            toast("请先选择书籍");
            return;
        }
        switch (which) {
            case 0:
                if (selectedPrivate() == 0) {
                    toast("所选书籍里没有私密阅读的书");
                } else {
                    unsupported("关闭私密阅读");
                }
                break;
            case 1:
                unsupported("离线缓存");
                break;
            case 2:
                unsupported("移动到分组");
                break;
            default:
                removeSelected();
                break;
        }
    }

    // ---------------- 移出书架（E-Ink 真删） ----------------

    private void removeSelected() {
        final List<String> ids = new ArrayList<String>(selected);
        if (ids.isEmpty()) return;
        final boolean hasCloud = anyCloud(ids);
        final Eink.Cred cred = Eink.load(store);
        if (hasCloud && !cred.ok()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要一次云端授权")
                    .setMessage("从账号书架移除书籍、查看书友想法走的是 E-Ink 客户端接口，"
                            + "要用同一个微信账号再扫一次码。\n\n"
                            + "只需扫这一次，之后令牌会自动续期，不用反复授权。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            startActivityForResult(
                                    new Intent(MainActivity.this, EinkLoginActivity.class), REQ_EINK);
                        }
                    })
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移出书架")
                .setMessage(hasCloud
                        ? ("将从账号书架移除 " + ids.size() + " 本书，同步后手机端也会消失。\n"
                        + "需要恢复时，可在微信读书 App 里重新加入。")
                        : ("将从本机删除 " + ids.size() + " 本导入的书（文件一并删除，无法恢复）。"))
                .setNegativeButton("取消", null)
                .setPositiveButton("移出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doRemove(ids);
                    }
                })
                .show();
    }

    private boolean anyCloud(List<String> ids) {
        for (String id : ids) if (!LocalBooks.isLocal(id)) return true;
        return false;
    }

    private void doRemove(final List<String> ids) {
        toast("正在移出 " + ids.size() + " 本…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Eink.Cred cred = Eink.load(store);
                int done = 0;
                String err = "";
                for (String id : ids) {
                    try {
                        if (LocalBooks.isLocal(id)) {
                            LocalBooks.delete(MainActivity.this, id);
                        } else {
                            Eink.removeFromShelf(store, cred, id);
                        }
                        done++;
                    } catch (Exception e) {
                        err = e.getMessage() == null ? "" : e.getMessage();
                        Trace.i("remove failed " + id + " " + err);
                    }
                }
                final int okCount = done;
                final String failMsg = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        exitEdit();
                        loadShelf(true);
                        String msg = "已移出 " + okCount + " 本";
                        if (failMsg.length() > 0) msg = msg + "，失败原因：" + failMsg;
                        toast(msg);
                    }
                });
            }
        }).start();
    }

    private void unsupported(String what) {
        new AlertDialog.Builder(this)
                .setTitle(what + "：暂不可用")
                .setMessage("微信读书 Web 接口不开放书架管理（官方说明：web 版暂不支持管理书架和设置私密阅读）。\n\n"
                        + "要让这几个按钮真正生效，需要再接一次「E-Ink 书架授权」登录（会再扫一次码）。"
                        + "当前请先在手机 App 里操作。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openSearch(String keyword) {
        startActivity(new Intent(this, SearchActivity.class).putExtra("keyword", keyword));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                nudge(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_DPAD_UP) {
                nudge(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_SEARCH) {
                openSearch("");
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------- 工具 ----------------

    private void openBook(Map<String, Object> book) {
        if (book == null) return;
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("bookId", String.valueOf(book.get("bookId")));
        intent.putExtra("title", String.valueOf(book.get("title")));
        Object cover = book.get("cover");
        if (cover != null) intent.putExtra("cover", String.valueOf(cover));
        Object author = book.get("author");
        if (author != null) intent.putExtra("author", String.valueOf(author));
        if (demo) intent.putExtra("demo", true);
        startActivity(intent);
    }

    private static final int REQ_EINK = 0x51;
    private static final int REQ_IMPORT = 0x52;
    private static final int REQ_PICK = 0x53;
    private static final int REQ_EXPORT_BM = 0x54;
    private static final int REQ_IMPORT_BM = 0x55;

    /** 选一个 TXT / EPUB 导入书架。用自带的文件浏览器，墨水屏 ROM 多数没装 DocumentsUI。 */
    private void pickBook() {
        startActivityForResult(new Intent(this, LocalPickerActivity.class), REQ_PICK);
    }

    // ---------------- 书签导出 / 导入 ----------------

    /**
     * 跨设备同步书签：导出成一个 JSON 文件，另一台设备导入时按「章节 + 偏移」合并，
     * 不会覆盖本地已有的书签。走系统文件选择器，不需要任何存储权限。
     */
    private void exportBookmarks() {
        final String json = Bookmarks.exportAll(store.allBookmarks());
        if (Bookmarks.parseAll(json).isEmpty()) {
            toast("还没有书签可以导出");
            return;
        }
        // 先落一份到应用专属目录：这个目录任何 Android 版本都能写、不需要权限，
        // 系统没有文件管理器时也能用数据线取走。
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File local = new File(dir, "wereadlite-bookmarks.json");
        boolean ok = writeText(local, json);
        pendingExport = json;
        try {
            Intent i = new Intent("android.intent.action.CREATE_DOCUMENT");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra("android.intent.extra.TITLE", "wereadlite-bookmarks.json");
            startActivityForResult(i, REQ_EXPORT_BM);
            toast("选择保存位置" + (ok ? "；也可数据线取 " + local.getAbsolutePath() : ""));
        } catch (Exception e) {
            pendingExport = null;
            toast(ok ? "系统没有文件管理器，已导出到 " + local.getAbsolutePath()
                    : "系统没有文件管理器，无法导出");
        }
    }

    private void pickBookmarkImport() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选择书签 JSON"), REQ_IMPORT_BM);
        } catch (Exception e) {
            toast("系统没有文件管理器，请把书签文件放到应用专属目录再试");
        }
    }

    private void writeBookmarksTo(android.net.Uri uri) {
        String json = pendingExport;
        pendingExport = null;
        if (json == null || json.length() == 0) json = Bookmarks.exportAll(store.allBookmarks());
        java.io.OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new java.io.IOException("打不开目标文件");
            out.write(json.getBytes("UTF-8"));
            toast("书签已导出");
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        } finally {
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void importBookmarksFrom(final android.net.Uri uri) {
        toast("正在导入书签…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String err = null;
                java.io.InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("打不开文件");
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    text = new String(buf.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    if (in != null) try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
                int added = 0;
                int booksN = 0;
                String fail = err;
                if (text != null) {
                    java.util.Map<String, String> incoming = Bookmarks.parseAll(text);
                    java.util.Map<String, String> merged =
                            new java.util.LinkedHashMap<String, String>();
                    for (java.util.Map.Entry<String, String> e : incoming.entrySet()) {
                        java.util.List<Bookmarks.Item> local =
                                Bookmarks.decode(store.bookmarks(e.getKey()));
                        java.util.List<Bookmarks.Item> all =
                                Bookmarks.merge(local, Bookmarks.decode(e.getValue()));
                        merged.put(e.getKey(), Bookmarks.encode(all));
                        added += all.size() - local.size();
                        booksN++;
                    }
                    if (!merged.isEmpty()) store.setAllBookmarks(merged);
                    fail = booksN == 0 ? "文件里没有读到书签" : null;
                }
                final int addedN = added;
                final int booksCount = booksN;
                final String errMsg = fail;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (errMsg != null) {
                            toast("导入失败：" + errMsg);
                            return;
                        }
                        refreshMeRows();
                        toast("已合并 " + booksCount + " 本书的书签，新增 " + addedN + " 条");
                    }
                });
            }
        }).start();
    }

    private static boolean writeText(File f, String text) {
        java.io.Writer w = null;
        try {
            w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
            w.write(text);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (w != null) try {
                w.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void doImportPath(final String path) {
        final String title = titleOf(new File(path).getName());
        if (localTitleExists(title)) {
            toast("《" + title + "》已经在书架上了");
            return;
        }
        toast("正在导入…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String id = null;
                String err = null;
                try {
                    id = LocalBooks.importPath(MainActivity.this, path);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String bookId = id;
                final String failMsg = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bookId == null) {
                            toast("导入失败：" + failMsg);
                            return;
                        }
                        mergeLocal();
                        rebuildGrid();
                        toast("已导入到书架");
                    }
                });
            }
        }).start();
    }

    /** 批量导入一个文件夹里的全部 TXT / EPUB（含子目录三层），已导入过的同名书跳过。 */
    private void doImportDir(final String dir) {
        final List<File> files = LocalBooks.scanSupported(new File(dir), 3);
        if (files.isEmpty()) {
            toast("这个文件夹里没有 TXT / EPUB");
            return;
        }
        toast("开始导入 " + files.size() + " 本…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int skip = 0;
                int fail = 0;
                String lastErr = null;
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    final String tip = "正在导入 " + (i + 1) + "/" + files.size()
                            + "：" + f.getName();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(tip);
                        }
                    });
                    if (localTitleExists(titleOf(f.getName()))) {
                        skip++;
                        continue;
                    }
                    try {
                        LocalBooks.importPath(MainActivity.this, f.getAbsolutePath());
                        ok++;
                    } catch (Exception e) {
                        fail++;
                        lastErr = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                final int okN = ok;
                final int skipN = skip;
                final int failN = fail;
                final String err = lastErr;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        mergeLocal();
                        rebuildGrid();
                        StringBuilder sb = new StringBuilder("导入完成：成功 " + okN + " 本");
                        if (skipN > 0) sb.append("，已在书架 " + skipN + " 本");
                        if (failN > 0) sb.append("，失败 " + failN + " 本");
                        if (failN > 0 && err != null) sb.append("（").append(err).append("）");
                        toast(sb.toString());
                    }
                });
            }
        }).start();
    }

    private static String titleOf(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        String low = n.toLowerCase();
        if (low.endsWith(".txt")) return n.substring(0, n.length() - 4).trim();
        if (low.endsWith(".epub")) return n.substring(0, n.length() - 5).trim();
        return n;
    }

    private boolean localTitleExists(String title) {
        if (title == null || title.length() == 0) return false;
        for (Map<String, Object> row : LocalBooks.list(this)) {
            Object t = row.get("title");
            if (t != null && title.equalsIgnoreCase(String.valueOf(t).trim())) return true;
        }
        return false;
    }

    private String displayName(android.net.Uri uri) {
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String v = cur.getString(i);
                    if (v != null && v.length() > 0) return v;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String path = uri.getPath();
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }

    private void doImport(final android.net.Uri uri) {
        final String name = displayName(uri);
        toast("正在导入 " + name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String id = null;
                String err = null;
                try {
                    id = LocalBooks.importFile(MainActivity.this, uri, name);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String bookId = id;
                final String failMsg = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bookId == null) {
                            toast("导入失败：" + failMsg);
                            return;
                        }
                        mergeLocal();
                        rebuildGrid();
                        toast("已导入到书架");
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            // 自带选择器里改用系统文件管理器时，回来的是 content:// Uri
            if (data.getData() != null) {
                doImport(data.getData());
                return;
            }
            String dir = data.getStringExtra("dir");
            if (dir != null && dir.length() > 0) {
                doImportDir(dir);
                return;
            }
            String path = data.getStringExtra("path");
            if (path != null && path.length() > 0) doImportPath(path);
            return;
        }
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            doImport(data.getData());
            return;
        }
        if (requestCode == REQ_EXPORT_BM && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            writeBookmarksTo(data.getData());
            return;
        }
        if (requestCode == REQ_IMPORT_BM && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            importBookmarksFrom(data.getData());
            return;
        }
        if (requestCode == REQ_EINK && resultCode == RESULT_OK) {
            toast("书架管理已授权");
            refreshMeRows();
        }
    }

    private String syncedText() {
        long t = store.syncedAt();
        if (t == 0L) return "未同步";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        return "上次 " + f.format(new java.util.Date(t));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String maskVid(String vid) {
        if (vid.length() <= 4) return vid;
        return vid.substring(0, 3) + "****" + vid.substring(vid.length() - 2);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ---------------- 单个书格 ----------------

    private class Cell extends LinearLayout {

        final Map<String, Object> book;
        final String bookId;
        final ImageView image;
        final TextView placeholder;
        final CheckMark check;
        final TextView title;
        String coverUrl;

        Cell(Map<String, Object> data) {
            super(MainActivity.this);
            book = data;
            bookId = String.valueOf(data.get("bookId"));
            coverUrl = data.get("cover") == null ? "" : String.valueOf(data.get("cover"));
            setOrientation(VERTICAL);

            FrameLayout frame = new FrameLayout(MainActivity.this);
            frame.setBackgroundColor(Ui.TILE);

            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            image.setTag(bookId);

            placeholder = Ui.text(MainActivity.this, String.valueOf(data.get("title")), 11, 0xFFB5B5B5);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMaxLines(5);
            placeholder.setLineSpacing(dp(3), 1f);
            placeholder.setPadding(dp(10), dp(10), dp(10), dp(10));
            frame.addView(placeholder, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));

            // 描边单独一层：setForeground 要 API 23，4.2 上没有
            View border = new View(MainActivity.this);
            border.setBackgroundDrawable(Ui.box(0x00000000, Ui.LINE, 1, 2));
            frame.addView(border, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (num(data.get("secret")) != 0) {
                TextView tag = Ui.text(MainActivity.this, "私密", 8.5f, Color.WHITE);
                tag.setBackgroundDrawable(Ui.box(0xE6202020, 0, 0, 2));
                tag.setPadding(dp(4), dp(1), dp(4), dp(1));
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.LEFT | Gravity.BOTTOM);
                tlp.leftMargin = dp(5);
                tlp.bottomMargin = dp(5);
                frame.addView(tag, tlp);
            }

            check = new CheckMark(MainActivity.this);
            check.setVisibility(View.GONE);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.RIGHT | Gravity.BOTTOM);
            clp.rightMargin = dp(5);
            clp.bottomMargin = dp(5);
            frame.addView(check, clp);

            addView(frame, new LinearLayout.LayoutParams(cellW, cellH));

            title = Ui.text(MainActivity.this, String.valueOf(data.get("title")), 12, 0xFF333333);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(dp(3), 1f);
            title.setMinHeight(dp(36));
            LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp2.topMargin = dp(7);
            addView(title, tlp2);

            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (editMode) {
                        toggleSelect(Cell.this);
                    } else {
                        openBook(book);
                    }
                }
            });
            setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!editMode) enterEdit(bookId);
                    return true;
                }
            });

            loadCover();
            applySelection();
        }

        void loadCover() {
            if (coverUrl.length() == 0) {
                image.setImageDrawable(null);
                placeholder.setVisibility(View.VISIBLE);
                return;
            }
            Covers.load(bookId, coverUrl, cellW, cellH, new Covers.Callback() {
                @Override
                public void onCover(String key, Bitmap bitmap) {
                    if (!bookId.equals(key)) return;
                    if (!bookId.equals(image.getTag())) return;
                    image.setImageBitmap(bitmap);
                    placeholder.setVisibility(View.GONE);
                }
            });
        }

        void applySelection() {
            boolean on = editMode;
            check.setVisibility(on ? View.VISIBLE : View.GONE);
            check.setChecked(on && selected.contains(bookId));
        }
    }
}
