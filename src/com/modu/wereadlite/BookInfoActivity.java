package com.modu.wereadlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * 书籍详情（对应息屏页稿）：大封面、推荐值与三档占比、阅读/可读/字数、简介。
 * 左滑或点「开始阅读」进阅读页；不在书架时提供「加入书架」。
 */
public class BookInfoActivity extends Activity {

    private Store store;
    private Api api;
    private final Handler ui = new Handler();

    private String bookId = "";
    private String title = "";
    private String author = "";
    private String cover = "";
    private boolean inShelf;

    private ImageView coverImage;
    private TextView coverMark;
    private TextView titleView;
    private TextView authorView;
    private TextView statusView;
    private TextView primary;
    private TextView secondary;
    private TextView ratingValue;
    private TextView ratingTitle;
    private TextView ratingCount;
    private TextView introView;
    private LinearLayout statRead;
    private LinearLayout statFree;
    private LinearLayout statTime;
    private LinearLayout statWords;
    private LinearLayout bars;
    private LinearLayout chips;
    private LinearLayout body;
    private float downX;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        api = new Api(store);
        Covers.init(this, api.compat());
        Intent got = getIntent();
        bookId = nz(got.getStringExtra("bookId"));
        title = nz(got.getStringExtra("title"));
        author = nz(got.getStringExtra("author"));
        cover = nz(got.getStringExtra("cover"));
        inShelf = got.getBooleanExtra("inShelf", false);
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bind();
        loadRemote();
    }

    // ---------------- 布局 ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(12), dp(2));

        FrameLayout back = new FrameLayout(this);
        NavIcon arrow = new NavIcon(this, NavIcon.BACK);
        arrow.setInk(Ui.INK);
        back.addView(arrow, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        back.setPadding(dp(10), dp(8), dp(10), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        TextView head = Ui.text(this, "书籍详情", 15, Ui.INK);
        bar.addView(head, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView search = Ui.text(this, "搜索", 13.5f, Ui.SUB);
        search.setPadding(dp(14), dp(8), dp(10), dp(8));
        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(BookInfoActivity.this, SearchActivity.class)
                        .putExtra("keyword", title));
            }
        });
        bar.addView(search);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                } else if (action == MotionEvent.ACTION_UP) {
                    // 左滑开始阅读：只在明显横向拖动时触发，不影响点击
                    if (downX - e.getRawX() > dp(60f)) open();
                }
                return false;
            }
        });

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(10), dp(22), dp(10));
        statusView = Ui.text(this, "正在读取详情…", 11.5f, Ui.LIGHT);
        statusView.setGravity(Gravity.CENTER);
        statusView.setLineSpacing(dp(4), 1f);
        statusView.setPadding(0, dp(12), 0, 0);
        body.addView(buildCover());
        body.addView(buildRating());
        body.addView(buildChips());
        body.addView(buildStats());
        body.addView(buildIntro());
        body.addView(buildButtons());
        body.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        TextView foot = Ui.text(this, "← 左滑开始阅读", 11.5f, Ui.LIGHT);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(12), 0, dp(14));
        root.addView(foot);
        return root;
    }

    private View buildCover() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int w = Math.min((int) (width * 0.56f), dp(280));
        int h = w * 4 / 3;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, 2));

        coverImage = new ImageView(this);
        coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(coverImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        coverMark = Ui.text(this, title, 16, 0xFF8F8F8F);
        coverMark.setGravity(Gravity.CENTER);
        coverMark.setMaxLines(4);
        coverMark.setEllipsize(TextUtils.TruncateAt.END);
        coverMark.setLineSpacing(dp(6), 1f);
        coverMark.setPadding(dp(18), dp(18), dp(18), dp(18));
        frame.addView(coverMark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(8);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(frame, lp);

        titleView = Ui.text(this, title, 17, Ui.INK);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setLineSpacing(dp(4), 1f);
        titleView.setPadding(0, dp(18), 0, 0);
        box.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        authorView = Ui.text(this, author.length() == 0 ? " " : author, 12.5f, Ui.SUB);
        authorView.setGravity(Gravity.CENTER);
        authorView.setPadding(0, dp(7), 0, 0);
        box.addView(authorView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View buildRating() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(24), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView head = Ui.text(this, "微信读书推荐值", 12.5f, Ui.SUB);
        left.addView(head);
        ratingValue = Ui.text(this, "—", 21, Ui.INK);
        ratingValue.setTypeface(Typeface.DEFAULT_BOLD);
        ratingValue.setPadding(0, dp(2), 0, 0);
        left.addView(ratingValue);
        ratingTitle = Ui.text(this, " ", 15, Ui.INK);
        ratingTitle.setPadding(0, dp(8), 0, 0);
        left.addView(ratingTitle);
        ratingCount = Ui.text(this, " ", 11, Ui.LIGHT);
        ratingCount.setPadding(0, dp(3), 0, 0);
        left.addView(ratingCount);

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(132),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(left, llp);

        bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.VERTICAL);
        bars.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(bars, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View buildChips() {
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        chips.setVisibility(View.GONE);
        return wrap(chips, lp);
    }

    private View buildStats() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(20), 0, 0);
        statRead = statCell("阅读");
        statFree = statCell("可读");
        statTime = statCell("已读");
        statWords = statCell("字数");
        addCell(row, statRead);
        row.addView(divider());
        addCell(row, statFree);
        row.addView(divider());
        addCell(row, statTime);
        row.addView(divider());
        addCell(row, statWords);
        return row;
    }

    private LinearLayout statCell(String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView l = Ui.text(this, label, 11, Ui.LIGHT);
        l.setGravity(Gravity.CENTER);
        TextView v = Ui.text(this, "—", 14.5f, Ui.INK);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(5), 0, 0);
        cell.addView(l);
        cell.addView(v);
        cell.setTag(v);
        return cell;
    }

    private void addCell(LinearLayout row, LinearLayout cell) {
        row.addView(cell, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(30)));
        return v;
    }

    private View buildIntro() {
        introView = Ui.text(this, "", 12.5f, Ui.SUB);
        introView.setLineSpacing(dp(6), 1f);
        introView.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        return wrap(introView, lp);
    }

    private View buildButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(24), 0, dp(6));

        primary = Ui.text(this, "开始阅读", 15, Color.WHITE);
        primary.setGravity(Gravity.CENTER);
        primary.setTypeface(Typeface.DEFAULT_BOLD);
        primary.setBackgroundDrawable(Ui.box(Ui.INK, Ui.INK, 1, dp(21)));
        primary.setPadding(dp(10), dp(13), dp(10), dp(13));
        primary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open();
            }
        });
        row.addView(primary, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        secondary = Ui.text(this, "加入书架", 15, Ui.INK);
        secondary.setGravity(Gravity.CENTER);
        secondary.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(21)));
        secondary.setPadding(dp(10), dp(13), dp(10), dp(13));
        secondary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                add();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(12);
        row.addView(secondary, lp);
        return row;
    }

    private View wrap(View child, LinearLayout.LayoutParams lp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setLayoutParams(lp);
        return box;
    }

    // ---------------- 数据 ----------------

    private void bind() {
        if (cover.length() > 0) {
            final int size = Math.min((int) (getResources().getDisplayMetrics().widthPixels
                    * 0.56f), dp(280));
            Covers.load("big-" + bookId, cover, size, size * 4 / 3, new Covers.Callback() {
                @Override
                public void onCover(String key, android.graphics.Bitmap bitmap) {
                    if (!key.equals("big-" + bookId)) return;
                    coverImage.setImageBitmap(bitmap);
                    coverMark.setVisibility(View.GONE);
                }
            });
        }
        applyShelfState();
    }

    private void applyShelfState() {
        secondary.setText(inShelf ? "已在书架" : "加入书架");
        secondary.setEnabled(!inShelf);
    }

    private void loadRemote() {
        if (bookId.length() == 0 || bookId.startsWith("local:")) {
            statusView.setText("");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> detail;
                try {
                    detail = api.bookDetail(bookId, title);
                } catch (Exception e) {
                    detail = null;
                }
                final Map<String, Object> row = detail;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (row == null) {
                            statusView.setText("详情拉取失败，可直接开始阅读");
                            return;
                        }
                        fill(row);
                        statusView.setText("");
                    }
                });
            }
        }).start();
    }

    private void fill(Map<String, Object> row) {
        String t = BookRow.str(row.get("title"));
        if (t.length() > 0) {
            title = t;
            titleView.setText(title);
            coverMark.setText(title);
        }
        String a = BookRow.str(row.get("author"));
        if (a.length() > 0) {
            author = a;
            authorView.setText(author);
        }
        String c = BookRow.str(row.get("cover"));
        if (c.length() > 0 && cover.length() == 0) {
            cover = c;
            bind();
        }

        int rating = BookRow.num(row.get("rating"));
        if (rating > 0) {
            ratingValue.setText(BookRow.percent(rating) + "%");
            String name = BookRow.str(row.get("ratingTitle"));
            ratingTitle.setText(name.length() == 0 ? " " : name);
        }
        int count = BookRow.num(row.get("ratingCount"));
        if (count > 0) {
            ratingCount.setText(BookRow.wan(count) + "人点评");
        }
        buildBars(row);

        int read = BookRow.num(row.get("readCount"));
        setValue(statRead, read > 0 ? BookRow.wan(read) : "—");
        setValue(statFree, readable(BookRow.num(row.get("payType"))));
        // 已读时长只有 E-Ink 接口给，取不到就留白，不编一个数
        String spent = Ui.duration(store.readTime(bookId));
        setValue(statTime, spent.length() == 0 ? "—" : spent);
        setValue(statWords, BookRow.words(BookRow.num(row.get("wordCount"))));

        String intro = BookRow.str(row.get("intro"));
        String publisher = BookRow.str(row.get("publisher"));
        if (intro.length() > 0) {
            introView.setText(publisher.length() > 0 ? intro + "\n\n" + publisher + " 出版" : intro);
            introView.setVisibility(View.VISIBLE);
        }
    }

    private void setValue(LinearLayout cell, String text) {
        ((TextView) cell.getTag()).setText(text);
    }

    private static String readable(int payType) {
        if (payType == 0) return "免费";
        if (payType == 1) return "需购买";
        return "会员卡";
    }

    /** 推荐 / 一般 / 不行 三根占比条。 */
    private void buildBars(Map<String, Object> row) {
        bars.removeAllViews();
        int good = BookRow.num(row.get("good"));
        int mixed = BookRow.num(row.get("mixed"));
        int poor = BookRow.num(row.get("poor"));
        if (good + mixed + poor <= 0) return;
        bars.addView(barRow("推荐", good, good + mixed + poor));
        bars.addView(barRow("一般", mixed, good + mixed + poor));
        bars.addView(barRow("不行", poor, good + mixed + poor));

        chips.setVisibility(View.VISIBLE);
        chips.removeAllViews();
        chips.addView(chip("推荐 " + BookRow.wan(good)));
        chips.addView(chip("一般 " + BookRow.wan(mixed)));
        chips.addView(chip("不行 " + BookRow.wan(poor)));
    }

    private View barRow(String label, int value, int total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView l = Ui.text(this, label, 10.5f, Ui.LIGHT);
        row.addView(l, new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundDrawable(Ui.box(0xFFE2E2E2, 0, 0, 1));
        int pct = total <= 0 ? 0 : (int) (value * 100.0 / total + 0.5);
        if (pct > 0) {
            View fill = new View(this);
            fill.setBackgroundColor(Ui.INK);
            track.addView(fill, new LinearLayout.LayoutParams(0, dp(4), pct));
        }
        View rest = new View(this);
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(4), Math.max(1, 100 - pct)));
        row.addView(track, new LinearLayout.LayoutParams(0, dp(4), 1f));
        return row;
    }

    private View chip(String label) {
        TextView t = Ui.text(this, label, 12.5f, Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(17)));
        t.setPadding(dp(6), dp(9), dp(6), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------- 动作 ----------------

    private void open() {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("bookId", bookId);
        intent.putExtra("title", title);
        intent.putExtra("author", author);
        intent.putExtra("cover", cover);
        startActivity(intent);
        finish();
    }

    private void add() {
        if (inShelf || bookId.length() == 0 || bookId.startsWith("local:")) return;
        statusView.setText("正在加入书架…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    api.addToShelf(bookId);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fail = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fail == null) {
                            inShelf = true;
                            applyShelfState();
                            statusView.setText("已加入书架");
                        } else {
                            statusView.setText("加入失败：" + fail);
                        }
                    }
                });
            }
        }).start();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
