package com.gabrielpc.enginesoundsinstaller;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.JsonReader;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.*;
import java.util.*;

/** Installs the banks embedded in this flavor into the dashboard's private bank store. */
public final class AudioInstallerActivity extends Activity {
    private static final String ORIGINAL_GROUP="original_cars_pack", MODDED_GROUP="modded_car_packs";
    private static final Uri STORE=Uri.parse("content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs");
    private final Handler main=new Handler(Looper.getMainLooper()); private TextView status; private ProgressBar progress; private Button install,delete; private List<Pack> packs=List.of();
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(content());try{packs=readIndex();idle();}catch(Exception e){status.setText("This installer has no embedded bank payload.");install.setEnabled(false);}}
    private View content(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(48,36,48,36);root.setGravity(Gravity.CENTER_VERTICAL);root.setBackgroundColor(0xff05080a);root.addView(text("ENGINE FMOD BANK INSTALLER",28,0xff00d7e8));root.addView(text((MODDED_GROUP.equals(BuildConfig.PAYLOAD_GROUP)?"MODDED CARS":"ORIGINAL ASSETTO CORSA CARS")+" · EMBEDDED PAYLOAD",17,0xffd5e2e8));status=text("Preparing…",18,0xffffffff);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=28;root.addView(status,sp);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);root.addView(progress,new LinearLayout.LayoutParams(-1,18));LinearLayout actions=new LinearLayout(this);actions.setPadding(0,28,0,0);install=action("INSTALL "+(MODDED_GROUP.equals(BuildConfig.PAYLOAD_GROUP)?"MODDED":"ORIGINAL")+" CARS",()->install(BuildConfig.PAYLOAD_GROUP));actions.addView(install);delete=action("DELETE ALL",this::deleteAll);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-2,-2);dp.leftMargin=20;actions.addView(delete,dp);root.addView(actions);return root;}
    private Button action(String label,Runnable run){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->run.run());return b;}
    private void idle(){Set<String> done=installed();long total=packs.stream().filter(p->BuildConfig.PAYLOAD_GROUP.equals(p.group)&&!p.dep).count(),count=packs.stream().filter(p->BuildConfig.PAYLOAD_GROUP.equals(p.group)&&!p.dep&&done.contains(p.group+"/"+p.id)).count();status.setText((MODDED_GROUP.equals(BuildConfig.PAYLOAD_GROUP)?"Modded":"Original")+" installer · "+count+"/"+total+" cars installed");}
    private void install(String group){busy(true);new Thread(()->{List<Pack> selected=packs.stream().filter(p->group.equals(p.group)||p.dep).toList();long total=selected.stream().mapToLong(p->p.bytes).sum(),copied=0;try{for(Pack p:selected){post("Installing "+p.name,copied,total);copied+=copy(p,total,copied);waitFor(p.group,p.id);}main.post(()->{status.setText("Import completed successfully.");progress.setProgress(1000);idle();busy(false);});}catch(Exception e){main.post(()->{status.setText("Installation failed: "+e.getMessage());busy(false);});}},"install-embedded-fmod-banks").start();}
    private long copy(Pack p,long total,long completed)throws IOException{Uri dest=Uri.parse(STORE+"/"+p.group+"/"+p.id);ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(dest,"w");if(fd==null)throw new IOException("Engine Sounds Simulator is not installed");long n=0;try(InputStream in=getAssets().open("packs/"+p.asset);FileOutputStream out=new FileOutputStream(fd.getFileDescriptor())){byte[] b=new byte[262144];int r;while((r=in.read(b))>=0){out.write(b,0,r);n+=r;post("Installing "+p.name,completed+n,total);}}finally{fd.close();}return n;}
    private void waitFor(String group,String id)throws IOException{for(int i=0;i<100;i++){if(installed().contains(group+"/"+id))return;try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Installation interrupted",e);}}throw new IOException("Dashboard did not publish "+id);}
    private void deleteAll(){busy(true);new Thread(()->{try{getContentResolver().delete(STORE,null,null);main.post(()->{status.setText("All installed FMOD banks were deleted.");progress.setProgress(0);busy(false);});}catch(Exception e){main.post(()->{status.setText("Delete failed: "+e.getMessage());busy(false);});}},"delete-fmod-banks").start();}
    private Set<String> installed(){Set<String> ids=new HashSet<>();try(Cursor c=getContentResolver().query(STORE,null,null,null,null)){if(c==null)return ids;int g=c.getColumnIndex("group"),i=c.getColumnIndex("id");while(c.moveToNext()&&g>=0&&i>=0)ids.add(c.getString(g)+"/"+c.getString(i));}return ids;}
    private void post(String msg,long n,long total){main.post(()->{status.setText(msg+" — "+n/(1024*1024)+" / "+total/(1024*1024)+" MB");progress.setProgress(total==0?0:(int)Math.min(1000,n*1000/total));});}
    private void busy(boolean b){install.setEnabled(!b);delete.setEnabled(!b);} private TextView text(String v,int s,int c){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);return t;}
    private List<Pack> readIndex()throws IOException{List<Pack> out=new ArrayList<>();try(JsonReader r=new JsonReader(new InputStreamReader(getAssets().open("packs/index.json")))){r.beginObject();while(r.hasNext()){if("packs".equals(r.nextName())){r.beginArray();while(r.hasNext()){Pack p=readPack(r);if(p.active&&(BuildConfig.PAYLOAD_GROUP.equals(p.group)||p.dep))out.add(p);}r.endArray();}else r.skipValue();}r.endObject();}if(out.isEmpty())throw new IOException("Pack index is empty");return out;}
    private Pack readPack(JsonReader r)throws IOException{String id=null,name=null,asset=null,group=null;long bytes=0;boolean active=false,dep=false;r.beginObject();while(r.hasNext()){switch(r.nextName()){case"id":id=r.nextString();break;case"name":name=r.nextString();break;case"asset":asset=r.nextString();break;case"group":group=r.nextString();break;case"bytes":bytes=r.nextLong();break;case"active":active=r.nextBoolean();break;case"dependency":dep=r.nextBoolean();break;default:r.skipValue();}}r.endObject();if(id==null||name==null||asset==null||group==null||bytes<=0)throw new IOException("Invalid pack index entry");return new Pack(id,name,asset,bytes,group,active,dep);}
    private static final class Pack{final String id,name,asset,group;final long bytes;final boolean active,dep;Pack(String i,String n,String a,long b,String g,boolean ac,boolean d){id=i;name=n;asset=a;bytes=b;group=g;active=ac;dep=d;}}
}
