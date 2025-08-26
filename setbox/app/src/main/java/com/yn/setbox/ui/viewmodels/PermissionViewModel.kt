package com.yn.setbox.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yn.setbox.core.PermissionManager
import com.yn.setbox.core.ShizukuManager
import com.yn.setbox.core.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ViewModel لإدارة منطق تفعيل التطبيق وعرض الحالة للمستخدم.
class PermissionViewModel : ViewModel() {

    private val _activationState = MutableStateFlow(PermissionManager.ActivationStatus.CHECKING)
    val activationState: StateFlow<PermissionManager.ActivationStatus> = _activationState.asStateFlow()

    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    // فحص ومحاولة تفعيل التطبيق تلقائيًا.
    fun checkAndTryToActivate(context: Context) {
        if (_activationState.value == PermissionManager.ActivationStatus.ACTIVATED) return

        viewModelScope.launch {
            // 1. التحقق من تثبيت البلوقن
            var isPluginReady = PermissionManager.isPluginInstalled(context)
            if (!isPluginReady) {
                Log.w("PermissionViewModel", "البلوقن غير مثبت.")

                // تحقق إذا كان ممكن استخدام Root أو Shizuku لتثبيته تلقائيًا
                val canInstall = PermissionManager.grantPermissionWithRoot() ||
                                 (ShizukuManager.isReady.value && ShizukuManager.isPermissionGranted.value)

                if (canInstall) {
                    Log.d("PermissionViewModel", "محاولة تثبيت البلوقن تلقائيًا من assets...")
                    isPluginReady = PluginManager.installPlugin(context)

                    if (isPluginReady) {
                        Log.i("PermissionViewModel", "تم تثبيت البلوقن بنجاح.")
                    } else {
                        Log.e("PermissionViewModel", "فشل تثبيت البلوقن تلقائيًا.")
                        _activationState.value = PermissionManager.ActivationStatus.PLUGIN_NOT_INSTALLED
                        return@launch
                    }
                } else {
                    _activationState.value = PermissionManager.ActivationStatus.PLUGIN_NOT_INSTALLED
                    return@launch
                }
            }

            // 2. التحقق من وجود الصلاحية بالفعل.
            if (PermissionManager.hasWriteSecureSettingsPermission(context)) {
                _activationState.value = PermissionManager.ActivationStatus.ACTIVATED
                return@launch
            }

            _activationState.value = PermissionManager.ActivationStatus.CHECKING
            Log.d("PermissionViewModel", "بدء محاولة التفعيل التلقائي.")

            // 3. محاولة التفعيل عبر الروت.
            if (PermissionManager.grantPermissionWithRoot()) {
                _activationState.value = PermissionManager.ActivationStatus.ACTIVATED
                Log.i("PermissionViewModel", "نجح التفعيل عبر الروت.")
                return@launch
            }

            // 4. محاولة التفعيل عبر Shizuku.
            if (ShizukuManager.isReady.value && ShizukuManager.isPermissionGranted.value) {
                if (PermissionManager.grantPermissionWithShizuku()) {
                    _activationState.value = PermissionManager.ActivationStatus.ACTIVATED
                    Log.i("PermissionViewModel", "نجح التفعيل عبر Shizuku.")
                    return@launch
                }
            }
            
            // 5. إذا فشلت كل المحاولات، يتم تحديد الحالة النهائية.
            Log.w("PermissionViewModel", "فشل التفعيل التلقائي.")
            val finalState = if (ShizukuManager.isReady.value && ShizukuManager.isPermissionGranted.value) {
                PermissionManager.ActivationStatus.NEEDS_PERMISSION_SHIZUKU_AVAILABLE
            } else {
                PermissionManager.ActivationStatus.NEEDS_PERMISSION_ADB_REQUIRED
            }
            _activationState.value = finalState
        }
    }

    // طلب إظهار حوار المساعدة.
    fun onActivationHelpRequested() {
        if (_activationState.value != PermissionManager.ActivationStatus.ACTIVATED &&
            _activationState.value != PermissionManager.ActivationStatus.CHECKING) {
            _showDialog.value = true
        }
    }

    fun dismissDialog() {
        _showDialog.value = false
    }
}
