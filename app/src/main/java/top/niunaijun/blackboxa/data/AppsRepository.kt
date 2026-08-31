package top.niunaijun.blackboxa.data

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.BlackBoxCore.getPackageManager
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.hotfix.HotfixManager
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackbox.utils.FileUtils
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.util.getString
import java.io.File


/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 23:05
 */

class AppsRepository {
    val TAG: String = "AppsRepository"
    private var mInstalledList = mutableListOf<AppInfo>()

    fun previewInstallList() {
        synchronized(mInstalledList) {
            val installedApplications: List<ApplicationInfo> =
                getPackageManager().getInstalledApplications(0)
            val installedList = mutableListOf<AppInfo>()

            for (installedApplication in installedApplications) {
                val file = File(installedApplication.sourceDir)

                if ((installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

                if (!AbiUtils.isSupport(file)) continue

                val isXpModule = BlackBoxCore.get().isXposedModule(file)

                val info = AppInfo(
                    installedApplication.loadLabel(getPackageManager()).toString(),
                    installedApplication.loadIcon(getPackageManager()),
                    installedApplication.packageName,
                    installedApplication.sourceDir,
                    isXpModule
                )
                installedList.add(info)
            }
            this.mInstalledList.clear()
            this.mInstalledList.addAll(installedList)
        }


    }

    fun getInstalledAppList(
        userID: Int,
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        loadingLiveData.postValue(true)
        synchronized(mInstalledList) {
            val blackBoxCore = BlackBoxCore.get()
            Log.d(TAG, mInstalledList.joinToString(","))
            val newInstalledList = mInstalledList.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    blackBoxCore.isInstalled(it.packageName, userID)
                )
            }
            appsLiveData.postValue(newInstalledList)
            loadingLiveData.postValue(false)


        }

    }

    fun getInstalledModuleList(
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {

        loadingLiveData.postValue(true)
        synchronized(mInstalledList) {
            val blackBoxCore = BlackBoxCore.get()
            val moduleList = mInstalledList.filter {
                it.isXpModule
            }.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    blackBoxCore.isInstalledXposedModule(it.packageName)
                )
            }
            appsLiveData.postValue(moduleList)
            loadingLiveData.postValue(false)
        }

    }


    fun getVmInstallList(userId: Int, appsLiveData: MutableLiveData<List<AppInfo>>) {
        val sortListData =
            AppManager.mRemarkSharedPreferences.getString("AppList$userId", "")
        val sortList = sortListData?.split(",")

        val applicationList = BlackBoxCore.get().getInstalledApplications(0, userId)

        val appInfoList = mutableListOf<AppInfo>()
        applicationList.also {
            if (sortList.isNullOrEmpty()) {
                return@also
            }
            it.sortWith(AppsSortComparator(sortList))

        }.forEach {
            val info = AppInfo(
                it.loadLabel(getPackageManager()).toString(),
                it.loadIcon(getPackageManager()),
                it.packageName,
                it.sourceDir,
                isInstalledXpModule(it.packageName)
            )

            appInfoList.add(info)
        }


        appsLiveData.postValue(appInfoList)
    }

    private fun isInstalledXpModule(packageName: String): Boolean {
        BlackBoxCore.get().installedXPModules.forEach {
            if (packageName == it.packageName) {
                return@isInstalledXpModule true
            }
        }

        return false
    }


    fun installApk(source: String, userId: Int, resultLiveData: MutableLiveData<String>) {
        val blackBoxCore = BlackBoxCore.get()
        try {
            val installResult = if (URLUtil.isValidUrl(source)) {
                val uri = Uri.parse(source)
                blackBoxCore.installPackageAsUser(uri, userId)
            } else {
                blackBoxCore.installPackageAsUser(source, userId)
            }

            if (installResult.success) {
                updateAppSortList(userId, installResult.packageName, true)
                resultLiveData.postValue(getString(R.string.install_success))
            } else {
                resultLiveData.postValue(getString(R.string.install_fail, installResult.msg))
            }
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.install_fail, e.message ?: e.javaClass.simpleName))
        }
        scanUser()
    }

    fun unInstall(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userID)
            updateAppSortList(userID, packageName, false)
            scanUser()
            resultLiveData.postValue(getString(R.string.uninstall_success))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.uninstall_fail))
        }
    }


    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        val result = BlackBoxCore.get().launchApk(packageName, userId)
        launchLiveData.postValue(result)
    }


    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().clearPackage(packageName, userID)
            resultLiveData.postValue(getString(R.string.clear_success))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.clear_fail))
        }
    }

    /**
     * 热修复补丁：宿主把用户选择的 dex 拷进 blackbox 根目录 hotfix/u<userId>/<pkg>.dex，
     * 分身进程每次启动时由 HotfixManager 前插到应用类加载器的 dexElements
     */
    fun getHotfixPatch(userId: Int, packageName: String): File? {
        val patch = BEnvironment.getHotfixPatchFile(userId, packageName)
        return if (patch.isFile) patch else null
    }

    fun saveHotfixPatch(userId: Int, packageName: String, uri: Uri, resultLiveData: MutableLiveData<String>) {
        try {
            val patch = BEnvironment.getHotfixPatchFile(userId, packageName)
            FileUtils.mkdirs(patch.parentFile!!.absolutePath)
            App.getContext().contentResolver.openInputStream(uri)!!.use { input ->
                patch.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // 覆盖旧补丁后清掉旧 odex 缓存，避免新旧内容不一致
            HotfixManager.clearOdexCache(packageName, userId)
            resultLiveData.postValue(getString(R.string.hotfix_saved))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.hotfix_save_fail, e.message ?: e.javaClass.simpleName))
        }
    }

    fun clearHotfixPatch(userId: Int, packageName: String, resultLiveData: MutableLiveData<String>) {
        try {
            if (!BEnvironment.getHotfixPatchFile(userId, packageName).exists()) {
                resultLiveData.postValue(getString(R.string.hotfix_none))
                return
            }
            HotfixManager.clearPatch(packageName, userId)
            resultLiveData.postValue(getString(R.string.hotfix_cleared))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.hotfix_clear_fail))
        }
    }

    /**
     * 倒序递归扫描用户，
     * 如果用户是空的，就删除用户，删除用户备注，删除应用排序列表
     */
    private fun scanUser() {
        val blackBoxCore = BlackBoxCore.get()
        val userList = blackBoxCore.users

        if (userList.isEmpty()) {
            return
        }

        val id = userList.last().id

        if (blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
            blackBoxCore.deleteUser(id)
            AppManager.mRemarkSharedPreferences.edit {
                remove("Remark$id")
                remove("AppList$id")
            }
            scanUser()
        }
    }


    /**
     * 更新排序列表
     * @param userID Int
     * @param pkg String
     * @param isAdd Boolean true是添加，false是移除
     */
    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {

        val savedSortList =
            AppManager.mRemarkSharedPreferences.getString("AppList$userID", "")

        val sortList = linkedSetOf<String>()
        if (savedSortList != null) {
            sortList.addAll(savedSortList.split(","))
        }

        if (isAdd) {
            sortList.add(pkg)
        } else {
            sortList.remove(pkg)
        }

        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID", sortList.joinToString(","))
        }

    }

    /**
     * 保存排序后的apk顺序
     */
    fun updateApkOrder(userID: Int, dataList: List<AppInfo>) {
        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID",
                dataList.joinToString(",") { it.packageName })
        }

    }

}
