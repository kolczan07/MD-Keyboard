package pl.mdinstal.ergokeyboard

import android.content.Context

/**
 * Jedyna "korekta" w klawiaturze: dodaje polskie znaki do CAŁYCH, znanych słów
 * pisanych bez nich (sie → się, wiecej → więcej). Nigdy nie zmienia innych liter.
 * Słownik (assets/pl_diacritics.txt) zawiera tylko pewne przypadki: słowa dwuznaczne
 * (np. zle → złe/źle) i prawdziwe słowa bez ogonków (ze, mam, pisze) są pominięte.
 */
object PlCorrector {
    @Volatile
    private var map: HashMap<String, String>? = null

    fun load(ctx: Context) {
        if (map != null) return
        val app = ctx.applicationContext
        Thread {
            try {
                val m = HashMap<String, String>(120_000)
                app.assets.open("pl_diacritics.txt").bufferedReader().useLines { lines ->
                    for (l in lines) {
                        val i = l.indexOf('\t')
                        if (i > 0) m[l.substring(0, i)] = l.substring(i + 1)
                    }
                }
                map = m
            } catch (e: Exception) {
            }
        }.start()
    }

    /** Zwraca poprawione słowo albo null, gdy nie ma czego poprawiać. */
    fun fix(word: String): String? {
        val m = map ?: return null
        if (word.length < 2 || !word.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
        val fixed = m[word.lowercase()] ?: return null
        return when {
            word.all { it.isLowerCase() } -> fixed
            word.all { it.isUpperCase() } -> fixed.uppercase()
            word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() } ->
                fixed.replaceFirstChar { it.uppercase() }
            else -> null
        }
    }
}
