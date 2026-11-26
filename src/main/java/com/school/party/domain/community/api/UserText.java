package com.school.party.domain.community.api;

import java.text.Normalizer;

/** User-entered free text: NFC, both ends trimmed, length in Unicode code points (#53). */
public final class UserText {
    private UserText() {}
    public static String normalize(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }
    public static int length(String value) { return value.codePointCount(0, value.length()); }
}
