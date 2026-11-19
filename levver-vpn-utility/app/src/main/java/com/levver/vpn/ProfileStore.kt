package com.levver.vpn
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
class ProfileStore(context:Context){
 private val prefs=context.getSharedPreferences("levver_profiles",Context.MODE_PRIVATE)
 fun load():List<Profile> = try { val a=JSONArray(prefs.getString("items","[]")); (0 until a.length()).map { val o=a.getJSONObject(it); Profile(o.getString("id"),o.getString("name"),o.getString("protocol"),o.getString("raw"),o.getString("server")) } } catch(_:Exception){emptyList()}
 fun save(items:List<Profile>){ val a=JSONArray(); items.forEach{p->a.put(JSONObject().put("id",p.id).put("name",p.name).put("protocol",p.protocol).put("raw",p.raw).put("server",p.server))}; prefs.edit().putString("items",a.toString()).apply() }
}
