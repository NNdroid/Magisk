package com.topjohnwu.magisk.core

import android.os.Bundle
import androidx.core.content.edit
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.model.ColorMode
import com.topjohnwu.magisk.core.repository.DBConfig
import com.topjohnwu.magisk.core.repository.PreferenceConfig
import com.topjohnwu.magisk.core.utils.LocaleSetting
import kotlinx.coroutines.GlobalScope

object Config : PreferenceConfig, DBConfig {

    override val stringDB get() = ServiceLocator.stringDB
    override val settingsDB get() = ServiceLocator.settingsDB
    override val context get() = ServiceLocator.deContext
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override val coroutineScope get() = GlobalScope

    object Key {
        // db configs
        const val ROOT_ACCESS = "root_access"
        const val SU_MULTIUSER_MODE = "multiuser_mode"
        const val SU_MNT_NS = "mnt_ns"
        const val SU_BIOMETRIC = "su_biometric"
        const val ZYGISK = "zygisk"
        const val BOOTLOOP = "bootloop"
        const val SU_MANAGER = "requester"
        const val KEYSTORE = "keystore"

        // prefs
        const val SU_REQUEST_TIMEOUT = "su_request_timeout"
        const val SU_AUTO_RESPONSE = "su_auto_response"
        const val SU_NOTIFICATION = "su_notification"
        const val SU_REAUTH = "su_reauth"
        const val SU_TAPJACK = "su_tapjack"
        const val SU_RESTRICT = "su_restrict"
        const val CHECK_UPDATES = "check_update"
        const val RELEASE_CHANNEL = "release_channel"
        const val CUSTOM_CHANNEL = "custom_channel"
        const val LOCALE = "locale"
        const val DARK_THEME = "dark_theme_extended"
        const val COLOR_MODE = "color_mode"
        const val DOWNLOAD_DIR = "download_dir"
        const val SAFETY = "safety_notice"
        const val THEME_ORDINAL = "theme_ordinal"
        const val ASKED_HOME = "asked_home"
        const val DOH = "doh"
        const val RAND_NAME = "rand_name"

        // Android TV WebUI
        const val WEBUI_ENABLED = "tv_webui_enabled"
        const val WEBUI_PORT = "tv_webui_port"
        const val WEBUI_AUTH_MODE = "tv_webui_auth_mode"
        const val WEBUI_RANDOM_TOKEN = "tv_webui_random_token"
        const val WEBUI_CUSTOM_TOKEN = "tv_webui_custom_token"
        const val WEBUI_THEME = "tv_webui_theme"

        val NO_MIGRATION = setOf(ASKED_HOME, SU_REQUEST_TIMEOUT,
            SU_AUTO_RESPONSE, SU_REAUTH, SU_TAPJACK)
    }

    object OldValue {
        // Update channels
        const val DEFAULT_CHANNEL = -1
        const val STABLE_CHANNEL = 0
        const val BETA_CHANNEL = 1
        const val CUSTOM_CHANNEL = 2
        const val CANARY_CHANNEL = 3
        const val DEBUG_CHANNEL = 4
    }

    object Value {
        // Update channels
        const val DEFAULT_CHANNEL = -1
        const val STABLE_CHANNEL = 0
        const val BETA_CHANNEL = 1
        const val DEBUG_CHANNEL = 2
        const val CUSTOM_CHANNEL = 3

        // root access mode
        const val ROOT_ACCESS_DISABLED = 0
        const val ROOT_ACCESS_APPS_ONLY = 1
        const val ROOT_ACCESS_ADB_ONLY = 2
        const val ROOT_ACCESS_APPS_AND_ADB = 3

        // su multiuser
        const val MULTIUSER_MODE_OWNER_ONLY = 0
        const val MULTIUSER_MODE_OWNER_MANAGED = 1
        const val MULTIUSER_MODE_USER = 2

        // su mnt ns
        const val NAMESPACE_MODE_GLOBAL = 0
        const val NAMESPACE_MODE_REQUESTER = 1
        const val NAMESPACE_MODE_ISOLATE = 2

        // su notification
        const val NO_NOTIFICATION = 0
        const val NOTIFICATION_TOAST = 1
        const val NOTIFICATION_STATUS_BAR = 2

        // su auto response
        const val SU_PROMPT = 0
        const val SU_AUTO_DENY = 1
        const val SU_AUTO_ALLOW = 2

        // su timeout
        val TIMEOUT_LIST = longArrayOf(0, -1, 10, 20, 30, 60)

        // Android TV WebUI authentication
        const val WEBUI_AUTH_NONE = 0
        const val WEBUI_AUTH_RANDOM_TOKEN = 1
        const val WEBUI_AUTH_CUSTOM_TOKEN = 2

        // Android TV WebUI theme
        const val WEBUI_THEME_SYSTEM = 0
        const val WEBUI_THEME_LIGHT = 1
        const val WEBUI_THEME_DARK = 2
    }

    @JvmField var keepVerity = false
    @JvmField var keepEnc = false
    @JvmField var recovery = false
    var denyList = false

    var askedHome by preference(Key.ASKED_HOME, false)
    var bootloop by dbSettings(Key.BOOTLOOP, 0)

