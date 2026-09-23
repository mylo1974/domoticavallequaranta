package it.vallequaranta.segnale.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import it.vallequaranta.segnale.analysis.Geo
import it.vallequaranta.segnale.analysis.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bussola basata sul sensore di rotazione (fusione magnetometro/giroscopio).
 *
 * - Telefono in piano: direzione verso cui punta il bordo superiore.
 * - Telefono in verticale: direzione verso cui guarda la fotocamera posteriore
 *   (comodo per "mirare" come si farebbe con l'antenna).
 *
 * Restituisce il nord VERO correggendo la declinazione magnetica del luogo
 * (in Italia +2…+5°): gli azimut dell'app sono tutti riferiti al nord vero.
 */
class Compass(context: Context, private val onHeading: (trueHeading: Double, accuracy: Int) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)
    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    var declination = 0.0
        private set

    val available: Boolean get() = sensor != null

    fun updateLocation(p: LatLon, altitude: Double?) {
        declination = GeomagneticField(p.lat.toFloat(), p.lon.toFloat(), (altitude ?: 0.0).toFloat(), System.currentTimeMillis())
            .declination.toDouble()
    }

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        SensorManager.getOrientation(rot, orient)
        val pitch = Math.toDegrees(orient[1].toDouble())
        val azimuthRad = if (abs(pitch) > 45) {
            // Telefono tenuto in verticale: si usa l'asse della fotocamera
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            SensorManager.getOrientation(remapped, orient)
            orient[0].toDouble()
        } else orient[0].toDouble()
        // Filtro passa-basso sul cerchio (evita il salto 359° → 0°)
        val a = 0.15
        smoothSin = (1 - a) * smoothSin + a * sin(azimuthRad)
        smoothCos = (1 - a) * smoothCos + a * cos(azimuthRad)
        val magnetic = Math.toDegrees(atan2(smoothSin, smoothCos))
        onHeading(Geo.normalize(magnetic + declination), accuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        this.accuracy = accuracy
    }
}
