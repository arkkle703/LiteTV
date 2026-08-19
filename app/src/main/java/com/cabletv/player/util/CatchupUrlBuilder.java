package com.cabletv.player.util;

import com.cabletv.player.model.Channel;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the catchup (回看/时移) URL for a channel from its {@code catchup-source} template.
 *
 * <p>Two ecosystem conventions are honoured, following rtp2httpd and FongMi/TV:
 *
 * <ul>
 *   <li>{@code ${(b)yyyyMMddHHmmss}} / {@code ${(e)yyyyMMddHHmmss}} — begin / end time in the
 *       device's local timezone (the ZTE-BST / 华为·中兴 IPTV form). A {@code |UTC} suffix forces
 *       UTC, and {@code (b)timestamp} yields Unix seconds.</li>
 *   <li>{@code ${utc}} / {@code ${utcend}} / {@code ${start}} / {@code ${end}} — Unix seconds;
 *       with a {@code :format} suffix they are formatted in UTC instead
 *       ({@code ${utc:yyyyMMddHHmmss}}). {@code ${now}} / {@code ${timestamp}} is the current
 *       time, and {@code ${duration}} the requested window length in seconds.</li>
 * </ul>
 *
 * <p>Both {@code ${...}} and bare {@code {...}} tokens are accepted; bare tokens use the short
 * format letters ({@code Y m d H M S}) exactly like rtp2httpd.
 *
 * <p>{@code catchup="default"} treats the source as a complete URL; {@code append}/{@code shift}
 * concatenate it onto the live stream URL, optionally after a regex replacement
 * ({@code catchup-replace="regex,replacement"}). Only http(s) results are ever returned.
 */
