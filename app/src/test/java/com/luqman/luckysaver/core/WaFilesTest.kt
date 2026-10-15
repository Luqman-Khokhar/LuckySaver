package com.luqman.luckysaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaFilesTest {
    @Test fun photo() = assertTrue(WaFiles.isMedia("A1B2C3.jpg", "image/jpeg"))
    @Test fun videoByMime() = assertTrue(WaFiles.isVideo("A1B2C3", "video/mp4"))
    @Test fun videoByExtension() = assertTrue(WaFiles.isVideo("A1B2C3.MP4", null))
    @Test fun nomedia() = assertFalse(WaFiles.isMedia(".nomedia", null))
    @Test fun tempFile() = assertFalse(WaFiles.isMedia("A1B2C3.tmp", "application/octet-stream"))
    @Test fun mimeFallback() = assertEquals("image/webp", WaFiles.mimeOf("x.webp", "application/octet-stream"))

    @Test fun statusFolder() = assertTrue(
        WaFiles.isStatusFolder("primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses", WaApp.WHATSAPP),
    )
    @Test fun businessFolder() = assertTrue(
        WaFiles.isStatusFolder("primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses/", WaApp.BUSINESS),
    )
    @Test fun parentFolderRejected() = assertFalse(
        WaFiles.isStatusFolder("primary:Android/media/com.whatsapp/WhatsApp/Media", WaApp.WHATSAPP),
    )
    @Test fun wrongAppRejected() = assertFalse(
        WaFiles.isStatusFolder("primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses", WaApp.BUSINESS),
    )

    @Test fun ofPackage() = assertEquals(WaApp.BUSINESS, WaApp.ofPackage("com.whatsapp.w4b"))
    @Test fun saveName() = assertEquals("WAB_A1B2.jpg", WaFiles.saveName(WaApp.BUSINESS, "A1B2.jpg"))
}