    var safetyNotice by preference(Key.SAFETY, true)
    var darkTheme by preference(Key.DARK_THEME, -1)
    var themeOrdinal by preference(Key.THEME_ORDINAL, 0)
    var colorMode by preference(Key.COLOR_MODE, ColorMode.MONET_SYSTEM.value)

    private var checkUpdatePrefs by preference(Key.CHECK_UPDATES, true)
    private var localePrefs by preference(Key.LOCALE, "")
    var doh by preference(Key.DOH, false)
    var updateChannel by preference(Key.RELEASE_CHANNEL, Value.DEFAULT_CHANNEL)
    val updateChannelIndex get() = when (updateChannel) {
        Value.DEFAULT_CHANNEL ->
            if (BuildConfig.DEBUG) Value.DEBUG_CHANNEL else Value.STABLE_CHANNEL
        else -> updateChannel
    }
    var customChannelUrl by preference(Key.CUSTOM_CHANNEL, "")
    var downloadDir by preference(Key.DOWNLOAD_DIR, "")
    var randName by preference(Key.RAND_NAME, true)

    var webUiEnabled by preference(Key.WEBUI_ENABLED, true)
    var webUiPort by preference(Key.WEBUI_PORT, 18091)
    var webUiAuthMode by preference(Key.WEBUI_AUTH_MODE, Value.WEBUI_AUTH_RANDOM_TOKEN)
    var webUiRandomToken by preference(Key.WEBUI_RANDOM_TOKEN, "")
    var webUiCustomToken by preference(Key.WEBUI_CUSTOM_TOKEN, "")
    var webUiTheme by preference(Key.WEBUI_THEME, Value.WEBUI_THEME_SYSTEM)

    var checkUpdate
        get() = checkUpdatePrefs
        set(value) {
            if (checkUpdatePrefs != value) {
                checkUpdatePrefs = value
                JobService.schedule(AppContext)
            }
        }
    var locale
        get() = localePrefs
        set(value) {
            localePrefs = value
            LocaleSetting.instance.setLocale(value)
        }

    var zygisk by dbSettings(Key.ZYGISK, Info.isEmulator)
    var suManager by dbStrings(Key.SU_MANAGER, "", true)
    var keyStoreRaw by dbStrings(Key.KEYSTORE, "", true)

    var suDefaultTimeout by preferenceStrInt(Key.SU_REQUEST_TIMEOUT, 10)
    var suAutoResponse by preferenceStrInt(Key.SU_AUTO_RESPONSE, Value.SU_PROMPT)
    var suNotification by preferenceStrInt(Key.SU_NOTIFICATION, Value.NOTIFICATION_TOAST)
    var rootMode by dbSettings(Key.ROOT_ACCESS, Value.ROOT_ACCESS_APPS_AND_ADB)
    var suMntNamespaceMode by dbSettings(Key.SU_MNT_NS, Value.NAMESPACE_MODE_REQUESTER)
    var suMultiuserMode by dbSettings(Key.SU_MULTIUSER_MODE, Value.MULTIUSER_MODE_OWNER_ONLY)
    private var suBiometric by dbSettings(Key.SU_BIOMETRIC, false)
    var suAuth
        get() = Info.isDeviceSecure && suBiometric
        set(value) {
            suBiometric = value
        }
    var suReAuth by preference(Key.SU_REAUTH, false)
    var suTapjack by preference(Key.SU_TAPJACK, true)
    var suRestrict by preference(Key.SU_RESTRICT, false)

    private const val SU_FINGERPRINT = "su_fingerprint"
    private const val UPDATE_CHANNEL = "update_channel"

    fun toBundle(): Bundle {
        val map = prefs.all - Key.NO_MIGRATION
        return Bundle().apply {
            for ((key, value) in map) {
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun fromBundle(bundle: Bundle) {
        val keys = bundle.keySet().apply { removeAll(Key.NO_MIGRATION) }
        prefs.edit {
            for (key in keys) {
                when (val value = bundle.get(key)) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
    }

    fun init(bundle: Bundle?) {
        // Only try to load prefs when fresh install
        if (bundle != null && prefs.all.isEmpty()) {
            fromBundle(bundle)
        }

        prefs.edit {
            // Migrate su_fingerprint
            if (prefs.getBoolean(SU_FINGERPRINT, false))
                suBiometric = true
            remove(SU_FINGERPRINT)

            // Migrate update_channel
            prefs.getString(UPDATE_CHANNEL, null)?.let {
                val channel = when (it.toInt()) {
                    OldValue.STABLE_CHANNEL -> Value.STABLE_CHANNEL
                    OldValue.CANARY_CHANNEL, OldValue.BETA_CHANNEL -> Value.BETA_CHANNEL
                    OldValue.DEBUG_CHANNEL -> Value.DEBUG_CHANNEL
                    OldValue.CUSTOM_CHANNEL -> Value.CUSTOM_CHANNEL
                    else -> Value.DEFAULT_CHANNEL
                }
                putInt(Key.RELEASE_CHANNEL, channel)
            }
            remove(UPDATE_CHANNEL)
        }
    }
}
