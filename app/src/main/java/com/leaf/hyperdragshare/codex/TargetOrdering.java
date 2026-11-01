package com.leaf.hyperdragshare.codex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable re-ordering of the share targets for the dragged payload. Apps that can act on
 * the payload's kind move to the front; everything else keeps its previous order.
 */
final class TargetOrdering {
    private static final Map<Integer, String[]> PREFERRED = new HashMap<>();

    static {
        PREFERRED.put(ContentKind.ADDRESS, new String[]{
                "com.autonavi.minimap",     // 高德地图
                "com.baidu.BaiduMap",       // 百度地图
                "com.tencent.map",          // 腾讯地图
                "com.google.android.apps.maps",
        });
        PREFERRED.put(ContentKind.LINK, new String[]{
                "com.tencent.mtt",          // QQ 浏览器
                "com.UCMobile",
                "mark.via",
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.microsoft.emmx",
        });
        PREFERRED.put(ContentKind.PHONE, new String[]{
                "com.android.dialer",
                "com.google.android.dialer",
                "com.android.contacts",
        });
        PREFERRED.put(ContentKind.DATETIME, new String[]{
                "com.android.calendar",
                "com.miui.calendar",
                "com.google.android.calendar",
        });
        PREFERRED.put(ContentKind.EMAIL, new String[]{
                "com.tencent.androidqqmail",
                "com.netease.mail",
                "com.android.email",
                "com.google.android.gm",
        });
    }

    private TargetOrdering() {}

    static List<ShareTarget> order(List<ShareTarget> targets, CharSequence text) {
        if (targets == null || targets.size() < 2) {
            return targets;
        }
        String[] preferred = PREFERRED.get(ContentKind.classify(text));
        if (preferred == null) {
            return targets;
        }
        List<ShareTarget> ordered = new ArrayList<>(targets.size());
        Set<ShareTarget> placed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String packageName : preferred) {
            for (ShareTarget target : targets) {
                if (packageName.equals(target.packageName()) && placed.add(target)) {
                    ordered.add(target);
                }
            }
        }
        for (ShareTarget target : targets) {
            if (placed.add(target)) {
                ordered.add(target);
            }
        }
        return ordered;
    }
}
