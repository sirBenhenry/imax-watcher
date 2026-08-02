package com.odysseywatch

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.odysseywatch.databinding.ActivityPickerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One screen for both choices: which film (single select) and which of the eight IMAX
 * 70mm venues (multi select). Selections are written straight to Prefs, so callers just
 * re-read state in onResume.
 */
class PickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_FILM = "film"
        const val MODE_VENUE = "venue"

        /** Film discovery costs ~32 requests, so reuse it for a while. */
        private const val FILM_CACHE_MS = 6 * 60 * 60 * 1000L
    }

    private lateinit var b: ActivityPickerBinding
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mode = MODE_FILM
    private var rows = listOf<Row>()
    private val selected = linkedSetOf<Int>()

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Item(val id: Int, val line1: String, val line2: String) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILM

        if (mode == MODE_VENUE) {
            b.title.text = "IMAX 70mm cinemas"
            b.hint.text = "The eight Cineplex venues with a 70mm projector"
            b.actions.visibility = View.VISIBLE
            selected.addAll(prefs.theatreIds)
            b.selectAll.setOnClickListener {
                if (selected.size == Venues.IDS.size) selected.clear()
                else { selected.clear(); selected.addAll(Venues.IDS) }
                adapter.notifyDataSetChanged(); updateTitle()
            }
            b.refresh.visibility = View.GONE
            rows = venueRows()
            b.empty.visibility = View.GONE
            adapter.notifyDataSetChanged()
            updateTitle()
        } else {
            b.title.text = "Films in IMAX 70mm"
            b.hint.text = "Only films with 70mm showtimes are listed"
            b.actions.visibility = View.VISIBLE
            b.selectAll.visibility = View.GONE
            b.refresh.setOnClickListener { loadFilms(force = true) }
            loadFilms(force = false)
        }

        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos) as? Row.Item ?: return@setOnItemClickListener
            if (mode == MODE_FILM) {
                prefs.filmId = row.id
                prefs.filmName = row.line1
                prefs.clearWatchState()
                finish()
            } else {
                if (!selected.remove(row.id)) selected.add(row.id)
                adapter.notifyDataSetChanged(); updateTitle()
            }
        }

        b.done.setOnClickListener {
            if (mode == MODE_VENUE) {
                prefs.theatreIds = if (selected.isEmpty()) Venues.IDS else selected.toList()
                prefs.clearWatchState()
            }
            finish()
        }
    }

    private fun updateTitle() {
        if (mode == MODE_VENUE) b.title.text = "IMAX 70mm cinemas (${selected.size}/${Venues.IDS.size})"
    }

    private fun venueRows(): List<Row> {
        val out = mutableListOf<Row>()
        Venues.ALL.groupBy { it.province }.toSortedMap().forEach { (prov, list) ->
            out.add(Row.Header(prov))
            list.sortedBy { it.city }.forEach { out.add(Row.Item(it.id, it.name, it.city)) }
        }
        return out
    }

    private fun loadFilms(force: Boolean) {
        val fresh = System.currentTimeMillis() - prefs.cachedFilmsAt < FILM_CACHE_MS
        if (!force && fresh && prefs.cachedFilms.isNotBlank()) {
            rows = filmRows(decodeFilms(prefs.cachedFilms))
            b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            return
        }

        b.empty.visibility = View.VISIBLE
        b.empty.text = "Scanning all 8 venues for 70mm showtimes…"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { CineplexApi.seventyMmFilms() } }
            res.onSuccess {
                prefs.cachedFilms = encodeFilms(it)
                prefs.cachedFilmsAt = System.currentTimeMillis()
                rows = filmRows(it)
                b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (rows.isEmpty()) b.empty.text = "Nothing is screening in IMAX 70mm right now."
                adapter.notifyDataSetChanged()
            }.onFailure {
                val cached = decodeFilms(prefs.cachedFilms)
                if (cached.isNotEmpty()) {
                    rows = filmRows(cached); b.empty.visibility = View.GONE
                    adapter.notifyDataSetChanged()
                } else b.empty.text = "Couldn't load: ${it.message}"
            }
        }
    }

    private fun filmRows(films: List<Film>): List<Row> = films.map { f ->
        val where = if (f.venueIds.size == Venues.IDS.size) "All ${Venues.IDS.size} cinemas"
        else f.venueIds.joinToString(", ") { Venues.shortName(it) }
        Row.Item(f.id, f.name, where)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Header -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_header, parent, false)
                    (v as TextView).text = row.text
                    v
                }
                is Row.Item -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_pick, parent, false)
                    v.findViewById<TextView>(R.id.line1).text = row.line1
                    v.findViewById<TextView>(R.id.line2).text = row.line2
                    val cb = v.findViewById<CheckBox>(R.id.check)
                    if (mode == MODE_VENUE) {
                        cb.visibility = View.VISIBLE
                        cb.isChecked = row.id in selected
                    } else cb.visibility = View.GONE
                    v
                }
            }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

fun encodeFilms(list: List<Film>): String {
    val a = JSONArray()
    list.forEach {
        a.put(
            JSONObject().put("id", it.id).put("name", it.name)
                .put("venues", JSONArray(it.venueIds))
        )
    }
    return a.toString()
}

fun decodeFilms(s: String): List<Film> = runCatching {
    val a = JSONArray(s)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        val v = o.optJSONArray("venues") ?: JSONArray()
        Film(o.optInt("id"), o.optString("name"), (0 until v.length()).map { i -> v.optInt(i) })
    }
}.getOrDefault(emptyList())
