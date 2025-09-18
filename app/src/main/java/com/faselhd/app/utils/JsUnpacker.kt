package com.faselhd.app.utils

import java.util.regex.Pattern

class JsUnpacker(private val packedJS: String) {

    fun unpack(): String? {
        try {
            val p = Pattern.compile("eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?}\\s*\\('(.*)',\\s*(\\d+),\\s*(\\d+),\\s*'(.*?)'\\.split\\('\\|'\\)", Pattern.DOTALL)
            val m = p.matcher(packedJS)

            if (m.find()) {
                val payload = m.group(2)
                val radix = m.group(3)!!.toInt()
                var count = m.group(4)!!.toInt()
                val dictionary = m.group(5)!!.split("|")

                fun baseNto10(num: String, base: Int): Int {
                    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
                    var result = 0
                    val numReversed = num.reversed()
                    for (i in numReversed.indices) {
                        result += alphabet.indexOf(numReversed[i]) * Math.pow(base.toDouble(), i.toDouble()).toInt()
                    }
                    return result
                }

                fun getIdentifier(c: Int, radix: Int): String {
                    var result = ""
                    var remaining = c
                    while (remaining > 0) {
                        result = "0123456789abcdefghijklmnopqrstuvwxyz"[remaining % radix] + result
                        remaining = (remaining - remaining % radix) / radix
                    }
                    return if (result.isEmpty()) "0" else result
                }

                val p2 = Pattern.compile("\\b\\w+\\b")
                val m2 = p2.matcher(payload)
                val sb = StringBuffer()

                while (m2.find()) {
                    val word = m2.group(0)
                    val index = baseNto10(word, radix)
                    val replacement = if (index < dictionary.size && dictionary[index].isNotEmpty()) {
                        dictionary[index]
                    } else {
                        word
                    }
                    m2.appendReplacement(sb, replacement)
                }
                m2.appendTail(sb)
                return sb.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}