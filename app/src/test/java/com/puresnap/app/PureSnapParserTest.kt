package com.puresnap.app

import com.puresnap.app.data.parser.MultiPlatformParserEngine
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.PlatformType
import com.puresnap.app.util.MediaStoreArchiver
import com.puresnap.app.util.ShareLinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureSnapParserTest {

    @Test
    fun testXiaohongshuShareCommandExtraction() {
        val raw = "68 摄影师阿泽发布了一篇小红书笔记，快来看吧！ 😆 秋日北海道街拍原片直出 😆 https://xhslink.com/a/hokkaido2026，复制本条消息，打开【小红书】App查看精彩内容！"
        val res = ShareLinkExtractor.extract(raw)
        assertEquals(PlatformType.XIAOHONGSHU, res.platform)
        assertEquals("https://xhslink.com/a/hokkaido2026", res.cleanUrl)
        assertFalse(res.isPrivateOrDeletedHint)
    }

    @Test
    fun testDouyinShareCommandExtraction() {
        val raw = "7.92 萌萌 复制此链接， https://v.douyin.com/iPureSnap8899/ 打开Dou音搜索！"
        val res = ShareLinkExtractor.extract(raw)
        assertEquals(PlatformType.DOUYIN, res.platform)
        assertEquals("https://v.douyin.com/iPureSnap8899/", res.cleanUrl)
    }

    @Test
    fun testLosslessUrlRewritingRules() {
        // 1. 小红书去水印与无损 CDN 拼接（剥离时间戳哈希前缀与 !nd_dft 裁剪后缀）
        val xhsRaw = "http://sns-webpic-qc.xhscdn.com/202610251120/0123456789abcdef0123456789abcdef/spectrum/1040g00831_key!nd_dft_wlteh_webp_3"
        val xhsLossless = MultiPlatformParserEngine.rewriteToLosslessOriginUrl(xhsRaw, PlatformType.XIAOHONGSHU)
        assertEquals("https://ci.xiaohongshu.com/spectrum/1040g00831_key?imageView2/2/w/0/format/png", xhsLossless)

        // 2. 抖音 playwm 替换为 play 并指定 ratio=1080p
        val dyRaw = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200fg10000&line=0"
        val dyLossless = MultiPlatformParserEngine.rewriteToLosslessOriginUrl(dyRaw, PlatformType.DOUYIN)
        assertFalse(dyLossless.contains("playwm"))
        assertTrue(dyLossless.contains("/play/"))
        assertTrue(dyLossless.contains("ratio=1080p"))

        // 3. 最右 watermarked 替换为 origin
        val zyRaw = "https://tbfile.izuiyou.com/img/view/id/998877/watermarked"
        val zyLossless = MultiPlatformParserEngine.rewriteToLosslessOriginUrl(zyRaw, PlatformType.ZUIYOU)
        assertFalse(zyLossless.contains("watermarked"))
        assertTrue(zyLossless.contains("origin"))
    }

    @Test
    fun testDouyinRouterDataRealHtmlExtraction() {
        val sampleHtml = """
            <html><body><script>
            window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7429001122334455667","desc":"真实秋日风景实拍 #旅行 #风景","author":{"nickname":"测试摄影师","unique_id":"photo_test","avatar_larger":{"url_list":["https://p3.douyinpic.com/avatar.jpeg"]}},"video":{"width":1080,"height":1920,"play_addr":{"uri":"v0200fg10000test","url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200fg10000test&line=0"]},"origin_cover":{"url_list":["https://p3.douyinpic.com/real_cover.jpeg"]}}}]}}}};
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.iesdouyin.com/share/video/7429001122334455667/",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("测试摄影师", post!!.authorName)
        assertEquals("真实秋日风景实拍 #旅行 #风景", post.content)
        assertEquals(1, post.mediaItems.size)
        assertEquals("https://p3.douyinpic.com/real_cover.jpeg", post.mediaItems[0].previewUrl)
        assertTrue(post.mediaItems[0].originUrl.contains("/play/"))
    }

    @Test
    fun testXiaohongshuInitialStateRealHtmlExtraction() {
        val sampleHtml = """
            <html><body><script>
            window.__INITIAL_STATE__={"noteData":{"data":{"noteData":{"noteId":"671000112233","type":"normal","title":"北海道真实游记","desc":"原图无滤镜分享","user":{"nickname":"阿泽","avatar":"https://sns-avatar.xhscdn.com/avatar.jpg"},"imageList":[{"traceId":"1040g00831realtrace","width":3024,"height":4032,"livePhoto":false,"urlDefault":"http://sns-webpic-qc.xhscdn.com/202610251120/abcdef0123456789abcdef0123456789/1040g00831realtrace!nd_dft_wlteh_webp_3"}]}}}}
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.xiaohongshu.com/discovery/item/671000112233",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("阿泽", post!!.authorName)
        assertEquals("北海道真实游记", post.title)
        assertEquals(1, post.mediaItems.size)
        assertEquals("https://ci.xiaohongshu.com/1040g00831realtrace?imageView2/2/w/0/format/png", post.mediaItems[0].originUrl)
        assertTrue(post.mediaItems[0].previewUrl.startsWith("https://sns-webpic-qc.xhscdn.com/"))
    }

    @Test
    fun testKuaishouShareCommandExtraction() {
        val raw = "https://v.kuaishou.com/KqdE2rZN 我承认我是天使 只是偶尔有点坏 \"萌了个萌假发 \"草莓空想曲Lolita \"合照 该作品在快手被播放过"
        val res = ShareLinkExtractor.extract(raw)
        assertEquals(PlatformType.KUAISHOU, res.platform)
        assertEquals("https://v.kuaishou.com/KqdE2rZN", res.cleanUrl)
    }

    @Test
    fun testKuaishouInitStateRealHtmlExtraction() {
        val sampleHtml = """
            <html><body><script>
            window.INIT_STATE = {"demo_key":{"result":1,"photo":{"singlePicture":true,"width":3072,"height":4096,"caption":"我承认我是天使 只是偶尔有点坏 #草莓空想曲Lolita","userName":"快手摄影师","headUrl":"https://p22.a.yximgs.com/avatar.jpg","coverUrls":[{"cdn":"p5.a.yximgs.com","url":"https://p5.a.yximgs.com/upic/sample_photo.jpg?bp=10000"}]},"single":{"music":"/ufile/atlas/sample_music.m4a","musicCdnList":[{"cdn":"txmov2.a.kwimgs.com"}]}}};
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://v.m.chenzhongtech.com/fw/photo/3x3ix6nir8gdcay",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("快手摄影师", post!!.authorName)
        assertTrue(post.content.contains("我承认我是天使"))
        assertEquals(1, post.mediaItems.size)
        assertEquals(3072, post.mediaItems[0].width)
        assertEquals(4096, post.mediaItems[0].height)
        assertFalse(post.mediaItems[0].originUrl.contains("bp=10000"))
        assertNotNull(post.bgmAudio)
        assertEquals("https://txmov2.a.kwimgs.com/ufile/atlas/sample_music.m4a", post.bgmAudio!!.url)
    }

    @Test
    fun testMd5ModificationProducesDistinctHash() {
        val originalBytes = "PureSnapLosslessImageHeaderAndPayload".toByteArray(Charsets.UTF_8)
        val originalMd5 = MediaStoreArchiver.computeMd5Hex(originalBytes)
        val modifiedBytes = MediaStoreArchiver.appendMd5SaltBytes(originalBytes)
        val modifiedMd5 = MediaStoreArchiver.computeMd5Hex(modifiedBytes)

        assertNotEquals(originalMd5, modifiedMd5)
        assertTrue(modifiedBytes.size > originalBytes.size)
    }

    @Test
    fun testXiaohongshuLivePhotoExtraction() {
        val sampleHtml = """
            <html><body><script>
            window.__INITIAL_STATE__={"noteData":{"data":{"noteData":{"noteId":"672000112233","type":"normal","title":"迪士尼实况图","desc":"烟花实况好美","user":{"nickname":"迪士尼在逃公主","avatar":"https://sns-avatar.xhscdn.com/avatar2.jpg"},"imageList":[{"traceId":"1040g00831livetrace","width":3024,"height":4032,"livePhoto":true,"urlDefault":"http://sns-webpic-qc.xhscdn.com/202610251120/abcdef/1040g00831livetrace!nd_dft_wlteh_webp_3","stream":{"h265":[{"masterUrl":"https://sns-video-bd.xhscdn.com/stream/live_disney_h265.mp4"}]}}]}}}}
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.xiaohongshu.com/discovery/item/672000112233",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("小红书·实况图", post!!.postTypeLabel)
        assertEquals(1, post.mediaItems.size)
        val item = post.mediaItems[0]
        assertTrue("Item should be Live Photo", item.isLivePhoto)
        assertEquals("https://sns-video-bd.xhscdn.com/stream/live_disney_h265.mp4", item.liveVideoUrl)
        assertEquals("https://ci.xiaohongshu.com/1040g00831livetrace?imageView2/2/w/0/format/png", item.originUrl)
    }

    @Test
    fun testDouyinLivePhotoImagePostExtraction() {
        val sampleHtml = """
            <html><body><script>
            window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7430001122334455667","desc":"海边落日实况图文 #实况 #日落","author":{"nickname":"日落收藏家","unique_id":"sunset_live","avatar_larger":{"url_list":["https://p3.douyinpic.com/avatar3.jpeg"]}},"image_post_info":{"images":[{"display_image":{"url_list":["https://p3.douyinpic.com/sunset_display.jpeg"]},"live_photo":{"video":{"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200sunset_live&line=0"]}}}}]}}]}}}};
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.iesdouyin.com/share/video/7430001122334455667/",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("抖音·实况图", post!!.postTypeLabel)
        assertEquals(1, post.mediaItems.size)
        val item = post.mediaItems[0]
        assertTrue("Item should be Live Photo", item.isLivePhoto)
        assertNotNull(item.liveVideoUrl)
        assertTrue(item.liveVideoUrl!!.contains("/play/"))
        assertFalse(item.liveVideoUrl!!.contains("playwm"))
        assertEquals("https://p3.douyinpic.com/sunset_display.jpeg", item.originUrl)
    }

    @Test
    fun testDouyinRealLivePhotoAwemeDetailRootVideoExtraction() {
        // 抖音真实实况图文结构：is_live_photo=true，图片在 images 列表中，动态视频在根级 video.play_addr
        val sampleHtml = """
            <html><body><script>
            window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7700131720217898162","desc":">﹏<# 中华风lolita 实况图","is_live_photo":true,"author":{"nickname":"许知意","unique_id":"xuzhiyi","avatar_larger":{"url_list":["https://p3.douyinpic.com/avatar_xzy.jpeg"]}},"images":[{"display_image":{"url_list":["https://p3.douyinpic.com/photo1.jpeg"]},"width":1080,"height":1440},{"display_image":{"url_list":["https://p3.douyinpic.com/photo2.jpeg"]},"width":1080,"height":1440}],"video":{"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200xuzhiyi_live&line=0"]}}}]}}}};
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.iesdouyin.com/share/video/7700131720217898162/",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("抖音·实况图", post!!.postTypeLabel)
        assertEquals("许知意", post.authorName)
        assertEquals(2, post.mediaItems.size)

        for (item in post.mediaItems) {
            assertTrue("Every item in live photo post must be LIVE_PHOTO", item.isLivePhoto)
            assertNotNull(item.liveVideoUrl)
            assertTrue(item.liveVideoUrl!!.contains("/play/"))
            assertFalse(item.liveVideoUrl!!.contains("playwm"))
        }
    }

    @Test
    fun testMotionPhotoSynthesis() {
        // 构造最小合法 JPEG 字节流：SOI + APP0 + SOS/ImageData + EOI
        val mockJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), // APP0 len=16
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // JFIF payload
            0xFF.toByte(), 0xDA.toByte(), 0x00.toByte(), 0x08.toByte(), // SOS
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
            0xFF.toByte(), 0xD9.toByte()  // EOI
        )

        val mockMp4Video = "ftypmp42mock_douyin_live_video_stream_payload_bytes_for_testing".toByteArray(Charsets.UTF_8)

        val synthesized = com.puresnap.app.util.MotionPhotoSynthesizer.synthesizeMotionPhoto(mockJpeg, mockMp4Video)

        // 1. 验证 Motion Photo 标识
        assertTrue(com.puresnap.app.util.MotionPhotoSynthesizer.isMotionPhoto(synthesized))

        // 2. 验证末尾紧贴的是完整的 MP4 视频流
        val videoOffset = mockMp4Video.size
        val extractedVideo = synthesized.copyOfRange(synthesized.size - videoOffset, synthesized.size)
        assertTrue(mockMp4Video.contentEquals(extractedVideo))

        // 3. 验证 XMP 内容中包含 Google Camera / Android 标准实况元数据与偏移
        val synthesizedStr = String(synthesized, Charsets.ISO_8859_1)
        assertTrue(synthesizedStr.contains("Camera:MotionPhoto=\"1\""))
        assertTrue(synthesizedStr.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(synthesizedStr.contains("GCamera:MicroVideoOffset=\"${mockMp4Video.size}\""))
        assertTrue(synthesizedStr.contains("Container:Directory"))
        assertTrue(synthesizedStr.contains("Item:Semantic=\"MotionPhoto\""))
    }
}
