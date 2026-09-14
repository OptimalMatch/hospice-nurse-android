package ie.unidatum.hospice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The clinician's side of the design, the calls the build sheet lists for the
 * nurse and aide app — made from a phone that IS a node of the branch's library,
 * so the reads and the writes are local and the branch sees them when the phone
 * next finds a link.
 *
 *   join       /api/sync to the branch node, then follow patients, visits and medications
 *   the day    /api/doc/find {clinician_id: mine, date: today}            locally
 *   arrive     visit_verification, the time and the place, signed          locally
 *   chart      /api/doc/update on the visit: assessment, symptoms, note    locally
 *   medicines  /api/doc/put a dose; a disposal the witness co-signs        locally
 *   signatures the caregiver's on the visit; the clinician's own claim     locally
 */
data class Visit(val doc: JSONObject) {
    val id get() = doc.optString("_id")
    val patientId get() = doc.optString("patient_id")
    val kind get() = doc.optString("kind", "routine")
    val scheduled get() = doc.optString("scheduled_at")
    val arrived get() = doc.optString("arrived_at")
    val left get() = doc.optString("left_at")
    val status get() = doc.optString("status", "scheduled")
    val synced get() = doc.optBoolean("_synced", false)
}

data class Patient(val doc: JSONObject) {
    val id get() = doc.optString("_id")
    val name get() = doc.optString("name")
    val address get() = doc.optString("address")
    val level get() = doc.optString("level_of_care", "routine")
    val meds: List<JSONObject> get() { val a = doc.optJSONArray("medicine_list") ?: JSONArray(); return (0 until a.length()).map { a.getJSONObject(it) } }
}

class Nurse private constructor(ctx: Context) {
    companion object {
        @Volatile private var one: Nurse? = null
        fun get(ctx: Context): Nurse = one ?: synchronized(this) { one ?: Nurse(ctx.applicationContext).also { one = it } }
        const val CLAIM_VISIT = "unidatum-hospice-visit/v1"
        const val CLAIM_WASTE = "unidatum-hospice-waste/v1"
    }

    private val prefs = ctx.getSharedPreferences("hospice", Context.MODE_PRIVATE)
    var branchHost: String
        get() = prefs.getString("branchHost", "192.168.1.2")!!
        set(v) { prefs.edit().putString("branchHost", v).apply() }
    var branchPort: Int
        get() = prefs.getInt("branchPort", 47810)
        set(v) { prefs.edit().putInt("branchPort", v).apply() }
    var library: String
        get() = prefs.getString("library", "hospice-demo")!!
        set(v) { prefs.edit().putString("library", v).apply() }
    /** Who is signing. The branch issues this; here it is derived from the handset so a demo needs no setup. */
    val clinicianId: String = "rn-" + android.os.Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    var clinicianName: String
        get() = prefs.getString("clinicianName", "R. Nurse")!!
        set(v) { prefs.edit().putString("clinicianName", v).apply() }

    val local = Api("http://127.0.0.1:${NodeService.UI_PORT}")

    @Volatile var joined = false
    @Volatile var lastError: String? = null
    @Volatile var day: List<Visit> = emptyList()
    @Volatile var patients: Map<String, Patient> = emptyMap()
    @Volatile var nodeLine: String = "starting"
    @Volatile var unsynced: Int = 0

    fun now(): String = Instant.now().toString()

    /**
     * The nurse's today, in the phone's own time zone rather than UTC. A visit at
     * half eight on a September evening in Chicago belongs to that evening's round,
     * and computing the date in UTC would file it under tomorrow and show the nurse
     * an empty day.
     */
    private fun today(): String = java.time.LocalDate.now().toString()

