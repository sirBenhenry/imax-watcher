package com.odysseywatch

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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

/**
 * One screen serving both pickers: single-select for the film, multi-select grouped by
 * province for the cinemas. Selections are written straight to Prefs, so callers just
 * re-read state in onResume rather than plumbing activity results around.
 */
class PickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_FILM = "film"
        const val MODE_THEATRE = "theatre"

        /**
         * Cineplex venues with an IMAX 70mm projector, found by sweeping all ~150
         * theatres' showtimes for a "70mm" experience. Offered as a one-tap preset;
         * everything remains manually selectable in case this drifts.
         */
        val IMAX_70MM = listOf(3401, 3403, 1405, 1409, 5130, 7420, 7408, 9406)
    }

    private lateinit var b: ActivityPickerBinding
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mode = MODE_FILM
    private var rows = listOf<Row>()
    private var visible = listOf<Row>()
    private val selected = linkedSetOf<Int>()

    /** A list entry: either a province header or a selectable item. */
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

        if (mode == MODE_THEATRE) {
            b.title.text = "Choose cinemas"
            b.search.hint = "Search city or cinema"
            b.actions.visibility = View.VISIBLE
            selected.addAll(prefs.theatreIds)
            b.preset70.setOnClickListener {
                selected.clear()
                selected.addAll(IMAX_70MM)
                adapter.notifyDataSetChanged()
                updateTitle()
            }
            b.clear.setOnClickListener {
                selected.clear()
                adapter.notifyDataSetChanged()
                updateTitle()
            }
        } else {
            b.title.text = "Choose a film"
            b.search.hint = "Search films"
        }

        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ ->
            val row = visible.getOrNull(pos) as? Row.Item ?: return@setOnItemClickListener
            if (mode == MODE_FILM) {
                prefs.filmId = row.id
                prefs.filmName = row.line1
                prefs.clearWatchState()
                finish()
            } else {
                if (!selected.remove(row.id)) selected.add(row.id)
                adapter.notifyDataSetChanged()
                updateTitle()
            }
        }

        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
        })

        b.done.setOnClickListener {
            if (mode == MODE_THEATRE) save()
            finish()
        }

        load()
    }

    private fun updateTitle() {
        if (mode == MODE_THEATRE) b.title.text = "Choose cinemas (${selected.size})"
    }

    private fun save() {
        val theatres = decodeTheatres(prefs.cachedTheatres)
        val order = theatres.filter { it.id in selected }
        prefs.theatreIds = order.map { it.id }
        prefs.theatreNames = order.associate { it.id to it.name }
        prefs.clearWatchState()
    }

    private fun load() {
        b.empty.visibility = View.VISIBLE
        b.empty.text = "Loading…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (mode == MODE_THEATRE) {
                        val list = CineplexApi.theatres()
                        prefs.cachedTheatres = encodeTheatres(list)
                        buildTheatreRows(list)
                    } else {
                        val list = CineplexApi.movies()
                        prefs.cachedMovies = encodeMovies(list)
                        buildMovieRows(list)
                    }
                }
            }

            rows = result.getOrElse {
                // Fall back to whatever was cached so the picker still works offline.
                val cached = if (mode == MODE_THEATRE)
                    buildTheatreRows(decodeTheatres(prefs.cachedTheatres))
                else buildMovieRows(decodeMovies(prefs.cachedMovies))
                if (cached.isEmpty()) {
                    b.empty.text = "Couldn't load: ${it.message}"
                    return@launch
                }
                cached
            }

            b.empty.visibility = View.GONE
            applyFilter(b.search.text?.toString() ?: "")
            updateTitle()
        }
    }

    private fun buildTheatreRows(list: List<Theatre>): List<Row> {
        val out = mutableListOf<Row>()
        list.groupBy { it.province.ifBlank { "—" } }
            .toSortedMap()
            .forEach { (prov, items) ->
                out.add(Row.Header("$prov · ${items.size}"))
                items.sortedBy { it.city }.forEach {
                    out.add(Row.Item(it.id, it.name, it.city))
                }
            }
        return out
    }

    private fun buildMovieRows(list: List<Movie>): List<Row> =
        list.map { Row.Item(it.id, it.name, it.releaseDate.ifBlank { "—" }) }

    private fun applyFilter(q: String) {
        val query = q.trim().lowercase()
        visible = if (query.isEmpty()) rows else {
            // Drop headers whose group has no surviving items.
            val kept = mutableListOf<Row>()
            var pendingHeader: Row.Header? = null
            var wroteUnderHeader = false
            for (r in rows) {
                when (r) {
                    is Row.Header -> { pendingHeader = r; wroteUnderHeader = false }
                    is Row.Item -> {
                        if (r.line1.lowercase().contains(query) || r.line2.lowercase().contains(query)) {
                            if (!wroteUnderHeader) {
                                pendingHeader?.let { kept.add(it) }
                                wroteUnderHeader = true
                            }
                            kept.add(r)
                        }
                    }
                }
            }
            kept
        }
        adapter.notifyDataSetChanged()
        b.empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        if (visible.isEmpty()) b.empty.text = "No matches"
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) =
            if (visible[position] is Row.Header) 0 else 1

        override fun isEnabled(position: Int) = visible[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = visible[position]) {
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
                    if (mode == MODE_THEATRE) {
                        cb.visibility = View.VISIBLE
                        cb.isChecked = row.id in selected
                    } else {
                        cb.visibility = View.GONE
                    }
                    v
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