public final class CatchupUrlBuilder {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\$?\\{[^}]+\\})");

    private CatchupUrlBuilder() {
    }

    /**
     * @return the playable catchup URL, or null when the channel has none or the result is unsafe.
     */
    public static String buildUrl(Channel channel, long startMs, long endMs) {
        if (channel == null || !channel.hasCatchup() || startMs <= 0 || endMs <= startMs) {
            return null;
        }
        String template = substitute(channel.catchupSource, startMs, endMs);
        if (template == null) {
            return null;
        }
        String type = channel.catchupType == null ? "" : channel.catchupType.trim();
        String url;
        if ("append".equalsIgnoreCase(type) || "shift".equalsIgnoreCase(type)) {
            String base = applyReplace(channel.url(), channel.catchupReplace);
            if (base == null || base.isEmpty()) {
                return null;
            }
            if (template.startsWith("?") && base.contains("?")) {
                template = "&" + template.substring(1);
            }
            url = base + template;
        } else {
            // "default" and anything unrecognised: the source is the complete URL.
            url = template;
        }
        return (url.startsWith("http://") || url.startsWith("https://")) ? url : null;
    }

    // ------------------------------------------------------------ substitution

    private static String substitute(String source, long startMs, long endMs) {
        long nowMs = System.currentTimeMillis();
        long durationSec = Math.max(0, (endMs - startMs) / 1000);
        Matcher matcher = TOKEN_PATTERN.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            boolean dollar = token.startsWith("${");
            String inner = token.substring(dollar ? 2 : 1, token.length() - 1);
            String value = resolveToken(inner, dollar, startMs, endMs, nowMs, durationSec);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String resolveToken(String inner, boolean dollar, long startMs, long endMs,
                                       long nowMs, long durationSec) {
        // ${(b)fmt} / ${(e)fmt}: begin / end time.
        if (inner.startsWith("(b)") || inner.startsWith("(e)")) {
            boolean begin = inner.startsWith("(b)");
            String fmt = inner.substring(3);
            boolean utc = fmt.endsWith("|UTC");
            if (utc) {
                fmt = fmt.substring(0, fmt.length() - 4);
            }
            long time = begin ? startMs : endMs;
            if (fmt.isEmpty() || "timestamp".equals(fmt)) {
                return String.valueOf(time / 1000);
            }
            return formatDateTime(time, dollar ? fmt : toLongFormat(fmt), utc);
        }

        // keyword:format — utc / utcend / start / end / now / lutc / timestamp.
        int colon = inner.indexOf(':');
        if (colon > 0) {
            String key = inner.substring(0, colon);
            String fmt = inner.substring(colon + 1);
            long time = keywordTime(key, startMs, endMs, nowMs);
            if (time < 0) {
                return "";
            }
            if ("timestamp".equals(fmt)) {
                return String.valueOf(time / 1000);
            }
            return formatDateTime(time, dollar ? fmt : toLongFormat(fmt), true);
        }

        // Bare keywords and single-field placeholders.
        switch (inner) {
            case "utc":
            case "start":
                return String.valueOf(startMs / 1000);
            case "utcend":
            case "end":
                return String.valueOf(endMs / 1000);
            case "now":
            case "lutc":
            case "timestamp":
                return String.valueOf(nowMs / 1000);
            case "duration":
                return String.valueOf(durationSec);
            case "yyyy":
            case "Y":
                return component(Calendar.YEAR, startMs, 4);
            case "MM":
            case "m":
                return component(Calendar.MONTH, startMs, 2, 1);
            case "dd":
            case "d":
                return component(Calendar.DAY_OF_MONTH, startMs, 2);
            case "HH":
            case "H":
                return component(Calendar.HOUR_OF_DAY, startMs, 2);
            case "mm":
            case "M":
                return component(Calendar.MINUTE, startMs, 2);
            case "ss":
            case "S":
                return component(Calendar.SECOND, startMs, 2);
            default:
                return ""; // unknown token: drop it rather than leak it raw
        }
    }

    private static long keywordTime(String key, long startMs, long endMs, long nowMs) {
        switch (key) {
            case "utc":
            case "start":
                return startMs;
            case "utcend":
            case "end":
                return endMs;
            case "now":
            case "lutc":
            case "timestamp":
                return nowMs;
            default:
                return -1;
        }
    }

    // ------------------------------------------------------------- formatting

    /**
     * Formats a timestamp with a Java-style date pattern ({@code yyyy MM dd HH mm ss}). Literal
     * characters pass through untouched, so {@code yyyy-MM-dd HH:mm:ss} works too.
     */
    private static String formatDateTime(long millis, String pattern, boolean utc) {
        Calendar cal = Calendar.getInstance(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        cal.setTimeInMillis(millis);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            int run = 1;
            while (i + run < pattern.length() && pattern.charAt(i + run) == c) {
                run++;
            }
            String value = fieldValue(cal, c, run);
            if (value != null) {
                out.append(value);
                i += run;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** @return the padded value for a date field, or null for a literal character. */
    private static String fieldValue(Calendar cal, char c, int width) {
        int value;
        boolean year = false;
        switch (c) {
            case 'y':
                year = true;
                value = cal.get(Calendar.YEAR);
                break;
            case 'M':
                value = cal.get(Calendar.MONTH) + 1;
                break;
            case 'd':
                value = cal.get(Calendar.DAY_OF_MONTH);
                break;
            case 'H':
                value = cal.get(Calendar.HOUR_OF_DAY);
                break;
            case 'm':
                value = cal.get(Calendar.MINUTE);
                break;
            case 's':
                value = cal.get(Calendar.SECOND);
                break;
            default:
                return null;
        }
        String text = String.valueOf(value);
        if (year && text.length() > width) {
            return text.substring(text.length() - width); // "yy" → last two digits
        }
        if (text.length() < width) {
            StringBuilder padded = new StringBuilder(width);
            for (int p = text.length(); p < width; p++) {
                padded.append('0');
            }
            return padded.append(text).toString();
        }
        return text;
    }

    /** rtp2httpd's short letters ({@code Y m d H M S}) → the long form used above. */
    private static String toLongFormat(String shortFormat) {
        return shortFormat
                .replace("Y", "yyyy")
                .replace("m", "__month__")
                .replace("d", "dd")
                .replace("H", "HH")
                .replace("M", "mm")
                .replace("S", "ss")
                .replace("__month__", "MM");
    }

    private static String component(int field, long millis, int width) {
        return component(field, millis, width, 0);
    }

    private static String component(int field, long millis, int width, int offset) {
        Calendar cal = Calendar.getInstance(TimeZone.getDefault());
        cal.setTimeInMillis(millis);
        int value = cal.get(field) + offset;
        String text = String.valueOf(value);
        if (text.length() < width) {
            StringBuilder padded = new StringBuilder(width);
            for (int p = text.length(); p < width; p++) {
                padded.append('0');
            }
            return padded.append(text).toString();
        }
        return text;
    }

    // --------------------------------------------------------------- append

    /** Applies the {@code regex,replacement} pair from {@code catchup-replace} to the live URL. */
    private static String applyReplace(String url, String replace) {
        if (url == null || replace == null || replace.isEmpty()) {
            return url;
        }
        String[] parts = replace.split(",", 2);
        if (parts.length != 2 || parts[0].isEmpty()) {
            return url;
        }
        try {
            return url.replaceAll(parts[0], parts[1]);
        } catch (Exception e) {
            return url; // invalid regex: leave the URL untouched rather than fail playback
        }
    }
}
