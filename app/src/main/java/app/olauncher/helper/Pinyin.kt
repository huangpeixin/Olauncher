package app.olauncher.helper

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

object Pinyin {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /**
     * Convert Chinese characters to Pinyin.
     * @param str Input string
     * @param separator Separator between characters (ignored in this impl, just concatenates)
     * @return Pinyin string
     */
    fun toPinyin(str: String, separator: String): String {
        val sb = StringBuilder()
        for (char in str) {
            if (char.toInt() > 127) { // Non-ASCII
                try {
                    val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(char, format)
                    if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                        sb.append(pinyinArray[0])
                    } else {
                        sb.append(char)
                    }
                } catch (e: Exception) {
                    sb.append(char)
                }
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }
}
