package com.cabletv.player.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Feed text that was escaped twice reaches the app with the entity still visible. These cases use
 * the shapes actually found in a local XMLTV feed (together&apos;趣&apos;观赛 and friends).
 */
public class HtmlEntitiesTest {

    @Test
    public void decodesTheDoubleEscapedEntitiesFoundInARealFeed() {
        // The parser hands this class what the XML layer left: 一起&apos;趣&apos;观赛(20)
        assertEquals("一起'趣'观赛(20)", HtmlEntities.decode("一起&apos;趣&apos;观赛(20)"));
        // &quot; means a plain quote; the app shows what the feed meant, not a prettier variant.
        assertEquals("\"运动成都\"栏目", HtmlEntities.decode("&quot;运动成都&quot;栏目"));
        assertEquals("2个男主角&2个女主角", HtmlEntities.decode("2个男主角&amp;2个女主角"));
    }

    @Test
    public void resolvesRepeatedEscaping() {
        // &amp;amp;apos; is what a feed that escapes three times produces.
        assertEquals("'", HtmlEntities.decode("&amp;amp;apos;"));
        assertEquals("A & B", HtmlEntities.decode("A &amp;amp; B"));
    }

    @Test
    public void decodesNumericReferences() {
        assertEquals("'", HtmlEntities.decode("&#39;"));
        assertEquals("'", HtmlEntities.decode("&#x27;"));
        assertEquals("中", HtmlEntities.decode("&#20013;"));
        assertEquals("A\u00e9B", HtmlEntities.decode("A&#233;B"));
    }

    @Test
    public void leavesOrdinaryTextExactlyAsItIs() {
        String plain = "新闻联播 19:00";
        assertSame("no entities: the same string, not a copy", plain, HtmlEntities.decode(plain));
        assertSame("a bare ampersand is not a reference", "A & B", HtmlEntities.decode("A & B"));
    }

    @Test
    public void leavesUnknownReferencesAlone() {
        assertEquals("&notarealentity;", HtmlEntities.decode("&notarealentity;"));
        assertEquals("&#99999999999;", HtmlEntities.decode("&#99999999999;"));
        assertEquals("&;", HtmlEntities.decode("&;"));
    }

    @Test
    public void mapsSpacesAndDropsSoftHyphens() {
        assertEquals("a b", HtmlEntities.decode("a&nbsp;b"));
        assertEquals("ab", HtmlEntities.decode("a&shy;b"));
    }

    @Test
    public void isSafeWithNothingToDo() {
        assertNull(HtmlEntities.decode(null));
        assertEquals("", HtmlEntities.decode(""));
        assertEquals("no ampersand here", HtmlEntities.decode("no ampersand here"));
    }

    @Test
    public void isIdempotent() {
        String once = HtmlEntities.decode("一起&apos;趣&apos;观赛");
        assertEquals(once, HtmlEntities.decode(once));
    }
}
