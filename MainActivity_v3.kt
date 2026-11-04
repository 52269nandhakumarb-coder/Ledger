package com.example.ledger

import android.Manifest
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var txs by mutableStateOf(listOf<Tx>())
    private var outCats by mutableStateOf(listOf<String>())
    private var inCats by mutableStateOf(listOf<String>())

    private fun refresh() {
        txs = Store.all(this)
        outCats = Store.cats(this, false)
        inCats = Store.cats(this, true)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            importInbox(); refresh()
        }
        perm.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    App(txs, outCats, inCats,
                        { id, c -> Store.setCat(this, id, c); refresh() },
                        { id -> Store.setCat(this, id, Store.IGNORED); refresh() },
                        { id, n ->
                            val isIn = txs.firstOrNull { it.id == id }?.isIn ?: false
                            Store.addCat(this, n, isIn); Store.setCat(this, id, n.trim()); refresh()
                        },
                        { id -> Store.clearCat(this, id); refresh() })
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    /** Reads this month's existing SMS once permission is granted, so nothing is missed. */
    private fun importInbox() {
        try {
            val start = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            val found = mutableListOf<Tx>()
            contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("body", "date"), "date>=?",
                arrayOf(start.toString()), null)?.use {
                while (it.moveToNext()) Store.parse(it.getString(0) ?: "", it.getLong(1))?.let(found::add)
            }
            Store.addAll(this, found)
        } catch (e: SecurityException) { }
    }
}

fun inr(n: Double): String = "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(Math.round(n))

fun fmt(ts: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(ts)

fun thisMonth(t: Tx): Boolean {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t.ts }
    return c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
}

@Composable
fun App(
    l: List<Tx>, outCats: List<String>, inCats: List<String>,
    onCat: (Long, String) -> Unit, onIgnore: (Long) -> Unit, onNew: (Long, String) -> Unit, onClear: (Long) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val pending = l.count { it.cat == null }
    Column {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0; detail = null }, text = { Text("Dashboard") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Categorize" + if (pending > 0) " ($pending)" else "") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Ignored") })
        }
        val d = detail
        when {
            tab == 0 && d != null -> Detail(l, d.first, d.second, { detail = null }, onClear)
            tab == 0 -> Dash(l) { isIn, c -> if (c == "Uncategorized") tab = 1 else detail = Pair(isIn, c) }
            tab == 1 -> Sort(l.filter { it.cat == null }, outCats, inCats, onCat, onIgnore, onNew)
            else -> Ignored(l, onClear)
        }
    }
}

fun group(l: List<Tx>): List<Pair<String, Double>> =
    l.groupBy { it.cat ?: "Uncategorized" }.mapValues { e -> e.value.sumOf { it.amt } }.toList().sortedByDescending { it.second }

@Composable
fun CatBar(k: String, v: Double, total: Double, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(k); Text(inr(v) + "  ›") }
        LinearProgressIndicator(progress = { (v / total).toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
fun Dash(l: List<Tx>, onOpen: (Boolean, String) -> Unit) {
    val m = l.filter { thisMonth(it) && it.cat != Store.IGNORED }
    val out = m.filter { !it.isIn }
    val inc = m.filter { it.isIn }
    val spent = out.sumOf { it.amt }
    val got = inc.sumOf { it.amt }
    val byOut = group(out)
    val byIn = group(inc)
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Spent this month", style = MaterialTheme.typography.labelLarge)
            Text(inr(spent), fontSize = 40.sp, color = MaterialTheme.colorScheme.error)
            Text("Received ${inr(got)}  ·  Net ${inr(got - spent)}")
        }
        item { Text("Expenses by category (tap to see entries)", style = MaterialTheme.typography.titleMedium) }
        if (byOut.isEmpty()) item { Text("No expenses yet this month.") }
        items(byOut) { (k, v) -> CatBar(k, v, spent) { onOpen(false, k) } }
        item { Text("Income by category (tap to see entries)", style = MaterialTheme.typography.titleMedium) }
        if (byIn.isEmpty()) item { Text("No income yet this month.") }
        items(byIn) { (k, v) -> CatBar(k, v, got) { onOpen(true, k) } }
    }
}

@Composable
fun Detail(l: List<Tx>, isIn: Boolean, cat: String, onBack: () -> Unit, onClear: (Long) -> Unit) {
    BackHandler { onBack() }
    val rows = l.filter { it.isIn == isIn && it.cat == cat && thisMonth(it) }.sortedByDescending { it.ts }
    Column(Modifier.padding(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(cat, style = MaterialTheme.typography.titleLarge)
        Text((if (isIn) "Received " else "Spent ") + inr(rows.sumOf { it.amt }) + " this month",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        if (rows.isEmpty()) Text("No entries left here.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.who, style = MaterialTheme.typography.titleMedium)
                            Text(fmt(t.ts), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text((if (t.isIn) "+" else "−") + inr(t.amt), style = MaterialTheme.typography.titleMedium,
                                color = if (t.isIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onClear(t.id) }) { Text("Remove from category") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Ignored(l: List<Tx>, onRestore: (Long) -> Unit) {
    val rows = l.filter { it.cat == Store.IGNORED }.sortedByDescending { it.ts }
    if (rows.isEmpty()) {
        Text("Nothing here yet. Entries you mark as \"Not a transaction\" are kept here, and you can restore them.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows) { t ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.who, style = MaterialTheme.typography.titleMedium)
                        Text(fmt(t.ts), style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text((if (t.isIn) "+" else "−") + inr(t.amt), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onRestore(t.id) }) { Text("Restore") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sort(l: List<Tx>, outCats: List<String>, inCats: List<String>, onCat: (Long, String) -> Unit, onIgnore: (Long) -> Unit, onNew: (Long, String) -> Unit) {
    var dlg by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    if (dlg != null) AlertDialog(
        onDismissRequest = { dlg = null },
        title = { Text("New category") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("e.g. Petrol, EMI, Gift") }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onNew(dlg!!, name); name = ""; dlg = null }) { Text("Add") } },
        dismissButton = { TextButton({ dlg = null }) { Text("Cancel") } })
    if (l.isEmpty()) { Text("All caught up. New debits and credits show up here.", Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(l.sortedByDescending { it.ts }) { t ->
            val cats = if (t.isIn) inCats else outCats
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text(t.who, style = MaterialTheme.typography.titleMedium)
                            Text(fmt(t.ts), style = MaterialTheme.typography.bodySmall)
                        }
                        Text((if (t.isIn) "+" else "−") + inr(t.amt), style = MaterialTheme.typography.titleMedium,
                            color = if (t.isIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Text(if (t.isIn) "Income: where did it come from?" else "Expense: what was it for?",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cats.forEach { c -> AssistChip(onClick = { onCat(t.id, c) }, label = { Text(c) }) }
                        AssistChip(onClick = { name = ""; dlg = t.id }, label = { Text("+ New") })
                    }
                    TextButton(onClick = { onIgnore(t.id) }) { Text("Not a transaction (move to Ignored)") }
                }
            }
        }
    }
}
