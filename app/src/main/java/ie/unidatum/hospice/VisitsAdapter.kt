package ie.unidatum.hospice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VisitsAdapter(private val nurse: Nurse, private val onOpen: (Visit) -> Unit) :
    RecyclerView.Adapter<VisitsAdapter.VH>() {

    var items: List<Visit> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.v_name)
        val when_: TextView = v.findViewById(R.id.v_when)
        val addr: TextView = v.findViewById(R.id.v_addr)
        val pill: TextView = v.findViewById(R.id.v_pill)
        val sync: TextView = v.findViewById(R.id.v_sync)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_visit, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val v = items[i]
        val p = nurse.patientOf(v)
        h.name.text = p?.name ?: v.patientId
        val time = nurse.localTime(v.scheduled)
        h.when_.text = if (time.isBlank()) v.kind else "$time  ·  ${v.kind}"
        h.addr.text = p?.address ?: ""
        h.pill.text = when (v.status) { "in_progress" -> "in the house"; "charted" -> "charted"; else -> "to visit" }
        h.pill.setBackgroundResource(when (v.status) {
            "in_progress" -> R.drawable.pill_open
            "charted" -> R.drawable.pill_done
            else -> R.drawable.pill_todo
        })
        h.sync.visibility = if (v.status == "charted" && !v.synced) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { onOpen(v) }
    }
}
