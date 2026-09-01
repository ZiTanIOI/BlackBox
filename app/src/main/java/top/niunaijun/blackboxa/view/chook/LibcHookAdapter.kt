package top.niunaijun.blackboxa.view.chook

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.LibcHookAppInfo
import top.niunaijun.blackboxa.databinding.ItemXpBinding


class LibcHookAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return LibcHookVH(inflate(R.layout.item_xp, parent))
    }

    class LibcHookVH(itemView: View) : RVHolder<LibcHookAppInfo>(itemView) {

        private val binding = ItemXpBinding.bind(itemView)

        override fun setContent(item: LibcHookAppInfo, isSelected: Boolean, payload: Any?) {
            binding.icon.setImageDrawable(item.icon)
            binding.name.text = item.name
            binding.desc.text = item.packageName
            binding.enable.isChecked = item.disabled
            binding.enable.setOnCheckedChangeListener { buttonView, _ ->
                if (buttonView.isPressed) {
                    binding.root.performClick()
                }
            }
        }
    }
}
