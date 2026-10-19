/*
 * Copyright (c) 2026 Jingge Chen <mariocanfly@hotmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Scale
import coil.size.Size
import coil.size.pxOrElse
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import com.radzivon.bartoshyk.avif.coder.ScaleMode
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

/**
 * A [Decoder] for AVIF (and HEIF) images backed by avif-coder, so that they can also be decoded on
 * the Android versions without any native AVIF support (i.e. below Android 12).
 *
 * @see <a href="https://github.com/awxkee/avif-coder">avif-coder</a>
 */
class HeifDecoder(
    private val source: SourceResult,
    private val options: Options
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val data = source.source.source().readByteArray()
        val preferredColorConfig = when {
            options.config == Bitmap.Config.RGB_565 -> PreferredColorConfig.RGB_565
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && options.config == Bitmap.Config.RGBA_F16 -> PreferredColorConfig.RGBA_F16
            else -> PreferredColorConfig.RGBA_8888
        }
        val coder = HeifCoder()
        return if (options.size == Size.ORIGINAL) {
            DecodeResult(
                BitmapDrawable(options.context.resources, coder.decode(data, preferredColorConfig)),
                false
            )
        } else {
            val scaleMode = when (options.scale) {
                Scale.FILL -> ScaleMode.FILL
                Scale.FIT -> ScaleMode.FIT
            }
            DecodeResult(
                BitmapDrawable(
                    options.context.resources,
                    coder.decodeSampled(
                        data, options.size.width.pxOrElse { 0 },
                        options.size.height.pxOrElse { 0 }, preferredColorConfig, scaleMode
                    )
                ),
                true
            )
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? =
            if (hasHeifBrand(result.source.source())) HeifDecoder(result, options) else null

        companion object {
            // The "ftyp" box, whose major brand is stored at offset 4, @see
            // https://aomediacodec.github.io/av1-avif/#file-format
            private const val BRAND_OFFSET = 4L

            private val BRANDS = listOf(
                "ftypmif1", "ftypmsf1", "ftypheic", "ftypheix", "ftyphevc", "ftyphevx", "ftypavif",
                "ftypavis"
            ).map { it.encodeUtf8() }

            private fun hasHeifBrand(source: BufferedSource): Boolean =
                try {
                    BRANDS.any { source.rangeEquals(BRAND_OFFSET, it) }
                } catch (e: IOException) {
                    false
                }
        }
    }
}
