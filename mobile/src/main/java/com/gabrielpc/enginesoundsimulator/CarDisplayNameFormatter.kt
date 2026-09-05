package com.gabrielpc.enginesoundsimulator

/** Formats car names for display in the dashboard UI. */
internal object CarDisplayNameFormatter {
    fun format(name: String): String {
        if (name.isEmpty()) {
            return name
        }

        val result = StringBuilder(name.length)
        var index = 0
        while (index < name.length) {
            val char = name[index]
            if (char.isLetter()) {
                val start = index
                while (index < name.length && name[index].isLetter()) {
                    index++
                }

                val letters = name.substring(start, index)
                if (letters.length in 2..3) {
                    result.append(letters.uppercase())
                } else {
                    result.append(letters)
                }
            } else {
                result.append(char)
                index++
            }
        }

        return result.toString()
    }
}
