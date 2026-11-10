package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/** 封面 + 书名 + 作者 + 推荐值。搜索页和「猜你喜欢」共用，避免两处各写一遍。 */
class BookRow extends LinearLayout {

    interface Open {
        void onOpen(Map<String, Object> book);
    }

    interface Press {
        void onLongPress(Map<String, Object> book);
    }

    private final Map<String, Object> book;
    private final String bookId;
    private final String coverUrl;
    private final ImageView image;
    private final TextView placeholder;

    BookRow(Context c, Map<String, Object> data, int coverW, int coverH,
            final Open open, final Press press) {
        super(c);
        book = data;
        bookId = str(data.get("bookId"));
        coverUrl = str(data.get("cover"));
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout frame = new FrameLayout(c);
        frame.setBackgroundDrawable(Ui.box(Ui.TILE, Ui.LINE, 1, 2));
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        placeholder = Ui.text(c, str(data.get("title")), 9.5f, 0xFF9A9A9A);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setMaxLines(4);
        placeholder.setEllipsize(TextUtils.TruncateAt.END);
        placeholder.setLineSpacing(dp(c, 3), 1f);
        placeholder.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        frame.addView(placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        addView(frame, new LinearLayout.LayoutParams(coverW, coverH));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.setPadding(dp(c, 10), 0, 0, 0);

        TextView title = Ui.text(c, str(data.get("title")), 14, Ui.INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(dp(c, 3), 1f);
        texts.addView(title);

        String author = str(data.get("author"));
        if (author.length() > 0) {
            TextView a = Ui.text(c, author, 11.5f, Ui.SUB);
            a.setMaxLines(1);
            a.setEllipsize(TextUtils.TruncateAt.END);
            a.setPadding(0, dp(c, 5), 0, 0);
            texts.addView(a);
        }

        String rating = ratingText(data);
        if (rating.length() > 0) {
            TextView r = Ui.text(c, rating, 10.5f, Ui.LIGHT);
            r.setMaxLines(1);
            r.setPadding(0, dp(c, 5), 0, 0);
            texts.addView(r);
        }

        addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (open != null) open.onOpen(book);
            }
        });
        if (press != null) {
            setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    press.onLongPress(book);
                    return true;
                }
            });
        }

        loadCover(coverW, coverH);
    }

    private void loadCover(int w, int h) {
        if (coverUrl.length() == 0) return;
        Covers.load("row-" + bookId, coverUrl, w, h, new Covers.Callback() {
            @Override
            public void onCover(String key, Bitmap bitmap) {
                if (!key.equals("row-" + bookId)) return;
                image.setImageBitmap(bitmap);
                placeholder.setVisibility(View.GONE);
            }
        });
    }

    /** 「推荐值 90.7%（神作）」；没有评分数据时返回空串，界面就不显示这一行。 */
    static String ratingText(Map<String, Object> book) {
        int rating = num(book.get("rating"));
        if (rating <= 0) return "";
        String s = "推荐值 " + percent(rating) + "%";
        Object title = book.get("ratingTitle");
        if (title != null && String.valueOf(title).length() > 0) {
            s = s + "（" + title + "）";
        }
        return s;
    }

    /** 千分制评分 -> 「93.0」。 */
    static String percent(int rating) {
        return String.valueOf(Math.round(rating / 10.0f * 10.0f) / 10.0f);
    }

    static String wan(long n) {
        if (n < 10000L) return String.valueOf(n);
        return trim1(n / 10000.0) + "万";
    }

    static String words(int n) {
        if (n <= 0) return "—";
        if (n < 10000) return n + "字";
        return trim1(n / 10000.0) + "万字";
    }

    private static String trim1(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (r == Math.floor(r)) return String.valueOf((long) r);
        return String.valueOf(r);
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static int dp(Context c, float v) {
        return Ui.dp(c, v);
    }
}
