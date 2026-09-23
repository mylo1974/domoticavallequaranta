package it.vallequaranta.segnale.radio

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.os.Build
import java.util.concurrent.Executor

/**
 * Legge tutte le celle visibili dal modem, per ogni SIM attiva.
 *
 * Con un telefono doppia SIM (es. una TIM e una Vodafone) si confrontano due
 * operatori nello stesso rilievo. Un telefono vede di norma solo le celle
 * dell'operatore della propria SIM: per confrontare più operatori servono
 * più SIM (o più giri di rilievo cambiando SIM).
 */
class CellScanner(private val context: Context, private val executor: Executor) {

    private val tm = context.getSystemService(TelephonyManager::class.java)

    /** Telephony manager per ogni SIM attiva (o quello predefinito se non accessibile). */
    @SuppressLint("MissingPermission")
    private fun managers(): List<TelephonyManager> {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val subs = try {
            sm?.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (subs.isEmpty()) return listOf(tm)
        return subs.map { tm.createForSubscriptionId(it.subscriptionId) }
    }

    /**
     * Chiede al modem misure fresche e restituisce l'elenco complessivo
     * (deduplicato) tramite [onResult], sul thread dell'[executor].
     */
    @SuppressLint("MissingPermission")
    fun scan(onResult: (List<CellMeasurement>) -> Unit) {
        val list = managers()
        val results = arrayOfNulls<List<CellMeasurement>>(list.size)
        var pending = list.size
        list.forEachIndexed { i, m ->
            val done: (List<CellMeasurement>) -> Unit = { cells ->
                results[i] = cells
                pending--
                if (pending == 0) onResult(merge(results.filterNotNull()))
            }
            try {
                m.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        done(convert(m, cellInfo))
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        // Fallback sull'ultima lettura in cache del modem
                        done(convert(m, safeAllCellInfo(m)))
                    }
                })
            } catch (e: SecurityException) {
                done(emptyList())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeAllCellInfo(m: TelephonyManager): List<CellInfo> =
        try {
            m.allCellInfo.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }

    private fun merge(parts: List<List<CellMeasurement>>): List<CellMeasurement> {
        // Con doppia SIM la stessa cella può comparire due volte: si tiene la più completa
        val byKey = LinkedHashMap<String, CellMeasurement>()
        for (c in parts.flatten()) {
            val k = c.entityKey + "|" + (c.sector ?: "") + "|" + (c.channel ?: "")
            val prev = byKey[k]
            if (prev == null || (c.registered && !prev.registered)) byKey[k] = c
        }
        return byKey.values.sortedWith(compareByDescending<CellMeasurement> { it.registered }.thenByDescending { it.level })
    }

    private fun convert(m: TelephonyManager, infos: List<CellInfo>): List<CellMeasurement> {
        // Operatore della SIM: usato per le celle vicine che non riportano MCC/MNC
        val simPlmn = m.networkOperator?.takeIf { it.length >= 5 }
        val out = ArrayList<CellMeasurement>()
        for (info in infos) {
            val c = when (info) {
                is CellInfoLte -> lte(info)
                is CellInfoNr -> nr(info)
                is CellInfoWcdma -> wcdma(info)
                is CellInfoGsm -> gsm(info)
                else -> null
            } ?: continue
            out += if (c.mcc == null && simPlmn != null) {
                c.copy(mcc = simPlmn.substring(0, 3), mnc = simPlmn.substring(3), operatorAssumed = true)
            } else c
        }
        out += nsaNr(m, simPlmn, out)
        return out
    }

    /**
     * 5G NSA: la cella NR non compare in getAllCellInfo ma la sua potenza è
     * disponibile in SignalStrength. La si aggiunge agganciata alla cella 4G servente.
     */
    private fun nsaNr(m: TelephonyManager, simPlmn: String?, found: List<CellMeasurement>): List<CellMeasurement> {
        if (found.any { it.tech == Tech.NR }) return emptyList()
        val ss = m.signalStrength ?: return emptyList()
        val anchor = found.firstOrNull { it.registered && it.tech == Tech.LTE } ?: return emptyList()
        return ss.cellSignalStrengths.filterIsInstance<CellSignalStrengthNr>().mapNotNull { s ->
            val rsrp = s.ssRsrp.valid() ?: return@mapNotNull null
            CellMeasurement(
                tech = Tech.NR,
                mcc = anchor.mcc ?: simPlmn?.substring(0, 3),
                mnc = anchor.mnc ?: simPlmn?.substring(3),
                registered = true,
                level = rsrp,
                quality = s.ssRsrq.valid(),
                sinr = s.ssSinr.valid(),
                // Nessun ID NR: si riusa l'eNB dell'ancora 4G (stesso traliccio nel caso tipico)
                cellId = null,
                pci = null,
                tac = anchor.tac,
                channel = null,
                timingAdvance = null,
                operatorAssumed = anchor.operatorAssumed,
            )
        }
    }

    private fun lte(info: CellInfoLte): CellMeasurement? {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val rsrp = s.rsrp.valid() ?: return null
        return CellMeasurement(
            tech = Tech.LTE,
            mcc = id.mccString,
            mnc = id.mncString,
            registered = info.isRegistered,
            level = rsrp,
            quality = s.rsrq.valid(),
            sinr = s.rssnr.valid(),
            cellId = id.ci.valid()?.toLong(),
            pci = id.pci.valid(),
            tac = id.tac.valid(),
            channel = id.earfcn.valid(),
            timingAdvance = s.timingAdvance.valid(),
        )
    }

    private fun nr(info: CellInfoNr): CellMeasurement? {
        val id = info.cellIdentity as CellIdentityNr
        val s = info.cellSignalStrength as CellSignalStrengthNr
        val rsrp = s.ssRsrp.valid() ?: s.csiRsrp.valid() ?: return null
        return CellMeasurement(
            tech = Tech.NR,
            mcc = id.mccString,
            mnc = id.mncString,
            registered = info.isRegistered,
            level = rsrp,
            quality = s.ssRsrq.valid(),
            sinr = s.ssSinr.valid(),
            cellId = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG && it > 0 },
            pci = id.pci.valid(),
            tac = id.tac.valid(),
            channel = id.nrarfcn.valid(),
            timingAdvance = null,
        )
    }

    private fun wcdma(info: CellInfoWcdma): CellMeasurement? {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val rscp = s.dbm.valid() ?: return null
        val ecno = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) s.ecNo.valid() else null
        return CellMeasurement(
            tech = Tech.UMTS,
            mcc = id.mccString,
            mnc = id.mncString,
            registered = info.isRegistered,
            level = rscp,
            quality = ecno,
            sinr = null,
            cellId = id.cid.valid()?.toLong(),
            pci = id.psc.valid(),
            tac = id.lac.valid(),
            channel = id.uarfcn.valid(),
            timingAdvance = null,
        )
    }

    private fun gsm(info: CellInfoGsm): CellMeasurement? {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val rssi = s.dbm.valid() ?: return null
        return CellMeasurement(
            tech = Tech.GSM,
            mcc = id.mccString,
            mnc = id.mncString,
            registered = info.isRegistered,
            level = rssi,
            quality = null,
            sinr = null,
            cellId = id.cid.valid()?.toLong(),
            pci = id.bsic.valid(),
            tac = id.lac.valid(),
            channel = id.arfcn.valid(),
            timingAdvance = s.timingAdvance.valid(),
        )
    }

    private fun Int.valid(): Int? = if (this == CellInfo.UNAVAILABLE) null else this
}
