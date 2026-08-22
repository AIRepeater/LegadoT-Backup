package io.legado.app.ui.book.read.config

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return createPrefContainer(container, requireContext().backgroundColor)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        replacePreferenceFragment(view, readAloudPreferTag) { ReadAloudPreferenceFragment() }
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            upTtsPreDownloadSummary()
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
                PreferKey.ttsPreDownloadChapterNum -> showNumberPicker(
                    requireContext(),
                    titleResId = R.string.tts_pre_download,
                    max = 50, min = 0, value = AppConfig.ttsPreDownloadChapterNum
                ) {
                    AppConfig.ttsPreDownloadChapterNum = it
                }
                "clearTtsCache" -> showClearTtsCacheDialog()
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ignoreAudioFocus -> {
                    findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                        it.isEnabled = AppConfig.ignoreAudioFocus
                    }
                }

                PreferKey.ttsPreDownloadChapterNum -> upTtsPreDownloadSummary()
            }
        }

        private fun upTtsPreDownloadSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsPreDownloadChapterNum),
                getString(
                    R.string.tts_pre_download_s,
                    AppConfig.ttsPreDownloadChapterNum.toString()
                )
            )
        }

        private fun showClearTtsCacheDialog() {
            requireContext().alert(
                titleResource = R.string.clear_tts_cache,
                messageResource = R.string.clear_tts_cache_confirm
            ) {
                okButton {
                    clearTtsCache()
                }
                noButton()
            }
        }

        private fun clearTtsCache() {
            if (HttpReadAloudService.isRunning) {
                // 在线朗读服务运行中: 记录请求时间并定向停止该服务, 销毁释放缓存后执行删除
                HttpReadAloudService.clearCacheRequestedAt = System.currentTimeMillis()
                appCtx.stopService(Intent(appCtx, HttpReadAloudService::class.java))
            } else {
                // 服务未运行, 缓存无句柄, 直接异步清理
                HttpReadAloudService.clearTtsCacheDirsAsync {
                    appCtx.toastOnUi(R.string.clear_cache_success)
                }
            }
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }

                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }
    }
}