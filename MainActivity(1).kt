package com.example.ledger

import android.Manifest
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
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
                        { id -> Store.delete(this, id); refresh() },
                        { id, n ->
                            val isIn = txs.firstOrNull { it.id == id }?.isIn ?: false
                            Store.addCat(this, n, isIn); Store.setCat(this, id, n.trim()); refresh()
                        })
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

@Composable
fun App(l: List<Tx>, outCats: List<String>, inCats: List<String>, onCat: (Long, String) -> Unit, onDel: (Long) -> Unit, onNew: (Long, String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val pending = l.count { it.cat == null }
    Column {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Dashboard") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Categorize" + if (pending > 0) " ($pending)" else "") })
        }
        if (tab == 0) Dash(l) else Sort(l.filter { it.cat == null }, outCats, inCats, onCat, onDel, onNew)
    }
}

fun group(l: List<Tx>): List<Pair<String, Double>> =
    l.groupBy { it.cat ?: "Uncategorized" }.mapValues { e -> e.value.sumOf { it.amt } }.toList().sortedByDescending { it.second }

@Composable
fun CatBar(k: String, v: Double, total: Double) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(k); Text(inr(v)) }
        LinearProgressIndicator(progress = { (v / total).toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
fun Dash(l: List<Tx>) {
    val now = Calendar.getInstance()
    val m = l.filter {
        val c = Calendar.getInstance().apply { timeInMillis = it.ts }
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }
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
        item { Text("Expenses by category", style = MaterialTheme.typography.titleMedium) }
        if (byOut.isEmpty()) item { Text("No expenses yet this month.") }
        items(byOut) { (k, v) -> CatBar(k, v, spent) }
        item { Text("Income by category", style = MaterialTheme.typography.titleMedium) }
        if (byIn.isEmpty()) item { Text("No income yet this month.") }
        items(byIn) { (k, v) -> CatBar(k, v, got) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sort(l: List<Tx>, outCats: List<String>, inCats: List<String>, onCat: (Long, String) -> Unit, onDel: (Long) -> Unit, onNew: (Long, String) -> Unit) {
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
                            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(t.ts),
                                style = MaterialTheme.typography.bodySmall)
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
                    TextButton(onClick = { onDel(t.id) }) { Text("Not a transaction") }
                }
            }
        }
    }
}
