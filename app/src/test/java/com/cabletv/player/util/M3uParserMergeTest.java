package com.cabletv.player.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Subscription links list one channel once per source (five entries named 央视一套, five URLs). They
 * are one channel to a viewer, so they arrive as one channel with several sources — the player
 * already fails over between sources and lets the viewer switch them by hand.
 *
 * <p>Merging keys on the group and the exact name, and the name is what the guide is matched on, so
 * a merged channel cannot disturb the programme list. A playlist with no repeated names must come
 * out exactly as it did before, which is the last case here.
 */
@RunWith(RobolectricTestRunner.class)
public class M3uParserMergeTest {

    private static final String HEADER = "#EXTM3U\n";

    private static List<ChannelGroup> parse(String body) {
        return M3uParser.parse(HEADER + body, true);
    }

    private static List<Channel> channels(List<ChannelGroup> groups) {
        return groups.get(0).channels;
    }

    @Test
    public void repeatedNamesInOneGroup_becomeOneChannelWithEverySource() {
        List<Channel> channels = channels(parse(
                "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://b/1\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://c/1\n"));

        assertEquals("one channel, not three", 1, channels.size());
        assertEquals("every source is kept", 3, channels.get(0).urlCount());
        assertEquals("http://a/1", channels.get(0).urls.get(0));
        assertEquals("order is the playlist's", "http://c/1", channels.get(0).urls.get(2));
    }

    @Test
    public void repeatedNamesInDifferentGroups_staySeparate() {
        List<ChannelGroup> groups = parse(
                "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"
                        + "#EXTINF:-1 group-title=\"高清\",CCTV1\nhttp://b/1\n");

        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).channels.size());
        assertEquals(1, groups.get(1).channels.size());
    }

    @Test
    public void differentNames_areNeverMerged() {
        // 央视一套 and 央视一套高清 are different streams; merging them would lose sources.
        List<Channel> channels = channels(parse(
                "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV1 高清\nhttp://b/1\n"));

        assertEquals(2, channels.size());
    }

    @Test
    public void mergeFillsInWhatTheFirstEntryLacked() {
        List<Channel> channels = channels(parse(
                "#EXTINF:-1 group-title=\"央视\" tvg-logo=\"http://logo/a.png\",CCTV1\nhttp://a/1\n"
                        + "#EXTINF:-1 group-title=\"央视\" catchup=\"default\" "
                        + "catchup-source=\"http://a/back?start=${(b)yyyyMMddHHmmss}\",CCTV1\nhttp://b/1\n"));

        Channel merged = channels.get(0);
        assertEquals("the logo the first entry carried", "http://logo/a.png", merged.logo);
        assertEquals("catchup from the second source, or replay would be lost",
                "default", merged.catchupType);
        assertEquals("http://a/back?start=${(b)yyyyMMddHHmmss}", merged.catchupSource);
    }

    @Test
    public void severalUrlLinesUnderOneEntry_areAllSources() {
        // What the README always promised: the lines below one #EXTINF are fallbacks for it.
        List<Channel> channels = channels(parse(
                "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\nhttp://b/1\nhttp://c/1\n"));

        assertEquals(1, channels.size());
        assertEquals(3, channels.get(0).urlCount());
    }

    @Test
    public void anEntryWithNoSourceIsNotAChannel() {
        List<Channel> channels = channels(parse(
                "#EXTINF:-1 group-title=\"央视\",CCTV1\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV2\nhttp://a/2\n"));

        assertEquals("a name with no URL is not a channel", 1, channels.size());
        assertEquals("CCTV2", channels.get(0).name);
    }

    @Test
    public void duplicateUrlIsKeptOnlyOnce() {
        List<Channel> channels = channels(parse(
                "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"));

        assertEquals(1, channels.size());
        assertEquals(1, channels.get(0).urlCount());
    }

    @Test
    public void txtPlaylistsMergeTheSameWay() {
        List<ChannelGroup> groups = M3uParser.parse(
                "央视,#genre#\nCCTV1,http://a/1#http://b/1\nCCTV1,http://c/1\n", true);

        assertEquals(1, groups.get(0).channels.size());
        assertEquals(3, groups.get(0).channels.get(0).urlCount());
    }

    @Test
    public void mergingOffKeepsEveryEntry() {
        List<ChannelGroup> groups = M3uParser.parse(HEADER
                + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://b/1\n", false);

        assertEquals("the playlist taken literally", 2, groups.get(0).channels.size());
        assertEquals(1, groups.get(0).channels.get(0).urlCount());
    }

    @Test
    public void playlistWithoutRepeatedNames_isIdenticalEitherWay() {
        // The verification this change has to pass: the playlist many viewers already have carries
        // no repeated names, so switching merging on must not alter a single channel, source or
        // their order. (Their television's list is exactly this shape.)
        String body = HEADER
                + "#EXTINF:-1 group-title=\"CCTV\" tvg-id=\"CCTV1\" tvg-logo=\"http://l/1.png\",CCTV1\nhttp://s/1\n"
                + "#EXTINF:-1 group-title=\"CCTV\" tvg-id=\"CCTV2\",CCTV2\nhttp://s/2\nhttp://s/2b\n"
                + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://s/3\n"
                + "#EXTINF:-1 group-title=\"卫视\" catchup=\"default\" catchup-source=\"http://s/back\",浙江卫视\nhttp://s/4\n"
                + "#EXTINF:-1 group-title=\"其他\",四川卫视\nhttp://s/5\n";

        List<ChannelGroup> merged = M3uParser.parse(body, true);
        List<ChannelGroup> literal = M3uParser.parse(body, false);

        assertEquals("same number of groups", literal.size(), merged.size());
        for (int g = 0; g < literal.size(); g++) {
            ChannelGroup expected = literal.get(g);
            ChannelGroup actual = merged.get(g);
            assertEquals("group name", expected.name, actual.name);
            assertEquals("channel count in " + expected.name,
                    expected.channels.size(), actual.channels.size());
            for (int c = 0; c < expected.channels.size(); c++) {
                Channel want = expected.channels.get(c);
                Channel got = actual.channels.get(c);
                assertEquals("name at " + g + "/" + c, want.name, got.name);
                assertEquals("group at " + g + "/" + c, want.group, got.group);
                assertEquals("logo at " + g + "/" + c, want.logo, got.logo);
                assertEquals("tvg-id at " + g + "/" + c, want.tvgId, got.tvgId);
                assertEquals("catchup at " + g + "/" + c, want.catchupType, got.catchupType);
                assertEquals("sources at " + g + "/" + c, want.urls, got.urls);
            }
        }
        int total = 0;
        for (ChannelGroup group : literal) {
            total += group.channels.size();
        }
        assertEquals("and the list is not empty, or the comparison proves nothing", 5, total);
    }

    @Test
    public void mergingIsWhatMakesTheDifferenceOnARepeatingPlaylist() {
        // Guards the case above from passing by accident: with repeats, the two modes differ.
        String body = HEADER
                + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a/1\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://b/1\n";
        assertNotEquals(M3uParser.parse(body, true).get(0).channels.size(),
                M3uParser.parse(body, false).get(0).channels.size());
    }
}
