package com.yn.setbox.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import com.yn.setbox.data.model.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipInputStream

// مستودع لإدارة الوحدات المثبتة محليًا على الجهاز.
class ModuleRepository(private val context: Context) {

    private val modulesRootPath by lazy { File(context.filesDir, "modules").absolutePath }

    // عنوان Content Provider الخاص بالبلوقن لتنفيذ الأوامر.
    private val pluginProviderUri = Uri.parse("content://com.yn.setbox.plugin.provider/settings")
    private val pluginProviderGetUri = Uri.parse("content://com.yn.setbox.plugin.provider/get_setting")


    suspend fun getModules(): List<Module> {
        return withContext(Dispatchers.IO) {
            val modulesList = mutableListOf<Module>()
            val modulesDir = File(modulesRootPath)
            if (!modulesDir.exists()) modulesDir.mkdirs()

            modulesDir.listFiles()?.forEach { moduleFolder ->
                if (moduleFolder.isDirectory) {
                    val modulePropFile = File(moduleFolder, "module.prop")
                    readModuleProp(modulePropFile)?.let { modulesList.add(it) }
                }
            }
            modulesList
        }
    }

    suspend fun installFromZip(zipUri: Uri, context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "unzip_temp_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                // فك ضغط الملف المؤقت.
                context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipInputStream ->
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            val newFile = File(tempDir, entry.name)
                            if (entry.isDirectory) newFile.mkdirs() else {
                                newFile.parentFile?.mkdirs()
                                FileOutputStream(newFile).use { fos -> zipInputStream.copyTo(fos) }
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                    }
                } ?: return@withContext false

                // قراءة module.prop للحصول على معرف الوحدة.
                val modulePropFile = findModuleProp(tempDir) ?: throw IOException("لم يتم العثور على module.prop.")
                val moduleContentDir = modulePropFile.parentFile ?: throw IOException("هيكل الوحدة غير صالح.")
                val props = Properties().apply { load(FileInputStream(modulePropFile)) }
                val moduleId = props.getProperty("id") ?: throw IOException("معرف الوحدة غير موجود.")

