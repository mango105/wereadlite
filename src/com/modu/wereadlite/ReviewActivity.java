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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 书友想法（只读）：把云端其他读者的想法同步下来看。
 * 想法数据只有 E-Ink 会话能取到，没授权时给出授权入口；不做本地划线和写想法。
 */
public class ReviewActivity extends Activity {

    private Store store;
    private Api api;
    private final Handler ui = new Handler();

    private String bookId = "";
    private String chapterUid = "";
    private String chapterTitle = "";
    private String mark = "";

    private ScrollView scroll;
    private LinearLayout list;
    private TextView status;
    private TextView pageBtn;
    private final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private int maxIdx;
    private boolean loading;
    private boolean exhausted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        api = new Api(store);
        Intent got = getIntent();
        bookId = nz(got.getStringExtra("bookId"));
        chapterUid = nz(got.getStringExtra("chapterUid"));
        chapterTitle = nz(got.getStringExtra("chapterTitle"));
        mark = nz(got.getStringExtra("mark"));
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (mark.length() > 0) list.addView(markCard(mark));
        load();
    }

    // ---------------- 布局 ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(12), dp(4));

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

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(6), 0, 0, 0);
        TextView t = Ui.text(this, "书友想法", 15.5f, Ui.INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(t);
        TextView sub = Ui.text(this, chapterTitle, 10.5f, Ui.LIGHT);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(3), 0, 0);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = Ui.text(this, "刷新", 13.5f, Ui.SUB);
        refresh.setPadding(dp(14), dp(8), dp(6), dp(8));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                maxIdx = 0;
                exhausted = false;
                clearRows();
                load();
            }
        });
        bar.addView(refresh);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(8));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        status = Ui.text(this, "正在加载…", 12.5f, Ui.LIGHT);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(5), 1f);
        status.setPadding(0, dp(18), 0, dp(10));
        box.addView(status);

        pageBtn = Ui.text(this, "下一页", 13, Ui.INK);
        pageBtn.setGravity(Gravity.CENTER);
        pageBtn.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(16)));
        pageBtn.setPadding(dp(24), dp(8), dp(24), dp(8));
        pageBtn.setVisibility(View.GONE);
        pageBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!loading) load();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(8);
        box.addView(pageBtn, plp);

        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildBottom(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return root;
    }

    private View buildBottom() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);

        View top = new View(this);
        top.setBackgroundColor(Ui.LINE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        TextView tip = Ui.text(this, "想法来自云端，只读", 11, Ui.LIGHT);
        bar.addView(tip, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(scrollButton(NavIcon.UP, -1));
        bar.addView(scrollButton(NavIcon.DOWN, 1));
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return wrap;
    }

    private View scrollButton(int kind, final int dir) {
        FrameLayout btn = new FrameLayout(this);
        btn.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(4)));
        NavIcon icon = new NavIcon(this, kind);
        icon.setInk(Ui.INK);
        btn.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        btn.setPadding(dp(18), dp(10), dp(18), dp(10));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nudge(dir);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = dp(6);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ---------------- 卡片 ----------------

    /** 顶部那张：本页对应的原文片段。全宽展示，长按复制。 */
    private View markCard(String text) {
        LinearLayout card = card();
        TextView body = Ui.text(this, text, 13.5f, Ui.INK);
        body.setTypeface(Typeface.DEFAULT_BOLD);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(dp(5), 1f);
        card.addView(body);

        TextView tag = Ui.text(this, "本章原文 · 长按复制", 10.5f, Ui.LIGHT);
        tag.setPadding(0, dp(8), 0, 0);
        card.addView(tag);

        body.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                TextView tv = (TextView) v;
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setText(tv.getText());
                Toast.makeText(ReviewActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        return card;
    }

    private View thoughtCard(final Map<String, Object> row) {
        LinearLayout card = card();

        String name = BookRow.str(row.get("author"));
        TextView who = Ui.text(this, name.length() == 0 ? "书友" : name, 11.5f, Ui.LIGHT);
        who.setMaxLines(1);
        who.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(who);

        String markText = BookRow.str(row.get("mark"));
        if (markText.length() > 0) {
            TextView quote = Ui.text(this, markText, 11.5f, Ui.SUB);
            quote.setMaxLines(3);
            quote.setEllipsize(TextUtils.TruncateAt.END);
            quote.setLineSpacing(dp(4), 1f);
            quote.setPadding(0, dp(8), 0, 0);
            card.addView(quote);
        }

        String content = BookRow.str(row.get("content"));
        TextView body = Ui.text(this, content, 13.5f, Ui.INK);
        body.setLineSpacing(dp(5), 1f);
        body.setPadding(0, dp(9), 0, 0);
        card.addView(body);

        View line = new View(this);
        line.setBackgroundColor(0xFFEDEDED);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lineLp.topMargin = dp(10);
        card.addView(line, lineLp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(9), 0, dp(2));

        int like = BookRow.num(row.get("like"));
        int comment = BookRow.num(row.get("comment"));
        foot.addView(counter(NavIcon.HEART, like));
        View spacer = new View(this);
        foot.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        final String reviewId = BookRow.str(row.get("reviewId"));
        if (comment > 0 && reviewId.length() > 0) {
            TextView more = Ui.text(this, "看评论 ›", 11.5f, Ui.SUB);
            more.setPadding(dp(10), 0, 0, 0);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showComments(reviewId, row);
                }
            });
            foot.addView(more);
        }
        View ctail = counter(NavIcon.CHAT, comment);
        foot.addView(ctail);
        card.addView(foot);
        return card;
    }

    /** 点「看评论」：拉 /web/review/single 的评论列表弹窗展示。 */
    private void showComments(final String reviewId, final Map<String, Object> row) {
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("评论")
                .setView(loadingView())
                .setPositiveButton("关闭", null)
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> comments;
                try {
                    comments = api.reviewDetail(reviewId);
                } catch (Exception e) {
                    comments = new ArrayList<Map<String, Object>>();
                }
                final List<Map<String, Object>> found = comments;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || !dlg.isShowing()) return;
                        android.widget.ScrollView sc = new android.widget.ScrollView(
                                ReviewActivity.this);
                        LinearLayout box = new LinearLayout(ReviewActivity.this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        box.setPadding(dp(6), dp(4), dp(6), dp(4));
                        if (found.isEmpty()) {
                            box.addView(Ui.text(ReviewActivity.this,
                                    "评论拉取失败或没有权限", 12f, Ui.LIGHT));
                        }
                        for (Map<String, Object> c : found) {
                            LinearLayout one = new LinearLayout(ReviewActivity.this);
                            one.setOrientation(LinearLayout.VERTICAL);
                            one.setPadding(0, dp(8), 0, dp(8));
                            TextView who = Ui.text(ReviewActivity.this,
                                    BookRow.str(c.get("author")), 11.5f, Ui.LIGHT);
                            one.addView(who);
                            TextView body = Ui.text(ReviewActivity.this,
                                    BookRow.str(c.get("content")), 13f, Ui.INK);
                            body.setPadding(0, dp(3), 0, 0);
                            one.addView(body);
                            box.addView(one);
                        }
                        sc.addView(box);
                        dlg.setView(sc);
                    }
                });
            }
        }).start();
    }

    private View loadingView() {
        TextView t = Ui.text(this, "正在加载评论…", 12.5f, Ui.LIGHT);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        return t;
    }

    private View counter(int kind, int value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        NavIcon icon = new NavIcon(this, kind);
        icon.setInk(Ui.SUB);
        box.addView(icon, new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView n = Ui.text(this, value > 0 ? String.valueOf(value) : "", 11.5f, Ui.SUB);
        n.setPadding(dp(6), 0, 0, 0);
        box.addView(n);
        return box;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(9)));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    // ---------------- 数据 ----------------

    private void clearRows() {
        list.removeAllViews();
        rows.clear();
        if (mark.length() > 0) list.addView(markCard(mark));
    }

    private void load() {
        if (loading) return;
        final Eink.Cred cred = Eink.load(store);
        if (!cred.ok()) {
            showAuth();
            return;
        }
        loading = true;
        status.setText("正在加载…");
        pageBtn.setVisibility(View.GONE);
        final int from = maxIdx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> page;
                String err = null;
                try {
                    page = Eink.reviews(store, cred, bookId, chapterUid, from);
                    if (page.isEmpty()) {
                        // 网页会话偶尔能取到，兜一次底
                        page = api.reviews(bookId, chapterUid, from);
                    }
                } catch (Exception e) {
                    try {
                        page = api.reviews(bookId, chapterUid, from);
                    } catch (Exception second) {
                        page = new ArrayList<Map<String, Object>>();
                        err = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                final List<Map<String, Object>> found = page;
                final String fail = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        for (Map<String, Object> row : found) {
                            rows.add(row);
                            list.addView(thoughtCard(row));
                        }
                        if (!found.isEmpty()) maxIdx += found.size();
                        if (found.isEmpty() && rows.isEmpty()) {
                            status.setText(fail == null
                                    ? "这一章还没有书友想法"
                                    : "想法拉取失败\n" + fail);
                            pageBtn.setVisibility(View.GONE);
                            return;
                        }
                        if (found.isEmpty() || found.size() < 20) exhausted = true;
                        if (exhausted) {
                            status.setText(rows.size() + " 条想法，到底了");
                            pageBtn.setVisibility(View.GONE);
                        } else {
                            status.setText(rows.size() + " 条想法");
                            pageBtn.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }).start();
    }

    private void showAuth() {
        status.setText("书友想法要用「E-Ink 客户端授权」才能取到。\n"
                + "授权只是多扫一次码，同一个微信读书账号。");
        pageBtn.setText("去授权");
        pageBtn.setVisibility(View.VISIBLE);
        pageBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(ReviewActivity.this,
                        EinkLoginActivity.class), REQ_EINK);
            }
        });
    }

    private static final int REQ_EINK = 0x61;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EINK || resultCode != RESULT_OK) return;
        pageBtn.setText("下一页");
        pageBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!loading) load();
            }
        });
        load();
    }

    // ---------------- 按键 ----------------

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
        }
        return super.dispatchKeyEvent(event);
    }

    private void nudge(int dir) {
        int dy = Math.max(dp(120), scroll.getHeight() - dp(60));
        scroll.scrollBy(0, dir * dy);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
