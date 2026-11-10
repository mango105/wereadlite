package com.modu.wereadlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索页：输入关键词 -> 结果列表（封面 / 书名 / 作者 / 推荐值）。
 * 空关键词时展示热门书籍，长按结果 = 加入书架，点结果 = 书籍详情。
 */
public class SearchActivity extends Activity {

    private Store store;
    private Api api;
    private final Handler ui = new Handler();

    private EditText input;
    private ScrollView scroll;
    private LinearLayout list;
    private LinearLayout wordsRow;
    private TextView status;
    private TextView more;

    private String keyword = "";
    private int maxIdx;
    private boolean loading;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        api = new Api(store);
        Covers.init(this, api.compat());
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String preset = getIntent().getStringExtra("keyword");
        if (preset != null && preset.trim().length() > 0) {
            input.setText(preset);
            input.setSelection(preset.length());
            run(preset.trim());
        } else {
            loadHot();
        }
    }

    // ---------------- 布局 ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));

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

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("搜索书名 / 作者");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.LIGHT);
        input.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.INK, 1, dp(19)));
        input.setPadding(dp(16), dp(9), dp(16), dp(9));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showWords(s.toString().trim());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard();
                    run(v.getText().toString().trim());
                    return true;
                }
                return false;
            }
        });
        bar.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = Ui.text(this, "搜索", 14, Ui.INK);
        go.setPadding(dp(14), dp(8), dp(6), dp(8));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                run(input.getText().toString().trim());
            }
        });
        bar.addView(go);

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

        wordsRow = new LinearLayout(this);
        wordsRow.setOrientation(LinearLayout.VERTICAL);
        wordsRow.setVisibility(View.GONE);
        box.addView(wordsRow);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(4));
        box.addView(list);

        status = Ui.text(this, "", 12.5f, Ui.LIGHT);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(14), 0, dp(14));
        box.addView(status);

        more = Ui.text(this, "加载更多", 13, Ui.INK);
        more.setGravity(Gravity.CENTER);
        more.setBackgroundDrawable(Ui.box(Color.WHITE, Ui.LINE, 1, dp(16)));
        more.setPadding(dp(22), dp(8), dp(22), dp(8));
        more.setVisibility(View.GONE);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!loading && !finished) fetch(false);
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        mlp.setMargins(0, dp(6), 0, dp(16));
        box.addView(more, mlp);

        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** 输入时的建议词，点一个直接搜。 */
    private void showWords(String text) {
        wordsRow.removeAllViews();
        if (text.length() == 0) {
            wordsRow.setVisibility(View.GONE);
            return;
        }
        final List<String> found = api.suggest(text);
        if (found.isEmpty()) {
            wordsRow.setVisibility(View.GONE);
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(4));
        for (int i = 0; i < found.size() && i < 4; i++) {
            final String word = found.get(i);
            TextView chip = Ui.text(this, word, 11.5f, Ui.SUB);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(dp(120));
            chip.setBackgroundDrawable(Ui.box(Ui.TILE, 0, 0, dp(12)));
            chip.setPadding(dp(11), dp(5), dp(11), dp(5));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideKeyboard();
                    input.setText(word);
                    input.setSelection(word.length());
                    run(word);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
        }
        wordsRow.addView(row);
        wordsRow.setVisibility(View.VISIBLE);
    }

    // ---------------- 数据 ----------------

    private void loadHot() {
        status.setText("正在取热门书籍…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> found = new ArrayList<Map<String, Object>>();
                try {
                    found = api.hotBooks();
                } catch (Exception ignore) {
                }
                final List<Map<String, Object>> hot = found;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        list.removeAllViews();
                        if (hot.isEmpty()) {
                            status.setText("输入书名开始搜索");
                            return;
                        }
                        status.setText("大家在读");
                        addRows(hot);
                    }
                });
            }
        }).start();
    }

    private void run(String text) {
        if (text.length() == 0) {
            keyword = "";
            more.setVisibility(View.GONE);
            loadHot();
            return;
        }
        keyword = text;
        maxIdx = 0;
        finished = false;
        list.removeAllViews();
        wordsRow.setVisibility(View.GONE);
        status.setText("正在搜索…");
        fetch(true);
    }

    private void fetch(final boolean reset) {
        if (loading) return;
        loading = true;
        final int from = maxIdx;
        final String q = keyword;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> page;
                String err = null;
                try {
                    page = api.search(q, from);
                } catch (Exception e) {
                    page = new ArrayList<Map<String, Object>>();
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final List<Map<String, Object>> rows = page;
                final String fail = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (reset) list.removeAllViews();
                        if (!rows.isEmpty()) {
                            maxIdx = from + rows.size();
                            addRows(rows);
                        }
                        if (reset && rows.isEmpty()) {
                            status.setText(fail == null ? "没有找到《" + q + "》" : fail);
                            more.setVisibility(View.GONE);
                            return;
                        }
                        if (!rows.isEmpty() && rows.size() < 20) finished = true;
                        if (finished) {
                            status.setText("没有更多了");
                            more.setVisibility(View.GONE);
                        } else {
                            status.setText("");
                            more.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }).start();
    }

    private void addRows(List<Map<String, Object>> rows) {
        int coverW = dp(52);
        int coverH = dp(70);
        for (int i = 0; i < rows.size(); i++) {
            BookRow row = new BookRow(this, rows.get(i), coverW, coverH, open, press);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(14);
            list.addView(row, lp);
        }
    }

    private final BookRow.Open open = new BookRow.Open() {
        @Override
        public void onOpen(Map<String, Object> book) {
            Intent intent = new Intent(SearchActivity.this, BookInfoActivity.class);
            intent.putExtra("bookId", BookRow.str(book.get("bookId")));
            intent.putExtra("title", BookRow.str(book.get("title")));
            intent.putExtra("author", BookRow.str(book.get("author")));
            intent.putExtra("cover", BookRow.str(book.get("cover")));
            intent.putExtra("inShelf", false);
            startActivity(intent);
        }
    };

    private final BookRow.Press press = new BookRow.Press() {
        @Override
        public void onLongPress(Map<String, Object> book) {
            add(book);
        }
    };

    private void add(Map<String, Object> book) {
        final String id = BookRow.str(book.get("bookId"));
        if (id.length() == 0 || id.startsWith("local:")) {
            toast("这本书已经在书架上了");
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
                        toast(fail == null ? "已加入书架" : "加入失败：" + fail);
                    }
                });
            }
        }).start();
    }

    // ---------------- 按键 / 工具 ----------------

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
                input.requestFocus();
                showKeyboard();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 墨水屏不做滚动动画，直接按整屏跳。 */
    private void nudge(int dir) {
        int dy = Math.max(dp(120), scroll.getHeight() - dp(60));
        scroll.scrollBy(0, dir * dy);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
