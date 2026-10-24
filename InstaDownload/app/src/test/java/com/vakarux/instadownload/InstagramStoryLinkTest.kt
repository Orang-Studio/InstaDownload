package com.vakarux.instadownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramStoryLinkTest {
    @Test
    fun parsesSharedStoryUrlWithQueryParameters() {
        val link = InstagramStoryLinks.parse(
            "https://www.instagram.com/stories/creator.name/12345678901234567/?igsh=abc"
        )

        requireNotNull(link)
        assertEquals("creator.name", link.username)
        assertEquals("12345678901234567", link.mediaId)
        assertEquals(
            "https://www.instagram.com/stories/creator.name/12345678901234567/",
            link.url
        )
    }

    @Test
    fun parsesCompositeStoryMediaIds() {
        val link = InstagramStoryLinks.parse(
            "https://instagram.com/stories/creator/123_456/"
        )

        assertEquals("123_456", requireNotNull(link).mediaId)
    }

    @Test
    fun rejectsStoryUrlsWithoutAMediaId() {
        assertNull(InstagramStoryLinks.parse("https://www.instagram.com/stories/creator/"))
        assertNull(InstagramStoryLinks.parse("https://example.com/stories/creator/123"))
    }
}
