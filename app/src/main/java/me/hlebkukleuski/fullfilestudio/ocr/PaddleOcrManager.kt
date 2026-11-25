/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 *
 * Character recognition dictionaries and ONNX model integration adapted and modified from
 * PaddleOCR (https://github.com/PaddlePaddle/PaddleOCR, ppocr/utils/dict/),
 * Copyright (c) 2020-2026 PaddlePaddle Authors, licensed under the Apache License, Version 2.0.
 * Modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Enterprise-grade 16KB-Page-Size Aligned PaddleOCR Dual-Rec Multilingual Manager.
 *
 * Implements a 3-Tier Hybrid Architecture with zero MLKit dependencies:
 *   - Tier 1 (Column-Aware, Zoom-Aware & Ink-Verified Structural Aligner):
 *     Computes exact mathematical coordinates for Font Specimen Overview & 8x8 Glyph Grids at any zoom/pan,
 *     refines Android 15+ native `[PDF_BOX:...]` bounds against rendered ink pixels, and aligns PDF / Presentation /
 *     DjVu native text hints with visual ink clusters using 2D spatial row matching.
 *   - Tier 2 (Dual-Head PaddleOCR Multilingual + Cyrillic/ESlav ONNX Engine):
 *     Planar BGR/RGB NCHW (`[1, 3, H, W]`) DBNet detector with anisotropic unclipping and Dual SVTR/CRNN CTC
 *     recognizers with head-specific vocabulary binding, per-word time-interval arbitration, Script Purity scoring,
 *     Intra-Word Homoglyph Normalization, and Page Lexicon snapping.
 *   - Tier 3 (Bipolar Contrast Segmenter + 2D Projection Line/Column Splitter + Clean Topological Classifier):
 *     Splits multi-line paragraph blocks into individual lines via horizontal projection valleys,
 *     splits multi-column rows via vertical whitespace valleys, suppresses page/table borders, and segments
 *     dark-on-light and light-on-dark glyphs.
 */
object PaddleOcrManager {


    private val initLock = Any()
    private val ortRunLock = Any()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var detSession: OrtSession? = null

    @Volatile
    private var primaryRecSession: OrtSession? = null

    @Volatile
    private var primarySupportsDynamicWidth: Boolean = false

    @Volatile
    private var primaryInputHeight: Int = 48

    @Volatile
    private var cyrillicRecSession: OrtSession? = null

    @Volatile
    private var cyrillicSupportsDynamicWidth: Boolean = false

    @Volatile
    private var cyrillicInputHeight: Int = 32

    @Volatile
    private var latinRecSession: OrtSession? = null

    @Volatile
    private var latinSupportsDynamicWidth: Boolean = false

    @Volatile
    private var latinInputHeight: Int = 48

    @Volatile
    private var isInitialized = false

    @Volatile
    private var cachedAppContext: Context? = null

    private val v1Dictionary = ArrayList<String>(8192)
    private val multilingualDictionary = ArrayList<String>(8192)
    private val v5Dictionary = ArrayList<String>(16384)
    private val cyrillicAssetDict = ArrayList<String>(256)
    private val latinAssetDict = ArrayList<String>(300)


    private fun discoverOnnxAssetsInFolder(context: Context, folder: String, preferredNames: List<String>): List<String> {
        val listed = try { context.assets.list(folder)?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
        val onnxFiles = listed.filter { it.endsWith(".onnx", ignoreCase = true) }
        val result = LinkedHashSet<String>()
        for (pref in preferredNames) {
            result.add("$folder/$pref")
        }
        for (f in onnxFiles.sorted()) {
            result.add("$folder/$f")
        }
        return result.toList()
    }

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        cachedAppContext = context.applicationContext ?: context
        if (isInitialized) return@withContext
        synchronized(initLock) {
            if (isInitialized) return@synchronized
            try {
                loadMultilingualDictionaries(context)
                ensureTopologicalGlyphBankLocked()

                val detCandidates = discoverOnnxAssetsInFolder(
                    context,
                    "models/det",
                    listOf("ppocrv6_small_det.onnx", "multilingual_det.onnx", "inference.onnx", "det.onnx")
                )
                val detModelFile = copyFirstAvailableAssetToCache(context, detCandidates)

                val allRecCandidates = discoverOnnxAssetsInFolder(
                    context,
                    "models/rec",
                    listOf("ppocrv6_small_rec.onnx", "multilingual_rec.onnx", "inference.onnx", "rec.onnx")
                )
                val primaryRecCandidates = allRecCandidates.filter {
                    !it.contains("cyrillic", ignoreCase = true) &&
                            !it.contains("eslav", ignoreCase = true) &&
                            !it.contains("latin", ignoreCase = true)
                }.ifEmpty { allRecCandidates }
                val primaryRecFile = copyFirstAvailableAssetToCache(context, primaryRecCandidates)

                val cyrillicRecCandidates = discoverOnnxAssetsInFolder(
                    context,
                    "models/rec",
                    listOf("eslav_ppocrv5_rec.onnx", "cyrillic_rec.onnx")
                ).filter {
                    it.contains("cyrillic", ignoreCase = true) || it.contains("eslav", ignoreCase = true)
                }
                val cyrillicRecFile = copyFirstAvailableAssetToCache(context, cyrillicRecCandidates)

                val latinRecCandidates = discoverOnnxAssetsInFolder(
                    context,
                    "models/rec",
                    listOf("latin_ppocrv5_rec.onnx", "latin_rec.onnx")
                ).filter { it.contains("latin", ignoreCase = true) }
                val latinRecFile = copyFirstAvailableAssetToCache(context, latinRecCandidates)

                synchronized(ortRunLock) {
                    val env = try { OrtEnvironment.getEnvironment() } catch (_: Throwable) { null }
                    try { env?.setTelemetry(false) } catch (_: Throwable) { }
                    ortEnv = env
                    if (env != null) {
                        val sessionOptions = OrtSession.SessionOptions().apply {
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                            setInterOpNumThreads(2)
                            setIntraOpNumThreads(2)
                        }

                        if (detModelFile != null && detModelFile.exists()) {
                            try {
                                detSession = env.createSession(detModelFile.absolutePath, sessionOptions)
                            } catch (_: Throwable) {
                                detSession = null
                            }
                        }

                        if (primaryRecFile != null && primaryRecFile.exists()) {
                            try {
                                val pRec = env.createSession(primaryRecFile.absolutePath, sessionOptions)
                                primaryRecSession = pRec
                                val (dynW, inH) = inspectAndProbeRecInputSpec(env, pRec, defaultHeight = 48)
                                primarySupportsDynamicWidth = dynW
                                primaryInputHeight = inH
                            } catch (_: Throwable) {
                                primaryRecSession = null
                            }
                        }

                        if (cyrillicRecFile != null && cyrillicRecFile.exists() &&
                            cyrillicRecFile.absolutePath != primaryRecFile?.absolutePath
                        ) {
                            try {
                                val isV3Cyrillic = cyrillicRecFile.name.contains("cyrillic_rec", ignoreCase = true)
                                val cRec = env.createSession(cyrillicRecFile.absolutePath, sessionOptions)
                                cyrillicRecSession = cRec
                                val (dynW, inH) = inspectAndProbeRecInputSpec(
                                    env,
                                    cRec,
                                    defaultHeight = if (isV3Cyrillic) 32 else 48
                                )
                                cyrillicSupportsDynamicWidth = dynW
                                cyrillicInputHeight = inH
                            } catch (_: Throwable) {
                                cyrillicRecSession = null
                            }
                        }

                        if (latinRecFile != null && latinRecFile.exists()) {
                            try {
                                val lRec = env.createSession(latinRecFile.absolutePath, sessionOptions)
                                latinRecSession = lRec
                                val (dynW, inH) = inspectAndProbeRecInputSpec(env, lRec, defaultHeight = 48)
                                latinSupportsDynamicWidth = dynW
                                latinInputHeight = inH
                            } catch (_: Throwable) {
                                latinRecSession = null
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            isInitialized = true
        }
    }

    /**
     * Inspects the ONNX recognizer input tensor shape and, if the height dimension is dynamic (`<= 0`),
     * probes `defaultHeight` (e.g. 32 vs 48) with a tiny dummy tensor so models with fixed internal
     * pooling strides never fail at runtime.
     */
    private fun inspectAndProbeRecInputSpec(
        env: OrtEnvironment,
        rec: OrtSession,
        defaultHeight: Int = 48
    ): Pair<Boolean, Int> {
        return try {
            val info = rec.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return false to defaultHeight
            val shape = info.shape
            val dynW = shape.size >= 4 && shape[3] <= 0L
            val declaredH = if (shape.size >= 3 && shape[2] in 16L..128L) shape[2].toInt() else -1
            if (declaredH > 0) {
                return dynW to declaredH
            }
            val inputName = rec.inputNames.iterator().next()
            val candidateHeights = if (defaultHeight == 32) intArrayOf(32, 48) else intArrayOf(48, 32)
            for (testH in candidateHeights) {
                val ok = try {
                    val dummy = FloatBuffer.allocate(3 * testH * 160)
                    OnnxTensor.createTensor(env, dummy, longArrayOf(1L, 3L, testH.toLong(), 160L)).use { t ->
                        rec.run(Collections.singletonMap(inputName, t)).use { }
                    }
                    true
                } catch (_: Throwable) {
                    false
                }
                if (ok) return dynW to testH
            }
            dynW to defaultHeight
        } catch (_: Throwable) {
            false to defaultHeight
        }
    }

    private fun loadDictionaryFromAsset(context: Context, assetPath: String, target: MutableList<String>): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        target.add(line)
                        line = reader.readLine()
                    }
                }
            }
            target.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun loadMultilingualDictionaries(context: Context) {
        v1Dictionary.clear()
        multilingualDictionary.clear()
        v5Dictionary.clear()
        cyrillicAssetDict.clear()
        latinAssetDict.clear()

        for (asset in listOf("eslav_ppocrv5_dict.txt", "cyrillic_dict.txt", "cyrillic_keys.txt")) {
            if (cyrillicAssetDict.isNotEmpty()) break
            loadDictionaryFromAsset(context, "models/rec/$asset", cyrillicAssetDict)
        }
        for (asset in listOf("latin_ppocrv5_dict.txt", "latin_dict.txt")) {
            if (latinAssetDict.isNotEmpty()) break
            loadDictionaryFromAsset(context, "models/rec/$asset", latinAssetDict)
        }

        loadDictionaryFromAsset(context, "models/rec/ppocr_keys_v1.txt", v1Dictionary)
        loadDictionaryFromAsset(context, "models/rec/multilingual_keys.txt", multilingualDictionary)
        loadDictionaryFromAsset(context, "models/rec/ppocrv5_dict.txt", v5Dictionary)
    }

    private fun resolveVocabularyForLogits(vocabSize: Int, headKind: RecHeadKind): List<String> {
        if (headKind == RecHeadKind.CYRILLIC_ESLAV) {
            if (cyrillicAssetDict.isNotEmpty() &&
                (vocabSize == cyrillicAssetDict.size + 1 || vocabSize == cyrillicAssetDict.size + 2 || vocabSize in 135..240)
            ) {
                return cyrillicAssetDict
            }
            if (vocabSize == OFFICIAL_PADDLE_CYRILLIC_BASE_DICT.size + 1 ||
                vocabSize == OFFICIAL_PADDLE_CYRILLIC_BASE_DICT.size + 2 ||
                vocabSize in 135..200
            ) {
                return OFFICIAL_PADDLE_CYRILLIC_BASE_DICT
            }
            if (vocabSize == OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT.size + 1 ||
                vocabSize == OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT.size + 2 ||
                vocabSize in 201..250
            ) {
                return OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT
            }
        }

        val orderedDicts = listOf(
            cyrillicAssetDict,
            OFFICIAL_PADDLE_CYRILLIC_BASE_DICT,
            OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT,
            latinAssetDict,
            OFFICIAL_PADDLE_LATIN_DICT,
            v1Dictionary,
            multilingualDictionary,
            v5Dictionary
        )
        for (dict in orderedDicts) {
            if (dict.isNotEmpty() && (vocabSize == dict.size + 1 || vocabSize == dict.size + 2)) {
                return dict
            }
        }

        if (vocabSize == BASE_PPOCR_V1_KEY_COUNT + 2 || vocabSize == BASE_PPOCR_V1_KEY_COUNT + 1) {
            if (v1Dictionary.size >= BASE_PPOCR_V1_KEY_COUNT) {
                return v1Dictionary.subList(0, BASE_PPOCR_V1_KEY_COUNT)
            }
            if (multilingualDictionary.size >= BASE_PPOCR_V1_KEY_COUNT) {
                return multilingualDictionary.subList(0, BASE_PPOCR_V1_KEY_COUNT)
            }
        }

        if (vocabSize in 135..200) {
            if (cyrillicAssetDict.isNotEmpty() && cyrillicAssetDict.size <= vocabSize - 1) {
                return cyrillicAssetDict
            }
            return OFFICIAL_PADDLE_CYRILLIC_BASE_DICT
        }
        if (vocabSize in 201..235) {
            return OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT
        }
        if (vocabSize in 236..340) {
            return if (latinAssetDict.isNotEmpty()) latinAssetDict else OFFICIAL_PADDLE_LATIN_DICT
        }

        return when {
            v5Dictionary.isNotEmpty() && vocabSize > 7000 -> v5Dictionary
            v1Dictionary.isNotEmpty() && vocabSize >= 6000 -> v1Dictionary
            multilingualDictionary.isNotEmpty() && vocabSize >= 6000 -> multilingualDictionary
            cyrillicAssetDict.isNotEmpty() -> cyrillicAssetDict
            else -> OFFICIAL_PADDLE_CYRILLIC_BASE_DICT
        }
    }

    private fun copyFirstAvailableAssetToCache(context: Context, candidatePaths: List<String>): File? {
        for (assetPath in candidatePaths) {
            try {
                val outFile = File(context.cacheDir, assetPath.replace('/', '_'))
                if (outFile.exists() && outFile.length() > 1024L) return outFile
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (outFile.exists() && outFile.length() > 1024L) {
                    return outFile
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }



    internal fun splitIntoGraphemes(text: String): List<String> =
        OcrLexiconAndTypography.splitIntoGraphemes(text)

    internal fun getRelativeGraphemeWeight(g: String): Float =
        OcrLexiconAndTypography.getRelativeGraphemeWeight(g)

    internal fun normalizeIntraWordHomoglyphs(word: String): String =
        OcrLexiconAndTypography.normalizeIntraWordHomoglyphs(word)

    internal fun resolveShowcaseLineFontSize(line: String): Float =
        OcrLexiconAndTypography.resolveShowcaseLineFontSize(line)

    suspend fun recognize(
        bitmap: Bitmap,
        pageTextHint: String = "",
        zoomPermille: Int = 1000,
        tileX: Int = 0,
        tileY: Int = 0,
        docWidth: Float = 0f,
        docHeight: Float = 0f,
        preferredLanguageCode: String = ""
    ): OcrResultPayload = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) {
            return@withContext OcrResultPayload(emptyList(), emptyList())
        }

        if (!isInitialized) {
            cachedAppContext?.let { ctx -> initialize(ctx) }
        }

        if (glyphPrototypes.isEmpty()) {
            synchronized(initLock) {
                ensureTopologicalGlyphBankLocked()
            }
        }

        val workingBitmap = flattenTransparentPixelsToWhite(bitmap)
        try {
            return@withContext recognizeInternal(
                bitmap = workingBitmap,
                pageTextHint = pageTextHint,
                zoomPermille = zoomPermille,
                tileX = tileX,
                tileY = tileY,
                docWidth = docWidth,
                docHeight = docHeight,
                preferredLanguageCode = preferredLanguageCode
            )
        } finally {
            if (workingBitmap !== bitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    private fun flattenTransparentPixelsToWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(src, 0f, 0f, null)
            out
        } catch (_: Throwable) {
            src
        }
    }

    private suspend fun recognizeInternal(
        bitmap: Bitmap,
        pageTextHint: String,
        zoomPermille: Int,
        tileX: Int,
        tileY: Int,
        docWidth: Float,
        docHeight: Float,
        preferredLanguageCode: String
    ): OcrResultPayload {

        val cleanHint = pageTextHint.trim()
        val zoom = (zoomPermille / 1000f).coerceIn(0.25f, 8.0f)
        val pageLexicon = extractPageLexicon(cleanHint)

        if (cleanHint.isNotEmpty()) {
            if (isFontGlyphGridPageHint(cleanHint)) {
                val gridPayload = buildFontGlyphGridPayload(bitmap, cleanHint, zoom, tileX, tileY)
                if (gridPayload.chars.isNotEmpty()) {
                    return gridPayload
                }
            }
            if (isFontSpecimenOverviewPageHint(cleanHint)) {
                val specimenPayload =
                    buildFontSpecimenOverviewPayload(bitmap, cleanHint, zoom, tileX, tileY)
                if (specimenPayload.chars.isNotEmpty()) {
                    return specimenPayload
                }
            }
            if (cleanHint.contains("[PDF_BOX:")) {
                val pdfBoxPayload = buildPdfNativeBoxAlignedPayload(
                    bitmap = bitmap,
                    rawHint = cleanHint,
                    zoom = zoom,
                    tileX = tileX,
                    tileY = tileY,
                    docWidth = docWidth,
                    docHeight = docHeight
                )
                if (pdfBoxPayload.chars.isNotEmpty()) {
                    val maskedRects = pdfBoxPayload.lines.map { it.rect }
                    if (!hasUnmaskedForegroundInk(bitmap, maskedRects)) {
                        return pdfBoxPayload
                    }
                    /* Hybrid PDF page (contains both native vector text and embedded raster scans/stamps): */
                    /* mask out native vector boxes on a copy and run OCR strictly on remaining raster regions, */
                    /* then stitch both layers together seamlessly in 2D reading order. */
                    val maskedBmp = createMaskedBitmapCopy(bitmap, maskedRects)
                    if (maskedBmp != null) {
                        try {
                            coroutineContext.ensureActive()
                            val rasterBoxes = detectTextLineBoxesHybrid(maskedBmp)
                            if (rasterBoxes.isNotEmpty()) {
                                coroutineContext.ensureActive()
                                val rasterPayload = recognizeDetectedBoxesPureRaster(
                                    bitmap = maskedBmp,
                                    boxes = rasterBoxes,
                                    startLineIndex = 0,
                                    pageLexicon = pageLexicon,
                                    preferredLanguageCode = preferredLanguageCode
                                )
                                if (rasterPayload.chars.isNotEmpty()) {
                                    return stitchNativeAndRasterPayloadsSeamlessly(
                                        nativePayload = pdfBoxPayload,
                                        rasterPayload = rasterPayload
                                    )
                                }
                            }
                        } finally {
                            if (!maskedBmp.isRecycled) maskedBmp.recycle()
                        }
                    }
                    return pdfBoxPayload
                }
            }
        }

        coroutineContext.ensureActive()
        val detectedBoxes = detectTextLineBoxesHybrid(bitmap)
        coroutineContext.ensureActive()

        var neuralPayload: OcrResultPayload? = null
        if (detectedBoxes.isNotEmpty() && hasAnyRecSession()) {
            neuralPayload = recognizeDetectedBoxesPureRaster(
                bitmap = bitmap,
                boxes = detectedBoxes,
                startLineIndex = 0,
                pageLexicon = pageLexicon,
                preferredLanguageCode = preferredLanguageCode
            )
            if (neuralPayload.chars.isNotEmpty()) {
                val contradictsNativeText = zoom <= 1.18f &&
                        isRecognizedTextInconsistentWithLexicon(neuralPayload.lines, pageLexicon)
                if (!contradictsNativeText) {
                    return neuralPayload
                }
            }
        }

        if (cleanHint.isNotEmpty()) {
            val alignedPayload = alignDetectedBoxesWithPageHint(
                bitmap = bitmap,
                detectedBoxes = detectedBoxes,
                pageTextHint = cleanHint,
                pageLexicon = pageLexicon,
                zoom = zoom,
                tileX = tileX,
                tileY = tileY,
                preferredLanguageCode = preferredLanguageCode
            )
            if (alignedPayload.chars.isNotEmpty()) {
                return alignedPayload
            }
        }

        return neuralPayload ?: recognizeDetectedBoxesPureRaster(
            bitmap = bitmap,
            boxes = detectedBoxes,
            startLineIndex = 0,
            pageLexicon = pageLexicon,
            preferredLanguageCode = preferredLanguageCode
        )
    }


    private suspend fun buildPdfNativeBoxAlignedPayload(
        bitmap: Bitmap,
        rawHint: String,
        zoom: Float,
        tileX: Int,
        tileY: Int,
        docWidth: Float,
        docHeight: Float
    ): OcrResultPayload {
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        val effDocW = if (docWidth > 0f) docWidth else bmpW
        val effDocH = if (docHeight > 0f) docHeight else bmpH
        val baseScale = minOf(bmpW / effDocW, bmpH / effDocH).coerceAtLeast(0.05f)
        val totalScale = baseScale * zoom
        val scaledW = effDocW * totalScale
        val scaledH = effDocH * totalScale
        val dx = (bmpW - scaledW) / 2f - tileX.toFloat()
        val dy = (bmpH - scaledH) / 2f - tileY.toFloat()

        val linesOut = ArrayList<OcrRecognizedLine>()
        val charsOut = ArrayList<OcrRecognizedChar>()

        for (rawLine in rawHint.lines()) {
            coroutineContext.ensureActive()
            val match = PDF_NATIVE_BOX_LINE_REGEX.matchEntire(rawLine.trim()) ?: continue
            val normL = match.groupValues[1].toFloatOrNull() ?: continue
            val normT = match.groupValues[2].toFloatOrNull() ?: continue
            val normR = match.groupValues[3].toFloatOrNull() ?: continue
            val normB = match.groupValues[4].toFloatOrNull() ?: continue
            val text = match.groupValues[5].trim()
            if (text.isEmpty()) continue

            val rawLeft = (dx + normL * scaledW).roundToInt()
            val rawTop = (dy + normT * scaledH).roundToInt()
            val rawRight = (dx + normR * scaledW).roundToInt()
            val rawBottom = (dy + normB * scaledH).roundToInt()

            if (rawRight <= 4 || rawBottom <= 4 || rawLeft >= bitmap.width - 4 || rawTop >= bitmap.height - 4) {
                continue
            }

            val nativeBox = Rect(rawLeft, rawTop, maxOf(rawRight, rawLeft + 2), maxOf(rawBottom, rawTop + 2))
            val boxH = nativeBox.height().coerceAtLeast(8)
            val padX = (boxH * 0.15f).roundToInt().coerceIn(2, 12)
            val padY = (boxH * 0.14f).roundToInt().coerceIn(2, 8)
            val paddedBox = Rect(
                nativeBox.left - padX,
                nativeBox.top - padY,
                nativeBox.right + padX,
                nativeBox.bottom + padY
            )
            val inkBox = Rect(nativeBox.left - 1, nativeBox.top - 1, nativeBox.right + 1, nativeBox.bottom + 1)
            val isFullyVisible = inkBox.left >= 0 && inkBox.top >= 0 &&
                    inkBox.right <= bitmap.width && inkBox.bottom <= bitmap.height

            val lineIdx = linesOut.size
            if (isFullyVisible) {
                emitAlignedGraphemesWithSubComponents(
                    bitmap = bitmap,
                    box = inkBox,
                    lineStr = text,
                    lineIndex = lineIdx,
                    outChars = charsOut
                )
            } else {
                emitGraphemesIntoBox(
                    lineText = text,
                    box = nativeBox,
                    lineIndex = lineIdx,
                    outChars = charsOut
                )
            }
            linesOut.add(OcrRecognizedLine(text, paddedBox))
        }

        return OcrResultPayload(linesOut, charsOut)
    }


    private fun detectTextLineBoxesHybrid(bitmap: Bitmap): List<Rect> {
        val dbNetBoxes = runDbNetDetectionOnnx(bitmap)
        val contrastBoxes = detectBipolarHighContrastLineBoxes(bitmap)

        if (dbNetBoxes.isEmpty()) {
            return contrastBoxes.filter { isPlausibleTextContrastBox(bitmap, it) }
        }
        if (contrastBoxes.isEmpty()) {
            return dbNetBoxes.map { tightenBoxToForegroundInk(bitmap, it) }
        }

        /* Split DBNet boxes only when DBNet merged multiple vertical lines or wide table columns, */
        /* while keeping intact DBNet boxes for normal single-line phrases on photos. */
        val merged = ArrayList<Rect>(dbNetBoxes.size + contrastBoxes.size)
        for (dbBox in dbNetBoxes) {
            val insideDb = contrastBoxes.filter { cBox ->
                val interLeft = maxOf(dbBox.left, cBox.left)
                val interTop = maxOf(dbBox.top, cBox.top)
                val interRight = minOf(dbBox.right, cBox.right)
                val interBot = minOf(dbBox.bottom, cBox.bottom)
                if (interRight <= interLeft || interBot <= interTop) {
                    false
                } else {
                    val interArea = (interRight - interLeft).toLong() * (interBot - interTop).toLong()
                    val cArea = (cBox.width().toLong() * cBox.height().toLong()).coerceAtLeast(1L)
                    interArea.toFloat() / cArea.toFloat() > 0.70f
                }
            }

            val stackedLines = insideDb
                .filter { it.height() < dbBox.height() * 0.62f && it.height() >= dbBox.height() * 0.18f }
                .sortedBy { it.top }
            var stackedBands = 0
            var stackedCoverage = 0
            var bandBottom = Int.MIN_VALUE
            for (cBox in stackedLines) {
                if (cBox.top >= bandBottom - cBox.height() / 4) {
                    stackedBands++
                    stackedCoverage += cBox.height()
                    bandBottom = cBox.bottom
                } else if (cBox.bottom > bandBottom) {
                    stackedCoverage += cBox.bottom - bandBottom
                    bandBottom = cBox.bottom
                }
            }
            val isMultiLineSplit = stackedBands >= 2 && stackedCoverage >= dbBox.height() * 0.70f

            val isWideRow = (dbBox.width().toFloat() / dbBox.height().coerceAtLeast(1)) > 6.5f
            val columnSegments = insideDb.filter {
                it.width() < dbBox.width() * 0.65f && it.height() >= dbBox.height() * 0.55f
            }.sortedBy { it.left }
            val minColumnGap = dbBox.height() * 1.5f
            var hasColumnGutters = columnSegments.size >= 2
            for (segIdx in 1 until columnSegments.size) {
                if (columnSegments[segIdx].left - columnSegments[segIdx - 1].right < minColumnGap) {
                    hasColumnGutters = false
                    break
                }
            }
            val isMultiColumnSplit = isWideRow && hasColumnGutters

            when {
                isMultiLineSplit -> merged.addAll(stackedLines)
                isMultiColumnSplit -> merged.addAll(columnSegments)
                else -> merged.add(tightenBoxToForegroundInk(bitmap, dbBox))
            }
        }

        /* Recover small or low-contrast text lines missed by DBNet, filtering out non-text photo edges */
        for (cBox in contrastBoxes) {
            val overlapsExisting = merged.any { existing ->
                val interLeft = maxOf(existing.left, cBox.left)
                val interTop = maxOf(existing.top, cBox.top)
                val interRight = minOf(existing.right, cBox.right)
                val interBot = minOf(existing.bottom, cBox.bottom)
                if (interRight <= interLeft || interBot <= interTop) {
                    false
                } else {
                    val interArea = (interRight - interLeft).toLong() * (interBot - interTop).toLong()
                    val minArea = minOf(
                        existing.width().toLong() * existing.height().toLong(),
                        cBox.width().toLong() * cBox.height().toLong()
                    ).coerceAtLeast(1L)
                    interArea.toFloat() / minArea.toFloat() > 0.35f
                }
            }
            if (!overlapsExisting && isPlausibleTextContrastBox(bitmap, cBox)) {
                merged.add(cBox)
            }
        }

        return sortLineBoxesReadingOrder(merged, bitmap.width)
    }

    private fun runDbNetDetectionOnnx(bitmap: Bitmap): List<Rect> {
        return synchronized(ortRunLock) {
            val env = ortEnv ?: return@synchronized emptyList()
            val det = detSession ?: return@synchronized emptyList()
            try {
                val origW = bitmap.width
                val origH = bitmap.height
                val maxSide = 960
                val scale = minOf(1.0f, maxSide.toFloat() / maxOf(origW, origH).coerceAtLeast(1))
                val targetW = (((origW * scale).roundToInt() + 31) / 32 * 32).coerceIn(32, maxSide)
                val targetH = (((origH * scale).roundToInt() + 31) / 32 * 32).coerceIn(32, maxSide)

                val scaledDetBmp = if (targetW == origW && targetH == origH) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                }

                val planeSize = targetW * targetH
                val detPixels = IntArray(planeSize)
                scaledDetBmp.getPixels(detPixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaledDetBmp !== bitmap) {
                    scaledDetBmp.recycle()
                }

                val nchwArray = FloatArray(3 * planeSize)
                val gOffset = planeSize
                val rOffset = planeSize * 2

                for (i in 0 until planeSize) {
                    val color = detPixels[i]
                    val b = ((color and 0xFF) / 255.0f - 0.406f) / 0.225f
                    val g = (((color ushr 8) and 0xFF) / 255.0f - 0.456f) / 0.224f
                    val r = (((color ushr 16) and 0xFF) / 255.0f - 0.485f) / 0.229f
                    nchwArray[i] = b
                    nchwArray[gOffset + i] = g
                    nchwArray[rOffset + i] = r
                }

                val detFloatBuf = FloatBuffer.wrap(nchwArray)
                val detTensor = OnnxTensor.createTensor(
                    env,
                    detFloatBuf,
                    longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
                )
                val detected = ArrayList<Rect>()
                detTensor.use {
                    val inputName = det.inputNames.iterator().next()
                    det.run(Collections.singletonMap(inputName, detTensor)).use { detResult ->
                        val outputValue = detResult.get(0).value
                        if (outputValue is Array<*>) {
                            @Suppress("UNCHECKED_CAST")
                            val probMap = outputValue[0] as? Array<Array<FloatArray>>
                            if (probMap != null && probMap.isNotEmpty()) {
                                detected.addAll(postProcessDbNet(probMap[0], targetW, targetH, origW, origH))
                            }
                        }
                    }
                }
                detected
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }


    private suspend fun alignDetectedBoxesWithPageHint(
        bitmap: Bitmap,
        detectedBoxes: List<Rect>,
        pageTextHint: String,
        pageLexicon: Set<String>,
        zoom: Float,
        tileX: Int,
        tileY: Int,
        preferredLanguageCode: String = ""
    ): OcrResultPayload {
        val hintParagraphs = pageTextHint.lines()
            .map { line ->
                val m = PDF_NATIVE_BOX_LINE_REGEX.matchEntire(line.trim())
                (m?.groupValues?.get(5) ?: line).trim()
            }
            .filter { it.isNotEmpty() }
        if (hintParagraphs.isEmpty()) {
            return recognizeDetectedBoxesPureRaster(bitmap, detectedBoxes, 0, pageLexicon, preferredLanguageCode)
        }

        if (detectedBoxes.isEmpty()) {
            val linesOut = ArrayList<OcrRecognizedLine>()
            val charsOut = ArrayList<OcrRecognizedChar>()
            val padX = (bitmap.width * 0.06f).roundToInt().coerceIn(8, 80)
            val topStart = (bitmap.height * 0.08f).roundToInt()
            val availH = (bitmap.height * 0.84f)
            val stepH = (availH / hintParagraphs.size.coerceAtLeast(1)).coerceIn(20f, 88f)
            for ((idx, para) in hintParagraphs.withIndex()) {
                val top = (topStart + idx * stepH).roundToInt().coerceIn(0, bitmap.height - 8)
                val bot = (top + stepH * 0.82f).roundToInt().clampTo(top + 4, bitmap.height)
                val rect = Rect(padX, top, bitmap.width - padX, bot)
                val lineIdx = linesOut.size
                linesOut.add(OcrRecognizedLine(para, rect))
                emitGraphemesIntoBox(para, rect, lineIdx, charsOut)
            }
            return OcrResultPayload(linesOut, charsOut)
        }

        val linesOut = ArrayList<OcrRecognizedLine>()
        val charsOut = ArrayList<OcrRecognizedChar>()

        val structuralBoxes = detectedBoxes.withIndex().filter { (_, b) ->
            b.height() >= 8 && b.width() >= maxOf(8, (b.height() * 0.65f).roundToInt())
        }.ifEmpty { detectedBoxes.withIndex().toList() }

        /* Group horizontally adjacent boxes on the same visual line into VisualRowGroups */
        class VisualRowGroup(firstIdx: Int, firstBox: Rect) {
            val itemIndices = ArrayList<Int>().apply { add(firstIdx) }
            val boxes = ArrayList<Rect>().apply { add(firstBox) }
            var minTop = firstBox.top
            var maxBottom = firstBox.bottom
            val centerY: Int get() = (minTop + maxBottom) / 2
            val height: Int get() = (maxBottom - minTop).coerceAtLeast(1)
            val totalWidth: Int get() = boxes.sumOf { it.width() }.coerceAtLeast(1)
        }

        val ySortedStructural = structuralBoxes.sortedWith(compareBy({ it.value.centerY() }, { it.value.left }))
        val rowGroups = ArrayList<VisualRowGroup>()
        for ((origIdx, box) in ySortedStructural) {
            val matchingRow = rowGroups.lastOrNull { row ->
                val vOverlap = minOf(row.maxBottom, box.bottom) - maxOf(row.minTop, box.top)
                val minH = minOf(row.height, box.height()).coerceAtLeast(1)
                vOverlap >= minH * 0.45f || abs(row.centerY - box.centerY()) <= minH * 0.45f
            }
            if (matchingRow != null) {
                matchingRow.itemIndices.add(origIdx)
                matchingRow.boxes.add(box)
                if (box.top < matchingRow.minTop) matchingRow.minTop = box.top
                if (box.bottom > matchingRow.maxBottom) matchingRow.maxBottom = box.bottom
            } else {
                rowGroups.add(VisualRowGroup(origIdx, box))
            }
        }
        for (rg in rowGroups) {
            val paired = rg.itemIndices.zip(rg.boxes).sortedBy { it.second.left }
            rg.itemIndices.clear()
            rg.boxes.clear()
            for ((idx, b) in paired) {
                rg.itemIndices.add(idx)
                rg.boxes.add(b)
            }
        }

        val visibleParagraphs: List<String> = if (zoom > 1.18f && hintParagraphs.size > 3) {
            val totalDocH = bitmap.height * zoom
            val centerShiftY = tileY.toFloat()
            val viewTopInDoc = ((totalDocH - bitmap.height) / 2f + centerShiftY).coerceIn(0f, totalDocH)
            val viewBotInDoc = (viewTopInDoc + bitmap.height).coerceIn(0f, totalDocH)
            val startFrac = (viewTopInDoc / totalDocH.coerceAtLeast(1f) - 0.08f).coerceIn(0f, 0.9f)
            val endFrac = (viewBotInDoc / totalDocH.coerceAtLeast(1f) + 0.08f).coerceIn(startFrac + 0.1f, 1.0f)
            val startIdx = (hintParagraphs.size * startFrac).toInt().coerceIn(0, hintParagraphs.lastIndex)
            val endIdx = (hintParagraphs.size * endFrac).roundToInt().clampTo(startIdx + 1, hintParagraphs.size)
            hintParagraphs.subList(startIdx, endIdx)
        } else {
            hintParagraphs
        }

        /* Expand long paragraphs to match the visual row count */
        val avgRowChars = rowGroups.map { rg ->
            ((rg.totalWidth.toFloat() / rg.height.coerceAtLeast(10)) * 1.85f).roundToInt().coerceIn(8, 110)
        }.average().let { if (it.isFinite() && it > 8.0) it.roundToInt() else 52 }

        val expandedHintLines = ArrayList<String>()
        if (visibleParagraphs.size == rowGroups.size) {
            expandedHintLines.addAll(visibleParagraphs)
        } else {
            for (para in visibleParagraphs) {
                if (para.length <= avgRowChars * 1.25f || expandedHintLines.size + (visibleParagraphs.size - 1) >= rowGroups.size) {
                    expandedHintLines.add(para)
                } else {
                    val words = para.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val sb = StringBuilder()
                    for (w in words) {
                        if (sb.isNotEmpty() && sb.length + 1 + w.length > avgRowChars) {
                            expandedHintLines.add(sb.toString())
                            sb.setLength(0)
                        }
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(w)
                    }
                    if (sb.isNotEmpty()) expandedHintLines.add(sb.toString())
                }
            }
        }

        val usedBoxIndices = HashSet<Int>()
        val countToAlign = minOf(expandedHintLines.size, rowGroups.size)

        for (i in 0 until countToAlign) {
            coroutineContext.ensureActive()
            val rg = rowGroups[i]
            val lineStr = expandedHintLines[i]
            rg.itemIndices.forEach { usedBoxIndices.add(it) }

            if (rg.boxes.size == 1) {
                val lineIdx = linesOut.size
                val tightBox = emitAlignedGraphemesWithSubComponents(bitmap, rg.boxes[0], lineStr, lineIdx, charsOut)
                linesOut.add(OcrRecognizedLine(lineStr, tightBox))
            } else {
                /* Multi-column or multi-segment visual row: distribute words proportionally across rg.boxes */
                val words = lineStr.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.size >= rg.boxes.size) {
                    val totalW = rg.totalWidth.toFloat().coerceAtLeast(1f)
                    var wordCursor = 0
                    for (bIdx in rg.boxes.indices) {
                        val segBox = rg.boxes[bIdx]
                        val remBoxes = rg.boxes.size - bIdx - 1
                        val targetWords = if (bIdx == rg.boxes.lastIndex) {
                            words.size - wordCursor
                        } else {
                            maxOf(1, ((segBox.width() / totalW) * words.size).roundToInt())
                                .coerceAtMost((words.size - wordCursor - remBoxes).coerceAtLeast(1))
                        }
                        val subWords = words.subList(wordCursor, (wordCursor + targetWords).coerceAtMost(words.size))
                        wordCursor += targetWords
                        val segText = subWords.joinToString(" ")
                        if (segText.isNotEmpty()) {
                            val lineIdx = linesOut.size
                            val tightBox = emitAlignedGraphemesWithSubComponents(bitmap, segBox, segText, lineIdx, charsOut)
                            linesOut.add(OcrRecognizedLine(segText, tightBox))
                        }
                    }
                } else {
                    val unionBox = Rect(
                        rg.boxes.minOf { it.left },
                        rg.minTop,
                        rg.boxes.maxOf { it.right },
                        rg.maxBottom
                    )
                    val lineIdx = linesOut.size
                    val tightBox = emitAlignedGraphemesWithSubComponents(bitmap, unionBox, lineStr, lineIdx, charsOut)
                    linesOut.add(OcrRecognizedLine(lineStr, tightBox))
                }
            }
        }

        val unalignedBoxes = detectedBoxes.filterIndexed { index, _ -> index !in usedBoxIndices }
        if (unalignedBoxes.isNotEmpty()) {
            val extraRaster = recognizeDetectedBoxesPureRaster(
                bitmap = bitmap,
                boxes = unalignedBoxes,
                startLineIndex = linesOut.size,
                pageLexicon = pageLexicon,
                preferredLanguageCode = preferredLanguageCode
            )
            linesOut.addAll(extraRaster.lines)
            charsOut.addAll(extraRaster.chars)
        }

        return OcrResultPayload(linesOut, charsOut)
    }

    /**
     * Aligns [lineStr] graphemes to the actual foreground ink sub-components (`subGlyphRects`)
     * inside [box]. Works when `subGlyphRects.size == nonSpaceGraphemes.size`, when letters touch
     * inside word clusters (`wordClusters.size == textWords.size`), or by proportionally mapping
     * words across the detected ink clusters.
     */
    private fun emitAlignedGraphemesWithSubComponents(
        bitmap: Bitmap,
        box: Rect,
        lineStr: String,
        lineIndex: Int,
        outChars: MutableList<OcrRecognizedChar>
    ): Rect {
        val graphemes = splitIntoGraphemes(lineStr)
        if (graphemes.isEmpty()) return box

        val subGlyphRects = segmentLineIntoGlyphRects(bitmap, box)
        if (subGlyphRects.isEmpty()) {
            emitGraphemesIntoBox(lineStr, box, lineIndex, outChars)
            return box
        }

        val tightLeft = subGlyphRects.minOf { it.left }.coerceAtLeast(box.left)
        val tightTop = subGlyphRects.minOf { it.top }.coerceAtLeast(box.top)
        val tightRight = subGlyphRects.maxOf { it.right }.clampTo(tightLeft + 2, box.right)
        val tightBottom = subGlyphRects.maxOf { it.bottom }.clampTo(tightTop + 2, box.bottom)
        val tightBox = Rect(tightLeft, tightTop, tightRight, tightBottom)

        val nonSpaceGraphemes = graphemes.filter { it.isNotBlank() }

        /* Case 1: Exact 1-to-1 match between connected ink components and non-space graphemes */
        if (subGlyphRects.size == nonSpaceGraphemes.size &&
            doGlyphRectsFollowGraphemeOrder(subGlyphRects, graphemes, tightBox)
        ) {
            var gIdx = 0
            for (g in graphemes) {
                if (g.isBlank()) {
                    val prevRight = outChars.lastOrNull { it.lineIndex == lineIndex }?.rect?.right ?: tightBox.left
                    val nextLeft = subGlyphRects.getOrNull(gIdx)?.left ?: tightBox.right
                    val spLeft = minOf(prevRight, nextLeft)
                    val spRight = maxOf(spLeft + 1, nextLeft)
                    outChars.add(OcrRecognizedChar(" ", Rect(spLeft, tightBox.top, spRight, tightBox.bottom), lineIndex))
                } else {
                    val r = subGlyphRects[gIdx++]
                    outChars.add(OcrRecognizedChar(g, Rect(r.left, tightBox.top, r.right, tightBox.bottom), lineIndex))
                }
            }
            return tightBox
        }

        /* Case 2: Group subGlyphRects into word clusters separated by inter-word gaps and match against words in lineStr */
        val medianH = subGlyphRects.map { it.height() }.sorted()[subGlyphRects.size / 2].toFloat().coerceAtLeast(8f)
        val wordGapThreshold = (medianH * 0.30f).coerceAtLeast(3.2f)
        val wordClusters = ArrayList<Rect>()
        for (r in subGlyphRects) {
            val prev = wordClusters.lastOrNull()
            if (prev != null && r.left - prev.right < wordGapThreshold) {
                wordClusters[wordClusters.lastIndex] = Rect(
                    minOf(prev.left, r.left),
                    minOf(prev.top, r.top),
                    maxOf(prev.right, r.right),
                    maxOf(prev.bottom, r.bottom)
                )
            } else {
                wordClusters.add(Rect(r))
            }
        }

        val textWords = lineStr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wordClusters.size == textWords.size && textWords.isNotEmpty() &&
            doWordClustersMatchWordWidths(wordClusters, textWords)
        ) {
            for (wIdx in textWords.indices) {
                val clusterRect = wordClusters[wIdx]
                if (wIdx > 0) {
                    val prevRight = wordClusters[wIdx - 1].right
                    val spLeft = minOf(prevRight, clusterRect.left)
                    val spRight = maxOf(spLeft + 1, clusterRect.left)
                    outChars.add(OcrRecognizedChar(" ", Rect(spLeft, tightBox.top, spRight, tightBox.bottom), lineIndex))
                }
                emitGraphemesIntoBox(
                    lineText = textWords[wIdx],
                    box = Rect(clusterRect.left, tightBox.top, clusterRect.right, tightBox.bottom),
                    lineIndex = lineIndex,
                    outChars = outChars
                )
            }
            return tightBox
        }

        /* Case 3: Emit graphemes into the ink-tightened bounding box */
        emitGraphemesIntoBox(lineStr, tightBox, lineIndex, outChars)
        return tightBox
    }

    private fun doGlyphRectsFollowGraphemeOrder(glyphRects: List<Rect>, graphemes: List<String>, lineBox: Rect): Boolean {
        val weights = FloatArray(graphemes.size) { getRelativeGraphemeWeight(graphemes[it]) }
        val totalWeight = weights.sum().coerceAtLeast(0.001f)
        val lineWidth = lineBox.width().coerceAtLeast(1).toFloat()
        val maxCenterDrift = lineWidth / graphemes.size * 0.9f
        var consumedWeight = 0f
        var glyphIdx = 0
        for (i in graphemes.indices) {
            if (graphemes[i].isNotBlank()) {
                val expectedCenter = lineBox.left + lineWidth * (consumedWeight + weights[i] / 2f) / totalWeight
                if (abs(glyphRects[glyphIdx].exactCenterX() - expectedCenter) > maxCenterDrift) return false
                glyphIdx++
            }
            consumedWeight += weights[i]
        }
        return true
    }

    private fun doWordClustersMatchWordWidths(wordClusters: List<Rect>, textWords: List<String>): Boolean {
        val wordWeights = FloatArray(textWords.size) { idx ->
            var sum = 0f
            for (g in splitIntoGraphemes(textWords[idx])) sum += getRelativeGraphemeWeight(g)
            sum
        }
        val totalWeight = wordWeights.sum().coerceAtLeast(0.001f)
        val totalWidth = wordClusters.sumOf { it.width() }.toFloat().coerceAtLeast(1f)
        for (idx in textWords.indices) {
            val expectedShare = wordWeights[idx] / totalWeight
            val actualShare = wordClusters[idx].width() / totalWidth
            val tolerance = maxOf(expectedShare * 0.45f, 0.035f)
            if (abs(actualShare - expectedShare) > tolerance) return false
        }
        return true
    }

    private fun emitCharsUsingCtcSteps(
        ctcResult: CtcDecodeResult,
        paddedLeft: Int,
        paddedW: Int,
        boundedBox: Rect,
        lineIndex: Int,
        outChars: MutableList<OcrRecognizedChar>
    ) {
        val totalSteps = ctcResult.totalTimesteps.coerceAtLeast(1).toFloat()
        val stepToPx = paddedW.toFloat() / totalSteps
        var prevRight = boundedBox.left
        for ((idx, tok) in ctcResult.tokens.withIndex()) {
            val rawL = (paddedLeft + (tok.startStep - 0.35f) * stepToPx).roundToInt()
            val rawR = (paddedLeft + (tok.endStep + 1.35f) * stepToPx).roundToInt()
            val cLeft = maxOf(prevRight, rawL.clampTo(boundedBox.left, boundedBox.right - 1))
            val cRight = if (idx == ctcResult.tokens.lastIndex) {
                maxOf(cLeft + 1, rawR.clampTo(cLeft + 1, boundedBox.right))
            } else {
                rawR.clampTo(cLeft + 1, boundedBox.right)
            }
            val graphemes = splitIntoGraphemes(tok.text)
            if (graphemes.size <= 1) {
                outChars.add(OcrRecognizedChar(tok.text, Rect(cLeft, boundedBox.top, cRight, boundedBox.bottom), lineIndex))
            } else {
                emitGraphemesIntoBox(tok.text, Rect(cLeft, boundedBox.top, cRight, boundedBox.bottom), lineIndex, outChars)
            }
            prevRight = cRight
        }
    }

    private suspend fun recognizeDetectedBoxesPureRaster(
        bitmap: Bitmap,
        boxes: List<Rect>,
        startLineIndex: Int = 0,
        pageLexicon: Set<String> = emptySet(),
        preferredLanguageCode: String = ""
    ): OcrResultPayload {
        if (boxes.isEmpty()) return OcrResultPayload(emptyList(), emptyList())

        val env = ortEnv
        val lines = ArrayList<OcrRecognizedLine>(boxes.size)
        val chars = ArrayList<OcrRecognizedChar>(boxes.size * 12)
        var lineIndex = startLineIndex

        for (rawBox in boxes) {
            coroutineContext.ensureActive()
            val box = tightenBoxToForegroundInk(bitmap, rawBox)
            val cropLeft = box.left.coerceIn(0, bitmap.width - 2)
            val cropTop = box.top.coerceIn(0, bitmap.height - 2)
            val cropW = box.width().coerceIn(1, bitmap.width - cropLeft)
            val cropH = box.height().coerceIn(1, bitmap.height - cropTop)
            if (cropW < 3 || cropH < 4) continue

            val padX = (cropH * 0.14f).roundToInt().coerceIn(2, 10)
            val padY = (cropH * 0.10f).roundToInt().coerceIn(1, 6)
            val paddedLeft = (cropLeft - padX).coerceIn(0, bitmap.width - 2)
            val paddedTop = (cropTop - padY).coerceIn(0, bitmap.height - 2)
            val paddedW = (cropLeft + cropW + padX - paddedLeft).coerceIn(1, bitmap.width - paddedLeft)
            val paddedH = (cropTop + cropH + padY - paddedTop).coerceIn(1, bitmap.height - paddedTop)

            val croppedBmp = Bitmap.createBitmap(bitmap, paddedLeft, paddedTop, paddedW, paddedH)
            try {
                val ctcResult: CtcDecodeResult? = if (env != null && hasAnyRecSession()) {
                    runRecHeadArbitration(croppedBmp, env, pageLexicon, preferredLanguageCode)
                } else null

                val useOnnxResult = ctcResult != null &&
                        ctcResult.text.isNotBlank() &&
                        ctcResult.arbitrationScore >= 0.40f

                val globalTightBox = Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH)

                if (ctcResult != null && useOnnxResult) {
                    val snappedText = snapLineToPageLexicon(ctcResult.text, pageLexicon)
                    val ctcSpanBox = globalTightBox
                    val subGlyphs = segmentLineIntoGlyphRects(bitmap, ctcSpanBox)
                    val nonSpaceCount = splitIntoGraphemes(snappedText).count { it.isNotBlank() }

                    if (subGlyphs.size == nonSpaceCount || subGlyphs.isNotEmpty()) {
                        val refinedBox = emitAlignedGraphemesWithSubComponents(
                            bitmap = bitmap,
                            box = ctcSpanBox,
                            lineStr = snappedText,
                            lineIndex = lineIndex,
                            outChars = chars
                        )
                        lines.add(OcrRecognizedLine(snappedText, refinedBox))
                    } else if (snappedText == ctcResult.text && ctcResult.tokens.isNotEmpty()) {
                        emitCharsUsingCtcSteps(ctcResult, paddedLeft, paddedW, ctcSpanBox, lineIndex, chars)
                        lines.add(OcrRecognizedLine(snappedText, ctcSpanBox))
                    } else {
                        emitGraphemesIntoBox(snappedText, ctcSpanBox, lineIndex, chars)
                        lines.add(OcrRecognizedLine(snappedText, ctcSpanBox))
                    }
                    lineIndex++
                } else {
                    val tier3Line = recognizeLineViaTopologicalClassifier(
                        fullBitmap = bitmap,
                        globalBox = globalTightBox,
                        ctcHint = ctcResult,
                        lineIndex = lineIndex,
                        pageLexicon = pageLexicon
                    )
                    if (tier3Line != null && tier3Line.first.text.isNotBlank()) {
                        lines.add(tier3Line.first)
                        chars.addAll(tier3Line.second)
                        lineIndex++
                    } else if (ctcResult != null && ctcResult.text.isNotBlank()) {
                        val snappedText = snapLineToPageLexicon(ctcResult.text, pageLexicon)
                        val ctcSpanBox = globalTightBox
                        val refinedBox = emitAlignedGraphemesWithSubComponents(
                            bitmap = bitmap,
                            box = ctcSpanBox,
                            lineStr = snappedText,
                            lineIndex = lineIndex,
                            outChars = chars
                        )
                        lines.add(OcrRecognizedLine(snappedText, refinedBox))
                        lineIndex++
                    }
                }
            } finally {
                if (croppedBmp !== bitmap && !croppedBmp.isRecycled) {
                    croppedBmp.recycle()
                }
            }
        }

        return OcrResultPayload(lines, chars)
    }


    private fun hasAnyRecSession(): Boolean =
        primaryRecSession != null || cyrillicRecSession != null || latinRecSession != null

    private fun runRecHead(
        headKind: RecHeadKind,
        cropBmp: Bitmap,
        env: OrtEnvironment,
        pageLexicon: Set<String>
    ): CtcDecodeResult? {
        val session: OrtSession
        val supportsDynamicW: Boolean
        val targetH: Int
        when (headKind) {
            RecHeadKind.PRIMARY_MULTILINGUAL -> {
                session = primaryRecSession ?: return null
                supportsDynamicW = primarySupportsDynamicWidth
                targetH = primaryInputHeight
            }
            RecHeadKind.CYRILLIC_ESLAV -> {
                session = cyrillicRecSession ?: return null
                supportsDynamicW = cyrillicSupportsDynamicWidth
                targetH = cyrillicInputHeight
            }
            RecHeadKind.LATIN -> {
                session = latinRecSession ?: return null
                supportsDynamicW = latinSupportsDynamicWidth
                targetH = latinInputHeight
            }
        }
        return runOnnxRecognitionWithDynamicSlicing(
            cropBmp = cropBmp,
            env = env,
            rec = session,
            supportsDynamicW = supportsDynamicW,
            targetH = targetH,
            headKind = headKind,
            pageLexicon = pageLexicon
        )
    }

    private fun containsHan(text: String): Boolean =
        text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    private fun isCyrillicDominant(text: String): Boolean {
        var letters = 0
        var cyrillic = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            if (ch in '\u0400'..'\u052F') cyrillic++
        }
        return cyrillic > 0 && cyrillic * 5 >= letters
    }

    private fun countNonBlankChars(text: String): Int = text.count { !it.isWhitespace() }

    private fun computeTimelineCoverage(res: CtcDecodeResult): Float {
        var firstStep = -1
        var lastStep = -1
        for (tok in res.tokens) {
            if (tok.text.isBlank()) continue
            if (firstStep < 0) firstStep = tok.startStep
            lastStep = tok.endStep
        }
        if (firstStep < 0) return 0f
        return (lastStep - firstStep + 1).toFloat() / res.totalTimesteps.coerceAtLeast(1)
    }

    private fun runRecHeadArbitration(
        cropBmp: Bitmap,
        env: OrtEnvironment,
        pageLexicon: Set<String> = emptySet(),
        preferredLanguageCode: String = ""
    ): CtcDecodeResult? {
        val lang = preferredLanguageCode.lowercase(Locale.ROOT)
        val expectsHan = lang.startsWith("zh") || lang.startsWith("ja") || pageLexicon.any { containsHan(it) }

        var primaryRes: CtcDecodeResult? = null
        var primaryTried = false
        if (expectsHan) {
            primaryRes = runRecHead(RecHeadKind.PRIMARY_MULTILINGUAL, cropBmp, env, pageLexicon)
            primaryTried = true
            if (primaryRes != null && containsHan(primaryRes.text) && primaryRes.averageConfidence >= 0.80f) {
                return primaryRes
            }
        }

        val eslavRes = runRecHead(RecHeadKind.CYRILLIC_ESLAV, cropBmp, env, pageLexicon)
        var best: CtcDecodeResult? = eslavRes?.takeIf { it.text.isNotBlank() }
        if (best == null || !isCyrillicDominant(best.text)) {
            val latinRes = runRecHead(RecHeadKind.LATIN, cropBmp, env, pageLexicon)?.takeIf { it.text.isNotBlank() }
            if (latinRes != null &&
                (best == null || countNonBlankChars(latinRes.text) * 10 >= countNonBlankChars(best.text) * 7)
            ) {
                best = latinRes
            }
        }

        if (!primaryTried && (best == null || computeTimelineCoverage(best) < 0.70f)) {
            primaryRes = runRecHead(RecHeadKind.PRIMARY_MULTILINGUAL, cropBmp, env, pageLexicon)
        }
        if (primaryRes != null && primaryRes.text.isNotBlank()) {
            if (best == null) return primaryRes
            if (containsHan(primaryRes.text) && primaryRes.averageConfidence >= 0.60f) return primaryRes
        }
        return best ?: primaryRes
    }

    /**
     * Computes a normalized script-coherence and lexicon-grounded arbitration score for a candidate recognition result.
     */
    private fun computeScriptArbitrationScore(
        text: String,
        avgMarginalConfidence: Float,
        headKind: RecHeadKind,
        pageLexicon: Set<String> = emptySet(),
        counterpartText: String = "",
        counterpartConfidence: Float = 0f
    ): Float {
        if (text.isBlank()) return 0f

        val lexiconHasHan = pageLexicon.any { w -> w.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
        val lexiconHasCyr = pageLexicon.any { w -> w.any { it in '\u0400'..'\u052F' } }
        val lexiconHasLat = pageLexicon.any { w -> w.any { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' } }

        /* Compensate for the smaller softmax denominator of the 192-class Cyrillic dictionary vs 6625-class Multilingual dictionary */
        val calibratedBaseConf = if (headKind == RecHeadKind.CYRILLIC_ESLAV) {
            (avgMarginalConfidence - 0.055f).coerceAtLeast(0f)
        } else {
            avgMarginalConfidence
        }

        var penalty = 0f
        var bonus = 0f

        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var totalCyr = 0
        var totalUniqueCyr = 0
        var totalLat = 0
        var totalHan = 0
        var totalDigitsAndPunct = 0

        for (word in words) {
            var wordHasLat = false
            var wordHasCyr = false
            var wordHasHan = false
            for (ch in word) {
                when {
                    ch in '\u0400'..'\u052F' -> {
                        wordHasCyr = true
                        totalCyr++
                        if (ch !in "АВЕКМНОРСТУХІЇЈаеорсухіїј") {
                            totalUniqueCyr++
                        }
                    }
                    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> {
                        wordHasLat = true
                        totalLat++
                    }
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                        wordHasHan = true
                        totalHan++
                    }
                    ch.isDigit() || ch in ".,:;!?()-+/=%№$€₽" -> {
                        totalDigitsAndPunct++
                    }
                }
            }

            if (wordHasHan && (wordHasLat || wordHasCyr)) {
                penalty += 0.42f
            } else if (wordHasLat && wordHasCyr) {
                penalty += 0.14f
            }

            val cleanWord = word.trim(',', '.', ';', ':', '!', '?', '(', ')', '[', ']', '"', '«', '»')
            if (cleanWord.length >= 2 && pageLexicon.isNotEmpty()) {
                val normalizedWord = normalizeIntraWordHomoglyphs(cleanWord)
                if (cleanWord in pageLexicon ||
                    normalizedWord in pageLexicon ||
                    pageLexicon.any { it.equals(cleanWord, ignoreCase = true) || it.equals(normalizedWord, ignoreCase = true) }
                ) {
                    bonus += 0.18f
                }
            }
        }

        val counterpartHasDistinctCyrillic = counterpartText.any {
            it in '\u0400'..'\u052F' && it !in "АВЕКМНОРСТУХІЇЈаеорсухіїј"
        }

        /* If the primary CJK+Latin head outputs Hanzi on a line where the Cyrillic head strongly recognizes */
        /* distinct Cyrillic letters (Б, Г, Д, Ж, З, И, Й, Л, П, Ф, Ц, Ч, Ш, Щ, Ъ, Ы, Ь, Э, Ю, Я), */
        /* penalize the Hanzi hallucination heavily. */
        if (totalHan > 0 && !lexiconHasHan) {
            if (lexiconHasCyr || (counterpartHasDistinctCyrillic && counterpartConfidence >= 0.68f)) {
                penalty += 0.40f
            } else if (totalCyr + totalLat > totalHan) {
                penalty += 0.28f
            } else if (avgMarginalConfidence < 0.84f && counterpartConfidence >= 0.78f) {
                penalty += 0.22f
            }
        }

        /* Reward the Cyrillic head when it decodes genuine Cyrillic words with distinct Cyrillic characters */
        if (headKind == RecHeadKind.CYRILLIC_ESLAV && totalUniqueCyr >= 1 && avgMarginalConfidence >= 0.72f) {
            bonus += 0.08f
        }

        /* Reward the Primary head when both heads agree on pure Latin/digits or when Primary decodes high-confidence CJK */
        if (headKind == RecHeadKind.PRIMARY_MULTILINGUAL && totalHan >= 2 && avgMarginalConfidence >= 0.86f && !counterpartHasDistinctCyrillic) {
            bonus += 0.08f
        }

        return (calibratedBaseConf - penalty + bonus.coerceAtMost(0.26f)).coerceIn(0f, 1f)
    }

    private fun runOnnxRecognitionWithDynamicSlicing(
        cropBmp: Bitmap,
        env: OrtEnvironment,
        rec: OrtSession,
        supportsDynamicW: Boolean,
        targetH: Int = 48,
        headKind: RecHeadKind,
        pageLexicon: Set<String> = emptySet()
    ): CtcDecodeResult? {
        val effectiveH = targetH.coerceIn(32, 64)
        val aspect = cropBmp.width.toFloat() / cropBmp.height.coerceAtLeast(1)

        if (!supportsDynamicW && aspect > 6.8f) {
            val sliceWidthPx = (cropBmp.height * 5.8f).roundToInt().coerceIn(32, cropBmp.width)
            val combinedTokens = ArrayList<DecodedCtcToken>()
            val combinedText = StringBuilder()
            var confSum = 0f
            var sliceCount = 0
            var curX = 0
            var cumulativeSteps = 0

            while (curX < cropBmp.width) {
                val w = minOf(sliceWidthPx, cropBmp.width - curX)
                if (w < 8) break
                val sliceBmp = Bitmap.createBitmap(cropBmp, curX, 0, w, cropBmp.height)
                val subRes = runSingleCropRecognitionOnnx(sliceBmp, env, rec, effectiveH, 320, headKind, pageLexicon)
                if (sliceBmp !== cropBmp) sliceBmp.recycle()

                if (subRes != null && subRes.text.isNotEmpty()) {
                    combinedText.append(subRes.text)
                    val stepSpan = subRes.totalTimesteps.coerceAtLeast(1)
                    for (tok in subRes.tokens) {
                        combinedTokens.add(
                            tok.copy(
                                startStep = cumulativeSteps + tok.startStep,
                                endStep = cumulativeSteps + tok.endStep
                            )
                        )
                    }
                    cumulativeSteps += stepSpan
                    confSum += subRes.averageConfidence
                    sliceCount++
                }
                curX += w
            }
            if (combinedText.isEmpty()) return null
            val finalStr = combinedText.toString().trim()
            val avgConf = confSum / sliceCount.coerceAtLeast(1)
            return CtcDecodeResult(
                text = finalStr,
                tokens = combinedTokens,
                totalTimesteps = cumulativeSteps.coerceAtLeast(1),
                averageConfidence = avgConf,
                arbitrationScore = computeScriptArbitrationScore(finalStr, avgConf, headKind, pageLexicon),
                headKind = headKind
            )
        }

        val maxTargetW = if (supportsDynamicW) {
            (((effectiveH * aspect).roundToInt() + 7) / 8 * 8).coerceIn(48, 1920)
        } else {
            320
        }
        return runSingleCropRecognitionOnnx(cropBmp, env, rec, effectiveH, maxTargetW, headKind, pageLexicon)
    }

    private fun runSingleCropRecognitionOnnx(
        cropBmp: Bitmap,
        env: OrtEnvironment,
        rec: OrtSession,
        targetH: Int,
        tensorW: Int,
        headKind: RecHeadKind,
        pageLexicon: Set<String> = emptySet()
    ): CtcDecodeResult? {
        return synchronized(ortRunLock) {
            if (!isInitialized) return@synchronized null
            try {
                val aspect = cropBmp.width.toFloat() / cropBmp.height.coerceAtLeast(1)
                val contentW = (targetH * aspect).roundToInt().coerceIn(1, tensorW)

                val tempScaledBmp = Bitmap.createScaledBitmap(cropBmp, contentW, targetH, true)
                val paddedBmp = if (contentW < tensorW) {
                    val padded = Bitmap.createBitmap(tensorW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(padded)
                    val edgeColor = tempScaledBmp.getPixel(contentW - 1, targetH / 2)
                    canvas.drawColor(edgeColor)
                    canvas.drawBitmap(tempScaledBmp, 0f, 0f, null)
                    if (tempScaledBmp !== cropBmp) tempScaledBmp.recycle()
                    padded
                } else {
                    tempScaledBmp
                }

                val planeSize = tensorW * targetH
                val pixels = IntArray(planeSize)
                paddedBmp.getPixels(pixels, 0, tensorW, 0, 0, tensorW, targetH)
                if (paddedBmp !== cropBmp) {
                    paddedBmp.recycle()
                }

                val nchwArray = FloatArray(3 * planeSize)
                val gOffset = planeSize
                val rOffset = planeSize * 2

                for (i in 0 until planeSize) {
                    val color = pixels[i]
                    val b = ((color and 0xFF) / 255.0f - 0.5f) / 0.5f
                    val g = (((color ushr 8) and 0xFF) / 255.0f - 0.5f) / 0.5f
                    val r = (((color ushr 16) and 0xFF) / 255.0f - 0.5f) / 0.5f
                    nchwArray[i] = b
                    nchwArray[gOffset + i] = g
                    nchwArray[rOffset + i] = r
                }

                val floatBuf = FloatBuffer.wrap(nchwArray)
                val tensor = OnnxTensor.createTensor(
                    env,
                    floatBuf,
                    longArrayOf(1L, 3L, targetH.toLong(), tensorW.toLong())
                )

                var result: CtcDecodeResult? = null
                tensor.use {
                    val inputName = rec.inputNames.iterator().next()
                    rec.run(Collections.singletonMap(inputName, tensor)).use { recResult ->
                        val outputValue = recResult.get(0).value
                        if (outputValue is Array<*>) {
                            @Suppress("UNCHECKED_CAST")
                            val logits = outputValue[0] as? Array<FloatArray>
                            if (logits != null && logits.isNotEmpty()) {
                                val activeRatio = contentW.toFloat() / tensorW.coerceAtLeast(1)
                                val validTimesteps = (logits.size * activeRatio).roundToInt().coerceIn(1, logits.size)
                                result = decodeCtcLogitsWithTimesteps(logits, validTimesteps, headKind, pageLexicon)
                            }
                        }
                    }
                }
                result
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun decodeCtcLogitsWithTimesteps(
        logits: Array<FloatArray>,
        validTimesteps: Int,
        headKind: RecHeadKind,
        pageLexicon: Set<String> = emptySet()
    ): CtcDecodeResult {
        val vocabSize = logits.firstOrNull()?.size ?: 0
        val activeVocabulary = resolveVocabularyForLogits(vocabSize, headKind)

        val tokens = ArrayList<DecodedCtcToken>()
        val sb = StringBuilder()
        var prevIndex = 0
        var activeTokenText: String? = null
        var activeStartStep = 0
        var activeMaxProb = 0f
        var confSum = 0f
        var tokenCount = 0

        for (t in 0 until validTimesteps) {
            val stepLogits = logits[t]
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            var minVal = Float.POSITIVE_INFINITY
            var sumRow = 0f

            for (i in stepLogits.indices) {
                val v = stepLogits[i]
                sumRow += v
                if (v < minVal) minVal = v
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = i
                }
            }

            val isAlreadyProbability = minVal >= -1e-4f && maxVal <= 1.0005f && abs(sumRow - 1.0f) <= 0.05f
            val prob = if (isAlreadyProbability) {
                maxVal.coerceIn(0f, 1f)
            } else {
                var expSum = 0.0
                for (i in stepLogits.indices) {
                    val d = stepLogits[i] - maxVal
                    if (d >= -20f) {
                        expSum += exp(d.toDouble())
                    }
                }
                (1.0 / expSum.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            }

            if (maxIdx != 0) {
                val dictIdx = maxIdx - 1
                val isSpaceClass = (dictIdx == activeVocabulary.size) ||
                        (maxIdx == stepLogits.size - 1 && stepLogits.size > activeVocabulary.size + 1)
                val tokenStr = when {
                    isSpaceClass -> " "
                    dictIdx in activeVocabulary.indices -> activeVocabulary[dictIdx]
                    maxIdx == stepLogits.size - 1 -> " "
                    else -> ""
                }
                if (tokenStr.isNotEmpty()) {
                    if (maxIdx == prevIndex && activeTokenText != null) {
                        if (prob > activeMaxProb) activeMaxProb = prob
                    } else {
                        if (activeTokenText != null) {
                            tokens.add(DecodedCtcToken(activeTokenText, activeStartStep, t - 1, activeMaxProb))
                            sb.append(activeTokenText)
                            confSum += activeMaxProb
                            tokenCount++
                        }
                        activeTokenText = tokenStr
                        activeStartStep = t
                        activeMaxProb = prob
                    }
                }
            } else if (activeTokenText != null) {
                tokens.add(DecodedCtcToken(activeTokenText, activeStartStep, t - 1, activeMaxProb))
                sb.append(activeTokenText)
                confSum += activeMaxProb
                tokenCount++
                activeTokenText = null
            }

            prevIndex = maxIdx
        }

        if (activeTokenText != null) {
            tokens.add(DecodedCtcToken(activeTokenText, activeStartStep, validTimesteps - 1, activeMaxProb))
            sb.append(activeTokenText)
            confSum += activeMaxProb
            tokenCount++
        }

        val rawText = sb.toString().trim()
        val finalText = rawText.split(' ').joinToString(" ") { normalizeIntraWordHomoglyphs(it) }
        val avgConf = if (tokenCount > 0) confSum / tokenCount else 0f
        return CtcDecodeResult(
            text = finalText,
            tokens = tokens,
            totalTimesteps = validTimesteps.coerceAtLeast(1),
            averageConfidence = avgConf,
            arbitrationScore = computeScriptArbitrationScore(finalText, avgConf, headKind, pageLexicon),
            headKind = headKind
        )
    }


    fun release() {
        synchronized(initLock) {
            synchronized(ortRunLock) {
                isInitialized = false
                try {
                    detSession?.close()
                    primaryRecSession?.close()
                    cyrillicRecSession?.close()
                    latinRecSession?.close()
                } catch (_: Throwable) {
                }
                detSession = null
                primaryRecSession = null
                cyrillicRecSession = null
                latinRecSession = null
            }
        }
    }
}
