package com.turkishfacts.captions

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class TabrizConverterTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun matchesPythonReference() {
        val conv = TabrizConverter(res("tabriz_rules.json"))
        val v = JSONArray(res("vectors.json"))
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val (lat, fa) = conv.convert(o.getString("in"))
            assertEquals("latin #$i", o.getString("lat"), lat)
            assertEquals("persian #$i", o.getString("fa"), fa)
        }
    }
}
