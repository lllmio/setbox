package com.yn.setbox.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object PluginManager {

    private const val TAG = "PluginManager"
    const val PLUGIN_PACKAGE_NAME = "com.yn.setbox.plugin"
    private const val PLUGIN_APK_NAME = "setbox-plugin-v1.0.0.apk"

    fun isPluginInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PLUGIN_PACKAGE_NAME, 0)
            Log.d(TAG, "الإضافة ($PLUGIN_PACKAGE_NAME) مثبتة بالفعل.")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "الإضافة ($PLUGIN_PACKAGE_NAME) غير مثبتة.")
            false
        }
    }

    /** تثبيت البلوقن من assets باستخدام صلاحيات Root أو Shizuku */
    suspend fun installPlugin(context: Context): Boolean = withContext(Dispatchers.IO) {
        val tempApkInCache = copyApkToExternalCache(context)
        if (tempApkInCache == null) {
            Log.e(TAG, "فشل نسخ APK إلى الكاش.")
            return@withContext false
        }

        val installed = installPrivileged(tempApkInCache.absolutePath)
        tempApkInCache.delete()
        Log.d(TAG, "تم حذف الملف المؤقت: ${tempApkInCache.absolutePath}")
        return@withContext installed
    }

    /** نسخ APK من assets إلى الكاش الخارجي */
    private fun copyApkToExternalCache(context: Context): File? {
        val externalCacheDir = context.externalCacheDir ?: return null
        return try {
            val destinationFile = File(externalCacheDir, PLUGIN_APK_NAME)
            context.assets.open(PLUGIN_APK_NAME).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "تم نسخ APK إلى: ${destinationFile.absolutePath}")
            destinationFile
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء النسخ إلى الكاش", e)
            null
        }
    }

    /** تثبيت البلوقن بصلاحيات عالية */
    private suspend fun installPrivileged(sourceApkPath: String): Boolean {
        val finalApkPath = "/data/local/tmp/$PLUGIN_APK_NAME"

        val installCommands = """
            cat "$sourceApkPath" > "$finalApkPath" &&
            chmod 644 "$finalApkPath" &&
            pm install --bypass-low-target-sdk-block "$finalApkPath" &&
            rm "$finalApkPath"
        """.trimIndent()

        val installedWithRoot = executeCommandAsRoot(installCommands)
        if (installedWithRoot) {
            Log.i(TAG, "تم التثبيت عبر الروت.")
            return true
        }

        val installedWithShizuku = ShizukuManager.isReady.value &&
                                   ShizukuManager.isPermissionGranted.value &&
                                   executeCommandWithShizuku(installCommands)
        if (installedWithShizuku) {
            Log.i(TAG, "تم التثبيت عبر Shizuku.")
            return true
        }

        Log.e(TAG, "فشل تثبيت البلوقن.")
        return false
    }

    private fun executeCommandAsRoot(command: String): Boolean {
        return try {
            Log.d(TAG, "تنفيذ عبر الروت:\n$command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.d(TAG, "خروج الروت برمز: $exitCode")
            if (output.isNotBlank()) Log.d(TAG, "STDOUT:\n$output")
            if (error.isNotBlank()) Log.e(TAG, "STDERR:\n$error")

            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء التنفيذ عبر الروت", e)
            false
        }
    }

    private fun executeCommandWithShizuku(command: String): Boolean {
        return try {
            Log.d(TAG, "تنفيذ عبر Shizuku:\n$command")
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.d(TAG, "خروج Shizuku برمز: $exitCode")
            if (output.isNotBlank()) Log.d(TAG, "STDOUT:\n$output")
            if (error.isNotBlank()) Log.e(TAG, "STDERR:\n$error")

            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء التنفيذ عبر Shizuku", e)
            false
        }
    }

    private fun InputStream.readText(): String = bufferedReader().use { it.readText() }
}
