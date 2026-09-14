package ie.unidatum.hospice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The day: what this clinician is due to visit, read from the node in the phone. */
class MainActivity : AppCompatActivity() {
    private lateinit var nurse: Nurse
    private lateinit var adapter: VisitsAdapter
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        nurse = Nurse.get(this)
        Keys.warm()

        adapter = VisitsAdapter(nurse) { v -> startActivity(Intent(this, VisitActivity::class.java).putExtra("visit", v.id)) }
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }

        findViewById<EditText>(R.id.host).setText(nurse.branchHost)
        findViewById<EditText>(R.id.library).setText(nurse.library)
        findViewById<Button>(R.id.rejoin).setOnClickListener {
            nurse.branchHost = findViewById<EditText>(R.id.host).text.toString().trim()
            nurse.library = findViewById<EditText>(R.id.library).text.toString().trim()
            Thread { nurse.join(); ui.post { render() } }.start()
        }
        findViewById<Button>(R.id.log).setOnClickListener {
            AlertDialog.Builder(this).setTitle("node log")
                .setMessage(NodeService.logText().lines().takeLast(60).joinToString("\n"))
                .setPositiveButton("close", null).show()
        }
        findViewById<Button>(R.id.witness).setOnClickListener { witnessDialog() }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)

        NodeService.start(this)
        Thread { Thread.sleep(2500); nurse.join(); ui.post { render() } }.start()
        tick()
    }

    override fun onResume() { super.onResume(); Thread { nurse.refreshDay(); ui.post { render() } }.start() }

    private fun tick() {
        ui.postDelayed({ render(); tick() }, 3000)
    }

    private fun render() {
        findViewById<TextView>(R.id.status).text = nurse.nodeLine
        val who = "${nurse.clinicianName} · ${nurse.clinicianId} · signed ${Keys.backing}"
        findViewById<TextView>(R.id.who).text = who
        adapter.items = nurse.day
        val empty = findViewById<TextView>(R.id.empty)
        empty.visibility = if (nurse.day.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        empty.text = if (nurse.joined) "No visits on this phone for today. The branch assigns them; pull the day with Rejoin."
                     else "Joining the branch…  " + (nurse.lastError ?: "")
        findViewById<TextView>(R.id.held).apply {
            visibility = if (nurse.day.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            text = if (nurse.following) "the day is on this phone; a signal is needed for none of it"
                   else "still pulling the day onto this phone…"
            setTextColor(resources.getColor(if (nurse.following) R.color.done else R.color.muted, null))
        }
        val u = nurse.unsynced
        findViewById<TextView>(R.id.unsynced).apply {
            visibility = if (u > 0) android.view.View.VISIBLE else android.view.View.GONE
            text = if (u == 1) "1 visit charted here and waiting for a link" else "$u visits charted here and waiting for a link"
        }
    }

    /** Witnessing a disposal somebody else began: the second signature, from this handset's own key. */
    private fun witnessDialog() {
        Thread {
            val open = nurse.awaitingWitness()
            ui.post {
                if (open.isEmpty()) { toast("Nothing is waiting for a witness on this node."); return@post }
                val labels = open.map {
                    "${it.optString("drug")} ${it.optString("wasted")} · ${it.optString("method")}\nby ${it.optString("wasted_by")}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Witness a disposal")
                    .setItems(labels) { _, i ->
                        val d = open[i]
                        Thread {
                            try {
                                nurse.witnessWaste(d.optString("_id"), d.optString("drug"), d.optString("wasted"), d.optString("method"))
                                ui.post { toast("Witnessed. Both signatures are on the record.") }
                            } catch (e: Exception) { ui.post { toast("Witness: ${e.message}") } }
                        }.start()
                    }.setNegativeButton("close", null).show()
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
