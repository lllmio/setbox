package com.yn.setbox.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// تهيئة DataStore لحفظ الإعدادات بشكل دائم.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// كلاس لإدارة حفظ واسترجاع تفضيلات المستخدم باستخدام DataStore.
class AppPreferences(private val context: Context) {

    companion object {
        // مفاتيح لتخزين قيم الإعدادات المختلفة.
        private val THEME_KEY = stringPreferencesKey("theme_key")
        private val LANGUAGE_KEY = stringPreferencesKey("language_key")
        private val BLACK_THEME_KEY = booleanPreferencesKey("black_theme_key")
        private val MATERIAL_YOU_KEY = booleanPreferencesKey("material_you_key")
        private val HUE_SHIFT_KEY = floatPreferencesKey("hue_shift_key")
        private val SATURATION_SHIFT_KEY = floatPreferencesKey("saturation_shift_key")
        private val MODULES_ENABLED_KEY = stringSetPreferencesKey("modules_enabled_key")
        // مفتاح لتخزين نسخة احتياطية من الإعدادات الأصلية كـ JSON String.
        private val ORIGINAL_SETTINGS_BACKUP_KEY = stringPreferencesKey("original_settings_backup")
    }

    // حفظ مجموعة معرفات الوحدات (Modules) المفعلة.
    suspend fun saveEnabledModuleIds(ids: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[MODULES_ENABLED_KEY] = ids
        }
    }

    // استرجاع معرفات الوحدات المفعلة كـ Flow لمراقبة التغييرات.
    fun getEnabledModuleIds(): Flow<Set<String>> {
        return context.dataStore.data.map { preferences ->
            preferences[MODULES_ENABLED_KEY] ?: emptySet()
        }
    }

    /**
     * يحفظ خريطة النسخ الاحتياطية للإعدادات.
     * يتم دمج الخريطة الجديدة مع الحالية لضمان عدم الكتابة فوق النسخ الاحتياطية القديمة.
     */
    suspend fun saveSettingsBackup(newBackups: Map<String, String>) {
        val currentBackups = getSettingsBackup().first().toMutableMap()
        newBackups.forEach { (key, value) ->
            // أضف النسخة الاحتياطية فقط إذا لم تكن موجودة بالفعل
            if (!currentBackups.containsKey(key)) {
                currentBackups[key] = value
            }
        }
        val jsonString = JSONObject(currentBackups as Map<*, *>).toString()
        context.dataStore.edit { preferences ->
            preferences[ORIGINAL_SETTINGS_BACKUP_KEY] = jsonString
        }
    }

    /**
     * يسترجع خريطة النسخ الاحتياطية للإعدادات.
     */
    fun getSettingsBackup(): Flow<Map<String, String>> {
        return context.dataStore.data.map { preferences ->
            val jsonString = preferences[ORIGINAL_SETTINGS_BACKUP_KEY] ?: "{}"
            val map = mutableMapOf<String, String>()
            try {
                val jsonObject = JSONObject(jsonString)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = jsonObject.getString(key)
                }
            } catch (e: Exception) {
                // تجاهل الخطأ إذا كان JSON غير صالح
            }
            map
        }
    }


    suspend fun saveLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    suspend fun getLanguage(): String {
        return context.dataStore.data.map { preferences ->
            preferences[LANGUAGE_KEY] ?: AppLanguage.SYSTEM.name
        }.first()
    }

    suspend fun saveTheme(themeName: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = themeName
        }
    }

    fun getTheme(): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[THEME_KEY] ?: AppTheme.SYSTEM.name
        }
    }

    suspend fun setBlackThemeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BLACK_THEME_KEY] = enabled
        }
    }

    fun isBlackThemeEnabled(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[BLACK_THEME_KEY] ?: false
        }
    }

    suspend fun setMaterialYouEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MATERIAL_YOU_KEY] = enabled
        }
    }

    fun isMaterialYouEnabled(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[MATERIAL_YOU_KEY] ?: true
        }
    }

    suspend fun saveHueShift(shift: Float) {
        context.dataStore.edit { preferences ->
            preferences[HUE_SHIFT_KEY] = shift
        }
    }

    fun getHueShift(): Flow<Float> {
        return context.dataStore.data.map { preferences ->
            preferences[HUE_SHIFT_KEY] ?: 0f
        }
    }
    suspend fun saveSaturationShift(shift: Float) {
        context.dataStore.edit { preferences ->
            preferences[SATURATION_SHIFT_KEY] = shift
        }
    }

    fun getSaturationShift(): Flow<Float> {
        return context.dataStore.data.map { preferences ->
            preferences[SATURATION_SHIFT_KEY] ?: 0f
        }
    }
}