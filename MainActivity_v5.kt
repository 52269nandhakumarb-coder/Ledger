package com.example.ledger

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var txs by mutableStateOf(listOf<Tx>())
    private var outCats by mutableStateOf(listOf<String>())
    private var inCats by mutableStateOf(listOf<String>())
    private var unlocked by mutableStateOf(false)
    private var prompting = false
    private lateinit var credLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    /** Asks for fingerprint / face / screen lock before showing any money data. */
    private fun authenticate() {
        if (unlocked || prompting) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) { unlocked = true; return }   // phone has no screen lock set
        prompting = true
        if (Build.VERSION.SDK_INT >= 30) {
            val p = BiometricPrompt.Builder(this)
                .setTitle("Unlock Ledger")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            p.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { prompting = false; unlocked = true }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { prompting = false }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Ledger", "Unlock to continue")
            if (i == null) { prompting = false; unlocked = true } else credLauncher.launch(i)
        }
    }

    private fun refresh() {
        txs = Store.all(this)
        outCats = Store.cats(this, false)
        inCats = Store.cats(this, true)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        credLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            prompting = false
            if (r.resultCode == Activity.RESULT_OK) unlocked = true
        }
        val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            importInbox(); refresh()
        }
        perm.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (!unlocked) LockScreen { authenticate() } else App(txs, outCats, inCats,
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

    override fun onResume() { super.onResume(); refresh(); authenticate() }

    override fun onStop() { super.onStop(); unlocked = false; prompting = false }

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

val PALETTE = listOf(
    Color(0xFFC44569), Color(0xFF0F7B6C), Color(0xFFD4A017), Color(0xFF2E75D4),
    Color(0xFF8E5BD6), Color(0xFFE67E22), Color(0xFF16A085), Color(0xFF6B7A72)
)

fun colorFor(i: Int, name: String): Color = if (name == "Uncategorized") Color(0xFF9AA0A6) else PALETTE[i % PALETTE.size]

fun ymOf(ts: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
}

fun short(v: Double): String = when {
    v >= 100000 -> String.format(Locale.US, "%.1fL", v / 100000)
    v >= 1000 -> String.format(Locale.US, "%.1fk", v / 1000)
    else -> v.toInt().toString()
}

@Composable
fun StatCard(title: String, value: String, sub: String, modifier: Modifier = Modifier,
             valueColor: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Card(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Donut(slices: List<Pair<Float, Color>>, centerText: String) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.minDimension * 0.18f
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            val total = slices.sumOf { it.first.toDouble() }.toFloat()
            if (total <= 0f) {
                drawArc(track, 0f, 360f, false, topLeft = Offset(sw / 2, sw / 2), size = arcSize, style = Stroke(sw))
            } else {
                var start = -90f
                slices.forEach { (v, c) ->
                    val sweep = v / total * 360f
                    drawArc(c, start, sweep, false, topLeft = Offset(sw / 2, sw / 2), size = arcSize, style = Stroke(sw))
                    start += sweep
                }
            }
        }
        Text(centerText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MonthBars(l: List<Tx>) {
    val months = (5 downTo 0).map { k ->
        Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -k) }
    }
    val spend = l.filter { !it.isIn && it.cat != Store.IGNORED }
    val vals = months.map { c -> val ym = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH); spend.filter { ymOf(it.ts) == ym }.sumOf { it.amt } }
    val max = maxOf(vals.maxOrNull() ?: 0.0, 1.0)
    val barColor = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(180.dp), Arrangement.spacedBy(8.dp), Alignment.Bottom) {
        months.forEachIndexed { i, c ->
            Column(Modifier.weight(1f).fillMaxHeight(), Arrangement.Bottom, Alignment.CenterHorizontally) {
                if (vals[i] > 0) Text(short(vals[i]), fontSize = 11.sp)
                Box(Modifier.fillMaxWidth().height(maxOf(3f, (120f * (vals[i] / max)).toFloat()).dp)
                    .background(if (vals[i] > 0) barColor else barColor.copy(alpha = 0.3f), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                Text(c.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()) ?: "", fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun Dash(l: List<Tx>, onOpen: (Boolean, String) -> Unit) {
    var showIn by remember { mutableStateOf(false) }
    val m = l.filter { thisMonth(it) && it.cat != Store.IGNORED }
    val out = m.filter { !it.isIn }
    val inc = m.filter { it.isIn }
    val spent = out.sumOf { it.amt }
    val got = inc.sumOf { it.amt }
    val byOut = group(out)
    val byIn = group(inc)
    val pending = l.count { it.cat == null }
    val shown = if (showIn) byIn else byOut
    val total = if (showIn) got else spent
    val top = byOut.firstOrNull()
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    LazyColumn(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Spent this month", inr(spent), "${out.size} expenses", Modifier.weight(1f), MaterialTheme.colorScheme.error)
                StatCard("Received this month", inr(got), "${inc.size} credits", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Top category", top?.first ?: "None", if (top != null) inr(top.second) else "no expenses yet", Modifier.weight(1f)) {
                    if (top != null) onOpen(false, top.first)
                }
                StatCard("Entries", "${m.size}", "this month", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Net this month", inr(got - spent), "received minus spent", Modifier.weight(1f))
                StatCard("To categorize", "$pending", if (pending > 0) "tap to categorize" else "all done", Modifier.weight(1f),
                    onClick = { onOpen(false, "Uncategorized") })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showIn, onClick = { showIn = false }, label = { Text("Expenses") })
                FilterChip(selected = showIn, onClick = { showIn = true }, label = { Text("Income") })
            }
        }
        item { Text(if (showIn) "Where it comes from" else "Where your money goes", style = MaterialTheme.typography.titleMedium) }
        item {
            Card(Modifier.fillMaxWidth(), colors = cardColors, border = cardBorder) {
                if (shown.isEmpty()) {
                    Text(if (showIn) "No income yet this month." else "No expenses yet this month.", Modifier.padding(16.dp))
                } else {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Donut(shown.mapIndexed { i, p -> Pair(p.second.toFloat(), colorFor(i, p.first)) }, inr(total))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            shown.forEachIndexed { i, (k, v) ->
                                Row(Modifier.fillMaxWidth().clickable { onOpen(showIn, k) }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(12.dp).background(colorFor(i, k), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(k, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(inr(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("${Math.round(v / total * 100)}%")
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Text("Last 6 months (spent)", style = MaterialTheme.typography.titleMedium) }
        item {
            Card(Modifier.fillMaxWidth(), colors = cardColors, border = cardBorder) {
                Box(Modifier.padding(14.dp)) { MonthBars(l) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("Ledger is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}
