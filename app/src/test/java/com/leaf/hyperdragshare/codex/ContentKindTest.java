package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ContentKindTest {
    @Test
    public void classifiesCommonPayloads() {
        assertEquals(ContentKind.LINK, ContentKind.classify("https://example.com/a?b=1"));
        assertEquals(ContentKind.LINK, ContentKind.classify("www.bilibili.com"));
        assertEquals(ContentKind.EMAIL, ContentKind.classify("drag@example.com"));
        assertEquals(ContentKind.PHONE, ContentKind.classify("13800138000"));
        assertEquals(ContentKind.PHONE, ContentKind.classify("+86 138 0013 8000"));
        assertEquals(ContentKind.ADDRESS,
                ContentKind.classify("北京市海淀区中关村大街1号"));
        assertEquals(ContentKind.DATETIME, ContentKind.classify("2026-11-01 14:30 开会"));
    }

    @Test
    public void keepsPlainTextAsOther() {
        assertEquals(ContentKind.OTHER, ContentKind.classify("今天天气不错"));
        assertEquals(ContentKind.OTHER, ContentKind.classify(""));
        assertEquals(ContentKind.OTHER, ContentKind.classify(null));
    }
}
