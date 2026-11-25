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

import android.graphics.Rect
import androidx.compose.runtime.Immutable

@Immutable
data class OcrRecognizedChar(
    val text: String,
    val rect: Rect,
    val lineIndex: Int
) {
    constructor(char: Char, rect: Rect, lineIndex: Int) : this(char.toString(), rect, lineIndex)

    val char: Char
        get() = text.firstOrNull() ?: ' '
}

@Immutable
data class OcrRecognizedLine(
    val text: String,
    val rect: Rect
)

@Immutable
data class OcrResultPayload(
    val lines: List<OcrRecognizedLine>,
    val chars: List<OcrRecognizedChar>
)

internal const val BASE_PPOCR_V1_KEY_COUNT = 6623
internal const val FONT_SPECIMEN_DOC_WIDTH = 1280f
internal const val FONT_SPECIMEN_DOC_HEIGHT = 1810f

internal val GLYPH_CELL_ENTRY_REGEX = Regex("""\[U\+([0-9A-Fa-f]{4,6})\s+'([\s\S]*?)'\]""")
internal val PDF_NATIVE_BOX_LINE_REGEX = Regex("""^\[PDF_BOX:([0-9.]+),([0-9.]+),([0-9.]+),([0-9.]+)\](.*)$""")
internal val LEXICON_TOKEN_SPLIT_REGEX = Regex("[\\s,.;:!?()\\[\\]\"'«»—–]+")

internal enum class RecHeadKind {
    PRIMARY_MULTILINGUAL,
    CYRILLIC_ESLAV,
    LATIN
}

internal val OFFICIAL_PADDLE_CYRILLIC_BASE_DICT: List<String> = listOf(
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

internal val OFFICIAL_PADDLE_CYRILLIC_EXTENDED_DICT: List<String> = OFFICIAL_PADDLE_CYRILLIC_BASE_DICT + listOf(
    "Ѣ", "ѣ", "Ѳ", "ѳ", "Ѵ", "ѵ", "Ѧ", "ѧ", "Ѩ", "ѩ", "Ѫ", "ѫ", "Ѭ", "ѭ", "Ѯ", "ѯ", "Ѱ", "ѱ", "Ꙋ", "ꙋ", "Ѻ", "ѻ", "Ѿ", "ѿ", "Ҁ", "ҁ",
    "№", "₽", "€", "₴", "«", "»", "—", "–"
)

internal val OFFICIAL_PADDLE_LATIN_DICT: List<String> = listOf(
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

internal class GlyphPrototype(
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

internal data class DecodedCtcToken(
    val text: String,
    val startStep: Int,
    val endStep: Int,
    val confidence: Float
)

internal data class CtcDecodeResult(
    val text: String,
    val tokens: List<DecodedCtcToken>,
    val totalTimesteps: Int,
    val averageConfidence: Float,
    val arbitrationScore: Float,
    val headKind: RecHeadKind
)

internal fun Int.clampTo(min: Int, max: Int): Int = if (max <= min) min else coerceIn(min, max)
