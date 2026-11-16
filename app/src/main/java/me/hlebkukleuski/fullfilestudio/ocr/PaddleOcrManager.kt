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
 */

package me.hlebkukleuski.fullfilestudio.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.text.BreakIterator
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class OcrRecognizedChar(
    val text: String,
    val rect: Rect,
    val lineIndex: Int
) {
    constructor(char: Char, rect: Rect, lineIndex: Int) : this(char.toString(), rect, lineIndex)

    val char: Char
        get() = text.firstOrNull() ?: ' '
}

data class OcrRecognizedLine(
    val text: String,
    val rect: Rect
)

data class OcrResultPayload(
    val lines: List<OcrRecognizedLine>,
    val chars: List<OcrRecognizedChar>
)

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

    private const val BASE_PPOCR_V1_KEY_COUNT = 6623
    private const val FONT_SPECIMEN_DOC_WIDTH = 1280f
    private const val FONT_SPECIMEN_DOC_HEIGHT = 1810f

    private val GLYPH_CELL_ENTRY_REGEX = Regex("""\[U\+([0-9A-Fa-f]{4,6})\s+'([\s\S]*?)'\]""")
    private val PDF_NATIVE_BOX_LINE_REGEX = Regex("""^\[PDF_BOX:([0-9.]+),([0-9.]+),([0-9.]+),([0-9.]+)\](.*)$""")

    private enum class RecHeadKind {
        PRIMARY_MULTILINGUAL,
        CYRILLIC_ESLAV
    }

    private val OFFICIAL_PADDLE_CYRILLIC_BASE_DICT: List<String> = listOf(
        "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ":", ";", "<", "=", ">", "?", "@",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "[", "\\", "]", "^", "_", "`",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "{", "|", "}", "~",
        "É", "é", "Ё", "Ђ", "Ѓ", "Є", "Ѕ", "І", "Ї", "Ј", "Љ", "Њ", "Ћ", "Ќ", "Ў", "Џ",
        "А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ы", "Ь", "Э", "Ю", "Я",
        "а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я",
        "ё", "ђ", "ѓ", "є", "ѕ", "і", "ї", "ј", "љ", "њ", "ћ", "ќ", "ў", "џ", "Ґ", "ґ"
    )

    private val OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT: List<String> = OFFICIAL_PADDLE_CYRILLIC_BASE_DICT + listOf(
        "Ѣ", "ѣ", "Ѳ", "ѳ", "Ѵ", "ѵ", "Ѧ", "ѧ", "Ѩ", "ѩ", "Ѫ", "ѫ", "Ѭ", "ѭ", "Ѯ", "ѯ", "Ѱ", "ѱ", "Ꙋ", "ꙋ", "Ѻ", "ѻ", "Ѿ", "ѿ", "Ҁ", "ҁ",
        "№", "₽", "€", "₴", "«", "»", "—", "–"
    )

    private val OFFICIAL_PADDLE_LATIN_DICT: List<String> = listOf(
        "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ":", ";", "<", "=", ">", "?", "@",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "[", "\\", "]", "^", "_", "`",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "{", "|", "}", "~",
        "¡", "¢", "£", "¤", "¥", "¦", "§", "¨", "©", "ª", "«", "¬", "®", "¯", "°", "±", "²", "³", "´", "µ", "¶", "·", "¸", "¹", "º", "»", "¼", "½", "¾", "¿",
        "À", "Á", "Â", "Ã", "Ä", "Å", "Æ", "Ç", "È", "É", "Ê", "Ë", "Ì", "Í", "Î", "Ï", "Ð", "Ñ", "Ò", "Ó", "Ô", "Õ", "Ö", "×", "Ø", "Ù", "Ú", "Û", "Ü", "Ý", "Þ", "ß",
        "à", "á", "â", "ã", "ä", "å", "æ", "ç", "è", "é", "ê", "ë", "ì", "í", "î", "ï", "ð", "ñ", "ò", "ó", "ô", "õ", "ö", "÷", "ø", "ù", "ú", "û", "ü", "ý", "þ", "ÿ",
        "Ā", "ā", "Ă", "ă", "Ą", "ą", "Ć", "ć", "Ĉ", "ĉ", "Ċ", "ċ", "Č", "č", "Ď", "ď", "Đ", "đ",
        "Ē", "ē", "Ĕ", "ĕ", "Ė", "ė", "Ę", "ę", "Ě", "ě", "Ĝ", "ĝ", "Ğ", "ğ", "Ġ", "ġ", "Ģ", "ģ",
        "Ĥ", "ĥ", "Ħ", "ħ", "Ĩ", "ĩ", "Ī", "ī", "Ĭ", "ĭ", "Į", "į", "İ", "ı", "Œ", "œ",
        "Ĺ", "ĺ", "Ļ", "ļ", "Ľ", "ľ", "Ŀ", "ŀ", "Ł", "ł", "Ń", "ń", "Ņ", "ņ", "Ň", "ň", "Ŋ", "ŋ",
        "Ō", "ō", "Ŏ", "ŏ", "Ő", "ő", "Ŕ", "ŕ", "Ŗ", "ŗ", "Ř", "ř",
        "Ś", "ś", "Ŝ", "ŝ", "Ş", "ş", "Š", "š", "Ţ", "ţ", "Ť", "ť", "Ŧ", "ŧ",
        "Ũ", "ũ", "Ū", "ū", "Ŭ", "ŭ", "Ů", "ů", "Ű", "ű", "Ų", "ų",
        "Ŵ", "ŵ", "Ŷ", "ŷ", "Ÿ", "Ź", "ź", "Ż", "ż", "Ž", "ž", "ẞ", "€"
    )

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
    private var isInitialized = false

    @Volatile
    private var cachedAppContext: Context? = null

    private val v1Dictionary = ArrayList<String>(8192)
    private val multilingualDictionary = ArrayList<String>(8192)
    private val v5Dictionary = ArrayList<String>(16384)
    private val cyrillicAssetDict = ArrayList<String>(256)
    private val latinAssetDict = ArrayList<String>(300)

    private class GlyphPrototype(
        val grapheme: String,
        val isUpper: Boolean,
        val isDigitOrPunct: Boolean,
        val aspectRatio: Float,
        val eulerHoles: Int,
        val holeCenterYNorm: Float,
        val bits16x16: LongArray,
        val zones4x4: FloatArray,
        val normL2: Float
    )

    private val glyphPrototypes = ArrayList<GlyphPrototype>(1400)

    private data class DecodedCtcToken(
        val text: String,
        val startStep: Int,
        val endStep: Int,
        val confidence: Float
    )

    private data class DecodedWordSpan(
        val text: String,
        val tokens: List<DecodedCtcToken>,
        val startStep: Int,
        val endStep: Int,
        val totalSteps: Int,
        val averageConfidence: Float,
        val arbitrationScore: Float
    )

    private data class CtcDecodeResult(
        val text: String,
        val tokens: List<DecodedCtcToken>,
        val totalTimesteps: Int,
        val averageConfidence: Float,
        val arbitrationScore: Float,
        val headKind: RecHeadKind
    )

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
                    !it.contains("cyrillic", ignoreCase = true) && !it.contains("eslav", ignoreCase = true)
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

                synchronized(ortRunLock) {
                    val env = try { OrtEnvironment.getEnvironment() } catch (_: Throwable) { null }
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

        loadDictionaryFromAsset(context, "models/rec/cyrillic_dict.txt", cyrillicAssetDict)
        if (cyrillicAssetDict.isEmpty()) {
            loadDictionaryFromAsset(context, "models/rec/cyrillic_keys.txt", cyrillicAssetDict)
        }
        loadDictionaryFromAsset(context, "models/rec/latin_dict.txt", latinAssetDict)

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

    internal fun splitIntoGraphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length)
        val bi = BreakIterator.getCharacterInstance(Locale.ROOT)
        bi.setText(text)
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = bi.next()
        }
        return out
    }

    private fun getRelativeGraphemeWeight(g: String): Float {
        if (g.isEmpty()) return 1.0f
        val cp = g.codePointAt(0)
        return when {
            cp in 0x1D504..0x1D59F || cp in 0x10330..0x1034A -> 1.15f
            g.length == 1 -> when (g[0]) {
                'W', 'M', 'w', 'm', 'Ш', 'Щ', 'Ж', 'Ю', 'Ы', 'Ф', 'ш', 'щ', 'ж', 'ю', 'ы', 'ф', 'Q', '@', '%', '#', 'Æ', 'Œ' -> 1.45f
                'I', 'l', 'i', 't', 'f', 'j', 'r', '1', '|', '!', ' ', '.', ',', ':', ';', '\'', '`', 'І', 'і', 'Ї', 'ї', 'ł' -> 0.50f
                'J', '(', ')', '[', ']', '{', '}', '/', '\\', '-', '–', '—', '«', '»' -> 0.68f
                else -> 1.0f
            }
            else -> 1.05f
        }
    }

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
                    // Hybrid PDF page (contains both native vector text and embedded raster scans/stamps):
                    // mask out native vector boxes on a copy and run OCR strictly on remaining raster regions,
                    // then stitch both layers together seamlessly in 2D reading order.
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

        if (detectedBoxes.isNotEmpty() && (primaryRecSession != null || cyrillicRecSession != null)) {
            val neuralPayload = recognizeDetectedBoxesPureRaster(
                bitmap = bitmap,
                boxes = detectedBoxes,
                startLineIndex = 0,
                pageLexicon = pageLexicon,
                preferredLanguageCode = preferredLanguageCode
            )
            if (neuralPayload.chars.isNotEmpty()) {
                return neuralPayload
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

        return recognizeDetectedBoxesPureRaster(
            bitmap = bitmap,
            boxes = detectedBoxes,
            startLineIndex = 0,
            pageLexicon = pageLexicon,
            preferredLanguageCode = preferredLanguageCode
        )
    }

    /**
     * Maps Android 15+ native `[PDF_BOX:normL,normT,normR,normB]text` lines onto the rendered [bitmap]
     * and refines each line's bounding box and word/character positions against actual foreground ink pixels.
     */
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

            val boxH = (rawBottom - rawTop).coerceAtLeast(8)
            val padX = (boxH * 0.15f).roundToInt().coerceIn(2, 12)
            val padY = (boxH * 0.14f).roundToInt().coerceIn(2, 8)
            val candidateBox = Rect(
                (rawLeft - padX).coerceIn(0, bitmap.width - 2),
                (rawTop - padY).coerceIn(0, bitmap.height - 2),
                (rawRight + padX).coerceIn((rawLeft - padX).coerceIn(0, bitmap.width - 2) + 2, bitmap.width),
                (rawBottom + padY).coerceIn((rawTop - padY).coerceIn(0, bitmap.height - 2) + 2, bitmap.height)
            )

            val exactNativeBox = Rect(
                rawLeft.coerceIn(0, bitmap.width - 2),
                rawTop.coerceIn(0, bitmap.height - 2),
                rawRight.coerceIn(rawLeft.coerceIn(0, bitmap.width - 2) + 2, bitmap.width),
                rawBottom.coerceIn(rawTop.coerceIn(0, bitmap.height - 2) + 2, bitmap.height)
            )
            val lineIdx = linesOut.size
            emitGraphemesIntoBox(
                lineText = text,
                box = exactNativeBox,
                lineIndex = lineIdx,
                outChars = charsOut
            )
            linesOut.add(OcrRecognizedLine(text, candidateBox))
        }

        return OcrResultPayload(linesOut, charsOut)
    }

    private fun sampleDominantBackgroundColor(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val sampleCoords = intArrayOf(
            2, 2,
            (w - 3).coerceAtLeast(0), 2,
            2, (h - 3).coerceAtLeast(0),
            (w - 3).coerceAtLeast(0), (h - 3).coerceAtLeast(0),
            w / 2, 2,
            w / 2, (h - 3).coerceAtLeast(0)
        )
        var rSum = 0; var gSum = 0; var bSum = 0; var cnt = 0
        var i = 0
        while (i + 1 < sampleCoords.size) {
            val px = bitmap.getPixel(sampleCoords[i].coerceIn(0, w - 1), sampleCoords[i + 1].coerceIn(0, h - 1))
            if (Color.alpha(px) > 200) {
                rSum += Color.red(px)
                gSum += Color.green(px)
                bSum += Color.blue(px)
                cnt++
            }
            i += 2
        }
        return if (cnt > 0) Color.rgb(rSum / cnt, gSum / cnt, bSum / cnt) else Color.WHITE
    }

    private fun hasUnmaskedForegroundInk(bitmap: Bitmap, maskedBoxes: List<Rect>): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return false
        val bg = sampleDominantBackgroundColor(bitmap)
        val bgR = Color.red(bg); val bgG = Color.green(bg); val bgB = Color.blue(bg)
        val step = 4
        var inkHits = 0
        var y = 6
        while (y < h - 6) {
            var x = 6
            while (x < w - 6) {
                var isMasked = false
                for (b in maskedBoxes) {
                    if (x >= b.left - 3 && x <= b.right + 3 && y >= b.top - 3 && y <= b.bottom + 3) {
                        isMasked = true
                        break
                    }
                }
                if (!isMasked) {
                    val px = bitmap.getPixel(x, y)
                    if (Color.alpha(px) > 128) {
                        val diff = abs(Color.red(px) - bgR) + abs(Color.green(px) - bgG) + abs(Color.blue(px) - bgB)
                        if (diff > 96) {
                            inkHits++
                            if (inkHits >= 55) return true
                        }
                    }
                }
                x += step
            }
            y += step
        }
        return false
    }

    private fun createMaskedBitmapCopy(bitmap: Bitmap, maskedBoxes: List<Rect>): Bitmap? {
        return try {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val bg = sampleDominantBackgroundColor(bitmap)
            val canvas = Canvas(copy)
            val erasePaint = Paint().apply {
                color = bg
                style = Paint.Style.FILL
            }
            for (b in maskedBoxes) {
                canvas.drawRect(
                    (b.left - 2f).coerceAtLeast(0f),
                    (b.top - 2f).coerceAtLeast(0f),
                    (b.right + 2f).coerceAtMost(copy.width.toFloat()),
                    (b.bottom + 2f).coerceAtMost(copy.height.toFloat()),
                    erasePaint
                )
            }
            copy
        } catch (_: Throwable) {
            null
        }
    }

    private fun stitchNativeAndRasterPayloadsSeamlessly(
        nativePayload: OcrResultPayload,
        rasterPayload: OcrResultPayload
    ): OcrResultPayload {
        data class LineWithChars(
            val line: OcrRecognizedLine,
            val chars: List<OcrRecognizedChar>
        )

        val nativeCharsByLine = nativePayload.chars.groupBy { it.lineIndex }
        val rasterCharsByLine = rasterPayload.chars.groupBy { it.lineIndex }

        val combined = ArrayList<LineWithChars>(nativePayload.lines.size + rasterPayload.lines.size)
        for (i in nativePayload.lines.indices) {
            val c = nativeCharsByLine[i].orEmpty()
            if (c.isNotEmpty()) combined.add(LineWithChars(nativePayload.lines[i], c))
        }
        for (i in rasterPayload.lines.indices) {
            val rLine = rasterPayload.lines[i]
            val c = rasterCharsByLine[i].orEmpty()
            if (c.isEmpty()) continue
            // Deduplicate if a raster box overlaps > 55% with an existing native PDF line
            val rArea = (rLine.rect.width().coerceAtLeast(1) * rLine.rect.height().coerceAtLeast(1)).toFloat()
            val overlapsNative = nativePayload.lines.any { nLine ->
                val ix = minOf(nLine.rect.right, rLine.rect.right) - maxOf(nLine.rect.left, rLine.rect.left)
                val iy = minOf(nLine.rect.bottom, rLine.rect.bottom) - maxOf(nLine.rect.top, rLine.rect.top)
                ix > 0 && iy > 0 && (ix * iy) / rArea > 0.55f
            }
            if (!overlapsNative) {
                combined.add(LineWithChars(rLine, c))
            }
        }

        // Sort all lines in natural 2D reading order (same visual row left-to-right, then top-to-bottom)
        combined.sortWith { a, b ->
            val ra = a.line.rect
            val rb = b.line.rect
            val vOverlap = minOf(ra.bottom, rb.bottom) - maxOf(ra.top, rb.top)
            val minH = minOf(ra.height(), rb.height()).coerceAtLeast(1)
            if (vOverlap >= minH * 0.45f) {
                ra.left.compareTo(rb.left)
            } else {
                ra.top.compareTo(rb.top)
            }
        }

        val finalLines = ArrayList<OcrRecognizedLine>(combined.size)
        val finalChars = ArrayList<OcrRecognizedChar>(nativePayload.chars.size + rasterPayload.chars.size)
        for ((newLineIdx, item) in combined.withIndex()) {
            finalLines.add(item.line)
            for (ch in item.chars) {
                finalChars.add(ch.copy(lineIndex = newLineIdx))
            }
        }
        return OcrResultPayload(finalLines, finalChars)
    }

    private fun extractPageLexicon(hint: String): Set<String> {
        if (hint.isBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (rawLine in hint.lines()) {
            val m = PDF_NATIVE_BOX_LINE_REGEX.matchEntire(rawLine.trim())
            val lineText = m?.groupValues?.get(5) ?: rawLine
            for (token in lineText.split(Regex("[\\s,.;:!?()\\[\\]\"'«»—–]+"))) {
                val trimmed = token.trim()
                if (trimmed.length in 2..42) {
                    out.add(trimmed)
                }
            }
        }
        return out
    }

    /**
     * Normalizes accidental mixed-script homoglyphs inside a single word (e.g. Latin 'o', 'a', 'e', 'c'
     * inside a predominantly Cyrillic word, or vice versa).
     */
    internal fun normalizeIntraWordHomoglyphs(word: String): String {
        if (word.length < 2) return word
        var cyrCount = 0
        var latCount = 0
        var hasUniqueCyrillic = false
        var hasUniqueLatin = false

        for (ch in word) {
            when {
                ch in '\u0400'..'\u052F' -> {
                    cyrCount++
                    if (ch !in "АВЕКМНОРСТУХІЇЈаеорсухіїј") hasUniqueCyrillic = true
                }
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> {
                    latCount++
                    if (ch !in "ABEKMHOPCTYXIJaekmopcyxij") hasUniqueLatin = true
                }
            }
        }
        if (cyrCount == 0 || latCount == 0) return word

        val convertToCyrillic = when {
            hasUniqueCyrillic && !hasUniqueLatin -> true
            hasUniqueLatin && !hasUniqueCyrillic -> false
            else -> cyrCount >= latCount
        }

        val sb = StringBuilder(word.length)
        if (convertToCyrillic) {
            for (ch in word) {
                sb.append(
                    when (ch) {
                        'A' -> 'А'; 'a' -> 'а'
                        'B' -> 'В'; 'E' -> 'Е'; 'e' -> 'е'
                        'K' -> 'К'; 'k' -> 'к'
                        'M' -> 'М'; 'H' -> 'Н'
                        'O' -> 'О'; 'o' -> 'о'
                        'P' -> 'Р'; 'p' -> 'р'
                        'C' -> 'С'; 'c' -> 'с'
                        'T' -> 'Т'; 'Y' -> 'У'; 'y' -> 'у'
                        'X' -> 'Х'; 'x' -> 'х'
                        'I' -> 'І'; 'i' -> 'і'
                        else -> ch
                    }
                )
            }
        } else {
            for (ch in word) {
                sb.append(
                    when (ch) {
                        'А' -> 'A'; 'а' -> 'a'
                        'В' -> 'B'; 'Е' -> 'E'; 'е' -> 'e'
                        'К' -> 'K'; 'к' -> 'k'
                        'М' -> 'M'; 'Н' -> 'H'
                        'О' -> 'O'; 'о' -> 'o'
                        'Р' -> 'P'; 'р' -> 'p'
                        'С' -> 'C'; 'с' -> 'c'
                        'Т' -> 'T'; 'У' -> 'Y'; 'у' -> 'y'
                        'Х' -> 'X'; 'х' -> 'x'
                        'І' -> 'I'; 'і' -> 'i'
                        else -> ch
                    }
                )
            }
        }
        return sb.toString()
    }

    private fun snapLineToPageLexicon(rawLine: String, pageLexicon: Set<String>): String {
        if (rawLine.isBlank()) return rawLine
        val words = rawLine.split(' ')
        val corrected = words.map { rawWord ->
            val w = normalizeIntraWordHomoglyphs(rawWord)
            if (pageLexicon.isEmpty()) {
                w
            } else {
                val clean = w.trim(',', '.', ';', ':', '!', '?', '(', ')', '[', ']', '"', '«', '»')
                if (clean.length < 2 || clean in pageLexicon) {
                    w
                } else {
                    val normalizedHomoglyphs = normalizeHomoglyphsToTargetScript(clean, pageLexicon)
                    if (normalizedHomoglyphs in pageLexicon) {
                        w.replaceFirst(clean, normalizedHomoglyphs)
                    } else {
                        var bestMatch: String? = null
                        var bestDist = Int.MAX_VALUE
                        val maxAllowedDist = when {
                            clean.length >= 8 -> 2
                            clean.length >= 4 -> 1
                            else -> 0
                        }
                        if (maxAllowedDist > 0) {
                            for (candidate in pageLexicon) {
                                if (abs(candidate.length - normalizedHomoglyphs.length) > maxAllowedDist) continue
                                val dist = levenshteinDistanceIgnoreCase(normalizedHomoglyphs, candidate, maxAllowedDist)
                                if (dist <= maxAllowedDist && dist < bestDist) {
                                    bestDist = dist
                                    bestMatch = candidate
                                }
                            }
                        }
                        if (bestMatch != null) {
                            w.replaceFirst(clean, bestMatch)
                        } else {
                            w
                        }
                    }
                }
            }
        }
        return corrected.joinToString(" ")
    }

    private fun normalizeHomoglyphsToTargetScript(word: String, pageLexicon: Set<String>): String {
        if (pageLexicon.isEmpty()) return word
        val lexiconHasCyrillic = pageLexicon.any { tok -> tok.any { it in '\u0400'..'\u052F' } }
        val lexiconHasLatin = pageLexicon.any { tok -> tok.any { it in 'A'..'Z' || it in 'a'..'z' } }
        if (!lexiconHasCyrillic && !lexiconHasLatin) return word
        val sb = StringBuilder(word.length)
        if (lexiconHasCyrillic && !lexiconHasLatin) {
            for (ch in word) {
                val mapped = when (ch) {
                    'A' -> 'А'; 'a' -> 'а'
                    'B' -> 'В'; 'E' -> 'Е'; 'e' -> 'е'
                    'K' -> 'К'; 'k' -> 'к'
                    'M' -> 'М'; 'H' -> 'Н'
                    'O', '0' -> 'О'; 'o' -> 'о'
                    'P' -> 'Р'; 'p' -> 'р'
                    'C' -> 'С'; 'c' -> 'с'
                    'T' -> 'Т'; 'Y' -> 'У'; 'y' -> 'y'
                    'X' -> 'Х'; 'x' -> 'х'
                    else -> ch
                }
                sb.append(mapped)
            }
        } else if (lexiconHasLatin && !lexiconHasCyrillic) {
            for (ch in word) {
                val mapped = when (ch) {
                    'А' -> 'A'; 'а' -> 'a'
                    'В' -> 'B'; 'Е' -> 'E'; 'е' -> 'e'
                    'К' -> 'K'; 'к' -> 'k'
                    'М' -> 'M'; 'Н' -> 'H'
                    'О' -> 'O'; 'о' -> 'o'
                    'Р' -> 'P'; 'р' -> 'p'
                    'С' -> 'C'; 'с' -> 'c'
                    'Т' -> 'T'; 'У' -> 'Y'; 'у' -> 'y'
                    'Х' -> 'X'; 'х' -> 'x'
                    'І' -> 'I'; 'і' -> 'i'
                    else -> ch
                }
                sb.append(mapped)
            }
        } else {
            return normalizeIntraWordHomoglyphs(word)
        }
        return sb.toString()
    }

    private fun levenshteinDistanceIgnoreCase(a: String, b: String, maxThreshold: Int): Int {
        val lenA = a.length
        val lenB = b.length
        if (abs(lenA - lenB) > maxThreshold) return maxThreshold + 1
        var prev = IntArray(lenB + 1) { it }
        var curr = IntArray(lenB + 1)
        for (i in 1..lenA) {
            curr[0] = i
            var minInRow = curr[0]
            val ca = a[i - 1].lowercaseChar()
            for (j in 1..lenB) {
                val cb = b[j - 1].lowercaseChar()
                val cost = if (ca == cb) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
                if (curr[j] < minInRow) minInRow = curr[j]
            }
            if (minInRow > maxThreshold) return maxThreshold + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[lenB]
    }

    private fun isFontGlyphGridPageHint(hint: String): Boolean {
        return hint.startsWith("[U+", ignoreCase = true) && GLYPH_CELL_ENTRY_REGEX.containsMatchIn(hint)
    }

    private fun isFontSpecimenOverviewPageHint(hint: String): Boolean {
        return hint.contains("UnitsPerEm:") && hint.contains("SFNT Tables")
    }

    private fun buildFontGlyphGridPayload(
        bitmap: Bitmap,
        hint: String,
        zoom: Float,
        tileX: Int,
        tileY: Int
    ): OcrResultPayload {
        val matches = GLYPH_CELL_ENTRY_REGEX.findAll(hint).toList()
        if (matches.isEmpty()) return OcrResultPayload(emptyList(), emptyList())

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        val baseScale = minOf(bmpW / FONT_SPECIMEN_DOC_WIDTH, bmpH / FONT_SPECIMEN_DOC_HEIGHT).coerceAtLeast(0.1f)
        val totalScale = baseScale * zoom
        val dx = (bmpW - FONT_SPECIMEN_DOC_WIDTH * totalScale) / 2f - tileX.toFloat()
        val dy = (bmpH - FONT_SPECIMEN_DOC_HEIGHT * totalScale) / 2f - tileY.toFloat()

        val margin = 40f
        val gridTop = 96f
        val gridCols = 8
        val gridRows = 8
        val cellW = (FONT_SPECIMEN_DOC_WIDTH - margin * 2f) / gridCols
        val cellH = (FONT_SPECIMEN_DOC_HEIGHT - gridTop - margin) / gridRows

        val lines = ArrayList<OcrRecognizedLine>(gridRows * 2)
        val chars = ArrayList<OcrRecognizedChar>(matches.size * 8)

        val rowsGrouped = matches.withIndex().groupBy { it.index / gridCols }
        for ((rowIndex, rowEntries) in rowsGrouped) {
            if (rowIndex >= gridRows) break
            val rawGlyphTop = (dy + (gridTop + (rowIndex + 0.10f) * cellH) * totalScale).roundToInt()
            val rawGlyphBot = (dy + (gridTop + (rowIndex + 0.64f) * cellH) * totalScale).roundToInt()
            val rawCodeTop = (dy + (gridTop + (rowIndex + 0.76f) * cellH) * totalScale).roundToInt()
            val rawCodeBot = (dy + (gridTop + (rowIndex + 0.96f) * cellH) * totalScale).roundToInt()
            if (rawCodeBot <= 0 || rawGlyphTop >= bitmap.height) continue

            val glyphTopPx = rawGlyphTop.coerceIn(0, bitmap.height - 4)
            val glyphBotPx = rawGlyphBot.coerceIn(glyphTopPx + 2, bitmap.height - 2)
            val codeTopPx = rawCodeTop.coerceIn(glyphBotPx + 1, bitmap.height - 2)
            val codeBotPx = rawCodeBot.coerceIn(codeTopPx + 1, bitmap.height)

            var rowLeftPx = bitmap.width
            var rowRightPx = 0
            val glyphLineBuilder = StringBuilder()
            val codeLineBuilder = StringBuilder()
            val glyphLineIdx = lines.size
            val codeLineIdx = lines.size + 1
            val pendingCodeChars = ArrayList<OcrRecognizedChar>()

            for ((itemIndex, matchResult) in rowEntries) {
                val colIndex = itemIndex % gridCols
                val hexPart = matchResult.groupValues[1].uppercase(Locale.ROOT)
                val codeHexStr = "U+$hexPart"
                val codePoint = hexPart.toIntOrNull(16)
                val glyphStr = if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                    String(Character.toChars(codePoint))
                } else {
                    matchResult.groupValues[2]
                }

                val rawLeft = (dx + (margin + colIndex * cellW + 8f) * totalScale).roundToInt()
                val rawRight = (dx + (margin + (colIndex + 1) * cellW - 8f) * totalScale).roundToInt()
                if (rawRight <= 0 || rawLeft >= bitmap.width) continue

                val cLeft = rawLeft.coerceIn(0, bitmap.width - 2)
                val cRight = rawRight.coerceIn(cLeft + 1, bitmap.width)

                if (cLeft < rowLeftPx) rowLeftPx = cLeft
                if (cRight > rowRightPx) rowRightPx = cRight

                if (glyphLineBuilder.isNotEmpty()) {
                    val prevRight = chars.last().rect.right
                    val spRect = Rect(prevRight, glyphTopPx, maxOf(prevRight + 1, cLeft), glyphBotPx)
                    chars.add(OcrRecognizedChar(" ", spRect, glyphLineIdx))
                    glyphLineBuilder.append(' ')
                }
                val glyphRect = Rect(cLeft, glyphTopPx, cRight, glyphBotPx)
                chars.add(OcrRecognizedChar(glyphStr, glyphRect, glyphLineIdx))
                glyphLineBuilder.append(glyphStr)

                if (codeLineBuilder.isNotEmpty() && pendingCodeChars.isNotEmpty()) {
                    val prevCodeRight = pendingCodeChars.last().rect.right
                    val spCodeRect = Rect(prevCodeRight, codeTopPx, maxOf(prevCodeRight + 1, cLeft), codeBotPx)
                    pendingCodeChars.add(OcrRecognizedChar(" ", spCodeRect, codeLineIdx))
                    codeLineBuilder.append(' ')
                }
                val codeRect = Rect(cLeft, codeTopPx, cRight, codeBotPx)
                emitGraphemesIntoBox(codeHexStr, codeRect, codeLineIdx, pendingCodeChars)
                codeLineBuilder.append(codeHexStr)
            }

            if (glyphLineBuilder.isNotEmpty() && rowRightPx > rowLeftPx) {
                lines.add(OcrRecognizedLine(glyphLineBuilder.toString(), Rect(rowLeftPx, glyphTopPx, rowRightPx, glyphBotPx)))
                lines.add(OcrRecognizedLine(codeLineBuilder.toString(), Rect(rowLeftPx, codeTopPx, rowRightPx, codeBotPx)))
                chars.addAll(pendingCodeChars)
            }
        }

        return OcrResultPayload(lines, chars)
    }

    internal fun resolveShowcaseLineFontSize(line: String): Float {
        var hasLatin = false
        var hasCyrillicOrSlavonic = false
        var hasDigitOrPunct = false
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            when {
                cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code -> hasLatin = true
                cp in 0x0400..0x052F || cp in 0x2DE0..0x2DFF || cp in 0xA640..0xA69F -> hasCyrillicOrSlavonic = true
                cp != ' '.code -> hasDigitOrPunct = true
            }
            i += Character.charCount(cp)
        }
        return when {
            hasLatin -> 38f
            hasCyrillicOrSlavonic -> 36f
            hasDigitOrPunct -> 34f
            else -> 38f
        }
    }

    private fun isPangramCandidateLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("PANGRAM:", ignoreCase = true)) return true
        if (trimmed.contains("Съешь", ignoreCase = true) ||
            trimmed.contains("мягких", ignoreCase = true) ||
            trimmed.contains("французских", ignoreCase = true) ||
            trimmed.contains("булок", ignoreCase = true) ||
            trimmed.contains("выпей", ignoreCase = true) ||
            trimmed.contains("чаю", ignoreCase = true) ||
            trimmed.contains("quick", ignoreCase = true) ||
            trimmed.contains("brown", ignoreCase = true) ||
            trimmed.contains("jumps", ignoreCase = true) ||
            trimmed.contains("lazy", ignoreCase = true)
        ) {
            return true
        }
        if (trimmed.endsWith("1234567890") && trimmed.count { it == ' ' } >= 3 && !trimmed.contains("!\"#")) {
            return true
        }
        return false
    }

    private fun buildFontSpecimenOverviewPayload(
        bitmap: Bitmap,
        hint: String,
        zoom: Float,
        tileX: Int,
        tileY: Int
    ): OcrResultPayload {
        val rawLines = hint.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return OcrResultPayload(emptyList(), emptyList())

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        val baseScale = minOf(bmpW / FONT_SPECIMEN_DOC_WIDTH, bmpH / FONT_SPECIMEN_DOC_HEIGHT).coerceAtLeast(0.1f)
        val totalScale = baseScale * zoom
        val dx = (bmpW - FONT_SPECIMEN_DOC_WIDTH * totalScale) / 2f - tileX.toFloat()
        val dy = (bmpH - FONT_SPECIMEN_DOC_HEIGHT * totalScale) / 2f - tileY.toFloat()

        val leftPadDoc = 68f
        val maxRightDoc = FONT_SPECIMEN_DOC_WIDTH - 68f
        val maxContentWidthDoc = maxRightDoc - leftPadDoc

        val linesOut = ArrayList<OcrRecognizedLine>()
        val charsOut = ArrayList<OcrRecognizedChar>()

        fun estimateLineRightDoc(text: String, leftDoc: Float, fontSizeDoc: Float): Float {
            val graphemes = splitIntoGraphemes(text)
            var weightedCount = 0f
            for (g in graphemes) {
                weightedCount += getRelativeGraphemeWeight(g)
            }
            val estWidth = (weightedCount * fontSizeDoc * 0.56f).coerceIn(fontSizeDoc * 1.2f, maxRightDoc - leftDoc)
            return (leftDoc + estWidth).coerceAtMost(maxRightDoc)
        }

        fun emitVirtualDocLine(
            text: String,
            leftDoc: Float,
            baselineYDoc: Float,
            fontSizeDoc: Float,
            useFullWidth: Boolean = false
        ) {
            val rightDoc = if (useFullWidth) maxRightDoc else estimateLineRightDoc(text, leftDoc, fontSizeDoc)
            val topDoc = baselineYDoc - fontSizeDoc * 0.88f
            val botDoc = baselineYDoc + fontSizeDoc * 0.28f
            val rawLeftPx = (dx + leftDoc * totalScale).roundToInt()
            val rawRightPx = (dx + rightDoc * totalScale).roundToInt()
            val rawTopPx = (dy + topDoc * totalScale).roundToInt()
            val rawBotPx = (dy + botDoc * totalScale).roundToInt()
            if (rawBotPx <= 0 || rawTopPx >= bitmap.height || rawRightPx <= 0 || rawLeftPx >= bitmap.width) return

            val lPx = rawLeftPx.coerceIn(0, bitmap.width - 2)
            val rPx = rawRightPx.coerceIn(lPx + 1, bitmap.width)
            val tPx = rawTopPx.coerceIn(0, bitmap.height - 2)
            val bPx = rawBotPx.coerceIn(tPx + 1, bitmap.height)
            val rect = Rect(lPx, tPx, rPx, bPx)
            val lineIdx = linesOut.size
            linesOut.add(OcrRecognizedLine(text, rect))
            emitGraphemesIntoBox(text, rect, lineIdx, charsOut)
        }

        val headerLines = ArrayList<String>()
        val showcaseLines = ArrayList<String>()
        val pangramTemplates = ArrayList<String>()

        var passedSfntTables = false
        for (l in rawLines) {
            when {
                !passedSfntTables -> {
                    headerLines.add(l)
                    if (l.startsWith("SFNT Tables")) passedSfntTables = true
                }
                isPangramCandidateLine(l) -> pangramTemplates.add(l.removePrefix("PANGRAM:").trim())
                else -> showcaseLines.add(l)
            }
        }

        if (pangramTemplates.isEmpty() && showcaseLines.size >= 2 && showcaseLines.last() == showcaseLines.first()) {
            pangramTemplates.add(showcaseLines.removeAt(showcaseLines.lastIndex))
        } else if (pangramTemplates.isEmpty() && showcaseLines.isNotEmpty()) {
            pangramTemplates.add(showcaseLines.first())
        }

        var yDoc = 96f
        if (headerLines.isNotEmpty()) {
            emitVirtualDocLine(headerLines[0], leftPadDoc, yDoc, 68f)
            yDoc += 44f
        }
        if (headerLines.size >= 2) {
            emitVirtualDocLine(headerLines[1], leftPadDoc, yDoc, 24f, useFullWidth = true)
            yDoc += 34f
        }
        for (idx in 2 until headerLines.size) {
            val hLine = headerLines[idx]
            emitVirtualDocLine(hLine, leftPadDoc, yDoc, 21f)
            yDoc += when {
                hLine.startsWith("SFNT Tables") -> 26f
                hLine.startsWith("PostScript:") || hLine.startsWith("Version:") || hLine.startsWith("Designer:") -> 28f
                else -> 30f
            }
        }

        yDoc += 46f

        for (sLine in showcaseLines) {
            val sz = resolveShowcaseLineFontSize(sLine)
            emitVirtualDocLine(sLine, leftPadDoc, yDoc, sz)
            yDoc += sz * 1.38f
        }

        yDoc += 12f + 48f

        val waterfallSizes = intArrayOf(22, 28, 36, 48, 62)
        for (pt in waterfallSizes) {
            if (yDoc + pt * 1.4f > FONT_SPECIMEN_DOC_HEIGHT - 54f) break
            for (pangram in pangramTemplates) {
                if (yDoc + pt * 1.3f > FONT_SPECIMEN_DOC_HEIGHT - 48f) break
                val maxCharsAtPt = ((maxContentWidthDoc - 76f) / (pt * 0.54f)).toInt().coerceAtLeast(8)
                val visiblePangram = if (pangram.length > maxCharsAtPt) {
                    pangram.take(maxCharsAtPt).trimEnd()
                } else {
                    pangram
                }
                emitVirtualDocLine("${pt}px $visiblePangram", leftPadDoc, yDoc, pt.toFloat())
                yDoc += pt * 1.32f
            }
            yDoc += 6f
        }

        return OcrResultPayload(linesOut, charsOut)
    }

    private fun emitGraphemesIntoBox(
        lineText: String,
        box: Rect,
        lineIndex: Int,
        outChars: MutableList<OcrRecognizedChar>
    ) {
        val graphemes = splitIntoGraphemes(lineText)
        if (graphemes.isEmpty()) return
        val boxW = box.width().coerceAtLeast(1)
        val weights = FloatArray(graphemes.size) { getRelativeGraphemeWeight(graphemes[it]) }
        val totalWeight = weights.sum().coerceAtLeast(0.001f)

        var curX = box.left.toFloat()
        for (i in graphemes.indices) {
            val gW = boxW * (weights[i] / totalWeight)
            val cLeft = curX.roundToInt().coerceIn(box.left, maxOf(box.left, box.right - 1))
            val cRight = if (i == graphemes.lastIndex) {
                box.right.coerceAtLeast(cLeft + 1)
            } else {
                (curX + gW).roundToInt().coerceIn(cLeft + 1, maxOf(cLeft + 1, box.right))
            }
            outChars.add(OcrRecognizedChar(graphemes[i], Rect(cLeft, box.top, cRight, box.bottom), lineIndex))
            curX += gW
        }
    }

    /**
     * Tightens a candidate line bounding box to the actual foreground ink pixels inside it,
     * eliminating excess horizontal/vertical whitespace margins from DBNet unclipping or contrast dilation.
     */
    private fun tightenBoxToForegroundInk(bitmap: Bitmap, candidate: Rect): Rect {
        val subGlyphs = segmentLineIntoGlyphRects(bitmap, candidate)
        if (subGlyphs.isEmpty()) return candidate
        val minL = subGlyphs.minOf { it.left }.coerceAtLeast(candidate.left)
        val minT = subGlyphs.minOf { it.top }.coerceAtLeast(candidate.top)
        val maxR = subGlyphs.maxOf { it.right }.coerceIn(minL + 2, candidate.right)
        val maxB = subGlyphs.maxOf { it.bottom }.coerceIn(minT + 2, candidate.bottom)
        return Rect(minL, minT, maxR, maxB)
    }

    private fun isPlausibleTextContrastBox(bitmap: Bitmap, box: Rect): Boolean {
        val w = box.width()
        val h = box.height()
        if (w < 8 || h < 8) return false
        if (h > bitmap.height * 0.25f) return false
        // Filter out thin vertical divider lines or huge non-text photo texture blocks
        val aspect = w.toFloat() / h.toFloat()
        if (aspect < 0.45f && h > 28) return false
        val glyphs = segmentLineIntoGlyphRects(bitmap, box)
        return glyphs.isNotEmpty()
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

        // Split DBNet boxes only when DBNet merged multiple vertical lines or wide table columns,
        // while keeping intact DBNet boxes for normal single-line phrases on photos.
        val merged = ArrayList<Rect>(dbNetBoxes.size + contrastBoxes.size)
        for (dbBox in dbNetBoxes) {
            val subContrast = contrastBoxes.filter { cBox ->
                val interLeft = maxOf(dbBox.left, cBox.left)
                val interTop = maxOf(dbBox.top, cBox.top)
                val interRight = minOf(dbBox.right, cBox.right)
                val interBot = minOf(dbBox.bottom, cBox.bottom)
                if (interRight <= interLeft || interBot <= interTop) {
                    false
                } else {
                    val interArea = (interRight - interLeft).toLong() * (interBot - interTop).toLong()
                    val cArea = (cBox.width().toLong() * cBox.height().toLong()).coerceAtLeast(1L)
                    val mostlyInsideDb = interArea.toFloat() / cArea.toFloat() > 0.70f
                    val isMultiLineSplit = cBox.height() < dbBox.height() * 0.62f
                    val isMultiColumnSplit = cBox.width() < dbBox.width() * 0.65f &&
                            (dbBox.width().toFloat() / dbBox.height().coerceAtLeast(1)) > 6.5f
                    mostlyInsideDb && (isMultiLineSplit || isMultiColumnSplit)
                }
            }
            if (subContrast.size >= 2) {
                merged.addAll(subContrast)
            } else {
                merged.add(tightenBoxToForegroundInk(bitmap, dbBox))
            }
        }

        // Recover small or low-contrast text lines missed by DBNet, filtering out non-text photo edges
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

    private fun postProcessDbNet(
        probPlane: Array<FloatArray>,
        detW: Int,
        detH: Int,
        origW: Int,
        origH: Int
    ): List<Rect> {
        val boxes = ArrayList<Rect>()
        try {
            val scaleX = origW.toFloat() / detW.coerceAtLeast(1)
            val scaleY = origH.toFloat() / detH.coerceAtLeast(1)
            val visited = BooleanArray(detH * detW)
            val queue = IntArray(detH * detW)
            val thresh = 0.26f

            for (r in 0 until detH) {
                val rowOff = r * detW
                val probRow = probPlane[r]
                for (c in 0 until detW) {
                    val flatIdx = rowOff + c
                    if (!visited[flatIdx] && probRow[c] > thresh) {
                        var minR = r
                        var maxR = r
                        var minC = c
                        var maxC = c
                        var probSum = 0f
                        var pixelCount = 0
                        var perimeterCount = 0

                        var qHead = 0
                        var qTail = 0
                        queue[qTail++] = (r shl 16) or c
                        visited[flatIdx] = true

                        while (qHead < qTail) {
                            val curr = queue[qHead++]
                            val cr = curr ushr 16
                            val cc = curr and 0xFFFF
                            probSum += probPlane[cr][cc]
                            pixelCount++

                            if (cr < minR) minR = cr
                            if (cr > maxR) maxR = cr
                            if (cc < minC) minC = cc
                            if (cc > maxC) maxC = cc

                            var isBoundary = false
                            if (cr > 0) {
                                val nr = cr - 1
                                val nIdx = nr * detW + cc
                                if (probPlane[nr][cc] > thresh) {
                                    if (!visited[nIdx]) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (nr shl 16) or cc
                                    }
                                } else isBoundary = true
                            } else isBoundary = true

                            if (cr + 1 < detH) {
                                val nr = cr + 1
                                val nIdx = nr * detW + cc
                                if (probPlane[nr][cc] > thresh) {
                                    if (!visited[nIdx]) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (nr shl 16) or cc
                                    }
                                } else isBoundary = true
                            } else isBoundary = true

                            if (cc > 0) {
                                val nc = cc - 1
                                val nIdx = cr * detW + nc
                                if (probPlane[cr][nc] > thresh) {
                                    if (!visited[nIdx]) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (cr shl 16) or nc
                                    }
                                } else isBoundary = true
                            } else isBoundary = true

                            if (cc + 1 < detW) {
                                val nc = cc + 1
                                val nIdx = cr * detW + nc
                                if (probPlane[cr][nc] > thresh) {
                                    if (!visited[nIdx]) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (cr shl 16) or nc
                                    }
                                } else isBoundary = true
                            } else isBoundary = true

                            if (isBoundary) perimeterCount++
                        }

                        val boxW = maxC - minC + 1
                        val boxH = maxR - minR + 1
                        val meanProb = probSum / pixelCount.coerceAtLeast(1)
                        if (boxW >= 2 && boxH >= 2 && meanProb >= 0.32f) {
                            val rectPerimeter = 2f * (boxW + boxH)
                            val effPerimeter = maxOf(perimeterCount.toFloat(), rectPerimeter * 0.75f).coerceAtLeast(4f)
                            val unclipDist = (pixelCount.toFloat() * 1.65f) / effPerimeter
                            val unclipY = unclipDist.roundToInt().coerceIn(1, maxOf(6, (boxH * 0.65f).roundToInt()))
                            val unclipX = (unclipDist * 1.25f).roundToInt().coerceIn(2, maxOf(10, (boxH * 0.95f).roundToInt()))

                            val unclipLeft = ((minC - unclipX) * scaleX).roundToInt().coerceIn(0, origW - 2)
                            val unclipTop = ((minR - unclipY) * scaleY).roundToInt().coerceIn(0, origH - 2)
                            val unclipRight = ((maxC + 1 + unclipX) * scaleX).roundToInt().coerceIn(unclipLeft + 1, origW)
                            val unclipBottom = ((maxR + 1 + unclipY) * scaleY).roundToInt().coerceIn(unclipTop + 1, origH)

                            boxes.add(Rect(unclipLeft, unclipTop, unclipRight, unclipBottom))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return sortLineBoxesReadingOrder(boxes, origW)
    }

    /**
     * Splits a horizontal line band into separate column/phrase segments whenever there is a wide
     * vertical gap without ink edges (`gap >= maxOf(lineGridH * 1.55f, 9)`), preventing adjacent
     * table cells or multi-column text from merging into a single full-width selection bar.
     */
    private fun splitRowIntoHorizontalSegments(
        edgeMask: BooleanArray,
        gridW: Int,
        minX: Int,
        maxX: Int,
        subMinY: Int,
        subMaxY: Int,
        step: Int,
        origW: Int,
        origH: Int,
        outBoxes: MutableList<Rect>
    ) {
        val lineGridH = (subMaxY - subMinY + 1).coerceAtLeast(1)
        val maxIntraPhraseGap = maxOf((lineGridH * 1.55f).roundToInt(), 9)

        var segStartX = -1
        var lastInkX = -1
        var segEdgePixels = 0

        fun flushSegment(x0: Int, x1: Int, pixelsCount: Int) {
            if (x1 < x0 || pixelsCount < 3) return
            var tightMinY = subMaxY
            var tightMaxY = subMinY
            var tightMinX = x1
            var tightMaxX = x0
            for (gy in subMinY..subMaxY) {
                val rOff = gy * gridW
                for (gx in x0..x1) {
                    if (edgeMask[rOff + gx]) {
                        if (gy < tightMinY) tightMinY = gy
                        if (gy > tightMaxY) tightMaxY = gy
                        if (gx < tightMinX) tightMinX = gx
                        if (gx > tightMaxX) tightMaxX = gx
                    }
                }
            }
            if (tightMaxY < tightMinY || tightMaxX < tightMinX) return
            val bw = (tightMaxX - tightMinX + 1) * step
            val bh = (tightMaxY - tightMinY + 1) * step
            if (bw >= 3 && bh >= 5) {
                val padX = (bh * 0.10f).roundToInt().coerceIn(1, 6)
                val padY = (bh * 0.08f).roundToInt().coerceIn(1, 4)
                val left = (tightMinX * step - padX).coerceIn(0, origW - 2)
                val top = (tightMinY * step - padY).coerceIn(0, origH - 2)
                val right = ((tightMaxX + 1) * step + padX).coerceIn(left + 1, origW)
                val bottom = ((tightMaxY + 1) * step + padY).coerceIn(top + 1, origH)
                outBoxes.add(Rect(left, top, right, bottom))
            }
        }

        for (gx in minX..maxX) {
            var colInk = 0
            for (gy in subMinY..subMaxY) {
                if (edgeMask[gy * gridW + gx]) colInk++
            }
            if (colInk > 0) {
                if (segStartX < 0) {
                    segStartX = gx
                    segEdgePixels = colInk
                } else if (gx - lastInkX > maxIntraPhraseGap) {
                    flushSegment(segStartX, lastInkX, segEdgePixels)
                    segStartX = gx
                    segEdgePixels = colInk
                } else {
                    segEdgePixels += colInk
                }
                lastInkX = gx
            }
        }
        if (segStartX >= 0 && lastInkX >= segStartX) {
            flushSegment(segStartX, lastInkX, segEdgePixels)
        }
    }

    /**
     * Splits any multi-line bounding box into individual horizontal text line boxes using
     * horizontal edge projection valleys, and then splits each line horizontally across wide
     * column gaps.
     */
    private fun splitTallBoxIntoHorizontalLines(
        edgeMask: BooleanArray,
        gridW: Int,
        gridH: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        step: Int,
        origW: Int,
        origH: Int,
        outBoxes: MutableList<Rect>
    ) {
        val boxGridH = maxY - minY + 1
        val boxGridW = maxX - minX + 1
        if (boxGridH <= 0 || boxGridW <= 0) return

        val rowCounts = IntArray(boxGridH)
        var maxRowCount = 0
        for (ry in 0 until boxGridH) {
            val gy = minY + ry
            val rowOff = gy * gridW
            var c = 0
            for (gx in minX..maxX) {
                if (edgeMask[rowOff + gx]) c++
            }
            rowCounts[ry] = c
            if (c > maxRowCount) maxRowCount = c
        }

        if (maxRowCount == 0) return
        val valleyThreshold = maxOf(1, (maxRowCount * 0.14f).roundToInt())

        val lineRanges = ArrayList<IntRange>()
        var inLine = false
        var lineStart = 0
        for (ry in 0 until boxGridH) {
            if (rowCounts[ry] > valleyThreshold) {
                if (!inLine) {
                    inLine = true
                    lineStart = ry
                }
            } else if (inLine) {
                if (ry - lineStart >= 3) {
                    lineRanges.add(lineStart until ry)
                }
                inLine = false
            }
        }
        if (inLine && boxGridH - lineStart >= 3) {
            lineRanges.add(lineStart until boxGridH)
        }

        if (lineRanges.isEmpty()) {
            lineRanges.add(0 until boxGridH)
        }

        for (range in lineRanges) {
            val subMinY = minY + range.first
            val subMaxY = minY + range.last
            splitRowIntoHorizontalSegments(
                edgeMask = edgeMask,
                gridW = gridW,
                minX = minX,
                maxX = maxX,
                subMinY = subMinY,
                subMaxY = subMaxY,
                step = step,
                origW = origW,
                origH = origH,
                outBoxes = outBoxes
            )
        }
    }

    private fun detectBipolarHighContrastLineBoxes(bitmap: Bitmap): List<Rect> {
        val origW = bitmap.width
        val origH = bitmap.height
        val step = if (maxOf(origW, origH) > 1200) 2 else 1
        val gridW = origW / step
        val gridH = origH / step
        if (gridW < 16 || gridH < 16) return emptyList()

        val pixels = IntArray(origW * origH)
        bitmap.getPixels(pixels, 0, origW, 0, 0, origW, origH)

        val luma = IntArray(gridW * gridH)
        for (gy in 0 until gridH) {
            val srcRow = (gy * step) * origW
            val dstRow = gy * gridW
            for (gx in 0 until gridW) {
                val c = pixels[srcRow + gx * step]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                luma[dstRow + gx] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }

        val edgeMask = BooleanArray(gridW * gridH)
        val contrastThreshold = 32
        for (gy in 2 until gridH - 2) {
            val row = gy * gridW
            for (gx in 2 until gridW - 2) {
                val idx = row + gx
                val center = luma[idx]
                val diffH = maxOf(abs(center - luma[idx - 1]), abs(center - luma[idx + 1]))
                val diffH2 = maxOf(abs(center - luma[idx - 2]), abs(center - luma[idx + 2]))
                val diffV = maxOf(abs(center - luma[idx - gridW]), abs(center - luma[idx + gridW]))
                if (maxOf(diffH, diffH2) >= contrastThreshold || diffV >= contrastThreshold + 6) {
                    edgeMask[idx] = true
                }
            }
        }

        // Suppress long vertical and horizontal border lines (table frames, page card borders)
        val minVertLineRun = (gridH * 30) / 100
        for (gx in 1 until gridW - 1) {
            var runStart = -1
            for (gy in 0 until gridH) {
                if (edgeMask[gy * gridW + gx]) {
                    if (runStart < 0) runStart = gy
                } else if (runStart >= 0) {
                    if (gy - runStart >= minVertLineRun) {
                        for (clearY in runStart until gy) edgeMask[clearY * gridW + gx] = false
                    }
                    runStart = -1
                }
            }
            if (runStart >= 0 && gridH - runStart >= minVertLineRun) {
                for (clearY in runStart until gridH) edgeMask[clearY * gridW + gx] = false
            }
        }

        val minHorizLineRun = (gridW * 32) / 100
        for (gy in 1 until gridH - 1) {
            val rowOff = gy * gridW
            var runStart = -1
            for (gx in 0 until gridW) {
                if (edgeMask[rowOff + gx]) {
                    if (runStart < 0) runStart = gx
                } else if (runStart >= 0) {
                    if (gx - runStart >= minHorizLineRun) {
                        for (clearX in runStart until gx) edgeMask[rowOff + clearX] = false
                    }
                    runStart = -1
                }
            }
            if (runStart >= 0 && gridW - runStart >= minHorizLineRun) {
                for (clearX in runStart until gridW) edgeMask[rowOff + clearX] = false
            }
        }

        // Horizontal-only morphological dilation: merges intra-word characters without bridging adjacent lines vertically
        val dilated = BooleanArray(gridW * gridH)
        val radiusX = (gridW / 120).coerceIn(2, 6)
        for (gy in 1 until gridH - 1) {
            val row = gy * gridW
            var lastEdgeX = -1000
            for (gx in 0 until gridW) {
                if (edgeMask[row + gx]) lastEdgeX = gx
                if (gx - lastEdgeX <= radiusX) dilated[row + gx] = true
            }
            var nextEdgeX = 100000
            for (gx in gridW - 1 downTo 0) {
                if (edgeMask[row + gx]) nextEdgeX = gx
                if (nextEdgeX - gx <= radiusX) dilated[row + gx] = true
            }
        }

        val visited = BooleanArray(gridW * gridH)
        val queue = IntArray(gridW * gridH)
        val rawBoxes = ArrayList<Rect>()

        for (gy in 1 until gridH - 1) {
            val row = gy * gridW
            for (gx in 1 until gridW - 1) {
                val startIdx = row + gx
                if (dilated[startIdx] && !visited[startIdx]) {
                    var minX = gx
                    var maxX = gx
                    var minY = gy
                    var maxY = gy
                    var count = 0

                    var qHead = 0
                    var qTail = 0
                    queue[qTail++] = (gy shl 16) or gx
                    visited[startIdx] = true

                    while (qHead < qTail) {
                        val curr = queue[qHead++]
                        val cy = curr ushr 16
                        val cx = curr and 0xFFFF
                        count++

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        for (ny in maxOf(0, cy - 1)..minOf(gridH - 1, cy + 1)) {
                            val nRow = ny * gridW
                            for (nx in maxOf(0, cx - 1)..minOf(gridW - 1, cx + 1)) {
                                val nIdx = nRow + nx
                                if (dilated[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[qTail++] = (ny shl 16) or nx
                                }
                            }
                        }
                    }

                    val bw = (maxX - minX + 1) * step
                    val bh = (maxY - minY + 1) * step
                    if (bw >= 3 && bh >= 5 && count >= 4) {
                        if (bh >= 20) {
                            splitTallBoxIntoHorizontalLines(
                                edgeMask = edgeMask,
                                gridW = gridW,
                                gridH = gridH,
                                minX = minX,
                                maxX = maxX,
                                minY = minY,
                                maxY = maxY,
                                step = step,
                                origW = origW,
                                origH = origH,
                                outBoxes = rawBoxes
                            )
                        } else {
                            splitRowIntoHorizontalSegments(
                                edgeMask = edgeMask,
                                gridW = gridW,
                                minX = minX,
                                maxX = maxX,
                                subMinY = minY,
                                subMaxY = maxY,
                                step = step,
                                origW = origW,
                                origH = origH,
                                outBoxes = rawBoxes
                            )
                        }
                    }
                }
            }
        }

        return sortLineBoxesReadingOrder(rawBoxes, origW)
    }

    private fun sortLineBoxesReadingOrder(boxes: List<Rect>, pageWidth: Int = 1920): List<Rect> {
        if (boxes.size <= 1) return boxes
        val heights = boxes.map { it.height() }.sorted()
        val medianH = heights[heights.size / 2].toFloat().coerceAtLeast(12f)
        val lineBin = (medianH * 0.58f).coerceAtLeast(8f)

        val ySorted = boxes.sortedWith(compareBy({ (it.centerY() / lineBin).toInt() }, { it.left }))
        val mergedLines = ArrayList<Rect>(ySorted.size)
        for (b in ySorted) {
            val prev = mergedLines.lastOrNull()
            if (prev != null) {
                val verticalOverlap = minOf(prev.bottom, b.bottom) - maxOf(prev.top, b.top)
                val minH = minOf(prev.height(), b.height()).coerceAtLeast(1)
                val horizGap = b.left - prev.right
                val maxMergeGap = (maxOf(prev.height(), b.height()) * 1.05f).roundToInt()
                if (verticalOverlap >= minH * 0.52f && horizGap in -10..maxMergeGap) {
                    mergedLines[mergedLines.lastIndex] = Rect(
                        minOf(prev.left, b.left),
                        minOf(prev.top, b.top),
                        maxOf(prev.right, b.right),
                        maxOf(prev.bottom, b.bottom)
                    )
                    continue
                }
            }
            mergedLines.add(Rect(b))
        }

        if (mergedLines.size <= 2) return mergedLines

        class SpatialTextBlock(firstLine: Rect) {
            val bounds = Rect(firstLine)
            val lines = ArrayList<Rect>().apply { add(firstLine) }
        }

        val blocks = ArrayList<SpatialTextBlock>()
        val fullWidthThreshold = (pageWidth * 0.52f).roundToInt()

        for (line in mergedLines) {
            val isFullWidthBanner = line.width() >= fullWidthThreshold
            var bestBlock: SpatialTextBlock? = null
            var bestVertGap = Int.MAX_VALUE

            if (!isFullWidthBanner) {
                for (blk in blocks) {
                    if (blk.bounds.width() >= fullWidthThreshold) continue
                    val vGap = line.top - blk.bounds.bottom
                    val maxAllowedVGap = (maxOf(line.height(), blk.lines.last().height()) * 2.2f).roundToInt().coerceAtLeast(18)
                    if (vGap in -12..maxAllowedVGap) {
                        val overlapX = minOf(blk.bounds.right, line.right) - maxOf(blk.bounds.left, line.left)
                        val minW = minOf(blk.bounds.width(), line.width()).coerceAtLeast(1)
                        val centerAligned = abs(blk.bounds.centerX() - line.centerX()) <= maxOf(blk.bounds.width(), line.width()) * 0.35f
                        val leftAligned = abs(blk.bounds.left - line.left) <= medianH * 2.0f
                        if ((overlapX >= minW * 0.30f || centerAligned || leftAligned) && vGap < bestVertGap) {
                            bestVertGap = vGap
                            bestBlock = blk
                        }
                    }
                }
            }

            if (bestBlock != null) {
                bestBlock.lines.add(line)
                bestBlock.bounds.union(line)
            } else {
                blocks.add(SpatialTextBlock(line))
            }
        }

        val blockRowBin = (medianH * 2.4f).coerceAtLeast(24f)
        blocks.sortWith { a, b ->
            val vOverlap = minOf(a.bounds.bottom, b.bounds.bottom) - maxOf(a.bounds.top, b.bounds.top)
            val minBlockH = minOf(a.bounds.height(), b.bounds.height()).coerceAtLeast(1)
            if (vOverlap >= minBlockH * 0.35f || abs(a.bounds.top - b.bounds.top) <= blockRowBin) {
                a.bounds.left.compareTo(b.bounds.left)
            } else {
                a.bounds.top.compareTo(b.bounds.top)
            }
        }

        val orderedOut = ArrayList<Rect>(mergedLines.size)
        for (blk in blocks) {
            blk.lines.sortWith(compareBy({ (it.centerY() / lineBin).toInt() }, { it.left }))
            orderedOut.addAll(blk.lines)
        }
        return orderedOut
    }

    /**
     * Aligns detected visual text line boxes with [pageTextHint] across all zoom levels (`zoom >= 0.5f`).
     * Groups horizontally adjacent boxes on the same visual line into `VisualRowGroup`s, filters out
     * tiny noise boxes, and distributes words proportionally across same-row column boxes so selection
     * zones never drift onto the wrong line or empty whitespace.
     */
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
                val bot = (top + stepH * 0.82f).roundToInt().coerceIn(top + 4, bitmap.height)
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

        // Group horizontally adjacent boxes on the same visual line into VisualRowGroups
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
            val endIdx = (hintParagraphs.size * endFrac).roundToInt().coerceIn(startIdx + 1, hintParagraphs.size)
            hintParagraphs.subList(startIdx, endIdx)
        } else {
            hintParagraphs
        }

        // Expand long paragraphs to match the visual row count
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
                // Multi-column or multi-segment visual row: distribute words proportionally across rg.boxes
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
        val tightRight = subGlyphRects.maxOf { it.right }.coerceIn(tightLeft + 2, box.right)
        val tightBottom = subGlyphRects.maxOf { it.bottom }.coerceIn(tightTop + 2, box.bottom)
        val tightBox = Rect(tightLeft, tightTop, tightRight, tightBottom)

        val nonSpaceGraphemes = graphemes.filter { it.isNotBlank() }

        // Case 1: Exact 1-to-1 match between connected ink components and non-space graphemes
        if (subGlyphRects.size == nonSpaceGraphemes.size) {
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

        // Case 2: Group subGlyphRects into word clusters separated by inter-word gaps and match against words in lineStr
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
        if (wordClusters.size == textWords.size && textWords.isNotEmpty()) {
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

        // Case 3: Emit graphemes into the ink-tightened bounding box
        emitGraphemesIntoBox(lineStr, tightBox, lineIndex, outChars)
        return tightBox
    }

    private fun refineBoxWithCtcTimesteps(
        ctcResult: CtcDecodeResult,
        paddedLeft: Int,
        paddedW: Int,
        fallbackBox: Rect
    ): Rect {
        val nonBlank = ctcResult.tokens.filter { it.text.isNotBlank() }
        val totalSteps = ctcResult.totalTimesteps.coerceAtLeast(1).toFloat()
        if (nonBlank.isEmpty()) return fallbackBox

        val stepToPx = paddedW.toFloat() / totalSteps
        val leftPx = (paddedLeft + (nonBlank.first().startStep - 0.65f) * stepToPx)
            .roundToInt().coerceIn(fallbackBox.left, fallbackBox.right - 2)
        val rightPx = (paddedLeft + (nonBlank.last().endStep + 1.65f) * stepToPx)
            .roundToInt().coerceIn(leftPx + 2, fallbackBox.right)
        return Rect(leftPx, fallbackBox.top, rightPx, fallbackBox.bottom)
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
            val cLeft = maxOf(prevRight, rawL.coerceIn(boundedBox.left, boundedBox.right - 1))
            val cRight = if (idx == ctcResult.tokens.lastIndex) {
                maxOf(cLeft + 1, rawR.coerceIn(cLeft + 1, boundedBox.right))
            } else {
                rawR.coerceIn(cLeft + 1, boundedBox.right)
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
                val ctcResult: CtcDecodeResult? = if (env != null && (primaryRecSession != null || cyrillicRecSession != null)) {
                    runDualRecArbitration(croppedBmp, env, pageLexicon, preferredLanguageCode)
                } else null

                val useOnnxResult = ctcResult != null &&
                        ctcResult.text.isNotBlank() &&
                        ctcResult.arbitrationScore >= 0.40f

                val globalTightBox = Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH)

                if (useOnnxResult && ctcResult != null) {
                    val snappedText = snapLineToPageLexicon(ctcResult.text, pageLexicon)
                    val ctcSpanBox = refineBoxWithCtcTimesteps(ctcResult, paddedLeft, paddedW, globalTightBox)
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
                        val ctcSpanBox = refineBoxWithCtcTimesteps(ctcResult, paddedLeft, paddedW, globalTightBox)
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

    /**
     * Unbiased Dual-Head Recognizer Arbitration:
     * Evaluates both `cyrillicRecSession` (`CYRILLIC_ESLAV`) and `primaryRecSession` (`PRIMARY_MULTILINGUAL`)
     * with their exact head-specific vocabularies and merges/arbitrates at both the word-interval level and line level.
     */
    private fun runDualRecArbitration(
        cropBmp: Bitmap,
        env: OrtEnvironment,
        pageLexicon: Set<String> = emptySet(),
        preferredLanguageCode: String = ""
    ): CtcDecodeResult? {
        val pSession = primaryRecSession
        val cSession = cyrillicRecSession

        if (cSession == null && pSession != null) {
            return runOnnxRecognitionWithDynamicSlicing(
                cropBmp = cropBmp,
                env = env,
                rec = pSession,
                supportsDynamicW = primarySupportsDynamicWidth,
                targetH = primaryInputHeight,
                headKind = RecHeadKind.PRIMARY_MULTILINGUAL,
                pageLexicon = pageLexicon
            )
        }
        if (pSession == null && cSession != null) {
            return runOnnxRecognitionWithDynamicSlicing(
                cropBmp = cropBmp,
                env = env,
                rec = cSession,
                supportsDynamicW = cyrillicSupportsDynamicWidth,
                targetH = cyrillicInputHeight,
                headKind = RecHeadKind.CYRILLIC_ESLAV,
                pageLexicon = pageLexicon
            )
        }
        if (pSession == null || cSession == null) return null

        val lexiconHasCyr = pageLexicon.any { w -> w.any { it in 'Ѐ'..'ԯ' } }
        val preferCyrillicFirst = preferredLanguageCode.lowercase(Locale.ROOT) in setOf("ru", "uk") || lexiconHasCyr

        if (preferCyrillicFirst) {
            val cyrRes = runOnnxRecognitionWithDynamicSlicing(
                cropBmp = cropBmp,
                env = env,
                rec = cSession,
                supportsDynamicW = cyrillicSupportsDynamicWidth,
                targetH = cyrillicInputHeight,
                headKind = RecHeadKind.CYRILLIC_ESLAV,
                pageLexicon = pageLexicon
            )
            // Fast-path accept when Cyrillic head decodes high-confidence pure Cyrillic text
            if (cyrRes != null &&
                cyrRes.text.isNotBlank() &&
                cyrRes.averageConfidence >= 0.90f &&
                cyrRes.text.any { it in 'Ѐ'..'ӿ' } &&
                cyrRes.text.none { it in 'A'..'Z' || it in 'a'..'z' }
            ) {
                return cyrRes.copy(
                    text = cyrRes.text.split(' ').joinToString(" ") { normalizeIntraWordHomoglyphs(it) }
                )
            }
            val primRes = runOnnxRecognitionWithDynamicSlicing(
                cropBmp = cropBmp,
                env = env,
                rec = pSession,
                supportsDynamicW = primarySupportsDynamicWidth,
                targetH = primaryInputHeight,
                headKind = RecHeadKind.PRIMARY_MULTILINGUAL,
                pageLexicon = pageLexicon
            )
            if (cyrRes == null) return primRes
            if (primRes == null) return cyrRes
            if (cyrRes.text.isBlank()) return primRes
            if (primRes.text.isBlank()) return cyrRes
            return mergeDualRecResultsAtWordLevel(cyrRes, primRes, pageLexicon)
        } else {
            val primRes = runOnnxRecognitionWithDynamicSlicing(
                cropBmp = cropBmp,
                env = env,
                rec = pSession,
                supportsDynamicW = primarySupportsDynamicWidth,
                targetH = primaryInputHeight,
                headKind = RecHeadKind.PRIMARY_MULTILINGUAL,
                pageLexicon = pageLexicon
            )
            // Fast-path accept when Multilingual head decodes high-confidence Latin/CJK text
            if (primRes != null &&
                primRes.text.isNotBlank() &&
                primRes.averageConfidence >= 0.88f
            ) {
                return primRes.copy(
                    text = primRes.text.split(' ').joinToString(" ") { normalizeIntraWordHomoglyphs(it) }
                )
            }
            val cyrRes = runOnnxRecognitionWithDynamicSlicing(
                cropBmp = cropBmp,
                env = env,
                rec = cSession,
                supportsDynamicW = cyrillicSupportsDynamicWidth,
                targetH = cyrillicInputHeight,
                headKind = RecHeadKind.CYRILLIC_ESLAV,
                pageLexicon = pageLexicon
            )
            if (cyrRes == null) return primRes
            if (primRes == null) return cyrRes
            if (cyrRes.text.isBlank()) return primRes
            if (primRes.text.isBlank()) return cyrRes
            return mergeDualRecResultsAtWordLevel(cyrRes, primRes, pageLexicon)
        }
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

        // Compensate for the smaller softmax denominator of the 192-class Cyrillic dictionary vs 6625-class Multilingual dictionary
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

        // If the primary CJK+Latin head outputs Hanzi on a line where the Cyrillic head strongly recognizes
        // distinct Cyrillic letters (Б, Г, Д, Ж, З, И, Й, Л, П, Ф, Ц, Ч, Ш, Щ, Ъ, Ы, Ь, Э, Ю, Я),
        // penalize the Hanzi hallucination heavily.
        if (totalHan > 0 && !lexiconHasHan) {
            if (lexiconHasCyr || (counterpartHasDistinctCyrillic && counterpartConfidence >= 0.68f)) {
                penalty += 0.40f
            } else if (totalCyr + totalLat > totalHan) {
                penalty += 0.28f
            } else if (avgMarginalConfidence < 0.84f && counterpartConfidence >= 0.78f) {
                penalty += 0.22f
            }
        }

        // Reward the Cyrillic head when it decodes genuine Cyrillic words with distinct Cyrillic characters
        if (headKind == RecHeadKind.CYRILLIC_ESLAV && totalUniqueCyr >= 1 && avgMarginalConfidence >= 0.72f) {
            bonus += 0.08f
        }

        // Reward the Primary head when both heads agree on pure Latin/digits or when Primary decodes high-confidence CJK
        if (headKind == RecHeadKind.PRIMARY_MULTILINGUAL && totalHan >= 2 && avgMarginalConfidence >= 0.86f && !counterpartHasDistinctCyrillic) {
            bonus += 0.08f
        }

        return (calibratedBaseConf - penalty + bonus.coerceAtMost(0.26f)).coerceIn(0f, 1f)
    }

    private fun splitResultIntoWordSpans(
        res: CtcDecodeResult,
        pageLexicon: Set<String>,
        counterpartText: String,
        counterpartConfidence: Float
    ): List<DecodedWordSpan> {
        if (res.tokens.isEmpty()) return emptyList()
        val spans = ArrayList<DecodedWordSpan>()
        val curTokens = ArrayList<DecodedCtcToken>()
        val curSb = StringBuilder()
        val totalT = res.totalTimesteps.coerceAtLeast(1)

        fun flushWord() {
            if (curTokens.isNotEmpty() && curSb.isNotEmpty()) {
                val rawWordStr = curSb.toString()
                val wordStr = normalizeIntraWordHomoglyphs(rawWordStr)
                val avgConf = curTokens.sumOf { it.confidence.toDouble() }.toFloat() / curTokens.size
                val score = computeScriptArbitrationScore(
                    text = wordStr,
                    avgMarginalConfidence = avgConf,
                    headKind = res.headKind,
                    pageLexicon = pageLexicon,
                    counterpartText = counterpartText,
                    counterpartConfidence = counterpartConfidence
                )
                spans.add(
                    DecodedWordSpan(
                        text = wordStr,
                        tokens = curTokens.toList(),
                        startStep = curTokens.first().startStep,
                        endStep = curTokens.last().endStep,
                        totalSteps = totalT,
                        averageConfidence = avgConf,
                        arbitrationScore = score
                    )
                )
            }
            curTokens.clear()
            curSb.setLength(0)
        }

        for (tok in res.tokens) {
            if (tok.text.isBlank()) {
                flushWord()
            } else {
                curTokens.add(tok)
                curSb.append(tok.text)
            }
        }
        flushWord()
        return spans
    }

    /**
     * Merges results from `cyrRes` and `primRes` at the word level when word spans align horizontally
     * (supporting mixed English + Russian / multilingual lines), or selects the higher-scoring line.
     */
    private fun mergeDualRecResultsAtWordLevel(
        cyrRes: CtcDecodeResult,
        primRes: CtcDecodeResult,
        pageLexicon: Set<String>
    ): CtcDecodeResult {
        val wordsCyr = splitResultIntoWordSpans(cyrRes, pageLexicon, primRes.text, primRes.averageConfidence)
        val wordsPrim = splitResultIntoWordSpans(primRes, pageLexicon, cyrRes.text, cyrRes.averageConfidence)

        if (wordsCyr.size == wordsPrim.size && wordsCyr.size > 1) {
            val mergedTokens = ArrayList<DecodedCtcToken>()
            val mergedSb = StringBuilder()
            var scoreSum = 0f
            var confSum = 0f
            val normTotalSteps = cyrRes.totalTimesteps.coerceAtLeast(1)

            for (i in wordsCyr.indices) {
                val wC = wordsCyr[i]
                val wP = wordsPrim[i]
                val winner = if (wC.arbitrationScore >= wP.arbitrationScore) wC else wP
                val stepScale = normTotalSteps.toFloat() / winner.totalSteps.coerceAtLeast(1).toFloat()

                if (mergedTokens.isNotEmpty()) {
                    val prevEnd = mergedTokens.last().endStep
                    val mappedStart = (winner.startStep * stepScale).roundToInt()
                    val spaceStep = maxOf(prevEnd + 1, mappedStart - 1)
                    mergedTokens.add(DecodedCtcToken(" ", prevEnd + 1, spaceStep, 0.92f))
                    mergedSb.append(' ')
                }
                for (tok in winner.tokens) {
                    mergedTokens.add(
                        tok.copy(
                            startStep = (tok.startStep * stepScale).roundToInt(),
                            endStep = (tok.endStep * stepScale).roundToInt()
                        )
                    )
                }
                mergedSb.append(winner.text)
                scoreSum += winner.arbitrationScore
                confSum += winner.averageConfidence
            }

            return CtcDecodeResult(
                text = mergedSb.toString(),
                tokens = mergedTokens,
                totalTimesteps = normTotalSteps,
                averageConfidence = confSum / wordsCyr.size,
                arbitrationScore = scoreSum / wordsCyr.size,
                headKind = cyrRes.headKind
            )
        }

        val scoreCyr = computeScriptArbitrationScore(
            text = cyrRes.text,
            avgMarginalConfidence = cyrRes.averageConfidence,
            headKind = RecHeadKind.CYRILLIC_ESLAV,
            pageLexicon = pageLexicon,
            counterpartText = primRes.text,
            counterpartConfidence = primRes.averageConfidence
        )
        val scorePrim = computeScriptArbitrationScore(
            text = primRes.text,
            avgMarginalConfidence = primRes.averageConfidence,
            headKind = RecHeadKind.PRIMARY_MULTILINGUAL,
            pageLexicon = pageLexicon,
            counterpartText = cyrRes.text,
            counterpartConfidence = cyrRes.averageConfidence
        )

        return if (scoreCyr >= scorePrim) {
            cyrRes.copy(
                text = cyrRes.text.split(' ').joinToString(" ") { normalizeIntraWordHomoglyphs(it) },
                arbitrationScore = scoreCyr
            )
        } else {
            primRes.copy(
                text = primRes.text.split(' ').joinToString(" ") { normalizeIntraWordHomoglyphs(it) },
                arbitrationScore = scorePrim
            )
        }
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
                        if (tokens.isNotEmpty() && activeTokenText == null && t - tokens.last().endStep >= 3 && !sb.endsWith(" ")) {
                            val lastEnd = tokens.last().endStep
                            tokens.add(DecodedCtcToken(" ", lastEnd + 1, t - 1, 0.90f))
                            sb.append(' ')
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

    private fun segmentLineIntoGlyphRects(bitmap: Bitmap, lineBox: Rect): List<Rect> {
        val left = lineBox.left.coerceIn(0, bitmap.width - 2)
        val top = lineBox.top.coerceIn(0, bitmap.height - 2)
        val w = lineBox.width().coerceIn(2, bitmap.width - left)
        val h = lineBox.height().coerceIn(2, bitmap.height - top)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)

        val fgMask = binarizeCropBipolar(pixels, w, h) ?: return emptyList()
        val colProjection = IntArray(w)
        for (x in 0 until w) {
            var sum = 0
            for (y in 0 until h) {
                if (fgMask[y * w + x]) sum++
            }
            colProjection[x] = sum
        }

        val rawSpans = ArrayList<IntRange>()
        var inChar = false
        var startX = 0
        for (x in 0 until w) {
            if (colProjection[x] > 0) {
                if (!inChar) {
                    inChar = true
                    startX = x
                }
            } else if (inChar) {
                rawSpans.add(startX until x)
                inChar = false
            }
        }
        if (inChar) {
            rawSpans.add(startX until w)
        }

        val mergedSpans = ArrayList<IntRange>(rawSpans.size)
        var sIdx = 0
        while (sIdx < rawSpans.size) {
            val cur = rawSpans[sIdx]
            if (sIdx + 1 < rawSpans.size) {
                val next = rawSpans[sIdx + 1]
                val curW = cur.last - cur.first + 1
                val nextW = next.last - next.first + 1
                val gap = next.first - cur.last - 1
                val combinedW = next.last - cur.first + 1
                if (gap <= maxOf(2, (h * 0.13f).roundToInt()) &&
                    nextW <= maxOf(3, (h * 0.24f).roundToInt()) &&
                    curW >= nextW * 1.4f &&
                    combinedW <= h * 1.18f
                ) {
                    mergedSpans.add(cur.first..next.last)
                    sIdx += 2
                    continue
                }
            }
            mergedSpans.add(cur)
            sIdx++
        }

        val refinedRects = ArrayList<Rect>(mergedSpans.size + 4)
        for (span in mergedSpans) {
            val spanW = span.last - span.first + 1
            if (spanW > h * 1.65f && spanW >= 12) {
                val midStart = span.first + spanW / 3
                val midEnd = span.last - spanW / 3
                var minCol = midStart
                var minVal = Int.MAX_VALUE
                for (cx in midStart..midEnd) {
                    if (colProjection[cx] < minVal) {
                        minVal = colProjection[cx]
                        minCol = cx
                    }
                }
                if (minVal <= maxOf(1, h / 8) && minCol > span.first + 2 && minCol < span.last - 2) {
                    buildTightGlyphRect(fgMask, w, h, span.first, minCol - 1, left, top)?.let { refinedRects.add(it) }
                    buildTightGlyphRect(fgMask, w, h, minCol + 1, span.last, left, top)?.let { refinedRects.add(it) }
                    continue
                }
            }
            buildTightGlyphRect(fgMask, w, h, span.first, span.last, left, top)?.let { refinedRects.add(it) }
        }

        return refinedRects
    }

    private fun buildTightGlyphRect(
        fgMask: BooleanArray,
        w: Int,
        h: Int,
        x0: Int,
        x1: Int,
        globalLeft: Int,
        globalTop: Int
    ): Rect? {
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in x0..x1) {
                if (fgMask[rowOff + x]) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxY < minY) return null
        return Rect(
            globalLeft + x0,
            globalTop + minY,
            globalLeft + x1 + 1,
            globalTop + maxY + 1
        )
    }

    private fun binarizeCropBipolar(pixels: IntArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        if (total <= 0) return null
        val luma = IntArray(total)
        val hist = IntArray(256)
        var minL = 255
        var maxL = 0

        for (i in 0 until total) {
            val c = pixels[i]
            val y = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            luma[i] = y
            hist[y]++
            if (y < minL) minL = y
            if (y > maxL) maxL = y
        }

        if (maxL - minL < 24) return null

        var sumAll = 0L
        for (t in 0..255) sumAll += t.toLong() * hist[t]

        var sumB = 0L
        var wB = 0
        var maxVariance = -1.0
        var threshold = (minL + maxL) / 2

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val betweenVar = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (betweenVar > maxVariance) {
                maxVariance = betweenVar
                threshold = t
            }
        }

        var borderBrightCount = 0
        var borderSamples = 0
        for (x in 0 until w) {
            if (luma[x] > threshold) borderBrightCount++
            if (luma[(h - 1) * w + x] > threshold) borderBrightCount++
            borderSamples += 2
        }
        for (y in 0 until h) {
            if (luma[y * w] > threshold) borderBrightCount++
            if (luma[y * w + (w - 1)] > threshold) borderBrightCount++
            borderSamples += 2
        }
        val isBackgroundBright = borderBrightCount * 2 >= borderSamples

        val mask = BooleanArray(total)
        var fgCount = 0
        for (i in 0 until total) {
            val isFg = if (isBackgroundBright) luma[i] <= threshold else luma[i] > threshold
            mask[i] = isFg
            if (isFg) fgCount++
        }

        return if (fgCount in 3..(total * 88 / 100)) mask else null
    }

    private fun recognizeLineViaTopologicalClassifier(
        fullBitmap: Bitmap,
        globalBox: Rect,
        ctcHint: CtcDecodeResult?,
        lineIndex: Int,
        pageLexicon: Set<String> = emptySet()
    ): Pair<OcrRecognizedLine, List<OcrRecognizedChar>>? {
        val glyphRects = segmentLineIntoGlyphRects(fullBitmap, globalBox)
        if (glyphRects.isEmpty()) return null

        val heights = glyphRects.map { it.height() }.sorted()
        val medianH = heights[heights.size / 2].toFloat().coerceAtLeast(8f)
        val spaceGapThreshold = (medianH * 0.32f).coerceAtLeast(3.5f)

        val lineChars = ArrayList<OcrRecognizedChar>(glyphRects.size * 2)
        val lineSb = StringBuilder()

        val tightTop = glyphRects.minOf { it.top }.coerceAtLeast(globalBox.top)
        val tightBot = glyphRects.maxOf { it.bottom }.coerceIn(tightTop + 2, globalBox.bottom)

        for (i in glyphRects.indices) {
            val gRect = glyphRects[i]
            if (i > 0) {
                val prevRect = glyphRects[i - 1]
                val gap = gRect.left - prevRect.right
                if (gap >= spaceGapThreshold) {
                    val spRect = Rect(prevRect.right, tightTop, gRect.left, tightBot)
                    lineChars.add(OcrRecognizedChar(" ", spRect, lineIndex))
                    lineSb.append(' ')
                }
            }

            val normCenter = ((gRect.centerX() - globalBox.left).toFloat() / globalBox.width().coerceAtLeast(1))
            val ctcMatchedToken = ctcHint?.tokens?.firstOrNull { tok ->
                val tokStartNorm = tok.startStep.toFloat() / ctcHint.totalTimesteps.coerceAtLeast(1)
                val tokEndNorm = (tok.endStep + 1).toFloat() / ctcHint.totalTimesteps.coerceAtLeast(1)
                val cp = if (tok.text.isNotEmpty()) tok.text.codePointAt(0) else 0
                val isSupportedAlphabet = cp in 0x21..0x7E || cp in 0x00A0..0x052F
                tok.confidence >= 0.84f &&
                        isSupportedAlphabet &&
                        normCenter in (tokStartNorm - 0.06f)..(tokEndNorm + 0.06f)
            }

            val recognizedGrapheme = ctcMatchedToken?.text
                ?: classifySingleGlyphRect(fullBitmap, gRect, isTallInLine = gRect.height() >= medianH * 0.92f)

            val expandedRect = Rect(gRect.left, tightTop, gRect.right, tightBot)
            lineChars.add(OcrRecognizedChar(recognizedGrapheme, expandedRect, lineIndex))
            lineSb.append(recognizedGrapheme)
        }

        val rawLineStr = lineSb.toString().trim()
        if (rawLineStr.isEmpty()) return null
        val tightLineBox = Rect(
            glyphRects.first().left.coerceAtLeast(globalBox.left),
            tightTop,
            glyphRects.last().right.coerceAtMost(globalBox.right),
            tightBot
        )
        val snappedLineStr = snapLineToPageLexicon(rawLineStr, pageLexicon)
        if (snappedLineStr != rawLineStr && snappedLineStr.length == rawLineStr.length) {
            val updatedChars = ArrayList<OcrRecognizedChar>(lineChars.size)
            val newGraphemes = splitIntoGraphemes(snappedLineStr)
            if (newGraphemes.size == lineChars.size) {
                for (k in lineChars.indices) {
                    updatedChars.add(lineChars[k].copy(text = newGraphemes[k]))
                }
                return OcrRecognizedLine(snappedLineStr, tightLineBox) to updatedChars
            }
        }
        return OcrRecognizedLine(snappedLineStr, tightLineBox) to lineChars
    }

    private fun classifySingleGlyphRect(
        bitmap: Bitmap,
        glyphRect: Rect,
        isTallInLine: Boolean
    ): String {
        val w = glyphRect.width().coerceAtLeast(1)
        val h = glyphRect.height().coerceAtLeast(1)

        if (h <= 5 && w <= 5) return "."
        if (h <= 5 && w >= h * 2.2f) return "-"

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, glyphRect.left, glyphRect.top, w, h)
        val fgMask = binarizeCropBipolar(pixels, w, h) ?: return "?"

        val descriptor = extractGlyphDescriptorFromMask(fgMask, w, h, "", isTallInLine, false)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestGrapheme = "?"

        for (proto in glyphPrototypes) {
            val aspectDiff = abs(proto.aspectRatio - descriptor.aspectRatio)
            if (aspectDiff > 0.65f) continue

            var matchingBits = 0
            for (k in 0 until 4) {
                val xorBits = proto.bits16x16[k] xor descriptor.bits16x16[k]
                matchingBits += 64 - java.lang.Long.bitCount(xorBits)
            }
            val bitSim = matchingBits / 256.0f

            var dot = 0f
            for (z in 0 until 16) {
                dot += proto.zones4x4[z] * descriptor.zones4x4[z]
            }
            val cosineSim = dot / (proto.normL2 * descriptor.normL2).coerceAtLeast(1e-5f)

            val holePenalty = abs(proto.eulerHoles - descriptor.eulerHoles) * 0.08f
            val caseBonus = if (proto.isUpper == isTallInLine) 0.03f else 0f

            val totalScore = cosineSim * 0.58f + bitSim * 0.42f - aspectDiff * 0.12f - holePenalty + caseBonus
            if (totalScore > bestScore) {
                bestScore = totalScore
                bestGrapheme = proto.grapheme
            }
        }

        return bestGrapheme
    }

    /**
     * Builds the clean-room 16x16 topological prototype bank using standard modern alphabets
     * (Russian, Ukrainian, Belarusian, English, German, Spanish, French, Polish, digits, and punctuation)
     * without archaic Slavonic or Mathematical Fraktur symbols that could cause false homoglyph matches.
     */
    private fun ensureTopologicalGlyphBankLocked() {
        if (glyphPrototypes.isNotEmpty()) return
        val alphabet = buildString {
            append("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ")
            append("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
            append("ІЇЄҐіїєґЎў")
            append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            append("abcdefghijklmnopqrstuvwxyz")
            append("ÄÖÜäöüßĄĆĘŁŃÓŚŹŻąćęłńóśźżÉÈÊËéèêëÁÍÓÚÑáíóúñ")
            append("0123456789")
            append("!?%&()[]{}+=*/:;.,«»№€₽$£¥")
        }
        val graphemes = splitIntoGraphemes(alphabet)

        val typefaces = listOf(
            Typeface.SANS_SERIF to 1.0f,
            Typeface.SERIF to 1.0f,
            Typeface.DEFAULT_BOLD to 1.0f,
            Typeface.DEFAULT_BOLD to 0.74f
        )

        val scratchBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val scratchCanvas = Canvas(scratchBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
        }
        val pixels = IntArray(32 * 32)
        val mask = BooleanArray(32 * 32)

        for ((tf, scaleX) in typefaces) {
            paint.typeface = tf
            paint.textScaleX = scaleX
            for (g in graphemes) {
                if (!paint.hasGlyph(g)) continue
                scratchBmp.eraseColor(Color.BLACK)
                scratchCanvas.drawText(g, 4f, 26f, paint)
                scratchBmp.getPixels(pixels, 0, 32, 0, 0, 32, 32)

                var minX = 32
                var maxX = -1
                var minY = 32
                var maxY = -1
                for (y in 0 until 32) {
                    val rOff = y * 32
                    for (x in 0 until 32) {
                        val isInk = (pixels[rOff + x] and 0xFF) > 96
                        mask[rOff + x] = isInk
                        if (isInk) {
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
                if (maxX >= minX && maxY >= minY) {
                    val cw = maxX - minX + 1
                    val chH = maxY - minY + 1
                    val croppedMask = BooleanArray(cw * chH)
                    for (y in 0 until chH) {
                        for (x in 0 until cw) {
                            croppedMask[y * cw + x] = mask[(minY + y) * 32 + (minX + x)]
                        }
                    }
                    val cp = g.codePointAt(0)
                    glyphPrototypes.add(
                        extractGlyphDescriptorFromMask(
                            mask = croppedMask,
                            w = cw,
                            h = chH,
                            grapheme = g,
                            isUpper = Character.isUpperCase(cp),
                            isDigitOrPunct = !Character.isLetter(cp)
                        )
                    )
                }
            }
        }
        scratchBmp.recycle()
    }

    private fun extractGlyphDescriptorFromMask(
        mask: BooleanArray,
        w: Int,
        h: Int,
        grapheme: String,
        isUpper: Boolean,
        isDigitOrPunct: Boolean
    ): GlyphPrototype {
        val bits16x16 = LongArray(4)
        val zones4x4 = FloatArray(16)

        for (gy in 0 until 16) {
            val srcY = (gy * h / 16).coerceIn(0, h - 1)
            for (gx in 0 until 16) {
                val srcX = (gx * w / 16).coerceIn(0, w - 1)
                if (mask[srcY * w + srcX]) {
                    val bitIndex = gy * 16 + gx
                    bits16x16[bitIndex ushr 6] = bits16x16[bitIndex ushr 6] or (1L shl (bitIndex and 63))
                    val zoneIdx = (gy / 4) * 4 + (gx / 4)
                    zones4x4[zoneIdx] += 1f / 16f
                }
            }
        }

        var sumSq = 0f
        for (z in zones4x4) sumSq += z * z
        val normL2 = sqrt(sumSq).coerceAtLeast(1e-4f)

        val bgVisited = BooleanArray(256)
        val q = IntArray(256)
        fun isInk16(x: Int, y: Int): Boolean {
            val idx = y * 16 + x
            return ((bits16x16[idx ushr 6] ushr (idx and 63)) and 1L) != 0L
        }

        var holes = 0
        var holeYAccum = 0f
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val startIdx = y * 16 + x
                if (!isInk16(x, y) && !bgVisited[startIdx]) {
                    var touchesBorder = false
                    var qH = 0
                    var qT = 0
                    var ySum = 0
                    q[qT++] = startIdx
                    bgVisited[startIdx] = true
                    while (qH < qT) {
                        val cur = q[qH++]
                        val cy = cur / 16
                        val cx = cur % 16
                        ySum += cy
                        if (cx == 0 || cx == 15 || cy == 0 || cy == 15) touchesBorder = true
                        val neighbors = intArrayOf(
                            if (cy > 0) (cy - 1) * 16 + cx else -1,
                            if (cy < 15) (cy + 1) * 16 + cx else -1,
                            if (cx > 0) cy * 16 + (cx - 1) else -1,
                            if (cx < 15) cy * 16 + (cx + 1) else -1
                        )
                        for (nb in neighbors) {
                            if (nb >= 0 && !bgVisited[nb] && !isInk16(nb % 16, nb / 16)) {
                                bgVisited[nb] = true
                                q[qT++] = nb
                            }
                        }
                    }
                    if (!touchesBorder && qT >= 2) {
                        holes++
                        holeYAccum += (ySum.toFloat() / qT) / 16f
                    }
                }
            }
        }

        return GlyphPrototype(
            grapheme = grapheme,
            isUpper = isUpper,
            isDigitOrPunct = isDigitOrPunct,
            aspectRatio = w.toFloat() / h.coerceAtLeast(1).toFloat(),
            eulerHoles = holes,
            holeCenterYNorm = if (holes > 0) holeYAccum / holes else 0.5f,
            bits16x16 = bits16x16,
            zones4x4 = zones4x4,
            normL2 = normL2
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
                } catch (_: Throwable) {
                }
                detSession = null
                primaryRecSession = null
                cyrillicRecSession = null
            }
        }
    }
}
