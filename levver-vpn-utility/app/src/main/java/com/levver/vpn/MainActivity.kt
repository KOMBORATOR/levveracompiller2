package com.levver.vpn

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.content.ComponentName
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Navy=Color(0xFF071B35)
private val Panel=Color(0xFF102B49)
private val Sky=Color(0xFF3880F6)
private val Pale=Color(0xFFBBD3FF)

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
   requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 740)
  }
  val store=ProfileStore(this)
  setContent {
   var profiles by remember { mutableStateOf(store.load()) }
   var selected by remember { mutableStateOf(profiles.firstOrNull()?.id) }
   var tab by remember { mutableIntStateOf(0) }
   var notice by remember { mutableStateOf("Добавь свой сервер, чтобы создать профиль.") }
   var input by remember { mutableStateOf("") }
   var vpnRunning by remember { mutableStateOf(false) }
   var probeResults by remember { mutableStateOf<Map<String, ServerProbe>>(emptyMap()) }
   val uiScope = rememberCoroutineScope()
   fun checkAllServers() {
    if (profiles.isEmpty()) { notice = "Сначала добавь серверы."; return }
    notice = "Проверяем доступность серверов…"
    uiScope.launch {
     val results = withContext(Dispatchers.IO) {
      profiles.associate { profile -> profile.id to ServerDiagnostics.probe(profile) }
     }
     probeResults = results
     val ranked = results.entries.filter { it.value.latencyMs != null }.sortedBy { it.value.latencyMs }
     notice = if (ranked.isEmpty()) "TCP-проверка не нашла доступных серверов. Для UDP-протоколов нужен отдельный тест." else {
      val best = profiles.first { it.id == ranked.first().key }
      "Лучший по TCP-задержке: ${best.name} — ${ranked.first().value.latencyMs} мс. Это предварительный результат."
     }
    }
   }
   var pendingConfig by remember { mutableStateOf<String?>(null) }
   val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == RESULT_OK) {
     pendingConfig?.let { config ->
      ContextCompat.startForegroundService(this@MainActivity,
       Intent(this@MainActivity, LevverVpnService::class.java)
        .setAction(LevverVpnService.ACTION_START)
        .putExtra(LevverVpnService.EXTRA_CONFIG, config))
      vpnRunning = true
      notice = "Запрос на подключение отправлен. Статус появится в уведомлении LEVVER."
     }
    } else {
     notice = "Android не выдал разрешение на VPN."
    }
   }
   fun connectSelected() {
    val profile = profiles.firstOrNull { it.id == selected }
    if (profile == null) { notice = "Сначала выбери профиль."; return }
    val config = try {
     SingBoxConfigBuilder.build(profile)
    } catch (e: Exception) {
     notice = e.message ?: "Не удалось подготовить конфигурацию."
     return
    }
    pendingConfig = config
    getSharedPreferences("levver_runtime", MODE_PRIVATE).edit().putString("last_config", config).apply()
    val permission = VpnService.prepare(this@MainActivity)
    if (permission != null) vpnPermission.launch(permission)
    else {
     ContextCompat.startForegroundService(this@MainActivity,
      Intent(this@MainActivity, LevverVpnService::class.java)
       .setAction(LevverVpnService.ACTION_START)
       .putExtra(LevverVpnService.EXTRA_CONFIG, config))
     vpnRunning = true
     notice = "Запрос на подключение отправлен. Статус появится в уведомлении LEVVER."
    }
   }
   fun disconnectVpn() {
    startService(Intent(this@MainActivity, LevverVpnService::class.java).setAction(LevverVpnService.ACTION_STOP))
    vpnRunning = false
    notice = "Запрошено отключение VPN."
   }
   fun mergeProfiles(parsed: List<Profile>) {
    val added=parsed.filter { p->profiles.none{it.raw==p.raw && it.subscriptionUrl==p.subscriptionUrl} }
    profiles=profiles+added
    store.save(profiles)
    selected=added.firstOrNull()?.id?:parsed.firstOrNull()?.id?:selected
    notice="Добавлено серверов: ${added.size}"
    input=""
   }
   fun importProfileText(raw:String){
    val value=raw.trim()
    if(value.startsWith("https://",true) || value.startsWith("http://",true)){
     notice="Загружаем список серверов…"
     uiScope.launch {
      runCatching { withContext(Dispatchers.IO) { SubscriptionImporter.fetch(value) } }
       .onSuccess { mergeProfiles(it); notice="Подписка добавлена: ${it.size} серверов" }
       .onFailure { e -> notice=e.message ?: "Не удалось загрузить подписку." }
     }
    } else {
     val parsed=runCatching { ProfileParser.parseSubscription(value, "local-import") }
      .getOrElse { e -> notice=e.message ?: "Не удалось распознать ссылку или конфигурацию."; return }
     mergeProfiles(parsed.map { it.copy(subscriptionUrl = null) })
    }
   }
   fun refreshSubscriptions(){
    val urls=profiles.mapNotNull { it.subscriptionUrl }.distinct()
    if(urls.isEmpty()){ notice="Сначала добавь URL подписки."; return }
    notice="Обновляем подписки…"
    uiScope.launch {
     var current=profiles
     var refreshed=0
     val errors=mutableListOf<String>()
     for(url in urls){
      try {
       val latest=withContext(Dispatchers.IO){SubscriptionImporter.fetch(url)}
       current=current.filterNot { it.subscriptionUrl==url } + latest
       refreshed += latest.size
      } catch(e:Exception){ errors += (e.message ?: "ошибка обновления") }
     }
     profiles=current.distinctBy { it.raw to it.subscriptionUrl }
     store.save(profiles)
     if(selected !in profiles.map { it.id }) selected=profiles.firstOrNull()?.id
     notice=if(errors.isEmpty()) "Подписки обновлены: $refreshed серверов." else "Обновлено: $refreshed. Ошибок: ${errors.size}."
    }
   }
   val qr=rememberLauncherForActivityResult(ScanContract()){ result->result.contents?.let{importProfileText(it)} }
   val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
     runCatching {
      contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
       ?: error("Не удалось открыть файл.")
     }.onSuccess { fileText ->
      if (fileText.length > 3_000_000) notice = "JSON слишком большой (лимит 3 МБ)."
      else importProfileText(fileText)
     }.onFailure { e -> notice = e.message ?: "Не удалось прочитать JSON-файл." }
    }
   }
   LaunchedEffect(Unit) {
    val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
    val deepLink = intent?.dataString
    when {
        !sharedText.isNullOrBlank() -> importProfileText(sharedText)
        !deepLink.isNullOrBlank() -> importProfileText(deepLink)
    }
   }
   MaterialTheme(colorScheme=darkColorScheme(primary=Sky,onPrimary=Navy,background=Navy,surface=Panel,onSurface=Color.White)){
    Scaffold(containerColor=Navy,bottomBar={
     NavigationBar(containerColor=Color(0xFF0B2340)){
      NavigationBarItem(tab==0,{tab=0},{Icon(Icons.Default.Shield,null)},label={Text("Главная")})
      NavigationBarItem(tab==1,{tab=1},{Icon(Icons.Default.Cloud,null)},label={Text("Серверы")})
      NavigationBarItem(tab==2,{tab=2},{Icon(Icons.Default.Settings,null)},label={Text("Настройки")})
     }
    }){ pad->
     when(tab){
      0->HomeScreen(profiles.size,profiles.firstOrNull{it.id==selected},notice,vpnRunning,{connectSelected()},{disconnectVpn()},Modifier.padding(pad))
      1->ProfilesScreen(profiles,input,{input=it},notice,{importProfileText(input)},{pasteFromClipboard()?.let{importProfileText(it)}?:run{notice="Буфер обмена пуст."}},{qr.launch(ScanOptions().setPrompt("Наведите камеру на QR-код").setBeepEnabled(false).setOrientationLocked(false))},{p->profiles=profiles.filterNot{it.id==p.id};store.save(profiles);if(selected==p.id)selected=profiles.firstOrNull()?.id;notice="Профиль удалён."},{selected=it},selected,Modifier.padding(pad),probeResults,{checkAllServers()},{jsonPicker.launch(arrayOf("application/json","text/*","application/octet-stream"))},{refreshSubscriptions()})
      else->SettingsScreen(profiles.size,Modifier.padding(pad),{ requestQuickSettingsTile() })
     }
    }
   }
  }
 }
 private fun requestQuickSettingsTile() {
  if (Build.VERSION.SDK_INT < 33) {
   android.widget.Toast.makeText(this, "Открой шторку Android → Изменить → добавь LEVVER VPN вручную.", android.widget.Toast.LENGTH_LONG).show()
   return
  }
  val statusBar = getSystemService(StatusBarManager::class.java)
  statusBar.requestAddTileService(
   ComponentName(this, QuickSettingsTileService::class.java),
   "LEVVER VPN",
   Icon.createWithResource(this, R.drawable.ic_levver_mark),
   mainExecutor
  ) { result: Int ->
   val msg = when(result) {
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка LEVVER VPN добавлена."
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка уже добавлена."
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Добавление плитки отменено."
    else -> "Android не добавил плитку (код $result). Добавь вручную через Изменить плитки."
   }
   android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
  }
 }
 private fun pasteFromClipboard():String?{
  val cm=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val clip=cm.primaryClip
  return if(clip!=null&&clip.itemCount>0) clip.getItemAt(0).coerceToText(this)?.toString() else null
 }
}

