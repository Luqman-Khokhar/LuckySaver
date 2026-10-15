package com.luqman.luckysaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtLinkParserTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun watch() = assertEquals(YtLink(id, YtKind.VIDEO), YtLinkParser.parse("https://www.youtube.com/watch?v=$id"))
    @Test fun watchWithParams() =
        assertEquals(YtLink(id, YtKind.VIDEO), YtLinkParser.parse("https://youtube.com/watch?feature=share&v=$id&t=42s"))
    @Test fun mobile() = assertEquals(YtLink(id, YtKind.VIDEO), YtLinkParser.parse("https://m.youtube.com/watch?v=$id"))
    @Test fun short() = assertEquals(YtLink(id, YtKind.VIDEO), YtLinkParser.parse("https://youtu.be/$id?si=abcDEF123"))
    @Test fun shorts() = assertEquals(YtLink(id, YtKind.SHORT), YtLinkParser.parse("https://youtube.com/shorts/$id?si=x"))
    @Test fun live() = assertEquals(YtLink(id, YtKind.VIDEO), YtLinkParser.parse("https://www.youtube.com/live/$id"))
    @Test fun music() = assertEquals(YtLink(id, YtKind.MUSIC), YtLinkParser.parse("https://music.youtube.com/watch?v=$id&si=q"))
    @Test fun inShareText() =
        assertEquals(YtLink(id, YtKind.VIDEO), YtLinkParser.parse("Watch this! https://youtu.be/$id."))
    @Test fun canonical() = assertEquals("https://www.youtube.com/watch?v=$id", YtLink(id, YtKind.MUSIC).watchUrl)

    @Test fun playlistOnly() = assertNull(YtLinkParser.parse("https://www.youtube.com/playlist?list=PL123"))
    @Test fun channel() = assertNull(YtLinkParser.parse("https://www.youtube.com/@somechannel"))
    @Test fun badId() = assertNull(YtLinkParser.parse("https://youtu.be/short"))
    @Test fun notYouTube() = assertNull(YtLinkParser.parse("https://www.instagram.com/p/ABC123/"))

    @Test fun routesYouTube() = assertEquals(LinkPlatform.YOUTUBE, Links.platformOf("https://youtu.be/$id"))
    @Test fun routesInstagram() = assertEquals(LinkPlatform.INSTAGRAM, Links.platformOf("https://www.instagram.com/reel/ABC123/"))
}
