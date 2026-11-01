package com.leaf.hyperdragshare.codex;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;

final class ShareTarget {
    final ComponentName component;
    final CharSequence label;
    final Drawable icon;
    private final String key;
    private final String packageName;
    private final boolean builtIn;
    private final Intent explicitIntent;

    ShareTarget(ComponentName component, CharSequence label, Drawable icon) {
        this(
                component,
                label,
                icon,
                component == null ? null : component.flattenToString(),
                component == null ? "" : component.getPackageName(),
                false);
    }

    private ShareTarget(
            ComponentName component,
            CharSequence label,
            Drawable icon,
            String key,
            String packageName,
            boolean builtIn) {
        this(component, label, icon, key, packageName, builtIn, null);
    }

    private ShareTarget(
            ComponentName component,
            CharSequence label,
            Drawable icon,
            String key,
            String packageName,
            boolean builtIn,
            Intent explicitIntent) {
        this.component = component;
        this.label = label;
        this.icon = icon;
        this.key = key;
        this.packageName = packageName;
        this.builtIn = builtIn;
        this.explicitIntent = explicitIntent;
    }

    /** A target that launches its own intent instead of an ACTION_SEND chooser entry. */
    static ShareTarget action(
            CharSequence label,
            Drawable icon,
            String key,
            String packageName,
            Intent intent) {
        return new ShareTarget(null, label, icon, key, packageName, true, intent);
    }

    boolean isActionTarget() {
        return explicitIntent != null;
    }

    Intent actionIntent() {
        return explicitIntent == null ? null : new Intent(explicitIntent);
    }

    static ShareTarget saveToLocal(Drawable icon) {
        return new ShareTarget(
                null,
                "保存到本地",
                icon,
                DragShareSettings.TARGET_SAVE_LOCAL,
                "builtin",
                true);
    }

    static ShareTarget copyTextToClipboard(Drawable icon) {
        return new ShareTarget(
                null,
                "复制文本",
                icon,
                DragShareSettings.TARGET_COPY_TEXT,
                "builtin",
                true);
    }

    static ShareTarget copyImageToClipboard(Drawable icon) {
        return new ShareTarget(
                null,
                "复制图片",
                icon,
                DragShareSettings.TARGET_COPY_IMAGE,
                "builtin",
                true);
    }

    static ShareTarget textSegmentation(Drawable icon) {
        return new ShareTarget(
                null,
                "文本分词",
                icon,
                DragShareSettings.TARGET_TEXT_SEGMENTATION,
                "builtin",
                true);
    }

    static ShareTarget systemShare(Drawable icon) {
        return new ShareTarget(
                null,
                "更多",
                icon,
                DragShareSettings.TARGET_SYSTEM_SHARE,
                "builtin",
                true);
    }

    String key() {
        return key;
    }

    String packageName() {
        return packageName;
    }

    boolean isBuiltIn() {
        return builtIn;
    }

    boolean isSaveToLocal() {
        return builtIn && DragShareSettings.TARGET_SAVE_LOCAL.equals(key);
    }

    boolean isCopyTextToClipboard() {
        return builtIn && DragShareSettings.TARGET_COPY_TEXT.equals(key);
    }

    boolean isCopyImageToClipboard() {
        return builtIn && DragShareSettings.TARGET_COPY_IMAGE.equals(key);
    }

    boolean isCopyToClipboard() {
        return isCopyTextToClipboard() || isCopyImageToClipboard();
    }

    boolean isTextSegmentation() {
        return builtIn && DragShareSettings.TARGET_TEXT_SEGMENTATION.equals(key);
    }

    boolean isSystemShare() {
        return builtIn && DragShareSettings.TARGET_SYSTEM_SHARE.equals(key);
    }
}
