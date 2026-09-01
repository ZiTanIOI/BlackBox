package top.niunaijun.blackboxa.view.chook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.LibcHookAppInfo
import top.niunaijun.blackboxa.databinding.ActivityLibcHookBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity

/**
 * 按应用禁用 libc GOT hook：勾选后该应用启动时不再对 so 打 IO 重定向补丁，
 * 用于兼容扫描 GOT 完整性的反作弊类应用（如带 ACE/TP 保护的游戏）。
 */
class LibcHookActivity : LoadingActivity() {

    private val viewBinding: ActivityLibcHookBinding by inflate()

    private lateinit var viewModel: LibcHookViewModel

    private lateinit var mAdapter: RVAdapter<LibcHookAppInfo>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.libc_hook_disable, true)

        viewModel = ViewModelProvider(this).get(LibcHookViewModel::class.java)

        initRecyclerView()
    }

    private fun observeLiveData() {
        viewBinding.stateView.showLoading()
        viewModel.appsLiveData.observe(this) {
            if (it.isNullOrEmpty()) {
                viewBinding.stateView.showEmpty()
            } else {
                mAdapter.setItems(it)
                viewBinding.stateView.showContent()
            }
        }
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<LibcHookAppInfo>(this, LibcHookAdapter()).bind(viewBinding.recyclerView)
                .setItemClickListener { _, item, position ->
                    item.disabled = !item.disabled
                    viewModel.setDisabled(item)
                    mAdapter.replaceAt(position, item)
                    toast(if (item.disabled) R.string.libc_hook_off_toast else R.string.libc_hook_on_toast)
                }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        viewBinding.stateView.showEmpty()
    }

    override fun onStart() {
        super.onStart()
        observeLiveData()
        viewModel.getApps()
    }

    override fun onStop() {
        super.onStop()
        viewModel.appsLiveData.value = null
        viewModel.appsLiveData.removeObservers(this)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, LibcHookActivity::class.java)
            context.startActivity(intent)
        }
    }
}
