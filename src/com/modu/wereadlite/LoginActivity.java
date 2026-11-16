package com.modu.wereadlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 扫码登录：屏上渲染二维码 → 手机微信扫码确认 → 长轮询取会话。 */
public class LoginActivity extends Activity {

    private Api api;
    private Store store;
    private final Handler ui = new Handler();

    private ImageView qrView;
    private TextView statusView;
    private EditText otpView;
    private LinearLayout otpRow;

    private volatile boolean running;
    private volatile String uid = "";
    private volatile String otp = "";
    private Thread poller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.WHITE));
        store = new Store(this);
        api = new Api(store);
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        newQr();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("微读lite");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        qrView = new ImageView(this);
        int size = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 3 / 5;
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(size, size);
        qp.topMargin = dp(16);
        root.addView(qrView, qp);

        statusView = new TextView(this);
        statusView.setText("正在生成二维码…");
        statusView.setTextColor(Color.BLACK);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(14), 0, 0);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout otpRow = new LinearLayout(this);
        this.otpRow = otpRow;
        otpRow.setOrientation(LinearLayout.HORIZONTAL);
        otpRow.setGravity(Gravity.CENTER_VERTICAL);
        otpRow.setPadding(0, dp(10), 0, 0);

        otpView = new EditText(this);
        otpView.setHint("手机上的 4 位数字");
        otpView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        otpView.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpView.setSingleLine(true);
        otpView.setBackgroundColor(Color.WHITE);
        otpView.setTextColor(Color.BLACK);
        otpRow.addView(otpView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button otpButton = new Button(this);
        otpButton.setText("确认");
        styleButton(otpButton);
        otpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                otp = otpView.getText().toString().trim();
                setStatus("已提交验证码，等待确认…");
                setOtpVisible(false);
            }
        });
        otpRow.addView(otpButton);
        otpRow.setVisibility(View.GONE);
        root.addView(otpRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button refresh = new Button(this);
        refresh.setText("刷新二维码");
        styleButton(refresh);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newQr();
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(18);
        root.addView(refresh, rp);

        // 不登录也能读本地 TXT / EPUB：书架页的「导入」不该被登录挡住
        Button skip = new Button(this);
        skip.setText("跳过登录，先读本地书");
        styleButton(skip);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPolling();
                Intent i = new Intent(LoginActivity.this, MainActivity.class);
                i.putExtra("offline", true);
                startActivity(i);
                finish();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        root.addView(skip, sp);
        return root;
    }

    private void setOtpVisible(final boolean visible) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || otpRow == null) return;
                otpRow.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }


    /** 墨水屏风格按钮：白底、黑字、1px 黑框。 */
    private void styleButton(Button b) {
        b.setTextColor(Color.BLACK);
        b.setBackgroundDrawable(blackFrame());
    }

    private android.graphics.drawable.GradientDrawable blackFrame() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.WHITE);
        d.setStroke(1, Color.BLACK);
        d.setCornerRadius(0);
        return d;
    }


    private void setStatus(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) statusView.setText(text);
            }
        });
    }

    private void newQr() {
        stopPolling();
        if (qrView.getDrawable() != null) {
            Bitmap old = ((android.graphics.drawable.BitmapDrawable) qrView.getDrawable()).getBitmap();
            qrView.setImageDrawable(null);
            if (old != null) old.recycle();
        }
        setStatus("正在生成二维码…");
        otp = "";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String newUid = api.loginUid();
                    if (!running && isFinishing()) return;
                    uid = newUid;
                    final String content = Protocol.BASE + "/web/confirm?uid=" + newUid;
                    final int size = Math.min(getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels) * 3 / 5;
                    final Bitmap bmp = Qr.encode(content, size);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (bmp == null) {
                                statusView.setText("二维码生成失败");
                                return;
                            }
                            qrView.setImageBitmap(bmp);
                            statusView.setText("请用手机微信扫描二维码");
                        }
                    });
                    startPolling();
                } catch (final Exception e) {
                    setStatus("网络异常：" + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void startPolling() {
        running = true;
        poller = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    String currentUid = uid;
                    if (currentUid.length() == 0) break;
                    try {
                        Api.Resp r = api.loginInfo(currentUid, otp);
                        String body = r.body == null ? "" : r.body.trim();
                        if (body.length() == 0) {
                            // 长轮询到点被服务端断开：还没人扫，继续
                            continue;
                        }
                        Object data = Json.parse(body);
                        String code = Json.str(data, "logicCode");
                        if ("NEED_OTP".equals(code)) {
                            otp = "";
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing()) return;
                                    otpRow.setVisibility(View.VISIBLE);
                                    statusView.setText("请输入手机微信上的 4 位数字");
                                }
                            });
                        } else if ("OTP_NOT_MATCH".equals(code)) {
                            otp = "";
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing()) return;
                                    otpRow.setVisibility(View.VISIBLE);
                                    statusView.setText("验证码不正确，请重新输入");
                                }
                            });
                        } else if ("OTP_EXPIRED".equals(code) || "LOGIN_TIMEOUT".equals(code)) {
                            setStatus("二维码已过期，正在刷新…");
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isFinishing()) newQr();
                                }
                            });
                            return;
                        } else if ("true".equals(String.valueOf(Json.asMap(data).get("succeed")))
                                && api.finishLogin(data)) {
                            setStatus("登录成功");
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing()) return;
                                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                                    finish();
                                }
                            });
                            return;
                        } else if (!"NEED_OTP".equals(code) && body.length() > 0
                                && body.contains("succeed") && !body.contains("true")) {
                            // 扫码了但未确认，继续等
                        }
                        Thread.sleep(600);
                    } catch (Exception e) {
                        if (!running) return;
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ignore) {
                            return;
                        }
                    }
                }
            }
        });
        poller.setDaemon(true);
        poller.start();
    }

    private void stopPolling() {
        running = false;
        Thread t = poller;
        poller = null;
        if (t != null) t.interrupt();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
