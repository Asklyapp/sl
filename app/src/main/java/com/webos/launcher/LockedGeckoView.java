package com.webos.launcher;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.mozilla.geckoview.GeckoView;

/** GeckoView that never connects to the on-screen keyboard (IME). Hardware keys still work. */
final class LockedGeckoView extends GeckoView {
    LockedGeckoView(Context c) { super(c); }

    @Override
    public boolean onCheckIsTextEditor() { return false; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) { return null; }
}
