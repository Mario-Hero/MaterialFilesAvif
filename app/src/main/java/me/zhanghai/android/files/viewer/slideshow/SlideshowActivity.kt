/*
 * Copyright (c) 2026 Jingge Chen <mariocanfly@hotmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.slideshow

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import coil.load
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.SlideshowActivityBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.shortAnimTime
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.systemuihelper.SystemUiHelper

class SlideshowActivity : AppActivity() {
    private lateinit var binding: SlideshowActivityBinding
    private lateinit var systemUiHelper: SystemUiHelper
    private val handler = Handler(Looper.getMainLooper())

    private val startPath by lazy { intent.extraPath!! }
    private val intervalSeconds by lazy {
        intent.getIntExtra(EXTRA_INTERVAL_SECONDS, SlideshowSettings.DEFAULT_INTERVAL_SECONDS)
            .coerceIn(
                SlideshowSettings.INTERVAL_SECONDS_MIN,
                SlideshowSettings.INTERVAL_SECONDS_MAX
            )
    }
    private val transitionEffect by lazy {
        intent.getIntExtra(EXTRA_TRANSITION_EFFECT, TRANSITION_EFFECT_FADE)
    }
    private val isRandom by lazy { intent.getBooleanExtra(EXTRA_RANDOM, false) }
    private val isLoop by lazy { intent.getBooleanExtra(EXTRA_LOOP, false) }

    // 双 ImageView 交替显示，切换效果在上一张图片的基础上进行
    private lateinit var currentImageView: ImageView
    private lateinit var nextImageView: ImageView

    private var sequentialPaths: List<Path>? = null
    private var sequentialIndex = 0
    private val nextRunnable = Runnable { showNext() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SlideshowActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        currentImageView = binding.imageView
        nextImageView = binding.imageView2
        systemUiHelper = SystemUiHelper(this, SystemUiHelper.LEVEL_IMMERSIVE,
            SystemUiHelper.FLAG_IMMERSIVE_STICKY) { visible ->
            binding.overlay.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else -binding.overlay.bottom.toFloat())
                .setDuration(shortAnimTime.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
        // 进入时默认隐藏系统栏和顶部浮层（文件名和退出按钮），点击屏幕后才显示
        systemUiHelper.hide()
        binding.root.setOnClickListener { systemUiHelper.toggle() }
        binding.closeButton.setOnClickListener { finish() }
        // 全面屏下浮层顶部要避开状态栏/刘海区域，否则文件名和退出按钮会被遮挡
        val overlayPaddingTop = binding.overlay.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.overlay) { view, insets ->
            view.updatePadding(
                top = overlayPaddingTop + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            )
            insets
        }
        showNext()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(nextRunnable)
    }

    private fun showNext() {
        if (isFinishing || isDestroyed) {
            return
        }
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { nextImagePath() }
            if (path == null) {
                showToast(getString(R.string.slideshow_no_image_found))
                finish()
                return@launch
            }
            loadImage(path)
        }
    }

    private fun nextImagePath(): Path? {
        return if (isRandom) {
            findRandomImage(startPath)
        } else {
            var paths = sequentialPaths
            if (paths == null) {
                paths = listImages(startPath).sortedBy { it.fileName.toString() }
                sequentialPaths = paths
                sequentialIndex = 0
            }
            if (paths.isEmpty()) {
                return null
            }
            if (sequentialIndex >= paths.size) {
                if (!isLoop) {
                    return null
                }
                sequentialIndex = 0
            }
            paths[sequentialIndex++]
        }
    }

    private fun loadImage(path: Path) {
        lifecycleScope.launch {
            val attributes = try {
                withContext(Dispatchers.IO) { path.readAttributes(BasicFileAttributes::class.java) }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            if (isFinishing || isDestroyed) {
                return@launch
            }
            if (attributes == null) {
                scheduleNext()
                return@launch
            }
            binding.fileNameText.text = path.fileName.toString()
            nextImageView.load(path to attributes) {
                listener(
                    onSuccess = { _, _ -> onImageLoaded() },
                    onError = { _, result ->
                        result.throwable.printStackTrace()
                        scheduleNext()
                    }
                )
            }
        }
    }

    /**
     * 新图片已经加载到 nextImageView，把它提到最上层做切换动画，
     * 这样淡入淡出/滑动都是在上一张图片的基础上进行，不会先变黑。
     */
    private fun onImageLoaded() {
        nextImageView.animate().cancel()
        nextImageView.bringToFront()
        // 浮层要保持在图片之上
        binding.overlay.bringToFront()
        when (transitionEffect) {
            TRANSITION_EFFECT_FADE -> {
                nextImageView.translationX = 0f
                nextImageView.alpha = 0f
                nextImageView.animate().alpha(1f)
                    .setDuration(TRANSITION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withEndAction { swapImageViews() }
                    .start()
            }
            TRANSITION_EFFECT_SLIDE -> {
                nextImageView.alpha = 1f
                nextImageView.translationX =
                    resources.displayMetrics.widthPixels.toFloat()
                nextImageView.animate().translationX(0f)
                    .setDuration(TRANSITION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withEndAction { swapImageViews() }
                    .start()
            }
            else -> {
                nextImageView.translationX = 0f
                nextImageView.alpha = 1f
                swapImageViews()
            }
        }
        scheduleNext()
    }

    private fun swapImageViews() {
        currentImageView.alpha = 0f
        currentImageView.translationX = 0f
        val previous = currentImageView
        currentImageView = nextImageView
        nextImageView = previous
    }

    private fun scheduleNext() {
        if (isFinishing || isDestroyed) {
            return
        }
        handler.removeCallbacks(nextRunnable)
        handler.postDelayed(nextRunnable, intervalSeconds * 1000L)
    }

    /**
     * 随机找图：把子目录和图片拼成一个列表后随机选择，
     * 选到图片就展示，选到子目录就进入该子目录继续随机选择；
     * 如果进入的目录什么都没有（死路），就回退到上一级再随机寻找；
     * 累计 10 次寻找都没有找到图片则放弃本次播放（返回 null）。
     */
    private fun findRandomImage(root: Path): Path? {
        var currentDir = root
        var depth = 0
        var attempts = 0
        while (attempts < MAX_RANDOM_ATTEMPTS) {
            val (directories, images) = listEntries(currentDir)
            val imageOnly = depth >= MAX_RANDOM_DEPTH
            if ((directories.isEmpty() && images.isEmpty()) || (imageOnly && images.isEmpty())) {
                // 死路：回退到最里面目录的上一级再随机寻找
                attempts++
                if (depth == 0) {
                    continue
                }
                val parent = currentDir.parent
                if (parent == null || !parent.startsWith(root)) {
                    continue
                }
                currentDir = parent
                depth--
                continue
            }
            val index = if (imageOnly) {
                // 到达最大递归深度时只在图片中选，防止目录无限下行
                directories.size + Random.nextInt(images.size)
            } else {
                Random.nextInt(directories.size + images.size)
            }
            if (index < directories.size) {
                currentDir = directories[index]
                depth++
            } else {
                return images[index - directories.size]
            }
        }
        return null
    }

    private fun listEntries(path: Path): Pair<List<Path>, List<Path>> {
        val directories = mutableListOf<Path>()
        val images = mutableListOf<Path>()
        try {
            path.newDirectoryStream().use { stream ->
                for (entry in stream) {
                    val attributes = try {
                        entry.readAttributes(BasicFileAttributes::class.java)
                    } catch (e: Exception) {
                        continue
                    }
                    if (attributes.isDirectory) {
                        directories.add(entry)
                    } else if (attributes.isRegularFile) {
                        val mimeType = AndroidFileTypeDetector.getMimeType(entry, attributes)
                            .asMimeType()
                        // 幻灯片播放不包含 GIF、ICO 文件和视频文件，只播放普通图片
                        if (mimeType.isImage && mimeType != MimeType.IMAGE_GIF
                            && mimeType != MimeType.IMAGE_X_ICON) {
                            images.add(entry)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return directories to images
    }

    private fun listImages(path: Path): List<Path> {
        return listEntries(path).second
    }

    companion object {
        const val EXTRA_INTERVAL_SECONDS = "me.zhanghai.android.files.slideshow.extra.INTERVAL_SECONDS"
        const val EXTRA_TRANSITION_EFFECT = "me.zhanghai.android.files.slideshow.extra.TRANSITION_EFFECT"
        const val EXTRA_RANDOM = "me.zhanghai.android.files.slideshow.extra.RANDOM"
        const val EXTRA_LOOP = "me.zhanghai.android.files.slideshow.extra.LOOP"
        const val TRANSITION_EFFECT_FADE = 0
        const val TRANSITION_EFFECT_SLIDE = 1
        const val TRANSITION_EFFECT_NONE = 2
        private const val TRANSITION_DURATION = 500L
        private const val MAX_RANDOM_ATTEMPTS = 10
        private const val MAX_RANDOM_DEPTH = 64
    }
}
