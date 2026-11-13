package com.puresnap.app

import android.app.Application
import com.puresnap.app.data.local.PureSnapDatabase
import com.puresnap.app.data.preferences.AppPreferencesRepository
import com.puresnap.app.util.DynamicMediaRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy

class PureSnapApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 延迟懒加载数据库与偏好仓库，彻底降低冷启动主线程阻塞耗时
    val database: PureSnapDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PureSnapDatabase.getInstance(this)
    }

    val preferencesRepository: AppPreferencesRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppPreferencesRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 开启 HTTP 连接池复用以加速多图并发提取与 CDN 拉取
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "16")

        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }

        // 后台异步预热数据库、偏好设置与自动清理过期缓存，零阻塞冷启动首帧渲染
        appScope.launch {
            preferencesRepository
            database.pureSnapDao()
            DynamicMediaRenderer.pruneDiskCache(this@PureSnapApplication)
        }
    }
}
