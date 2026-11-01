package com.leaf.hyperdragshare.codex;

import java.util.regex.Pattern;

/**
 * Cheap, offline classification of dragged text. The share dock uses it to surface the
 * apps that can actually act on the payload (maps for an address, browser for a link,
 * dialer for a phone number) the same way OriginOS reorders its drag targets.
 */
final class ContentKind {
    static final int OTHER = 0;
    static final int LINK = 1;
    static final int PHONE = 2;
    static final int ADDRESS = 3;
    static final int DATETIME = 4;
    static final int EMAIL = 5;

    private static final Pattern PATTERN_URL =
            Pattern.compile("(?i)^(https?://|www\\.)\\S+$");
    private static final Pattern PATTERN_EMAIL =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PATTERN_PHONE =
            Pattern.compile("^\\+?\\d[\\d\\s\\-()]{5,}$");
    private static final Pattern PATTERN_DATETIME = Pattern.compile(
            ".*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}|\\d{1,2}[:：]\\d{2}).*");
    private static final Pattern PATTERN_ADDRESS = Pattern.compile(
            ".*(省|市|区|县|镇|村|路|街|道|巷|弄|号|大道|广场|大厦|小区|花园|园区|楼).*");

    private ContentKind() {}

    static int classify(CharSequence text) {
        if (text == null) {
            return OTHER;
        }
        String value = text.toString().trim();
        if (value.isEmpty()) {
            return OTHER;
        }
        if (PATTERN_URL.matcher(value).matches()) {
            return LINK;
        }
        if (PATTERN_EMAIL.matcher(value).matches()) {
            return EMAIL;
        }
        if (PATTERN_PHONE.matcher(value).matches()) {
            return PHONE;
        }
        if (value.length() >= 4 && PATTERN_ADDRESS.matcher(value).matches()) {
            return ADDRESS;
        }
        if (PATTERN_DATETIME.matcher(value).matches()) {
            return DATETIME;
        }
        return OTHER;
    }
}
