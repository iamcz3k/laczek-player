package com.v3x.laczekplayer

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.database.Cursor

data class Track(val title:String,val artist:String,val uri:Uri,val duration:Long)

class MainActivity:Activity(){
 private val tracks= mutableListOf<Track>()
 private var player:MediaPlayer?=null
 private var index=-1
 private lateinit var list:LinearLayout
 private lateinit var status:TextView
 private lateinit var current:TextView
 private lateinit var play:Button
 private lateinit var seek:SeekBar
 private val orange=Color.rgb(255,138,61)
 private val bg=Color.rgb(16,17,21)
 private val fg=Color.rgb(245,245,248)
 private val muted=Color.rgb(170,173,182)
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=bg;window.navigationBarColor=bg;buildUi();if(permitted())scan() else requestPermissions(arrayOf(permission()),100)}
 private fun permission()=if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
 private fun permitted()=checkSelfPermission(permission())==PackageManager.PERMISSION_GRANTED
 private fun buildUi(){
  val root=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(18),dp(18),dp(8));setBackgroundColor(bg)}
  root.addView(TextView(this).apply{text="LaCzek Player";textSize=29f;setTextColor(orange);setTypeface(null,1)})
  root.addView(TextView(this).apply{text="YOUR MUSIC. YOUR PHONE. OFFLINE.";textSize=11f;setTextColor(muted);setPadding(0,dp(5),0,dp(12))})
  status=TextView(this).apply{text="Scanning local music…";setTextColor(muted);textSize=14f}
  root.addView(status)
  val scroll=ScrollView(this);list=LinearLayout(this).apply{orientation=1};scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  current=TextView(this).apply{text="Choose a song";textSize=16f;setTextColor(fg);setPadding(0,dp(10),0,0)};root.addView(current)
  seek=SeekBar(this).apply{max=1000;progressTintList=android.content.res.ColorStateList.valueOf(orange);thumbTintList=android.content.res.ColorStateList.valueOf(orange);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){if(user)try{player?.seekTo(((player?.duration?:0)*v/1000))}catch(_:Exception){}}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){}
  })};root.addView(seek)
  val controls=LinearLayout(this).apply{gravity=Gravity.CENTER}
  fun button(label:String,action:()->Unit)=Button(this).apply{text=label;setTextColor(fg);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(37,39,47));setOnClickListener{action()}}
  controls.addView(button("Previous"){if(tracks.isNotEmpty())playAt((index-1+tracks.size)%tracks.size)})
  play=button("Play"){toggle()};controls.addView(play)
  controls.addView(button("Next"){if(tracks.isNotEmpty())playAt((index+1)%tracks.size)})
  root.addView(controls);setContentView(root)
 }
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==100&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED)scan() else status.text="Music access is needed to read local files."}
 private fun scan(){
  tracks.clear()
  val collection=if(Build.VERSION.SDK_INT>=29)MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
  val cols=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.DURATION)
  try{
   val c:Cursor?=contentResolver.query(collection,cols,"${MediaStore.Audio.Media.IS_MUSIC} != 0",null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")
   c?.use{while(it.moveToNext()){val id=it.getLong(0);tracks.add(Track(it.getString(1)?: "Unknown title",it.getString(2)?: "Unknown artist",ContentUris.withAppendedId(collection,id),it.getLong(3)))}}
  }catch(_:Exception){status.text="Couldn't read music storage."}
  status.text=if(tracks.isEmpty())"No local music found. Add audio files and reopen." else "${tracks.size} local songs"
  list.removeAllViews()
  tracks.forEachIndexed{i,t->
   val row=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener{playAt(i)}}
   row.addView(TextView(this).apply{text=t.title;textSize=16f;setTextColor(fg);maxLines=1})
   row.addView(TextView(this).apply{text="${t.artist}  ·  ${t.duration/60000}:${"%02d".format((t.duration/1000)%60)}";textSize=12f;setTextColor(muted);setPadding(0,dp(3),0,0)})
   list.addView(row);list.addView(android.view.View(this).apply{setBackgroundColor(Color.rgb(38,39,45))},LinearLayout.LayoutParams(-1,dp(1)))
  }
 }
 private fun playAt(i:Int){
  if(tracks.isEmpty())return
  index=i.coerceIn(0,tracks.lastIndex)
  try{
   player?.release()
   player=MediaPlayer().apply{
    setDataSource(this@MainActivity,tracks[index].uri)
    setOnPreparedListener{it.start();play.text="Pause";current.text=tracks[index].title+"\n"+tracks[index].artist}
    setOnCompletionListener{if(tracks.isNotEmpty())playAt((index+1)%tracks.size)}
    setOnErrorListener{_,_,_->Toast.makeText(this@MainActivity,"Unable to play this file",Toast.LENGTH_SHORT).show();true}
    prepareAsync()
   }
  }catch(_:Exception){Toast.makeText(this,"Unable to play this file",Toast.LENGTH_SHORT).show()}
 }
 private fun toggle(){val p=player;if(p==null){if(tracks.isNotEmpty())playAt(0)}else try{if(p.isPlaying){p.pause();play.text="Play"}else{p.start();play.text="Pause"}}catch(_:Exception){}}
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 override fun onDestroy(){player?.release();player=null;super.onDestroy()}
}