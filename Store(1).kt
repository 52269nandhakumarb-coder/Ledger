package com.example.ledger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Tx(val id: Long, val ts: Long, val amt: Double, val isIn: Boolean, val who: String, val cat: String?)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("ledger", Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): List<Tx> {
        val a = JSONArray(sp(c).getString("tx", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Tx(o.getLong("id"), o.getLong("ts"), o.getDouble("amt"), o.getBoolean("in"), o.getString("who"),
                if (o.isNull("cat")) null else o.getString("cat"))
        }
    }

    @Synchronized
    fun save(c: Context, l: List<Tx>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("ts", it.ts).put("amt", it.amt)
                .put("in", it.isIn).put("who", it.who).put("cat", it.cat ?: JSONObject.NULL))
        }
        sp(c).edit().putString("tx", a.toString()).apply()
    }

    /** Adds new transactions, skipping any already stored (same id). */
    @Synchronized
    fun addAll(c: Context, n: List<Tx>) {
        val cur = all(c)
        val ids = cur.map { it.id }.toSet()
        val fresh = n.filter { it.id !in ids }
        if (fresh.isNotEmpty()) save(c, cur + fresh)
    }

    val DEFAULT_CATS = listOf("Food", "Groceries", "Transport", "Bills", "Rent", "Shopping", "Health", "Fun", "Salary", "Other")
    val OUT_CATS = listOf("Food", "Groceries", "Transport", "Bills", "Rent", "Shopping", "Health", "Fun", "Other")
    val IN_CATS = listOf("Salary", "Borrowed", "Refund", "Gift", "Interest", "Other")

    private fun readList(s: String): List<String> {
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getString(it) }
    }

    /** Separate category lists: income (money received) and expense (money paid). */
    fun cats(c: Context, isIn: Boolean): List<String> {
        val saved = sp(c).getString(if (isIn) "cats_in" else "cats_out", null)
        if (saved != null) return readList(saved)
        // carry over custom categories made in the earlier version
        val old = sp(c).getString("cats", null)?.let { readList(it) }?.filter { it !in DEFAULT_CATS } ?: emptyList()
        return (if (isIn) IN_CATS else OUT_CATS) + old
    }

    fun addCat(c: Context, name: String, isIn: Boolean) {
        val n = name.trim()
        val l = cats(c, isIn)
        if (n.isEmpty() || l.any { it.equals(n, true) }) return
        val a = JSONArray(); (l + n).forEach { a.put(it) }
        sp(c).edit().putString(if (isIn) "cats_in" else "cats_out", a.toString()).apply()
    }

    fun setCat(c: Context, id: Long, cat: String) = save(c, all(c).map { if (it.id == id) it.copy(cat = cat) else it })
    fun delete(c: Context, id: Long) = save(c, all(c).filter { it.id != id })

    private val amtRx = Regex("(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val outRx = Regex("debit|spent|paid|withdr|purchase|sent|transferred|payment of", RegexOption.IGNORE_CASE)
    private val inRx = Regex("credit|received|deposit|refund", RegexOption.IGNORE_CASE)
    private val whoRx = Regex(
        "(?:\\bat|\\bto|\\bfrom|\\binfo:?|\\bvpa)\\s+([A-Za-z0-9 &._@*-]{2,28}?)(?=\\s+on\\b|\\s+ref\\b|\\s+upi\\b|[.,;]|\$)",
        RegexOption.IGNORE_CASE)

    /** Turns a bank SMS into a transaction, or null if it isn't one. */
    fun parse(body: String, ts: Long): Tx? {
        if (body.contains("otp", true)) return null
        val amt = amtRx.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val o = outRx.find(body)?.range?.first ?: -1
        val i = inRx.find(body)?.range?.first ?: -1
        if (o < 0 && i < 0) return null
        val isIn = i >= 0 && (o < 0 || i < o)
        val who = whoRx.find(body)?.groupValues?.get(1)?.trim() ?: "Unknown"
        return Tx(ts, ts, amt, isIn, who, null)
    }
}
