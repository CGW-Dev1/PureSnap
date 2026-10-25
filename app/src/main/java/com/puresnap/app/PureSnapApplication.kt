package com.puresnap.app

import android.app.Application
import com.puresnap.app.data.local.PureSnapDatabase
import com.puresnap.app.data.preferences.AppPreferencesRepository
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy

class PureSnapApplication : Application() {

    lateinit var database: PureSnapDatabase
        private set

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // 全局启用 CookieManager，确保抖音/小红书/最右短链 302 重定向链自动携带会话 Cookie
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
        database = PureSnapDatabase.getInstance(this)
        preferencesRepository = AppPreferencesRepository(this)
    }
}
