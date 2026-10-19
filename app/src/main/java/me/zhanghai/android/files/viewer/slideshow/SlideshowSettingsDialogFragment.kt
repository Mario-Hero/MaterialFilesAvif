/*
 * Copyright (c) 2026 Jingge Chen <mariocanfly@hotmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.slideshow

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SlideshowSettingsDialogBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.startActivitySafe

class SlideshowSettingsDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val binding = SlideshowSettingsDialogBinding.inflate(layoutInflater)
        // 恢复全局设置
        binding.intervalEdit.setText(SlideshowSettings.getIntervalSeconds(context).toString())
        val checkedTransitionEffectId = when (SlideshowSettings.getTransitionEffect(context)) {
            SlideshowActivity.TRANSITION_EFFECT_SLIDE -> R.id.transition_effect_slide
            SlideshowActivity.TRANSITION_EFFECT_NONE -> R.id.transition_effect_none
            else -> R.id.transition_effect_fade
        }
        binding.transitionEffectGroup.check(checkedTransitionEffectId)
        binding.randomCheck.isChecked = SlideshowSettings.isRandom(context)
        binding.loopCheck.isChecked = SlideshowSettings.isLoop(context)
        // 任何修改都立即保存（先设置初始值，再注册监听，避免重复写入）
        binding.intervalEdit.doAfterTextChanged { text ->
            text?.toString()?.toIntOrNull()
                ?.takeIf { it in SlideshowSettings.INTERVAL_SECONDS_MIN..SlideshowSettings.INTERVAL_SECONDS_MAX }
                ?.let { SlideshowSettings.putIntervalSeconds(context, it) }
        }
        binding.transitionEffectGroup.setOnCheckedChangeListener { _, checkedId ->
            SlideshowSettings.putTransitionEffect(context, transitionEffectFromId(checkedId))
        }
        binding.randomCheck.setOnCheckedChangeListener { _, isChecked ->
            SlideshowSettings.putRandom(context, isChecked)
        }
        binding.loopCheck.setOnCheckedChangeListener { _, isChecked ->
            SlideshowSettings.putLoop(context, isChecked)
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_list_action_slideshow)
            .setView(binding.root)
            .setPositiveButton(R.string.slideshow_play) { _, _ -> play(binding) }
            .setNegativeButton(R.string.slideshow_cancel, null)
            .create()
    }

    private fun transitionEffectFromId(checkedId: Int): Int = when (checkedId) {
        R.id.transition_effect_slide -> SlideshowActivity.TRANSITION_EFFECT_SLIDE
        R.id.transition_effect_none -> SlideshowActivity.TRANSITION_EFFECT_NONE
        else -> SlideshowActivity.TRANSITION_EFFECT_FADE
    }

    private fun play(binding: SlideshowSettingsDialogBinding) {
        val context = requireContext()
        val intervalSeconds = binding.intervalEdit.text?.toString()?.toIntOrNull()
            ?.coerceIn(
                SlideshowSettings.INTERVAL_SECONDS_MIN,
                SlideshowSettings.INTERVAL_SECONDS_MAX
            ) ?: SlideshowSettings.DEFAULT_INTERVAL_SECONDS
        SlideshowSettings.putIntervalSeconds(context, intervalSeconds)
        val transitionEffect =
            transitionEffectFromId(binding.transitionEffectGroup.checkedRadioButtonId)
        val intent = Intent(context, SlideshowActivity::class.java)
            .putExtra(SlideshowActivity.EXTRA_INTERVAL_SECONDS, intervalSeconds)
            .putExtra(SlideshowActivity.EXTRA_TRANSITION_EFFECT, transitionEffect)
            .putExtra(SlideshowActivity.EXTRA_RANDOM, binding.randomCheck.isChecked)
            .putExtra(SlideshowActivity.EXTRA_LOOP, binding.loopCheck.isChecked)
        intent.extraPath = args.path
        startActivitySafe(intent)
    }

    companion object {
        fun show(fragment: Fragment, path: Path) {
            SlideshowSettingsDialogFragment().putArgs(Args(path)).show(fragment)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
