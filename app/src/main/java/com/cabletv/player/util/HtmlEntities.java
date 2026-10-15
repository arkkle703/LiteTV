package com.cabletv.player.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes the XML/HTML entities that programme guides leave in their text.
 *
 * <p>The XML parser already resolves the XML layer, so those are not the problem. The problem is
 * feeds that escape twice: a title whose author wrote {@code 一起'趣'观赛} is turned into
 * {@code 一起&amp;apos;趣&amp;apos;观赛} by a server that HTML-escapes first and XML-escapes second.
 * A conforming parser resolves that to {@code 一起&apos;趣&apos;观赛}, and the viewer sees the
 * entity itself on screen — which is exactly what was reported against a local feed (76 such
 * titles, plus {@code &amp;quot;} and {@code &amp;amp;}).
 *
 * <p>Decoding is therefore applied to text that has already been through the parser, and it repeats
 * a bounded number of times so a doubly- and triply-escaped feed both come out readable. Text with
 * no entities is returned untouched (the same instance), and an entity this class does not know is
 * left exactly as it was rather than guessed at.
 */
public final class HtmlEntities {

    /** A feed that escapes three times over is already pathological; the bound rules out loops. */
    private static final int MAX_PASSES = 3;

    /** Named or numeric reference, as it appears in text: {@code &amp;}, {@code &#39;}, {@code &#x27;}. */
    private static final Pattern ENTITY = Pattern.compile(
            "&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});");

    private static final Map<String, String> NAMED = namedEntities();

    private HtmlEntities() {
    }

    /**
     * Returns the text with its entities resolved, or the same string when there is nothing to do.
     * Null-safe.
     */
    public static String decode(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        String current = text;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String decoded = decodeOnce(current);
            if (decoded == current) {
                // Nothing recognised (or nothing left), so further passes would change nothing.
                return current;
            }
            current = decoded;
        }
        return current;
    }

    /**
     * One pass: every recognised reference is replaced, unknown ones are kept. Returns the same
     * instance when nothing was recognised.
     */
    static String decodeOnce(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = null;
        int copiedTo = 0;
        while (matcher.find()) {
            String replacement = decodeOne(matcher.group(1));
            if (replacement == null) {
                // Unknown reference: copy it verbatim like any other text.
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            // Built by hand rather than with Matcher.appendReplacement(StringBuilder, ...):
            // that overload only exists from API 26, and this app runs on API 21 devices where
            // calling it throws NoSuchMethodError at runtime (found on a MiTV running Android 6).
            out.append(text, copiedTo, matcher.start()).append(replacement);
            copiedTo = matcher.end();
        }
        if (out == null) {
            return text;
        }
        out.append(text, copiedTo, text.length());
        return out.toString();
    }

    /** Body of a reference (without {@code &} and {@code ;}), or null when it is not known. */
    private static String decodeOne(String body) {
        if (body.charAt(0) == '#') {
            return codePoint(body.substring(1));
        }
        return NAMED.get(body);
    }

    private static String codePoint(String digits) {
        try {
            int value = (digits.charAt(0) == 'x' || digits.charAt(0) == 'X')
                    ? Integer.parseInt(digits.substring(1), 16)
                    : Integer.parseInt(digits);
            if (!Character.isValidCodePoint(value)) {
                return null;
            }
            return new String(Character.toChars(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The references worth carrying for television text: the five XML ones, the spaces, and the
     * punctuation that shows up in programme titles. Anything else stays as written, so an unknown
     * name is never silently replaced by a wrong character.
     */
    private static Map<String, String> namedEntities() {
        Map<String, String> map = new HashMap<>();
        // XML's five, plus the non-breaking spaces (mapped to an ordinary space: a hard space
        // inside a title reads as a stray gap on a television).
        map.put("amp", "&");
        map.put("lt", "<");
        map.put("gt", ">");
        map.put("quot", "\"");
        map.put("apos", "'");
        map.put("nbsp", " ");
        map.put("ensp", " ");
        map.put("emsp", " ");
        map.put("thinsp", " ");
        map.put("shy", "");
        // Typographic punctuation, which feeds that escape quotes tend to bring along.
        map.put("ndash", "\u2013");
        map.put("mdash", "\u2014");
        map.put("hellip", "\u2026");
        map.put("middot", "\u00b7");
        map.put("bull", "\u2022");
        map.put("laquo", "\u00ab");
        map.put("raquo", "\u00bb");
        map.put("lsaquo", "\u2039");
        map.put("rsaquo", "\u203a");
        map.put("ldquo", "\u201c");
        map.put("rdquo", "\u201d");
        map.put("lsquo", "\u2018");
        map.put("rsquo", "\u2019");
        map.put("prime", "\u2032");
        map.put("Prime", "\u2033");
        // Signs that appear in sports and documentary titles.
        map.put("times", "\u00d7");
        map.put("divide", "\u00f7");
        map.put("plusmn", "\u00b1");
        map.put("deg", "\u00b0");
        map.put("permil", "\u2030");
        map.put("sect", "\u00a7");
        map.put("para", "\u00b6");
        map.put("copy", "\u00a9");
        map.put("reg", "\u00ae");
        map.put("euro", "\u20ac");
        map.put("pound", "\u00a3");
        map.put("yen", "\u00a5");
        return map;
    }
}
