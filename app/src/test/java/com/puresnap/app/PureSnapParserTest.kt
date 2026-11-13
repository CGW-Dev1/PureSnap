package com.puresnap.app

import com.google.gson.Gson
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.data.parser.DomesticExtPlatformParsers
import com.puresnap.app.data.parser.InternationalPlatformParsers
import com.puresnap.app.data.parser.MultiPlatformParserEngine
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.MediaStoreArchiver
import com.puresnap.app.util.MotionPhotoSynthesizer
import com.puresnap.app.util.ShareLinkExtractor
import org.junit.Assert.assertArrayEquals
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
        assertTrue(post.hasLivePhotos)
        assertEquals(1, post.livePhotoCount)
        assertEquals(1, post.mediaItems.size)
        val item = post.mediaItems[0]
        assertEquals(MediaKind.LIVE_PHOTO, item.kind)
        assertEquals(QualityBadge.LIVE, item.badge)
        assertEquals("https://ci.xiaohongshu.com/1040g00831livetrace?imageView2/2/w/0/format/png", item.originUrl)
        assertEquals("https://sns-video-bd.xhscdn.com/stream/live_disney_h265.mp4", item.liveVideoUrl)
        val candidates = item.orderedDownloadCandidates(preferRawUntouched = true)
        assertEquals("https://ci.xiaohongshu.com/1040g00831livetrace", candidates.first())
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
        assertTrue(post.hasLivePhotos)
        assertEquals(1, post.mediaItems.size)
        val item = post.mediaItems[0]
        assertEquals(MediaKind.LIVE_PHOTO, item.kind)
        assertEquals(QualityBadge.LIVE, item.badge)
        assertEquals("https://p3.douyinpic.com/sunset_display.jpeg", item.originUrl)
        assertTrue(item.liveVideoUrl.orEmpty().contains("/play/"))
    }

    @Test
    fun testDouyinRealLivePhotoAwemeDetailRootVideoExtraction() {
        // 抖音图集带全局 BGM 时，每张图不附带独立 video 轨，绝不误判为实况图
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
        assertEquals("抖音·图集", post!!.postTypeLabel)
        assertEquals("许知意", post.authorName)
        assertEquals(2, post.mediaItems.size)
        assertFalse(post.hasLivePhotos)

        for (item in post.mediaItems) {
            assertEquals(MediaKind.IMAGE, item.kind)
            assertEquals(QualityBadge.RAW, item.badge)
        }
    }

    @Test
    fun testWeiboStatusPicInfosAndLivePhoto() {
        assertEquals(
            "https://wx3.sinaimg.cn/large/001abcgy1h2z.jpg",
            DomesticExtPlatformParsers.rewriteWeiboOriginUrl("https://wx3.sinaimg.cn/mw2000/001abcgy1h2z.jpg")
        )
        val weiboJson = """
            {
              "ok": 1,
              "data": {
                "idstr": "501234567890",
                "text_raw": "微博实况与原图测试 #风景#",
                "user": { "screen_name": "微博摄影君", "avatar_hd": "https://tvax1.sinaimg.cn/avatar.jpg" },
                "pic_ids": ["picA", "picB"],
                "pic_infos": {
                  "picA": {
                    "type": "pic",
                    "largest": { "url": "https://wx1.sinaimg.cn/large/picA.jpg", "width": 3000, "height": 4000 }
                  },
                  "picB": {
                    "type": "livephoto",
                    "original": { "url": "https://wx2.sinaimg.cn/mw2000/picB.jpg", "width": 2000, "height": 2666 },
                    "video": "https://video.weibo.com/live/picB.mov"
                  }
                }
              }
            }
        """.trimIndent()
        val post = DomesticExtPlatformParsers.parseWeiboStatusJson(
            rawInput = "https://weibo.com/12345/501234567890",
            cleanUrl = "https://weibo.com/12345/501234567890",
            finalUrl = "https://weibo.com/12345/501234567890",
            jsonStr = weiboJson,
            workId = "501234567890"
        )
        assertNotNull(post)
        assertEquals(PlatformType.WEIBO, post!!.platform)
        assertEquals("微博摄影君", post.authorName)
        assertEquals(2, post.mediaItems.size)
        assertEquals(MediaKind.IMAGE, post.mediaItems[0].kind)
        assertEquals("https://wx1.sinaimg.cn/large/picA.jpg", post.mediaItems[0].originUrl)
        assertEquals(MediaKind.LIVE_PHOTO, post.mediaItems[1].kind)
        assertEquals("https://wx2.sinaimg.cn/large/picB.jpg", post.mediaItems[1].originUrl)
        assertEquals("https://video.weibo.com/live/picB.mov", post.mediaItems[1].liveVideoUrl)
    }

    @Test
    fun testBilibiliVideoAndDynamicParsing() {
        assertEquals(
            "https://i0.hdslb.com/bfs/new_dyn/demo.jpg",
            DomesticExtPlatformParsers.rewriteBilibiliOriginUrl("//i0.hdslb.com/bfs/new_dyn/demo.jpg@1080w_1e.webp")
        )
        val viewJson = """
            {
              "code": 0,
              "data": {
                "bvid": "BV1xx411c7mD",
                "title": "B站测试高清视频",
                "desc": "原画测试",
                "pic": "http://i0.hdslb.com/bfs/archive/cover.jpg@600w_400h.webp",
                "duration": 42,
                "dimension": { "width": 1920, "height": 1080 },
                "owner": { "name": "B站UP主", "face": "https://i0.hdslb.com/bfs/face/up.jpg" }
              }
            }
        """.trimIndent()
        val playJson = """
            {
              "code": 0,
              "data": {
                "durl": [{ "url": "https://upos-sz-mirror.bilivideo.com/upgcxcode/demo.mp4" }]
              }
            }
        """.trimIndent()
        val videoPost = DomesticExtPlatformParsers.parseBilibiliVideoJson(
            rawInput = "https://www.bilibili.com/video/BV1xx411c7mD",
            finalUrl = "https://www.bilibili.com/video/BV1xx411c7mD",
            viewJsonStr = viewJson,
            playJsonStr = playJson
        )
        assertNotNull(videoPost)
        assertEquals(PlatformType.BILIBILI, videoPost!!.platform)
        assertEquals(2, videoPost.mediaItems.size)
        assertEquals(MediaKind.VIDEO, videoPost.mediaItems[0].kind)
        assertEquals("https://upos-sz-mirror.bilivideo.com/upgcxcode/demo.mp4", videoPost.mediaItems[0].originUrl)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", videoPost.mediaItems[1].originUrl)
    }

    @Test
    fun testZuiyouAndPipixiaJsonParsing() {
        val zuiyouJson = """
            {
              "data": {
                "post": {
                  "id": 99112233,
                  "content": "最右无水印原图测试",
                  "member": { "name": "快乐右友", "avatar": "556677" },
                  "imgs": [
                    { "id": "88001", "w": 1440, "h": 1920, "video": 0 }
                  ]
                }
              }
            }
        """.trimIndent()
        val zyPost = DomesticExtPlatformParsers.parseZuiyouJson(
            rawInput = "https://share.izuiyou.com/hybrid/share/post?pid=99112233",
            finalUrl = "https://share.izuiyou.com/hybrid/share/post?pid=99112233",
            jsonStr = zuiyouJson,
            workId = "99112233"
        )
        assertNotNull(zyPost)
        assertEquals(PlatformType.ZUIYOU, zyPost!!.platform)
        assertEquals("https://file.izuiyou.com/img/view/id/88001", zyPost.mediaItems[0].originUrl)

        val ppxJson = """
            {
              "data": {
                "data": {
                  "item": {
                    "item_id_str": "7123456789",
                    "content": "皮皮虾去水印测试",
                    "author": { "name": "皮皮虾达人", "avatar": { "url_list": [{ "url": "https://p1.pstatp.com/avatar.jpg" }] } },
                    "origin_video_download": { "url_list": [{ "url": "https://v3.pipix.com/nowm/video.mp4" }] },
                    "cover": { "url_list": [{ "url": "https://p1.pipix.com/cover.jpg" }] },
                    "video": { "width": 1080, "height": 1920 }
                  }
                }
              }
            }
        """.trimIndent()
        val ppxPost = DomesticExtPlatformParsers.parsePipixiaJson(
            rawInput = "https://h5.pipix.com/s/demo",
            finalUrl = "https://h5.pipix.com/item/7123456789",
            jsonStr = ppxJson,
            workId = "7123456789"
        )
        assertNotNull(ppxPost)
        assertEquals(PlatformType.PIPIXIA, ppxPost!!.platform)
        assertEquals(MediaKind.VIDEO, ppxPost.mediaItems[0].kind)
        assertEquals("https://v3.pipix.com/nowm/video.mp4", ppxPost.mediaItems[0].originUrl)
    }

    @Test
    fun testTwitterAndPinterestParsersAndRewrites() {
        assertEquals(
            "https://pbs.twimg.com/media/GhTest123?format=jpg&name=orig",
            InternationalPlatformParsers.rewriteTwitterOrigUrl("https://pbs.twimg.com/media/GhTest123?format=jpg&name=small")
        )
        assertEquals(
            "https://i.pinimg.com/originals/ab/cd/ef/demo_pin.jpg",
            InternationalPlatformParsers.rewritePinterestOriginUrl("https://i.pinimg.com/736x/ab/cd/ef/demo_pin.jpg")
        )

        val fxJson = """
            {
              "code": 200,
              "tweet": {
                "text": "Sample X post #photography",
                "author": { "name": "Alice", "screen_name": "alice_x", "avatar_url": "https://pbs.twimg.com/profile/a.jpg" },
                "media": {
                  "all": [
                    { "type": "photo", "url": "https://pbs.twimg.com/media/Sample1.jpg?name=large", "width": 2048, "height": 1365 },
                    { "type": "video", "url": "https://video.twimg.com/ext_tw_video/1/pu/vid/1080x1920/demo.mp4", "thumbnail_url": "https://pbs.twimg.com/ext_tw_video_thumb/1/thumb.jpg", "width": 1080, "height": 1920 }
                  ]
                }
              }
            }
        """.trimIndent()
        val twPost = InternationalPlatformParsers.parseFxTwitterJson(
            rawInput = "https://x.com/alice_x/status/1888888888",
            finalUrl = "https://x.com/alice_x/status/1888888888",
            jsonStr = fxJson,
            statusId = "1888888888"
        )
        assertNotNull(twPost)
        assertEquals(PlatformType.TWITTER, twPost!!.platform)
        assertEquals(2, twPost.mediaItems.size)
        assertTrue(twPost.mediaItems[0].originUrl.contains("name=orig"))
        assertEquals(MediaKind.VIDEO, twPost.mediaItems[1].kind)

        val pwsJson = """
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "987654321": {
                      "id": "987654321",
                      "title": "Nordic Design Pin",
                      "images": {
                        "orig": { "url": "https://i.pinimg.com/originals/11/22/33/orig_pin.png", "width": 2400, "height": 3200 }
                      },
                      "pinner": { "full_name": "DesignStudio", "username": "design_studio" }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val pinPost = InternationalPlatformParsers.parsePinterestPwsJson(
            rawInput = "https://www.pinterest.com/pin/987654321/",
            finalUrl = "https://www.pinterest.com/pin/987654321/",
            jsonStr = pwsJson,
            pinId = "987654321"
        )
        assertNotNull(pinPost)
        assertEquals(PlatformType.PINTEREST, pinPost!!.platform)
        assertEquals("https://i.pinimg.com/originals/11/22/33/orig_pin.png", pinPost.mediaItems[0].originUrl)
    }

    @Test
    fun testTikTokInstagramYouTubeRedditParsers() {
        val tikWmJson = """
            {
              "code": 0,
              "data": {
                "id": "74123456789",
                "title": "Tokyo Neon Night #tokyo",
                "music": "https://sf16.tiktokcdn-us.com/bgm.mp3",
                "author": { "nickname": "TokyoCam", "unique_id": "tokyocam" },
                "images": [
                  "https://p16-sign.tiktokcdn.com/photo1.jpeg",
                  "https://p16-sign.tiktokcdn.com/photo2.jpeg"
                ]
              }
            }
        """.trimIndent()
        val ttPost = InternationalPlatformParsers.parseTikWmJson(
            rawInput = "https://www.tiktok.com/@tokyocam/photo/74123456789",
            finalUrl = "https://www.tiktok.com/@tokyocam/photo/74123456789",
            jsonStr = tikWmJson
        )
        assertNotNull(ttPost)
        assertEquals(PlatformType.TIKTOK, ttPost!!.platform)
        assertEquals(2, ttPost.mediaItems.size)
        assertNotNull(ttPost.bgmAudio)

        val igJson = """
            {
              "shortcode_media": {
                "shortcode": "C9demoIG",
                "owner": { "username": "ig_artist" },
                "edge_media_to_caption": { "edges": [{ "node": { "text": "Sunset carousel #ig" } }] },
                "edge_sidecar_to_children": {
                  "edges": [
                    { "node": { "is_video": false, "display_url": "https://scontent.cdninstagram.com/v/ig1.jpg", "dimensions": { "width": 1080, "height": 1350 } } },
                    { "node": { "is_video": true, "video_url": "https://scontent.cdninstagram.com/v/ig2.mp4", "display_url": "https://scontent.cdninstagram.com/v/ig2.jpg", "dimensions": { "width": 1080, "height": 1920 } } }
                  ]
                }
              }
            }
        """.trimIndent()
        val igPost = InternationalPlatformParsers.parseInstagramHtmlOrJson(
            rawInput = "https://www.instagram.com/p/C9demoIG/",
            finalUrl = "https://www.instagram.com/p/C9demoIG/",
            body = igJson,
            workId = "C9demoIG"
        )
        assertNotNull(igPost)
        assertEquals(PlatformType.INSTAGRAM, igPost!!.platform)
        assertEquals(2, igPost.mediaItems.size)
        assertEquals(MediaKind.IMAGE, igPost.mediaItems[0].kind)
        assertEquals(MediaKind.VIDEO, igPost.mediaItems[1].kind)

        val ytJson = """
            {
              "videoDetails": {
                "videoId": "dQw4w9WgXcQ",
                "title": "Sample YouTube 1080p",
                "author": "CreatorChannel",
                "lengthSeconds": "95"
              },
              "streamingData": {
                "formats": [
                  { "mimeType": "video/mp4; codecs=\"avc1\"", "url": "https://rr1---sn.googlevideo.com/videoplayback?itag=22", "width": 1280, "height": 720 }
                ]
              }
            }
        """.trimIndent()
        val ytPost = InternationalPlatformParsers.parseYouTubePlayerJson(
            rawInput = "https://youtu.be/dQw4w9WgXcQ",
            finalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            jsonStr = ytJson,
            fallbackVideoId = "dQw4w9WgXcQ"
        )
        assertNotNull(ytPost)
        assertEquals(PlatformType.YOUTUBE, ytPost!!.platform)
        assertEquals(MediaKind.VIDEO, ytPost.mediaItems[0].kind)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", ytPost.mediaItems[1].originUrl)

        val redditJson = """
            [
              {
                "data": {
                  "children": [
                    {
                      "data": {
                        "id": "r8899",
                        "title": "Wallpapers Gallery",
                        "author": "wall_op",
                        "subreddit_name_prefixed": "r/wallpapers",
                        "gallery_data": { "items": [{ "media_id": "img1" }, { "media_id": "img2" }] },
                        "media_metadata": {
                          "img1": { "s": { "u": "https://i.redd.it/wall1.png", "x": 3840, "y": 2160 } },
                          "img2": { "s": { "u": "https://i.redd.it/wall2.jpg", "x": 2560, "y": 1440 } }
                        }
                      }
                    }
                  ]
                }
              }
            ]
        """.trimIndent()
        val rdPost = InternationalPlatformParsers.parseRedditPostJson(
            rawInput = "https://www.reddit.com/r/wallpapers/comments/r8899/wallpapers_gallery/",
            finalUrl = "https://www.reddit.com/r/wallpapers/comments/r8899/wallpapers_gallery/",
            jsonStr = redditJson
        )
        assertNotNull(rdPost)
        assertEquals(PlatformType.REDDIT, rdPost!!.platform)
        assertEquals(2, rdPost.mediaItems.size)
        assertEquals("https://i.redd.it/wall1.png", rdPost.mediaItems[0].originUrl)
    }

    @Test
    fun testBatchUrlExtractionAndPlatformDetection() {
        val mixed = """
            第一条 https://xhslink.com/a/abc123 ，
            第二条 https://www.instagram.com/p/C9test/ ，
            第三条 https://x.com/user/status/1234567890 ，
            第四条 https://www.reddit.com/r/pics/comments/abc/title/ ，
            直链 https://cdn.example.com/raw/photo.png?token=1
        """.trimIndent()

        val list = ShareLinkExtractor.extractAllUrls(mixed)
        assertEquals(5, list.size)
        assertEquals(PlatformType.XIAOHONGSHU, list[0].platform)
        assertEquals(PlatformType.INSTAGRAM, list[1].platform)
        assertEquals(PlatformType.TWITTER, list[2].platform)
        assertEquals(PlatformType.REDDIT, list[3].platform)
        assertEquals(PlatformType.UNKNOWN, list[4].platform)
        assertTrue(ShareLinkExtractor.isDirectMediaUrl("https://cdn.example.com/raw/photo.png?token=1"))
    }

    @Test
    fun testMotionPhotoSynthesisSplitAndMp4FreeSalt() {
        // 最小合法 JPEG：SOI + APP0 + SOS/payload + EOI
        val fakeJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            0x11, 0x22, 0x33, 0x44,
            0xFF.toByte(), 0xD9.toByte()
        )
        // 构建 > 1024 字节的伪 MP4 容器（ftypmp42 + mdat）
        val mp4Header = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
        )
        val fakeMp4 = mp4Header + ByteArray(1400) { (it % 251).toByte() }

        val mpPlain = MotionPhotoSynthesizer.synthesizeMotionPhoto(fakeJpeg, fakeMp4, modifyMd5 = false)
        val mpSalt = MotionPhotoSynthesizer.synthesizeMotionPhoto(fakeJpeg, fakeMp4, modifyMd5 = true)

        assertNotEquals(MediaStoreArchiver.computeMd5Hex(mpPlain), MediaStoreArchiver.computeMd5Hex(mpSalt))

        val splitPlain = MotionPhotoSynthesizer.splitMotionPhoto(mpPlain)
        val splitSalt = MotionPhotoSynthesizer.splitMotionPhoto(mpSalt)

        assertNotNull(splitPlain)
        assertNotNull(splitSalt)
        assertArrayEquals(fakeMp4, splitPlain!!.videoBytes)
        assertArrayEquals(fakeMp4, splitSalt!!.videoBytes)
        assertTrue(splitSalt.jpegBytes[0] == 0xFF.toByte() && splitSalt.jpegBytes[1] == 0xD8.toByte())

        // 验证 ISO BMFF 'free' box MD5 盐块头结构
        val freeBox = MediaStoreArchiver.createMp4FreeBoxSalt()
        assertTrue(freeBox.size > 12)
        assertEquals('f'.code.toByte(), freeBox[4])
        assertEquals('r'.code.toByte(), freeBox[5])
        assertEquals('e'.code.toByte(), freeBox[6])
        assertEquals('e'.code.toByte(), freeBox[7])
    }

    @Test
    fun testDouyinImagePrefersCleanUrlOverWatermarkedDownloadUrlList() {
        val sampleHtml = """
            <html><body><script>
            window._ROUTER_DATA = {"loaderData":{"note_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7707635579504379300","desc":"蛙不淋雨的图文作品","author":{"nickname":"蛙不淋雨","unique_id":"49340948830"},"images":[{"width":1440,"height":2158,"download_url_list":["https://p3-sign.douyinpic.com/tos-cn-i-0813/img0_water_id.jpeg?lk3s=1","https://p3-sign.douyinpic.com/tos-cn-i-0813/img0~tplv-dy-water-v2:49340948830.jpeg"],"url_list":["https://p3-sign.douyinpic.com/tos-cn-i-0813/img0~tplv-dy-aweme-images:q75.webp","https://p9-sign.douyinpic.com/tos-cn-i-0813/img0~tplv-dy-aweme-images:q75.jpeg"]},{"width":1440,"height":2158,"download_image":{"url_list":["https://p3-sign.douyinpic.com/tos-cn-i-0813/img1_watermarked.jpeg"]},"display_image":{"url_list":["https://p3-sign.douyinpic.com/tos-cn-i-0813/img1~tplv-dy-aweme-images:q75.webp","https://p3-sign.douyinpic.com/tos-cn-i-0813/img1~tplv-dy-aweme-images:q75.jpeg"]}}]}]}}}};
            </script></body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.iesdouyin.com/share/note/7707635579504379300/",
            html = sampleHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals(2, post!!.mediaItems.size)

        val item0 = post.mediaItems[0]
        assertEquals("https://p9-sign.douyinpic.com/tos-cn-i-0813/img0~tplv-dy-aweme-images:q75.jpeg", item0.originUrl)
        assertFalse(item0.originUrl.contains("water"))
        assertTrue(item0.fallbackUrls.none { it.contains("img0_water_id") || it.contains("tplv-dy-water") })

        val item1 = post.mediaItems[1]
        assertEquals("https://p3-sign.douyinpic.com/tos-cn-i-0813/img1~tplv-dy-aweme-images:q75.jpeg", item1.originUrl)
        assertTrue(item1.fallbackUrls.none { it.contains("img1_watermarked") })
    }

    @Test
    fun testDouyinDesktopPacePayloadPrefersCleanUrlListAndStripsWatermarks() {
        val innerJsonPayload = """
            8:["$","${'$'}La",null,{"awemeId":"7707635579504379300","aweme":{"statusCode":0,"detail":{"awemeId":"7707635579504379300","desc":"🧜🏻‍♀️","authorInfo":{"uid":"1524397465942115","nickname":"蛙不淋雨","avatarUri":"https://p3-pc.douyinpic.com/aweme/100x100/avatar.jpeg"},"images":[{"width":2160,"height":3238,"uri":"tos-cn-i-0813/oIEA3iqB4CABAOBEIfn0ANAgiUBO7e8Q1DLA9a","urlList":["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oIEA3iqB~tplv-dy-aweme-images:q75.webp?x=1","https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oIEA3iqB~tplv-dy-aweme-images:q75.jpeg?x=1"],"downloadUrlList":["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oIEA3iqB~tplv-dy-water-v2:5oqW6Z-z5Y-377yaNDkzNDA5NDg4MzA=:2160:3238.jpeg?x=1"]}]}}}]
        """.trimIndent()
        val escapedLiteral = Gson().toJson(innerJsonPayload)
        val paceHtml = """
            <html><head><title>🧜🏻‍♀️ - 抖音</title></head><body>
            <script>self.__pace_f.push([1,$escapedLiteral])</script>
            </body></html>
        """.trimIndent()

        val post = MultiPlatformParserEngine.parseFromWebViewHtml(
            url = "https://www.douyin.com/note/7707635579504379300",
            html = paceHtml,
            preferences = UserPreferencesState()
        )
        assertNotNull(post)
        assertEquals("蛙不淋雨", post!!.authorName)
        assertEquals(1, post.mediaItems.size)

        val item = post.mediaItems.first()
        assertEquals(2160, item.width)
        assertEquals(3238, item.height)
        assertEquals(QualityBadge.FOUR_K, item.badge)
        assertTrue(item.originUrl.contains("tplv-dy-aweme-images:q75.jpeg"))
        assertFalse(MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(item.originUrl))
        assertTrue(item.fallbackUrls.none { MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(it) })
    }

    @Test
    fun testDouyinHistorySanitizationRemovesWatermarkedUrls() {
        val staleItems = listOf(
            MediaItem(
                id = "dy_img_0",
                index = 0,
                kind = MediaKind.IMAGE,
                badge = QualityBadge.RAW,
                originUrl = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/abc~tplv-dy-water-v2:5oqW6Z-z5Y-377yaNDkzNDA5NDg4MzA=.jpeg",
                previewUrl = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/abc~tplv-dy-water-v2:5oqW6Z-z5Y-377yaNDkzNDA5NDg4MzA=.jpeg",
                fallbackUrls = listOf(
                    "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/abc~tplv-dy-water-v2:5oqW6Z-z5Y-377yaNDkzNDA5NDg4MzA=.jpeg",
                    "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/abc~tplv-dy-aweme-images:q75.webp",
                    "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/abc~tplv-dy-aweme-images:q75.jpeg"
                )
            )
        )
        val staleEntity = ParseHistoryEntity(
            postId = "post_7707635579504379300",
            sourceShareText = "https://v.douyin.com/iUv-ObAMlZ4/",
            extractedUrl = "https://v.douyin.com/iUv-ObAMlZ4/",
            noteId = "7707635579504379300",
            platform = PlatformType.DOUYIN.name,
            postTypeLabel = "抖音·图集",
            authorName = "蛙不淋雨",
            authorHandle = "@49340948830",
            authorAvatarUrl = "",
            coverUrl = staleItems.first().previewUrl,
            title = "🧜🏻‍♀️",
            content = "🧜🏻‍♀️",
            tagsJson = "[]",
            mediaItemsJson = Gson().toJson(staleItems),
            bgmAudioJson = null,
            mediaCount = 1,
            downloadedOnce = true,
            parsedAtMillis = 1794577000000L
        )

        val sanitized = MultiPlatformParserEngine.fromHistoryEntity(staleEntity)
        val restoredItem = sanitized.mediaItems.first()
        assertEquals("https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/abc~tplv-dy-aweme-images:q75.jpeg", restoredItem.originUrl)
        assertFalse(MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(restoredItem.originUrl))
        assertFalse(MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(sanitized.coverUrl))
        assertTrue(restoredItem.fallbackUrls.none { MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(it) })
    }
}
