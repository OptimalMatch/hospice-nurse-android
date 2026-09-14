package ie.unidatum.hospice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * One visit, charted at the bedside. Every control here writes to the node in
 * this phone, so the screen behaves the same in a house with no signal as it
 * does on the branch Wi-Fi.
 */
class VisitActivity : AppCompatActivity() {
    private lateinit var nurse: Nurse
    private var visit: Visit? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val scores = listOf("pain", "breathing", "nausea", "agitation")
    private val bars = mutableMapOf<String, SeekBar>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_visit)
        fitSystemBars(findViewById(R.id.root), keyboard = true)
        nurse = Nurse.get(this)
        val id = intent.getStringExtra("visit") ?: return finish()
        visit = nurse.day.firstOrNull { it.id == id } ?: return finish()

        val holder = findViewById<LinearLayout>(R.id.scores)
        for (s in scores) {
            val row = layoutInflater.inflate(R.layout.item_score, holder, false)
            row.findViewById<TextView>(R.id.s_label).text = s
            val bar = row.findViewById<SeekBar>(R.id.s_bar)
            val num = row.findViewById<TextView>(R.id.s_value)
            bar.max = 10
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { num.text = p.toString() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            bars[s] = bar
            holder.addView(row)
        }

        findViewById<Button>(R.id.arrive).setOnClickListener { arrive() }
        findViewById<Button>(R.id.dose).setOnClickListener { doseDialog() }
        findViewById<Button>(R.id.waste).setOnClickListener { wasteDialog() }
        findViewById<Button>(R.id.clearSig).setOnClickListener { findViewById<SignatureView>(R.id.sig).clear() }
        findViewById<Button>(R.id.complete).setOnClickListener { complete() }
        render()
    }

    private fun render() {
        val v = visit ?: return
        val p = nurse.patientOf(v)
        findViewById<TextView>(R.id.title).text = p?.name ?: v.patientId
        findViewById<TextView>(R.id.sub).text = listOfNotNull(p?.address, p?.level?.let { "level: $it" }).joinToString("  ·  ")
        val meds = p?.meds.orEmpty().joinToString("\n") { "• ${it.optString("drug")} ${it.optString("dose")} ${it.optString("route")}" }
        findViewById<TextView>(R.id.meds).text = if (meds.isBlank()) "No medicine list on this record." else meds
        val arrived = v.arrived.isNotEmpty()
        findViewById<Button>(R.id.arrive).apply {
            isEnabled = !arrived
            text = if (arrived) "arrived ${nurse.localTime(v.arrived)}" else "Arrive"
        }
        val show = if (arrived) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<LinearLayout>(R.id.body).visibility = show
        findViewById<LinearLayout>(R.id.footer).visibility = show
        Thread {
            val open = nurse.openWaste(v)
            ui.post {
                findViewById<TextView>(R.id.pending).apply {
                    visibility = if (open.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    text = if (open.size == 1) "1 disposal is waiting for a witness's signature"
                           else "${open.size} disposals are waiting for a witness's signature"
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun fix(): Pair<Double, Double>? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(LocationManager::class.java)
            val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            l?.let { Pair(it.longitude, it.latitude) }
        } catch (e: Exception) { null }
    }

    private fun arrive() {
        val v = visit ?: return
        val f = fix()
        Thread {
            try { nurse.arrive(v, f?.first, f?.second); visit = nurse.day.firstOrNull { it.id == v.id } ?: v; ui.post { render(); toast("Arrival signed on this phone.") } }
            catch (e: Exception) { ui.post { toast("Arrive: ${e.message}") } }
        }.start()
    }

    private fun doseDialog() {
        val v = visit ?: return
        val view = layoutInflater.inflate(R.layout.dialog_dose, null)
        AlertDialog.Builder(this).setTitle("A dose given").setView(view)
            .setPositiveButton("record") { _, _ ->
                val drug = view.findViewById<EditText>(R.id.d_drug).text.toString().trim()
                val dose = view.findViewById<EditText>(R.id.d_dose).text.toString().trim()
                val route = view.findViewById<EditText>(R.id.d_route).text.toString().trim()
                if (drug.isBlank()) return@setPositiveButton
                Thread {
                    try { nurse.giveDose(v, drug, dose, route); ui.post { toast("Recorded on this phone.") } }
                    catch (e: Exception) { ui.post { toast("Dose: ${e.message}") } }
                }.start()
            }.setNegativeButton("cancel", null).show()
    }

    private fun wasteDialog() {
        val v = visit ?: return
        val view = layoutInflater.inflate(R.layout.dialog_waste, null)
        AlertDialog.Builder(this).setTitle("Dispose, with a witness").setView(view)
            .setPositiveButton("sign my half") { _, _ ->
                val drug = view.findViewById<EditText>(R.id.w_drug).text.toString().trim()
                val amt = view.findViewById<EditText>(R.id.w_amount).text.toString().trim()
                val method = view.findViewById<EditText>(R.id.w_method).text.toString().trim()
                if (drug.isBlank()) return@setPositiveButton
                Thread {
                    try {
                        nurse.wasteRecord(v, drug, amt, method)
                        ui.post { render(); toast("Your signature is on it. The witness signs from their own handset.") }
                    } catch (e: Exception) { ui.post { toast("Disposal: ${e.message}") } }
                }.start()
            }.setNegativeButton("cancel", null).show()
    }

    private fun complete() {
        val v = visit ?: return
        val sig = findViewById<SignatureView>(R.id.sig)
        val name = findViewById<EditText>(R.id.caregiver).text.toString().trim()
        if (sig.isEmpty) { toast("The caregiver signs here before the visit closes."); return }
        val note = findViewById<EditText>(R.id.note).text.toString().trim()
        val symptoms = JSONObject().apply { for ((k, b) in bars) put(k, b.progress) }
        val png = sig.toBase64Png()
        val f = fix()
        Thread {
            try {
                nurse.chart(v, symptoms, note)
                nurse.close(v, name, png, f?.first, f?.second)
                ui.post { toast("Charted and signed on this phone."); finish() }
            } catch (e: Exception) { ui.post { toast("Complete: ${e.message}") } }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
