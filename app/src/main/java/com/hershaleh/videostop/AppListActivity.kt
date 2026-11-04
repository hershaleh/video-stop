package com.hershaleh.videostop

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView

/** בחירת מצב (הכול / חוץ מהמסומנות / רק המסומנות) וסימון אפליקציות. הכניסה אליו מוגנת בקוד. */
class AppListActivity : Activity() {

    private class Row(val pkg: String, val label: String, val icon: Drawable?)

    private val all = ArrayList<Row>()
    private val shown = ArrayList<Row>()
    private val selected = HashSet<String>()
    private lateinit var hint: TextView
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        selected.addAll(Prefs.listedApps(this))
        hint = findViewById(R.id.hint)

        val group = findViewById<RadioGroup>(R.id.modeGroup)
        group.check(
            when (Prefs.listMode(this)) {
                Prefs.MODE_EXCEPT -> R.id.rbExcept
                Prefs.MODE_ONLY -> R.id.rbOnly
                else -> R.id.rbAll
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            Prefs.setListMode(
                this,
                when (id) {
                    R.id.rbExcept -> Prefs.MODE_EXCEPT
                    R.id.rbOnly -> Prefs.MODE_ONLY
                    else -> Prefs.MODE_ALL
                }
            )
            updateHint()
        }

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: LayoutInflater.from(this@AppListActivity)
                    .inflate(R.layout.item_app, parent, false)
                val row = shown[position]
                v.findViewById<ImageView>(R.id.icon).setImageDrawable(row.icon)
                v.findViewById<TextView>(R.id.label).text = row.label
                v.findViewById<CheckBox>(R.id.check).isChecked = row.pkg in selected
                return v
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val pkg = shown[position].pkg
            if (!selected.remove(pkg)) selected.add(pkg)
            Prefs.setListedApps(this, selected)
            adapter.notifyDataSetChanged()
            updateHint()
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        updateHint()
        loadApps()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val rows = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        Row(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
                    } catch (_: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                all.clear()
                all.addAll(rows)
                applyFilter(findViewById<EditText>(R.id.search).text.toString())
            }
        }.start()
    }

    private fun applyFilter(q: String) {
        shown.clear()
        shown.addAll(all.filter { q.isBlank() || it.label.contains(q.trim(), ignoreCase = true) })
        adapter.notifyDataSetChanged()
    }

    private fun updateHint() {
        hint.setText(
            when (Prefs.listMode(this)) {
                Prefs.MODE_EXCEPT -> R.string.apps_hint_except
                Prefs.MODE_ONLY ->
                    if (selected.isEmpty()) R.string.apps_hint_only_empty else R.string.apps_hint_only
                else -> R.string.apps_hint_all
            }
        )
    }
}