                // نسخ محتويات الوحدة إلى المجلد الدائم.
                val finalModuleDir = File(modulesRootPath, moduleId)
                if (finalModuleDir.exists()) finalModuleDir.deleteRecursively()
                finalModuleDir.mkdirs()
                moduleContentDir.copyRecursively(finalModuleDir, overwrite = true)
                true
            } catch (e: Exception) {
                false
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun findModuleProp(directory: File): File? {
        directory.walkTopDown().forEach { file ->
            if (file.isFile && file.name == "module.prop") return file
        }
        return null
    }

    /**
     * يطبق الإعدادات من ملف الأوامر (on أو off) عبر التواصل مع البلوقن.
     * @return `true` إذا نجح تنفيذ جميع الأوامر، و`false` إذا فشل أمر واحد على الأقل.
     */
    suspend fun applySettingsFromFile(modulePath: String, commandFileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            val commandFile = File(modulePath, commandFileName)
            if (!commandFile.exists()) return@withContext true

            val lines = commandFile.readLines()
            var allSucceeded = true
            var i = 0
            while (i < lines.size) {
                val trimmedLine = lines[i].trim()
                if (trimmedLine.startsWith("if")) {
                    val (endIndex, success) = handleIfBlock(lines, i, modulePath)
                    i = endIndex
                    if (!success) allSucceeded = false
                } else {
                    if (!executeLine(trimmedLine)) {
                        allSucceeded = false
                    }
                }
                i++
            }
            allSucceeded
        }
    }

    private fun handleIfBlock(lines: List<String>, startIndex: Int, modulePath: String): Pair<Int, Boolean> {
        var currentIndex = startIndex
        var conditionMet = false
        var blockSuccess = true

        while (currentIndex < lines.size) {
            val line = lines[currentIndex].trim()
            when {
                line.startsWith("if") || line.startsWith("elif") -> {
                    if (conditionMet) {
                        // تخطي هذا البلوك وبحث عن الـ `fi`
                        currentIndex = findNextBlock(lines, currentIndex)
                        continue
                    }
                    val condition = line.substringAfter("[").substringBefore("]").trim()
                    val comparison = line.split(" ").filter { it.isNotBlank() }
                    if (comparison.size >= 4) {
                        val operator = comparison[2]
                        val valueToCompare = comparison[3]
                        val currentValue = getSettingValue("global", condition) // يمكنك تعديل الجدول هنا "system", "secure"

                        if (evaluateCondition(currentValue, operator, valueToCompare)) {
                            conditionMet = true
                            val (endOfBlock, success) = executeBlock(lines, currentIndex + 1, modulePath)
                            blockSuccess = success
                            currentIndex = endOfBlock
                            continue
                        }
                    }
                }
                line.startsWith("else") -> {
                    if (conditionMet) {
                       // تخطي هذا البلوك وبحث عن الـ `fi`
                        currentIndex = findNextBlock(lines, currentIndex, seekFi = true)
                        continue
                    }
                    val (endOfBlock, success) = executeBlock(lines, currentIndex + 1, modulePath)
                    blockSuccess = success
                    currentIndex = endOfBlock
                    conditionMet = true // لضمان عدم تنفيذ أي elif أو else لاحقة
                    continue
                }
                line.startsWith("fi") -> {
                    return Pair(currentIndex, blockSuccess)
                }
            }
            currentIndex++
        }
        return Pair(lines.size - 1, blockSuccess) // حالة عدم العثور على fi
    }

    private fun findNextBlock(lines: List<String>, startIndex: Int, seekFi: Boolean = false): Int {
        var i = startIndex + 1
        var nestedIfs = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("if")) {
                nestedIfs++
            } else if (line.startsWith("fi")) {
                if (nestedIfs == 0) {
                     return if(seekFi) i else i -1
                }
                nestedIfs--
            } else if (nestedIfs == 0 && (line.startsWith("elif") || line.startsWith("else")) && !seekFi) {
                return i - 1
            }
            i++
        }
        return i-1
    }


    private fun executeBlock(lines: List<String>, startIndex: Int, modulePath: String): Pair<Int, Boolean> {
        var i = startIndex
        var allSucceeded = true
        var nestedIfs = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if(line.startsWith("if")) {
                 nestedIfs++
            } else if (nestedIfs > 0 && line.startsWith("fi")) {
                nestedIfs--
            } else if (nestedIfs == 0 && (line.startsWith("elif") || line.startsWith("else") || line.startsWith("fi"))) {
                return Pair(i, allSucceeded)
            }
             if (!executeLine(line)) {
                 allSucceeded = false
             }
            i++
        }
        return Pair(i, allSucceeded)
    }

    private fun evaluateCondition(currentValue: String?, operator: String, valueToCompare: String): Boolean {
        if (currentValue == null) return false
        return when (operator) {
            "==" -> currentValue == valueToCompare
            "!=" -> currentValue != valueToCompare
            ">" -> currentValue.toIntOrNull() ?: 0 > valueToCompare.toIntOrNull() ?: 0
            "<" -> currentValue.toIntOrNull() ?: 0 < valueToCompare.toIntOrNull() ?: 0
            // يمكنك إضافة المزيد من العمليات هنا
            else -> false
        }
    }


    private fun executeLine(line: String): Boolean {
        val trimmedLine = line.trim()
        if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
            val parts = trimmedLine.split("\\s+".toRegex())
            if (parts.size >= 3) {
                val values = ContentValues().apply {
                    put("table", parts[0]) // system, global, secure
                    put("key", parts[1])   // اسم الإعداد
                    put("value", parts.drop(2).joinToString(" ")) // القيمة
                }
                try {
                    val updatedRows = context.contentResolver.update(pluginProviderUri, values, null, null)
                    return updatedRows > 0
                } catch (e: Exception) {
                    return false
                }
            }
        }
        return true // تعتبر الأسطر الفارغة أو التعليقات ناجحة
    }

    private fun getSettingValue(table: String, key: String): String? {
        val selectionArgs = arrayOf(table, key)
        try {
            context.contentResolver.query(
                pluginProviderGetUri,
                null, // Projection
                null, // Selection
                selectionArgs, // Selection args
                null // Sort order
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val valueIndex = cursor.getColumnIndex("value")
                    if (valueIndex != -1) {
                         return cursor.getString(valueIndex)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }


    suspend fun uninstallModule(module: Module): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // استدعاء الدالة المحسنة لإعادة الإعدادات، يتم تجاهل النتيجة هنا للحفاظ على السلوك
                revertModuleSettings(module)
                // حذف مجلد الوحدة.
                File(module.path).deleteRecursively()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * يقرأ ملف "on" الخاص بالوحدة ويعيد كل الإعدادات المذكورة فيه إلى قيمتها الافتراضية.
     * @return `true` إذا نجحت كل عمليات الإعادة، و`false` إذا فشلت إحداها.
     */
    suspend fun revertModuleSettings(module: Module): Boolean {
        return withContext(Dispatchers.IO) {
            val onFile = File(module.path, "on")
            if (!onFile.exists()) return@withContext true

            var allSucceeded = true
            onFile.readLines().forEach { line ->
                val trimmedLine = line.trim()
                // تجاهل التعليقات والأسطر الفارغة والهياكل الشرطية
                val isControlOrComment = trimmedLine.isBlank() ||
                        trimmedLine.startsWith("#") ||
                        trimmedLine.startsWith("if") ||
                        trimmedLine.startsWith("fi") ||
                        trimmedLine.startsWith("else") ||
                        trimmedLine.startsWith("elif")

                if (!isControlOrComment) {
                    val parts = trimmedLine.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        val revertLine = "${parts[0]} ${parts[1]} default"
                        if (!executeLine(revertLine)) {
                            allSucceeded = false
                        }
                    }
                }
            }
            allSucceeded
        }
    }
    
    // يقرأ ملف module.prop ويحوله إلى كائن Module.
    private fun readModuleProp(modulePropFile: File): Module? {
        if (!modulePropFile.exists()) return null
        val props = Properties()
        return try {
            FileInputStream(modulePropFile).use { props.load(it) }
            Module(
                id = props.getProperty("id") ?: return null,
                name = props.getProperty("name") ?: return null,
                version = props.getProperty("version") ?: return null,
                versionCode = props.getProperty("versionCode"),
                author = props.getProperty("author") ?: return null,
                description = props.getProperty("description"),
                path = modulePropFile.parentFile?.absolutePath ?: "",
                repository = props.getProperty("repository"),
                isEnabled = false
            )
        } catch (e: Exception) {
            null
        }
    }
}