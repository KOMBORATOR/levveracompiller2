package com.levver.vpn

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
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

private val Navy=Color(0xFF071B35)
private val Panel=Color(0xFF102B49)
private val Sky=Color(0xFF3880F6)
private val Pale=Color(0xFFBBD3FF)

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val store=ProfileStore(this)
  setContent {
   var profiles by remember { mutableStateOf(store.load()) }
   var selected by remember { mutableStateOf(profiles.firstOrNull()?.id) }
   var tab by remember { mutableIntStateOf(0) }
   var notice by remember { mutableStateOf("Добавь свой сервер, чтобы создать профиль.") }
   var input by remember { mutableStateOf("") }
   fun importProfileText(raw:String){
    val parsed=ProfileParser.parse(raw)
    if(parsed.isEmpty()) notice="Не удалось распознать ссылку. Проверь формат."
    else {
     val added=parsed.filter { p->profiles.none{it.raw==p.raw} }
     profiles=profiles+added; store.save(profiles)
     selected=added.firstOrNull()?.id?:parsed.first().id
     notice="Импортировано профилей: ${added.size}"
     input=""
    }
   }
   val qr=rememberLauncherForActivityResult(ScanContract()){ result->result.contents?.let{importProfileText(it)} }
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
      0->HomeScreen(profiles.size,profiles.firstOrNull{it.id==selected},notice,Modifier.padding(pad))
      1->ProfilesScreen(profiles,input,{input=it},notice,{importProfileText(input)},{pasteFromClipboard()?.let{importProfileText(it)}?:run{notice="Буфер обмена пуст."}},{qr.launch(ScanOptions().setPrompt("Наведите камеру на QR-код").setBeepEnabled(false).setOrientationLocked(false))},{p->profiles=profiles.filterNot{it.id==p.id};store.save(profiles);if(selected==p.id)selected=profiles.firstOrNull()?.id;notice="Профиль удалён."},{selected=it},selected,Modifier.padding(pad))
      else->SettingsScreen(profiles.size,Modifier.padding(pad))
     }
    }
   }
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
  Box(Modifier.size(46.dp).background(Sky,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.Shield,null,tint=Color.White,modifier=Modifier.size(29.dp))}
  Spacer(Modifier.width(12.dp)); Column { Text("LEVVER",color=Color.White,fontWeight=FontWeight.Bold,fontSize=19.sp,letterSpacing=2.sp); Text("UTILITY • SKY BLUE",color=Pale,fontSize=10.sp,letterSpacing=1.5.sp) }
  Spacer(Modifier.weight(1f)); Text("0.5.0",color=Pale,fontSize=11.sp)
 }
}
@Composable private fun HomeScreen(count:Int,active:Profile?,notice:String,modifier:Modifier=Modifier){
 Column(modifier.fillMaxSize().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally){
  Header(); Spacer(Modifier.height(48.dp))
  Box(Modifier.size(164.dp).border(3.dp,Sky,CircleShape).padding(12.dp).border(1.dp,Sky.copy(alpha=.25f),CircleShape),contentAlignment=Alignment.Center){
   Box(Modifier.size(126.dp).background(Sky,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Default.Shield,null,tint=Color.White,modifier=Modifier.size(58.dp))}
  }
  Spacer(Modifier.height(22.dp)); Text("LEVVER UTILITY",color=Color.White,fontWeight=FontWeight.Bold,fontSize=21.sp)
  Text("Универсальный менеджер личных VPN-профилей",color=Pale,fontSize=12.sp)
  Spacer(Modifier.height(18.dp))
  Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF3A2B18)),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){
   Column(Modifier.padding(14.dp)){
    Text("ALPHA • VPN ENGINE IN PROGRESS",color=Color(0xFFFFD18A),fontWeight=FontWeight.Bold,fontSize=11.sp)
    Spacer(Modifier.height(5.dp))
    Text("Импорт и хранение профилей работают. Реальное VPN-подключение пока не реализовано — трафик через приложение ещё не проходит.",color=Color.White,fontSize=12.sp)
   }
  }
  Spacer(Modifier.height(18.dp)); InfoCard("ВЫБРАННЫЙ ПРОФИЛЬ",active?.name?: "Профиль не выбран")
  Spacer(Modifier.height(12.dp)); Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Metric("ПРОФИЛИ",count.toString(),Modifier.weight(1f));Metric("VPN-ТУННЕЛЬ","Не подключён",Modifier.weight(1f))}
  Spacer(Modifier.weight(1f)); Text(notice,color=Pale,fontSize=12.sp)
  Text("LEVVER • BUILD 0.5.0 ALPHA",color=Color(0xFF6FA6CC),fontSize=10.sp)
 }
}
@Composable private fun ProfilesScreen(profiles:List<Profile>,input:String,onInput:(String)->Unit,notice:String,onImport:()->Unit,onPaste:()->Unit,onScan:()->Unit,onDelete:(Profile)->Unit,onSelect:(String)->Unit,selected:String?,modifier:Modifier=Modifier){
 Column(modifier.fillMaxSize().padding(18.dp)){
  Header(); Spacer(Modifier.height(20.dp)); Text("Мои серверы",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
  Text("Добавляй конфигурации, которыми имеешь право пользоваться.",color=Pale,fontSize=11.sp)
  Spacer(Modifier.height(14.dp))
  OutlinedTextField(value=input,onValueChange=onInput,modifier=Modifier.fillMaxWidth(),label={Text("Ссылка или несколько ссылок")},minLines=2,maxLines=5,colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Sky,focusedLabelColor=Sky))
  Spacer(Modifier.height(8.dp)); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
   Button(onClick=onImport,modifier=Modifier.weight(1f)){Icon(Icons.Default.Add,null);Text("Импорт")}
   OutlinedButton(onClick=onPaste){Icon(Icons.Default.ContentPaste,null);Text("Вставить")}
   OutlinedButton(onClick=onScan){Icon(Icons.Default.QrCodeScanner,null)}
  }
  Text(notice,color=Pale,fontSize=11.sp); Spacer(Modifier.height(10.dp))
  LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
   items(profiles,key={it.id}){p->
    Card(onClick={onSelect(p.id)},colors=CardDefaults.cardColors(containerColor=if(p.id==selected)Color(0xFF164B70) else Panel),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){
     Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.weight(1f)){Text(p.name,color=Color.White,fontWeight=FontWeight.SemiBold);Text("${p.protocol} • ${p.server}",color=Pale,fontSize=11.sp)}
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
@Composable private fun SettingsScreen(count:Int,modifier:Modifier=Modifier){
 Column(modifier.fillMaxSize().padding(22.dp)){Header();Spacer(Modifier.height(24.dp));Text("Настройки",color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp))
  InfoCard("О ПРИЛОЖЕНИИ","LEVVER Utility\nВерсия 0.5.0 Alpha • Sky Blue Edition");Spacer(Modifier.height(12.dp))
  InfoCard("ЛОКАЛЬНЫЕ ДАННЫЕ","Сохранено профилей: $count");Spacer(Modifier.height(12.dp))
  InfoCard("БЕЗОПАСНОСТЬ","Ссылки и конфигурации могут содержать секреты доступа. Не отправляй их посторонним.")
  Spacer(Modifier.height(12.dp))
  InfoCard("ЛИЦЕНЗИИ","Перед выпуском с VPN-движком будут добавлены все требуемые лицензии и уведомления.")
 }
}
