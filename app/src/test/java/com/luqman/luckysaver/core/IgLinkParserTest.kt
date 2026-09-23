package com.luqman.luckysaver.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IgLinkParserTest {
    @Test fun post() = assertEquals(IgLink.Post("DAbc_12-xYz"), IgLinkParser.parse("https://www.instagram.com/p/DAbc_12-xYz/?igsh=abc"))
    @Test fun reel() = assertEquals(IgLink.Post("C9q1"), IgLinkParser.parse("https://instagram.com/reel/C9q1/"))
    @Test fun reelsPlural() = assertEquals(IgLink.Post("C9q1"), IgLinkParser.parse("https://www.instagram.com/reels/C9q1"))
    @Test fun userScopedPost() = assertEquals(IgLink.Post("C9q1"), IgLinkParser.parse("https://www.instagram.com/natgeo/p/C9q1/"))
    @Test fun inShareText() = assertEquals(
        IgLink.Post("C9q1"), IgLinkParser.parse("Check this out! https://www.instagram.com/reel/C9q1/?igsh=MWx. cool"),
    )
    @Test fun story() = assertEquals(IgLink.Story("some.user", "3456"), IgLinkParser.parse("https://www.instagram.com/stories/some.user/3456/?utm_source=ig"))
    @Test fun storyNoPk() = assertEquals(IgLink.Story("some_user", null), IgLinkParser.parse("https://www.instagram.com/stories/some_user/"))
    @Test fun highlight() = assertEquals(IgLink.Highlight("17900"), IgLinkParser.parse("https://www.instagram.com/stories/highlights/17900/"))
    @Test fun profile() = assertEquals(IgLink.Profile("natgeo"), IgLinkParser.parse("https://www.instagram.com/natgeo/?hl=en"))
    @Test fun share() = assertEquals(IgLink.Share("https://www.instagram.com/share/reel/BAabc"), IgLinkParser.parse("https://www.instagram.com/share/reel/BAabc?x=1"))
    @Test fun reserved() = assertNull(IgLinkParser.parse("https://www.instagram.com/explore/"))
    @Test fun notInstagram() = assertNull(IgLinkParser.parse("https://example.com/p/abc"))
}

class ShortcodeTest {
    @Test fun roundTrip() {
        val id = "3456789012345678901"
        assertEquals(id, Shortcode.toMediaId(Shortcode.fromMediaId(id)))
    }
    @Test fun known() = assertEquals("BL3gDKJlf8c", Shortcode.fromMediaId(Shortcode.toMediaId("BL3gDKJlf8c")))
    @Test fun small() = assertEquals("65", Shortcode.toMediaId("BB"))
}

class IgJsonTest {
    @Test fun carousel() {
        val json = JSONObject(
            """
            {"pk":"1","code":"abc","media_type":8,"taken_at":10,"user":{"username":"u"},
             "carousel_media":[
               {"media_type":1,"image_versions2":{"candidates":[{"url":"s","width":320,"height":320},{"url":"L","width":1080,"height":1350}]}},
               {"media_type":2,"image_versions2":{"candidates":[{"url":"t","width":640,"height":640}]},
                "video_versions":[{"url":"v","width":720,"height":1280}]}
             ]}
            """
        )
        val items = IgJson.parseMedia(json)
        assertEquals(2, items.size)
        assertEquals("L", items[0].url)
        assertEquals(MediaKind.VIDEO, items[1].kind)
        assertEquals("v", items[1].url)
        assertEquals("t", items[1].thumbnailUrl)
        assertEquals("u_abc_1.mp4", items[1].fileName())
    }

    @Test fun reelsMap() {
        val json = JSONObject("""{"reels":{"9":{"user":{"username":"z"},"items":[{"pk":"5","media_type":1,"image_versions2":{"candidates":[{"url":"i","width":1,"height":1}]}}]}}}""")
        val nodes = IgJson.parseReels(json)
        assertEquals(1, nodes.size)
        assertEquals("z", IgJson.parseMedia(nodes[0]).single().owner)
    }
}
