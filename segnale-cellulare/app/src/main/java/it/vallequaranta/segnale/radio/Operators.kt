package it.vallequaranta.segnale.radio

/** Nomi degli operatori a partire da MCC/MNC. Tabella centrata sull'Italia (MCC 222). */
object Operators {
    private val names = mapOf(
        "22201" to "TIM",
        "22210" to "Vodafone",
        "22206" to "Vodafone",
        "22288" to "WindTre",
        "22299" to "WindTre",
        "22250" to "Iliad",
        "22208" to "Fastweb",
        "22230" to "RFI",
        // San Marino e Vaticano, utili in zone di confine
        "29201" to "SMT San Marino",
        "22501" to "Vaticano",
    )

    fun name(mcc: String?, mnc: String?): String {
        if (mcc == null || mnc == null) return "Sconosciuto"
        return names[mcc + mnc] ?: "$mcc-$mnc"
    }

    fun nameForPlmn(plmn: String?): String =
        if (plmn == null || plmn.length < 5) "Sconosciuto"
        else name(plmn.substring(0, 3), plmn.substring(3))
}
