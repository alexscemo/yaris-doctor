package com.yarisdoctor

import com.yarisdoctor.domain.car.PIDS
import com.yarisdoctor.domain.health.Checker
import com.yarisdoctor.domain.health.TREND_RULES
import com.yarisdoctor.domain.health.WARN
import org.json.JSONArray
import org.json.JSONObject

// The domain tables as the hub's sync payload reads them; org.json is Android-only, so this stays out of :domain.

/** Every rule for the hub, which shows them with the alerts: [key, title, level, about, uses]. Trend rules read no live values. */
fun ruleDefs(): JSONArray = JSONArray().apply {
    for (r in Checker.RULES) put(JSONArray().put(r.key).put(r.title).put(r.level).put(r.about).put(JSONArray(r.uses)))
    for ((key, title, about) in TREND_RULES) put(JSONArray().put(key).put(title).put(WARN).put(about).put(JSONArray()))
}

/** The table for the hub, which decodes frames with it: [lid, bitStart, bitEnd, len, signed, mul, div, offset, decimals, name, unit, category, enum]. */
fun pidDefs(): JSONArray = JSONArray().apply {
    for (p in PIDS) put(JSONArray().put(p.lid).put(p.bitStart).put(p.bitEnd).put(p.len).put(p.signed).put(p.mul).put(p.div)
        .put(p.offset).put(p.decimals).put(p.name).put(p.unit).put(p.category).put(p.enum?.let { JSONArray(it) } ?: JSONObject.NULL))
}
