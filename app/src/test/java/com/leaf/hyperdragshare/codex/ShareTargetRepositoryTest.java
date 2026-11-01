package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.ComponentName;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class ShareTargetRepositoryTest {
    @Test
    public void dedupesActionAndSendTargetsPerPackage() {
        ShareTarget copy = ShareTarget.copyTextToClipboard(null);
        ShareTarget amapGeo = ShareTarget.action(
                "高德地图",
                null,
                "action:geo:com.autonavi.minimap",
                "com.autonavi.minimap",
                new Intent());
        ShareTarget baiduGeo = ShareTarget.action(
                "百度地图",
                null,
                "action:geo:com.baidu.BaiduMap",
                "com.baidu.BaiduMap",
                new Intent());
        ShareTarget amapSend = new ShareTarget(
                new ComponentName(
                        "com.autonavi.minimap",
                        "com.autonavi.map.activity.NewMapActivity"),
                "高德地图",
                null);
        ShareTarget baiduSend = new ShareTarget(
                new ComponentName(
                        "com.baidu.BaiduMap",
                        "com.baidu.baidumaps.WelcomeScreen.Alias0"),
                "百度地图",
                null);
        ShareTarget wechatSend = new ShareTarget(
                new ComponentName(
                        "com.tencent.mm",
                        "com.tencent.mm.ui.tools.ShareToTimeLineUI"),
                "微信",
                null);

        List<ShareTarget> result = ShareTargetRepository.dedupeByPackage(Arrays.asList(
                copy,
                amapGeo,
                baiduGeo,
                amapSend,
                baiduSend,
                wechatSend));

        assertEquals(4, result.size());
        assertSame(copy, result.get(0));
        assertSame(amapGeo, result.get(1));
        assertSame(baiduGeo, result.get(2));
        assertSame(wechatSend, result.get(3));
    }

    @Test
    public void keepsEveryBuiltInAction() {
        List<ShareTarget> result = ShareTargetRepository.dedupeByPackage(Arrays.asList(
                ShareTarget.copyTextToClipboard(null),
                ShareTarget.textSegmentation(null),
                ShareTarget.systemShare(null)));

        assertEquals(3, result.size());
    }
}
