package com.webos.launcher;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native settings sheet shown by a three-finger double tap. The only place a keyboard is allowed. */
final class AdminPanel extends FrameLayout {

    interface Listener {
        void onGo(String url);
        void onReload();
        void onAndroidSettings();
        void onHomeSettings();
        void onPermissions();
        void onToggleKiosk();
        void onRelease();
        void onClose();
    }

    private final Listener l;
    private final EditText url;
    private final TextView status;
    private final Button kioskBtn;

    AdminPanel(Context c, Listener listener) {
        super(c);
        l = listener;
        setBackgroundColor(0xF2000000);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setVisibility(GONE);

        ScrollView sv = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        col.setPadding(p, dp(48), p, p);
        sv.addView(col);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(c);
        title.setText("WebOS Launcher");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        col.addView(title);

        status = new TextView(c);
        status.setTextColor(0xB3FFFFFF);
        status.setTextSize(13);
        status.setPadding(0, dp(6), 0, dp(16));
        col.addView(status);

        url = new EditText(c);
        url.setHint("https://your-web-os.example");
        url.setHintTextColor(0x66FFFFFF);
        url.setTextColor(Color.WHITE);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setOnEditorActionListener((v, actionId, ev) -> { l.onGo(url.getText().toString()); return true; });
        col.addView(url);

        col.addView(button(c, "Go", v -> l.onGo(url.getText().toString())));
        col.addView(button(c, "Reload page", v -> l.onReload()));
        col.addView(button(c, "Android settings", v -> l.onAndroidSettings()));
        col.addView(button(c, "Choose default home app", v -> l.onHomeSettings()));
        col.addView(button(c, "Grant all permissions", v -> l.onPermissions()));
        kioskBtn = button(c, "", v -> l.onToggleKiosk());
        col.addView(kioskBtn);
        col.addView(button(c, "Release phone (remove lock-down)", v -> l.onRelease()));
        col.addView(button(c, "Close", v -> l.onClose()));
    }

    private Button button(Context c, String text, OnClickListener cl) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    void open(String currentUrl, String statusText, boolean kioskOn) {
        url.setText(currentUrl == null ? "" : currentUrl);
        url.setSelection(url.getText().length());
        status.setText(statusText);
        kioskBtn.setText(kioskOn ? "Pause lock-down" : "Resume lock-down");
        setVisibility(VISIBLE);
        url.requestFocus();
        postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(url, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    void close() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        setVisibility(GONE);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