    /** A scheduled time as the nurse reads it: their own clock, from the stored instant. */
    fun localTime(iso: String): String = try {
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()))
    } catch (e: Exception) { iso.substringAfter("T").take(5) }

    /** The collections a day needs on the handset before the nurse leaves the Wi-Fi. */
    private val follow = listOf("patients", "visits", "medications", "visit_verification")

    /** Join the branch: one sync introduces this node, then follow what the day needs. */
    fun join() {
        try {
            local.sync(branchHost, branchPort)
            joined = true; lastError = null
            NodeService.logLine("joined $library at $branchHost:$branchPort")
            ensureFollowing()
            refreshDay()
        } catch (e: Exception) { joined = false; lastError = e.message; NodeService.logLine("join: ${e.message}") }
    }

    /**
     * Follow every collection the day needs. A node runs one replication at a
     * time and refuses the rest, so asking for four in a row leaves three
     * unfollowed and a nurse holding a catalogue with no members in it. Ask for
     * each in turn, come back to the ones it refused, and stop when nothing is
     * outstanding. Called on the way in and again by the poll, so a collection
     * added later is picked up without anyone tapping anything.
     */
    fun ensureFollowing(rounds: Int = 8) {
        for (r in 0 until rounds) {
            var again = false
            for (c in follow) {
                try {
                    val msg = local.replicate(c).optString("msg")
                    if (msg.contains("already running") || msg.contains("fetching")) again = true
                    if (msg.isNotBlank() && !msg.contains("already running")) NodeService.logLine("$c: $msg")
                } catch (e: Exception) { again = true }
            }
            if (!again) { following = true; return }
            Thread.sleep(2500)
        }
    }

    /** True once every collection is held here rather than answered by a peer. */
    @Volatile var following = false

    /** Whether the day came from this phone's own members, which is what an hour with no signal depends on. */
    fun localDay(): Boolean = try { local.count("visits", JSONObject()); true } catch (e: Exception) { false }

    /** The day, from this phone's own node. Works with the link gone, which is the whole point. */
    fun refreshDay() {
        try {
            val f = JSONObject().put("clinician_id", clinicianId).put("date", today())
            val vs = local.find("visits", f).map { Visit(it) }
                .sortedBy { it.scheduled }
            day = vs
            val ids = vs.map { it.patientId }.filter { it.isNotEmpty() }.distinct()
            if (ids.isNotEmpty()) {
                val pf = JSONObject().put("_id", JSONObject().put("\$in", JSONArray(ids)))
                patients = local.find("patients", pf).map { Patient(it) }.associateBy { it.id }
            }
            unsynced = vs.count { it.status == "charted" && !it.synced }
            nodeLine = statusLine()
            lastError = null
        } catch (e: Exception) { lastError = e.message; NodeService.logLine("day: ${e.message}") }
    }

    private fun statusLine(): String = try {
        val s = local.status()
        val peers = s.optInt("peers")
        "node ${s.optString("nodeName")} · ${s.optString("engineVersion")} · ${s.optString("library")} · ${s.optInt("files")} files · $peers peers"
    } catch (e: Exception) { "node starting" }

    fun patientOf(v: Visit): Patient? = patients[v.patientId]

    // ---- the visit -------------------------------------------------------

    /** Arrive: the time and the place, signed by the key in the phone, onto the verification collection and the visit. */
    fun arrive(v: Visit, lon: Double?, lat: Double?) {
        val at = now()
        val claim = "$CLAIM_VISIT|$clinicianId|${v.id}|arrived|${fmt(lon)}|${fmt(lat)}|$at"
        val doc = JSONObject()
            .put("_id", "${v.id}-arrived")
            .put("visit_id", v.id).put("clinician_id", clinicianId)
            .put("event", "arrived").put("at", at)
            .put("claim", claim).put("signature", Keys.sign(claim))
            .put("public_key", Keys.publicKeyB64()).put("algorithm", Keys.ALGORITHM)
        if (lon != null && lat != null) doc.put("location", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(lon, lat))))
        local.put("visit_verification", doc)
        local.update("visits", JSONObject().put("_id", v.id), JSONObject().put("arrived_at", at).put("status", "in_progress"))
        NodeService.logLine("arrived ${v.id}")
        refreshDay()
    }

    /** Chart: the assessment, the symptom scores and the note, written where the care happened. */
    fun chart(v: Visit, symptoms: JSONObject, note: String) {
        local.update("visits", JSONObject().put("_id", v.id),
            JSONObject().put("symptoms", symptoms).put("note", note).put("charted_at", now()))
        NodeService.logLine("charted ${v.id}")
    }

    /** A dose given from the comfort kit in the home. */
    fun giveDose(v: Visit, drug: String, dose: String, route: String) {
        val at = now()
        val id = "${v.id}-${drug.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-${at.replace(Regex("[^0-9]"), "").takeLast(9)}"
        local.put("medications", JSONObject()
            .put("_id", id).put("visit_id", v.id).put("patient_id", v.patientId)
            .put("drug", drug).put("dose", dose).put("route", route)
            .put("given_at", at).put("clinician_id", clinicianId))
        NodeService.logLine("dose $drug $dose $route")
    }

    /**
     * A controlled-substance disposal. The nurse writes their half here, signed by
     * this handset's key; the witness writes theirs from their own handset, signed
     * by theirs. Field-level merge keeps both halves on one document, and a record
     * carrying one signature stays visibly open until the second arrives.
     */
    fun wasteRecord(v: Visit, drug: String, amount: String, method: String): String {
        val at = now()
        val id = "${v.id}-waste-${at.replace(Regex("[^0-9]"), "").takeLast(9)}"
        val claim = "$CLAIM_WASTE|nurse|$clinicianId|$id|$drug|$amount|$method|$at"
        local.put("medications", JSONObject()
            .put("_id", id).put("visit_id", v.id).put("patient_id", v.patientId)
            .put("drug", drug).put("wasted", amount).put("method", method)
            .put("wasted_at", at).put("wasted_by", clinicianId)
            .put("waste_claim", claim).put("waste_signature", Keys.sign(claim))
            .put("waste_public_key", Keys.publicKeyB64()))
        NodeService.logLine("waste $drug $amount — awaiting a witness")
        return id
    }

    /** The witness's half, signed by the witness's own key on their own handset. */
    fun witnessWaste(wasteId: String, drug: String, amount: String, method: String) {
        val at = now()
        val claim = "$CLAIM_WASTE|witness|$clinicianId|$wasteId|$drug|$amount|$method|$at"
        local.update("medications", JSONObject().put("_id", wasteId), JSONObject()
            .put("witness_id", clinicianId).put("witnessed_at", at)
            .put("witness_claim", claim).put("witness_signature", Keys.sign(claim))
            .put("witness_public_key", Keys.publicKeyB64()))
        NodeService.logLine("witnessed $wasteId")
    }

    /** Disposals on this visit that still carry one signature. */
    fun openWaste(v: Visit): List<JSONObject> = try {
        local.find("medications", JSONObject().put("visit_id", v.id))
            .filter { it.has("waste_signature") && !it.has("witness_signature") }
    } catch (e: Exception) { emptyList() }

    /** Every disposal on this phone's node still waiting for a second signature, whoever wrote the first half. */
    fun awaitingWitness(): List<JSONObject> = try {
        local.find("medications", JSONObject().put("witness_signature", JSONObject().put("\$exists", false)))
            .filter { it.has("waste_signature") }
    } catch (e: Exception) { emptyList() }

    /** The caregiver signs the visit; the clinician signs their own claim over it. */
    fun close(v: Visit, caregiverName: String, caregiverSignaturePng: String, lon: Double?, lat: Double?) {
        val at = now()
        val claim = "$CLAIM_VISIT|$clinicianId|${v.id}|left|${fmt(lon)}|${fmt(lat)}|$at"
        local.update("visits", JSONObject().put("_id", v.id), JSONObject()
            .put("left_at", at).put("status", "charted")
            .put("caregiver_name", caregiverName)
            .put("caregiver_signature", caregiverSignaturePng)
            .put("clinician_claim", claim).put("clinician_signature", Keys.sign(claim))
            .put("clinician_public_key", Keys.publicKeyB64()))
        val doc = JSONObject()
            .put("_id", "${v.id}-left").put("visit_id", v.id).put("clinician_id", clinicianId)
            .put("event", "left").put("at", at)
            .put("claim", claim).put("signature", Keys.sign(claim))
            .put("public_key", Keys.publicKeyB64()).put("algorithm", Keys.ALGORITHM)
        if (lon != null && lat != null) doc.put("location", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(lon, lat))))
        local.put("visit_verification", doc)
        NodeService.logLine("closed ${v.id}")
        refreshDay()
    }

    private fun fmt(d: Double?): String = if (d == null) "" else String.format("%.6f", d)
}