@Composable private fun Header(){
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
  Image(painterResource(R.drawable.ic_levver_mark),contentDescription="LEVVER Utility",modifier=Modifier.size(42.dp))
  Spacer(Modifier.width(12.dp)); Column { Text("LEVVER",color=Color.White,fontWeight=FontWeight.Bold,fontSize=19.sp,letterSpacing=2.sp); Text("UTILITY",color=Pale,fontSize=10.sp,letterSpacing=1.5.sp) }
  Spacer(Modifier.weight(1f)); Text("1.0.0",color=Pale,fontSize=11.sp)
 }
}
@Composable private fun HomeScreen(count:Int,active:Profile?,notice:String,connected:Boolean,onConnect:()->Unit,onDisconnect:()->Unit,modifier:Modifier=Modifier){
 Column(modifier.fillMaxSize().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally){
  Header(); Spacer(Modifier.height(48.dp))
  Box(Modifier.size(164.dp).border(3.dp,Sky,CircleShape).padding(12.dp).border(1.dp,Sky.copy(alpha=.25f),CircleShape),contentAlignment=Alignment.Center){
   Box(Modifier.size(126.dp),contentAlignment=Alignment.Center){Image(painterResource(R.drawable.ic_levver_mark),contentDescription="Логотип LEVVER",modifier=Modifier.size(100.dp))}
  }
  Spacer(Modifier.height(22.dp)); Text("LEVVER UTILITY",color=Color.White,fontWeight=FontWeight.Bold,fontSize=21.sp)
  Text("VPN-клиент • ссылки и sing-box JSON",color=Pale,fontSize=12.sp)
  Spacer(Modifier.height(18.dp))
  Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF3A2B18)),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){
   Column(Modifier.padding(14.dp)){
    Text(if (connected) "VPN • ЗАПУСК ЗАПРОШЕН" else "VPN • ГОТОВ К ПОДКЛЮЧЕНИЮ",color=Color(0xFFFFD18A),fontWeight=FontWeight.Bold,fontSize=11.sp)
    Spacer(Modifier.height(5.dp))
    Text("Движок sing-box подключён. Для текущего теста нужен sing-box JSON с TUN inbound. Проверь статус в системном уведомлении.",color=Color.White,fontSize=12.sp)
   }
  }
  Spacer(Modifier.height(18.dp)); InfoCard("ВЫБРАННЫЙ ПРОФИЛЬ",active?.name?: "Профиль не выбран")
  Spacer(Modifier.height(12.dp))
  if (connected) Button(onClick=onDisconnect,modifier=Modifier.fillMaxWidth()) { Text("Отключить VPN") }
  else Button(onClick=onConnect,enabled=active!=null,modifier=Modifier.fillMaxWidth()) { Text("Подключить") }
  Spacer(Modifier.height(12.dp)); Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Metric("ПРОФИЛИ",count.toString(),Modifier.weight(1f));Metric("VPN-ТУННЕЛЬ",if (connected) "Запуск" else "Отключён",Modifier.weight(1f))}
  Spacer(Modifier.weight(1f)); Text(notice,color=Pale,fontSize=12.sp)
 
 }
}
@Composable private fun ProfilesScreen(profiles:List<Profile>,input:String,onInput:(String)->Unit,notice:String,onImport:()->Unit,onPaste:()->Unit,onScan:()->Unit,onDelete:(Profile)->Unit,onSelect:(String)->Unit,selected:String?,modifier:Modifier=Modifier,diagnostics:Map<String,ServerProbe> = emptyMap(),onProbeAll:()->Unit = {},onPickJson:()->Unit = {},onRefreshSubscriptions:()->Unit = {}){
 Column(modifier.fillMaxSize().padding(18.dp)){
  Header(); Spacer(Modifier.height(20.dp)); Text("Серверы и подписки",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
  Text("Добавляй конфигурации, которыми имеешь право пользоваться.",color=Pale,fontSize=11.sp)
  Spacer(Modifier.height(14.dp))
  OutlinedTextField(value=input,onValueChange=onInput,modifier=Modifier.fillMaxWidth(),label={Text("Ссылка, JSON, список или URL подписки")},minLines=2,maxLines=5,colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Sky,focusedLabelColor=Sky))
  Spacer(Modifier.height(8.dp)); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
   Button(onClick=onImport,modifier=Modifier.weight(1f)){Icon(Icons.Default.Add,null);Text("Импорт")}
   OutlinedButton(onClick=onPaste){Icon(Icons.Default.ContentPaste,null);Text("Вставить")}
   OutlinedButton(onClick=onScan){Icon(Icons.Default.QrCodeScanner,null)}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){ OutlinedButton(onClick=onPickJson,modifier=Modifier.weight(1f)){Text("Импорт JSON")} ; OutlinedButton(onClick=onRefreshSubscriptions,modifier=Modifier.weight(1f)){Text("Обновить подписки")} }
  Text("Подписка загружает сразу несколько серверов. Обновление заменяет старый список актуальным.",color=Color(0xFF8EADD8),fontSize=10.sp)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){ Text(notice,color=Pale,fontSize=11.sp,modifier=Modifier.weight(1f)); OutlinedButton(onClick=onProbeAll){Text("Пинг / рейтинг")} }
  Text("TCP-задержка — ориентир доступности. UDP-серверы (например, Hysteria2) этим тестом честно не сравнить.",color=Color(0xFF8EADD8),fontSize=10.sp)
  Spacer(Modifier.height(10.dp))
  LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
   items(profiles,key={it.id}){p->
    Card(onClick={onSelect(p.id)},colors=CardDefaults.cardColors(containerColor=if(p.id==selected)Color(0xFF164B70) else Panel),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){
     Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.weight(1f)){Text(p.name,color=Color.White,fontWeight=FontWeight.SemiBold);Text("${p.protocol} • ${p.server}",color=Pale,fontSize=11.sp); p.subscriptionUrl?.let { Text("Подписка • ${it.substringAfter("://").substringBefore('/')}",color=Color(0xFF8EADD8),fontSize=10.sp) }; diagnostics[p.id]?.let { result -> Text(result.latencyMs?.let { "TCP: $it мс • ${result.detail}" } ?: result.detail,color=if(result.latencyMs==null) Color(0xFFFFC078) else Color(0xFF8DE0B0),fontSize=10.sp) }}
      IconButton(onClick={onDelete(p)}){Icon(Icons.Default.Delete,null,tint=Color(0xFFFF8D8D))}
     }
    }
   }
  }
 }
}
@Composable private fun InfoCard(title:String,value:String){
 Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
  Column(Modifier.padding(16.dp)){Text(title,color=Pale,fontSize=10.sp,letterSpacing=1.4.sp);Spacer(Modifier.height(7.dp));Text(value,color=Color.White,fontWeight=FontWeight.SemiBold)}
 }
}
@Composable private fun Metric(title:String,value:String,modifier:Modifier=Modifier){
 Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(16.dp)){
  Column(Modifier.padding(14.dp)){Text(title,color=Pale,fontSize=10.sp);Spacer(Modifier.height(7.dp));Text(value,color=Color.White,fontWeight=FontWeight.Bold)}
 }
}
@Composable private fun SettingsScreen(count:Int,modifier:Modifier=Modifier,onAddTile:()->Unit){
 Column(modifier.fillMaxSize().padding(22.dp)){Header();Spacer(Modifier.height(24.dp));Text("Настройки",color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp))
  InfoCard("О ПРИЛОЖЕНИИ","LEVVER VPN UTILITY\nВерсия 1.0.0 • sing-box");Spacer(Modifier.height(12.dp))
  Button(onClick=onAddTile,modifier=Modifier.fillMaxWidth()){Text("Добавить плитку LEVVER VPN в шторку Android")}
  Spacer(Modifier.height(12.dp))
  InfoCard("ЛОКАЛЬНЫЕ ДАННЫЕ","Сохранено профилей: $count");Spacer(Modifier.height(12.dp))
  InfoCard("БЕЗОПАСНОСТЬ","Ссылки и конфигурации могут содержать секреты доступа. Не отправляй их посторонним.")
  Spacer(Modifier.height(12.dp))
  InfoCard("ЛИЦЕНЗИИ","VPN-движок sing-box распространяется по GPL-3.0-or-later.")
 }
}
