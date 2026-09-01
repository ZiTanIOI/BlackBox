package top.niunaijun.blackboxa.view.chook

import android.content.pm.PackageManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.LibcHookConfig
import top.niunaijun.blackbox.fake.frameworks.BXposedManager
import top.niunaijun.blackboxa.bean.LibcHookAppInfo

class LibcHookViewModel : ViewModel() {

    val appsLiveData = MutableLiveData<List<LibcHookAppInfo>>()

    fun getApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packageManager = BlackBoxCore.getPackageManager()
            val apps = BlackBoxCore.get().getInstalledApplications(PackageManager.GET_META_DATA, 0)
            val result = apps.map {
                LibcHookAppInfo(
                        it.loadLabel(packageManager).toString(),
                        it.packageName,
                        LibcHookConfig.isDisabled(0, it.packageName),
                        it.loadIcon(packageManager)
                )
            }.sortedBy { it.name }
            appsLiveData.postValue(result)
        }
    }

    fun setDisabled(info: LibcHookAppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            LibcHookConfig.setDisabled(0, info.packageName, info.disabled)
            // 进程内的 GOT hook 在 bindApplication 阶段完成，杀掉后下次启动生效
            BXposedManager.get().killPackageAsUser(info.packageName, 0)
        }
    }
}
