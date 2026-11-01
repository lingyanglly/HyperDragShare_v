package com.leaf.hyperdragshare.codex;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    static final String TAPLUS_PACKAGE = "com.miui.contentextension";
    /** Target apps whose ImageView drawables are extracted for a pure (uncomposited) image. */
    static final String BILIBILI_PACKAGE = "tv.danmaku.bili";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        DragShareLog.i("DragShare/MainHook", "loaded package=" + lpparam.packageName);
        if (TAPLUS_PACKAGE.equals(lpparam.packageName)) {
            PortalHooks.install(lpparam.classLoader);
        } else if (BILIBILI_PACKAGE.equals(lpparam.packageName)) {
            AppViewHook.install(lpparam.classLoader, lpparam.packageName);
        }
    }
}
