package com.nuvio.tv.perfprobe;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Debug;
import android.os.Process;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Scratch-only actual full-guide Sports pipeline, with bounded JNI candidate filtering. */
public final class NativeSportsPipelineProbe {
    private static final long HOUR=3_600_000L, HALF=HOUR/2, DAY=24*HOUR;
    private static final long DAY_START=1_800_000_000_000L/DAY*DAY, NOW=DAY_START+12*HOUR;
    private static final String EXTRA_WORD="positive reload sentinel";
    private NativeSportsPipelineProbe() {}

    public static JSONObject run(Context context,ClassLoader loader,String variant,boolean profile,
            int logicalChannels,int listingsPerChannel,Consumer<String> status)throws Exception{
        if(!"com.nuvio.tv.perfprobe".equals(context.getPackageName()))throw new IllegalArgumentException("Isolated probe package only");
        if(profile)throw new IllegalArgumentException("This bounded parity/timing probe does not profile");
        if(!variant.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid label");
        int gids=logicalChannels<=0?300:logicalChannels,slots=listingsPerChannel<=0?336:listingsPerChannel;
        if(gids<4||gids>6000||slots<48||slots>1000||(long)gids*slots>2_000_000L)
            throw new IllegalArgumentException("Use 4..6000 channels,48..1000 listings/channel,at most2M rows");
        File directory=new File(context.getCacheDir(),"native-sports-golden-"+variant+"-"+System.nanoTime());
        if(!directory.mkdirs())throw new IOException("Cannot create validation directory");
        Binding b=new Binding(loader,directory);
        context.deleteDatabase("livetv_guide2.db");
        Object database=b.databaseConstructor.newInstance(context);
        try{
            SQLiteDatabase sql=(SQLiteDatabase)call(b.writable,database);
            status.accept("Seeding isolated actual Sports database outside clocks");
            Fixture f=new Fixture(b,gids,slots);seed(sql,f);
            String before=databaseSha(sql);long revision=((Number)call(b.revision,database)).longValue();
            boolean candidate=b.managedConstructor!=null;
            JSONArray samples=new JSONArray();JSONObject cases=new JSONObject();
            String[] names={"followed_full_guide","mixed_race_multiday","titles_only"};
            for(int ci=0;ci<names.length;ci++){
                List<Object> events=ci==1?f.mixedEvents:f.cacheableEvents;
                List<String> words=ci==2?Collections.emptyList():f.followed;
                Golden expected=null;
                for(int trial=0;trial<=3;trial++){
                    status.accept(names[ci]+" "+(trial==0?"warmup":"trial "+trial+"/3"));
                    // Alternate same-DEX mode order. Every timed operation has a fresh matcher.
                    boolean[] modes=candidate?(trial%2==0?new boolean[]{true,false}:new boolean[]{false,true}):new boolean[]{true};
                    for(boolean nativeOn:modes){
                        boolean initial=candidate&&ci==0&&trial==0&&nativeOn;
                        long startBatches=initial?0:b.batches();
                        Timed result=timed(()->read(b,b.matcher(database,nativeOn),f,events,words));
                        long endBatches=b.batches(),delta=endBatches-startBatches;
                        if(candidate){
                            if(!Boolean.TRUE.equals(call(b.nativeAvailable,null)))throw new IllegalStateException("Native library did not load");
                            if(nativeOn&&delta<=0)throw new IllegalStateException("Native ON silently used managed fallback");
                            if(!nativeOn&&delta!=0)throw new IllegalStateException("Native OFF executed JNI batches");
                        }
                        Golden actual=golden(b,result.value);
                        if(expected==null){expected=actual;cases.put(names[ci],expected.json());}
                        else equal(expected,actual,names[ci]+" trial"+trial+" native="+nativeOn);
                        if(ci==0)assertSemanticFixture(result.value);
                        samples.put(new JSONObject().put("case",names[ci]+"/"+(candidate?(nativeOn?"native_on":"native_off"):"control_full_guide"))
                            .put("phase",trial==0?"warmup":"steady").put("trial",trial).put("wallMs",result.wall)
                            .put("threadCpuMs",result.thread).put("processCpuMs",result.process).put("completedNativeBatches",delta)
                            .put("exactParity",true).put("golden",expected.json()).put("diagnosticSummary",result.value.summary));
                    }
                }
                // Exercise actual warm caches without including validation in timings.
                Object warm=b.matcher(database,candidate);Read first=read(b,warm,f,events,words);
                equal(expected,golden(b,first),names[ci]+" cache seed");long batches=b.batches();
                equal(expected,golden(b,read(b,warm,f,events,words)),names[ci]+" cache reuse");
                if(ci!=1&&b.batches()!=batches)throw new IllegalStateException("Warm cacheable result unexpectedly rescanned native text");
                deleteGoldenFiles(directory);
            }
            JSONObject nativeChecks=candidate?nativePreflight(loader):new JSONObject().put("applicable",false);
            String after=databaseSha(sql);
            if(!before.equals(after))throw new IllegalStateException("Read-only Sports operation changed guide metadata");
            if(((Number)call(b.revision,database)).longValue()!=revision)throw new IllegalStateException("Guide revision changed");
            return new JSONObject().put("label","Actual full-guide SportsMatcher: bounded native text versus identical managed pipeline")
                .put("variant",variant).put("candidate",candidate).put("allExactParityChecksPassed",true)
                .put("nativeExecutionRequired",candidate).put("nativePreflight",nativeChecks).put("profiled",false).put("wholeAppReload",false)
                .put("fixture",new JSONObject().put("logicalChannels",gids).put("eligibleChannels",f.channels.size())
                    .put("listingsPerChannel",slots).put("physicalListings",(long)gids*slots).put("aliasesPerGuideChannel",3)
                    .put("aliasShiftsMs",new JSONArray(Arrays.asList(0L,0L,HALF))).put("nowMs",NOW)
                    .put("firstListingMs",f.first).put("guideDaysFromMs",DAY_START-DAY).put("guideDaysToMs",DAY_START+2*DAY).put("orderedDatabaseSha256",before).put("revision",revision))
                .put("semanticChecks",new JSONObject().put("unicodeAccents",true).put("unicodeSmallMarks",true)
                    .put("wholeWordNegative",true).put("punctuation",true).put("oversizedMetadataFallback",true)
                    .put("descriptionOnlyFollowed",true).put("aliasAndAllModelMetadataIncluded",true).put("databaseUnchanged",true))
                .put("cases",cases).put("samples",samples).put("steadyMedians",medians(samples)).put("databaseAfter",databaseCounts(sql))
                .put("timingScope","Fresh actual matcher constructor, guideEventsDays and match including dictionary creation, packing/JNI, SQLite traversal, managed scoring and result allocation. No table/index preparation. Fixture, full tuple serialization, parity and reporting excluded. Warmup excluded from medians.")
                .put("parityScope","Complete typed UTF16 model backing fields, ordered lists, canonical map keys; all bytes and SHA256 compared within run. Diagnostic elapsed-time summary excluded. Cross-source baseline must match case hashes AND fixture hash externally.")
                .put("caveat","Native-enabled pipeline includes documented Unicode/oversized managed fallbacks. Completed batches prove C execution, not zero unexpected native failures. Synthetic isolated emulator; warm OS/SQLite fixture caches, not cold process/disk, network/provider, physical-TV or UI acceptance. Reflective actual method invocation is included identically; no app matching algorithm is copied.");
        }finally{
            try{call(b.close,database);}finally{deleteGoldenFiles(directory);if(!directory.delete()&&directory.exists())directory.deleteOnExit();}
        }
    }

    /** Small actual JNI boundary checks, after every timing sample and outside clocks. */
    private static JSONObject nativePreflight(ClassLoader loader)throws Exception{
        Class<?> bridge=Class.forName("com.nuvio.tv.livetv.sports.SportsTextNative",false,loader);
        Method create=bridge.getDeclaredMethod("nativeCreate",String[].class);
        Method destroy=bridge.getDeclaredMethod("nativeDestroy",long.class);
        Method batch=bridge.getDeclaredMethod("nativeBatch",long.class,char[].class,int.class,int[].class,int[].class,int[].class,int.class);
        create.setAccessible(true);destroy.setAccessible(true);batch.setAccessible(true);
        Class<?> cat=loader.loadClass("com.nuvio.tv.livetv.sports.SportsCatalog");
        Object catalog=cat.getField("INSTANCE").get(null);Method norm=cat.getMethod("norm",String.class);
        String[] titles={"only exact tokensuffix","prefixonly exact token","ONLY exact token!","alpha-beta","nothing"};
        String[] descriptions={"","","","Fish & Chips.","xx only exact token yy"};
        int[] offsets=new int[titles.length],tl=new int[titles.length],dl=new int[titles.length];StringBuilder packedText=new StringBuilder();
        for(int i=0;i<titles.length;i++){offsets[i]=packedText.length();tl[i]=titles[i].length();dl[i]=descriptions[i].length();packedText.append(titles[i]).append(descriptions[i]);}
        char[] packed=packedText.toString().toCharArray();
        long handle=((Number)call(create,null,(Object)new String[]{"only exact token","alpha beta","and"})).longValue();
        if(handle==0)throw new IllegalStateException("JNI preflight dictionary was not created");
        try{
            Object[] output=(Object[])call(batch,null,handle,packed,packed.length,offsets,tl,dl,titles.length);
            int[] expected={2,3,4};
            if(output==null||output.length!=3||!Arrays.equals(expected,(int[])output[0]))throw new IllegalStateException("Native whole-word selectivity mismatch");
            String[] nt=(String[])output[1],nd=(String[])output[2];
            if(nt.length!=expected.length||nd.length!=expected.length)throw new IllegalStateException("Native normalization shape mismatch");
            for(int i=0;i<expected.length;i++)if(!call(norm,catalog,titles[expected[i]]).equals(nt[i])||!call(norm,catalog,descriptions[expected[i]]).equals(nd[i]))
                throw new IllegalStateException("Native exact ASCII normalization mismatch");
            expectIllegal(()->call(batch,null,handle,packed,packed.length+1,offsets,tl,dl,titles.length),"used exceeds packed");
            expectIllegal(()->call(batch,null,handle,packed,packed.length,new int[]{0},new int[]{packed.length+1},new int[]{0},1),"row slice exceeds used");
            expectIllegal(()->call(batch,null,handle,new char[]{'é'},1,new int[]{0},new int[]{1},new int[]{0},1),"Unicode direct JNI input");
            expectIllegal(()->call(batch,null,handle,packed,packed.length,new int[257],new int[257],new int[257],257),"row budget");
        }finally{call(destroy,null,handle);}
        expectIllegal(()->call(batch,null,handle,packed,packed.length,offsets,tl,dl,titles.length),"closed handle");
        expectBadCreate(create,destroy,new String[]{"UPPER"},"non-normalized dictionary");
        expectBadCreate(create,destroy,new String[]{"same","same"},"duplicate dictionary");
        // Near the dictionary character cap, all possible suffixes of one long word.
        // Endpoint guard should reject these unbroken tokens without per-character
        // suffix-chain walks; this is a correctness/bounded-work check, not a speed claim.
        String[] suffixTerms=new String[721];for(int i=0;i<suffixTerms.length;i++)suffixTerms[i]=repeat('a',i+3);
        long suffix=((Number)call(create,null,(Object)suffixTerms)).longValue();
        if(suffix==0)throw new IllegalStateException("Suffix preflight dictionary was not created");
        try{
            char[] longText=new char[65_536];Arrays.fill(longText,'a');int[] starts=new int[8],lengths=new int[8];Arrays.fill(lengths,8192);
            for(int i=0;i<8;i++)starts[i]=8192*i;
            Object[] output=(Object[])call(batch,null,suffix,longText,longText.length,starts,lengths,new int[8],8);
            if(output==null||((int[])output[0]).length!=0)throw new IllegalStateException("Suffix-heavy whole-word negative mismatch");
        }finally{call(destroy,null,suffix);}
        return new JSONObject().put("applicable",true).put("allPassed",true).put("exactKeptRowsAndNormalization",true)
            .put("invalidCallsRejected",7).put("suffixTerms",721).put("suffixTermCharacters",261_723)
            .put("suffixRows",8).put("suffixRawCharacters",65_536).put("timed",false);
    }
    private static void expectBadCreate(Method create,Method destroy,String[] terms,String label)throws Exception{
        Object result;
        try{result=call(create,null,(Object)terms);}catch(IllegalArgumentException expected){return;}
        long unexpected=((Number)result).longValue();if(unexpected!=0)call(destroy,null,unexpected);
        throw new IllegalStateException("Actual JNI did not reject "+label);
    }
    private interface Check { Object run()throws Exception; }
    private static void expectIllegal(Check check,String label)throws Exception{
        try{check.run();}catch(IllegalArgumentException expected){return;}
        throw new IllegalStateException("Actual JNI did not reject "+label);
    }

    private static void assertSemanticFixture(Read read)throws Exception{
        List<String> titles=new ArrayList<>();
        for(Object list:((Map<?,?>)read.days).values())for(Object event:(List<?>)list)
            titles.add((String)call(event.getClass().getMethod("getTitle"),event));
        for(String required:Arrays.asList("Café & Derby","only exact token","alpha-beta","deep fallback sentinel","alpha beta","description-only sentinel"))
            if(titles.stream().noneMatch(t->t.contains(required)))throw new IllegalStateException("Semantic fixture missing: "+required);
        if(titles.stream().anyMatch(t->t.contains("prefixonly exact tokensuffix")))throw new IllegalStateException("Whole-word negative matched");
    }
    private static String repeat(char c,int count){char[] chars=new char[count];Arrays.fill(chars,c);return new String(chars);}
    private static String databaseSha(SQLiteDatabase sql)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(new java.io.OutputStream(){@Override public void write(int b){}@Override public void write(byte[]b,int off,int len){}},digest),64*1024))){
            for(String query:Arrays.asList("SELECT * FROM listings ORDER BY gid,start","SELECT * FROM chan ORDER BY ch","SELECT * FROM guide_ch ORDER BY gid")){
                text(out,query);try(Cursor c=sql.rawQuery(query,null)){
                    out.writeInt(c.getColumnCount());for(String name:c.getColumnNames())text(out,name);
                    while(c.moveToNext()){out.writeByte(127);for(int i=0;i<c.getColumnCount();i++){
                        int type=c.getType(i);out.writeByte(type);
                        if(type==Cursor.FIELD_TYPE_INTEGER)out.writeLong(c.getLong(i));
                        else if(type==Cursor.FIELD_TYPE_FLOAT)out.writeDouble(c.getDouble(i));
                        else if(type==Cursor.FIELD_TYPE_STRING)text(out,c.getString(i));
                        else if(type==Cursor.FIELD_TYPE_BLOB){byte[]v=c.getBlob(i);out.writeInt(v.length);out.write(v);}
                    }}
                }
            }
        }
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static Read read(Binding b, Object matcher, Fixture f, List<Object> events, List<String> words) throws Exception {
        Object days = call(b.days, matcher, f.days, f.leagues, words, f.byKey, 0L, NOW, false);
        Object input = b.inputConstructor.newInstance(events, f.channels, f.quality, 0L, f.settings, NOW, false, true, NOW + 3 * HOUR);
        Object matched = call(b.match, matcher, input, null);
        return new Read(days, call(b.matches, matched), (String)call(b.summary, matched));
    }
    private static final class Read { final Object days, matches; final String summary; Read(Object d, Object m, String s) { days=d; matches=m; summary=s; } }
    private interface Work { Read run() throws Exception; }
    private static final class Timed { final Read value; final double wall, thread; final long process;
        Timed(Read value,double wall,double thread,long process){this.value=value;this.wall=wall;this.thread=thread;this.process=process;} }
    private static Timed timed(Work work) throws Exception {
        long cpu=Debug.threadCpuTimeNanos(), process=Process.getElapsedCpuTime(), start=System.nanoTime();
        Read value=work.run();
        return new Timed(value,(System.nanoTime()-start)/1e6,(Debug.threadCpuTimeNanos()-cpu)/1e6,Process.getElapsedCpuTime()-process);
    }
    private static JSONObject medians(JSONArray samples) throws Exception {
        Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
        for(int i=0;i<samples.length();i++){JSONObject s=samples.getJSONObject(i);if(s.getInt("trial")>0)groups.computeIfAbsent(s.getString("case"),k->new ArrayList<>()).add(s);}
        JSONObject out=new JSONObject();
        for(Map.Entry<String,List<JSONObject>> entry:groups.entrySet()){
            JSONObject median=new JSONObject();
            for(String key:Arrays.asList("wallMs","threadCpuMs","processCpuMs")){
                List<Double> values=new ArrayList<>();for(JSONObject s:entry.getValue())values.add(s.getDouble(key));Collections.sort(values);median.put(key,values.get(values.size()/2));
            }
            median.put("trials",entry.getValue().size());out.put(entry.getKey(),median);
        }
        return out;
    }
    private static JSONObject databaseCounts(SQLiteDatabase sql) throws Exception {
        JSONObject out=new JSONObject();for(String table:Arrays.asList("listings","chan","sports_rows","sports_words")){
            try(Cursor c=sql.rawQuery("SELECT COUNT(*) FROM "+table,null)){c.moveToFirst();out.put(table,c.getLong(0));}
            catch(android.database.sqlite.SQLiteException absent){if(!table.startsWith("sports_"))throw absent;out.put(table,JSONObject.NULL);}
        }
        try(Cursor c=sql.rawQuery("PRAGMA page_count",null)){c.moveToFirst();out.put("pageCount",c.getLong(0));}
        try(Cursor c=sql.rawQuery("PRAGMA page_size",null)){c.moveToFirst();out.put("pageSize",c.getLong(0));}
        return out;
    }
    private static void seed(SQLiteDatabase sql,Fixture f){
        SQLiteStatement channel=sql.compileStatement("INSERT INTO chan(ch,gid,sh) VALUES(?,?,?)");
        SQLiteStatement guide=sql.compileStatement("INSERT INTO guide_ch(gid,src,xid,cnt,last_stop,state) VALUES(?,?,?,?,?,1)");
        SQLiteStatement row=sql.compileStatement("INSERT INTO listings(gid,start,stop,title,descr,cat,ep,icon,year,people,provider_id,provider_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");
        sql.beginTransaction();
        try{
            for(int gid=1;gid<=f.gids;gid++){
                guide.clearBindings();guide.bindLong(1,gid);guide.bindString(2,"sports-probe");guide.bindString(3,"gid-"+gid);guide.bindLong(4,f.slots);guide.bindLong(5,f.first+f.slots*HALF);guide.executeInsert();
                for(int alias=0;alias<3;alias++){channel.clearBindings();channel.bindString(1,gid+"-"+alias);channel.bindLong(2,gid);channel.bindLong(3,alias==2?HALF:0);channel.executeInsert();}
                for(int n=0;n<f.slots;n++){
                    long start=f.first+n*HALF;String title="Regional bulletin "+gid+" "+n;String description=f.description;String category="News";
                    if((gid*37L+n)%50==0){
                        int kind=(gid+n/50)%5;
                        title=new String[]{"Detroit Lions at Pittsburgh Steelers","Live Darts: PDC World Championship","Live Formula 1: Monaco Grand Prix","WWE Monday Night Raw","Live cycling with Pogačar"}[kind];
                        description="Live coverage from venue "+gid+". "+f.description;category="Sports";
                    }else if((gid+n)%20==0)description="São Paulo café weather. "+description;
                    if(gid==1&&n==f.zeroSlot){title="Detroit Lions at Pittsburgh Steelers";description="Live NFL football. "+f.description;category="Sports";}
                    if(gid==2&&n==Math.min(f.slots-1,f.zeroSlot+12)){title="Live Formula 1: Monaco Grand Prix";description="Live race coverage. "+f.description;category="Sports";}
                    if(gid==3&&n==Math.min(f.slots-1,f.zeroSlot+16)){title="Live Darts: PDC World Championship";description="Live PDC. "+f.description;category="Sports";}
                    if(gid==4&&n==Math.min(f.slots-1,f.zeroSlot+20)){title="WWE Monday Night Raw";description="Live wrestling. "+f.description;category="Sports";}
                    if(gid==1&&n==f.zeroSlot+1){title="Live "+EXTRA_WORD;description="Only the added followed word selects this programme. "+f.description;category="Special";}
                    // Deliberate semantic rows are outside fixture generation clocks.
                    if(gid==1 && n>=f.zeroSlot+2 && n<=f.zeroSlot+8){
                        switch(n-f.zeroSlot){
                            case 2:title="Live Café & Derby";description="Unicode accent field";category="Special";break;
                            case 3:title="Live prefixonly exact tokensuffix";description="Boundary negative";category="Special";break;
                            case 4:title="Live only exact token";description=null;category=null;break;
                            case 5:title="Live alpha-beta";description="";category="";break;
                            case 6:title="Live deep fallback sentinel";description=repeat('x',8300);category="Special";break;
                            case 7:title="Live ᴸᴵⱽᴱ alpha beta";description="Unicode compatibility normalization";category="Special";break;
                            case 8:title="Live description-only sentinel";description="Live deep fallback sentinel";category="Special";break;
                        }
                    }
                    row.clearBindings();row.bindLong(1,gid);row.bindLong(2,start);row.bindLong(3,start+HALF);row.bindString(4,title);if(description!=null)row.bindString(5,description);if(category!=null)row.bindString(6,category);
                    row.bindString(7,"S1 E"+(n+1));row.bindString(8,"https://fixture.invalid/show/"+n%20);row.bindLong(9,2020);row.bindString(10,"Actor\nDirector");row.bindString(11,"gid-"+gid+"-"+n);row.bindString(12,"sports-probe");row.executeInsert();
                }
            }
            sql.setTransactionSuccessful();
        }finally{sql.endTransaction();row.close();guide.close();channel.close();}
    }

    private static final class Fixture {
        final int gids,slots,zeroSlot;final long first;final String description;
        final List<Object> channels=new ArrayList<>(),cacheableEvents=new ArrayList<>(),mixedEvents=new ArrayList<>(),leagues=new ArrayList<>(),days=new ArrayList<>();
        final Map<String,Object> byKey=new LinkedHashMap<>();final Map<String,String> quality=new LinkedHashMap<>();
        final List<String> followed=Arrays.asList("Pogačar","obscure competitor","café & derby","only exact token","alpha beta","deep fallback sentinel");final Object settings;
        Fixture(Binding b,int gids,int slots)throws Exception{
            this.gids=gids;this.slots=slots;zeroSlot=Math.min(120,slots/3);first=NOW-zeroSlot*HALF;
            description="Local weather and community information; interviews, regional updates, arts and family programming. No named fixture in this bulletin. "+
                    "Local weather and community information continue throughout the day.";
            settings=b.settingsConstructor.newInstance();
            for(int gid=1;gid<=gids;gid++)for(int alias=0;alias<3;alias++){
                String key=gid+"-"+alias;Object channel=b.channelConstructor.newInstance(key,"sports-probe","Fixture", "Station "+gid+" "+alias,
                        null,null,null,"group-"+gid%20,gid%10==0?"Sports":"General",(gid-1)*3+alias+1,"https://fixture.invalid/"+key,
                        Collections.emptyMap(),null,null,0,false,null);
                channels.add(channel);byKey.put(key,channel);if(gid%25==0||gid==1)quality.put(key,alias==0?"FHD":alias==1?"HD":"SD");
            }
            for(int n=0;n<96;n++)cacheableEvents.add(event(b,"nfl-"+n,"football/nfl",NOW-4*HOUR+n*HALF,true,"Detroit Lions at Pittsburgh Steelers",null,null));
            mixedEvents.addAll(cacheableEvents);
            for(int n=0;n<8;n++)mixedEvents.add(event(b,"race-"+n,"racing/f1",NOW+(n-2)*6*HOUR,n%2==0,"Formula 1 Monaco Grand Prix Weekend",NOW+48*HOUR,null));
            for(String id:Arrays.asList("guide/darts","guide/wwe","racing/f1")){Object league=call(b.league,b.catalog,id);if(league==null)throw new IllegalStateException("Fixture league unavailable: "+id);leagues.add(league);}
            for(int n=-1;n<=1;n++)days.add(b.pairConstructor.newInstance(DAY_START+n*DAY,DAY_START+(n+1)*DAY));
        }
        private Object event(Binding b,String id,String league,long start,boolean timed,String title,Long end,Object sourceOverride)throws Exception{
            Object away=null,home=null;
            if(league.equals("football/nfl")){
                away=b.sideConstructor.newInstance(b.teamConstructor.newInstance(league,"detroit","Detroit Lions","Detroit Lions","DET",null,null,null,null,null),"0",null,false,false,Collections.emptyList());
                home=b.sideConstructor.newInstance(b.teamConstructor.newInstance(league,"pittsburgh","Pittsburgh Steelers","Pittsburgh Steelers","PIT",null,null,null,null,null),"0",null,false,true,Collections.emptyList());
            }
            return b.eventConstructor.newInstance(id,league,start,timed,start<=NOW?b.live:b.pre,title,null,null,away,home,
                    Collections.singletonList("ESPN"),null,sourceOverride==null?b.espn:sourceOverride,null,Collections.emptyList(),null,null,end,null,Collections.emptyList(),null,Collections.emptyList());
        }
    }

    private static final class Binding {
        final ClassLoader loader;final File goldenDirectory;final Object unit,checkpoint,catalog,live,pre,espn;
        final Constructor<?> databaseConstructor,matcherConstructor,managedConstructor,inputConstructor,settingsConstructor,channelConstructor,eventConstructor,teamConstructor,sideConstructor,programConstructor,pairConstructor;
        final Method writable,close,revision,days,match,matches,summary,league,nativeBatches,nativeAvailable;
        final Map<Class<?>,List<Method>> getters=new LinkedHashMap<>();
        Binding(ClassLoader loader,File goldenDirectory)throws Exception{
            this.loader=loader;this.goldenDirectory=goldenDirectory;Class<?> database=loader.loadClass("com.nuvio.tv.livetv.data.EpgDatabase"),matcher=loader.loadClass("com.nuvio.tv.livetv.sports.SportsMatcher"),input=loader.loadClass("com.nuvio.tv.livetv.sports.SportsMatcher$Input");
            Class<?> function=loader.loadClass("kotlin.jvm.functions.Function0");
            unit=loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            checkpoint=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,method,args)->{
                if(method.getName().equals("invoke")){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Probe interrupted");return unit;}
                if(method.getName().equals("toString"))return "SportsProbeCheckpoint";if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];return null;
            });
            databaseConstructor=database.getConstructor(Context.class);
            matcherConstructor=matcher.getDeclaredConstructor(database,File.class,boolean.class,function);matcherConstructor.setAccessible(true);
            Constructor<?> managed=null;Method batches=null,available=null;
            try{
                managed=matcher.getDeclaredConstructor(database,File.class,boolean.class,function,boolean.class);managed.setAccessible(true);
                Class<?> bridge=Class.forName("com.nuvio.tv.livetv.sports.SportsTextNative",false,loader);
                batches=bridge.getDeclaredMethod("completedBatches");batches.setAccessible(true);
                available=bridge.getDeclaredMethod("available");available.setAccessible(true);
            }catch(ClassNotFoundException|NoSuchMethodException absent){
                if(managed!=null)throw new IllegalStateException("Incomplete native candidate bridge",absent);
            }
            managedConstructor=managed;nativeBatches=batches;nativeAvailable=available;
            inputConstructor=constructor(input,9);settingsConstructor=loader.loadClass("com.nuvio.tv.livetv.sports.SportsSettings").getConstructor();
            channelConstructor=constructor(loader.loadClass("com.nuvio.tv.livetv.model.LiveChannel"),17);
            eventConstructor=constructor(loader.loadClass("com.nuvio.tv.livetv.sports.SportsEvent"),22);
            teamConstructor=constructor(loader.loadClass("com.nuvio.tv.livetv.sports.TeamInfo"),10);sideConstructor=constructor(loader.loadClass("com.nuvio.tv.livetv.sports.Side"),6);
            programConstructor=constructor(loader.loadClass("com.nuvio.tv.livetv.model.EpgProgram"),11);pairConstructor=loader.loadClass("kotlin.Pair").getConstructor(Object.class,Object.class);
            writable=database.getMethod("getWritableDatabase");close=database.getMethod("close");revision=database.getMethod("revision");
            days=matcher.getMethod("guideEventsDays",List.class,List.class,List.class,Map.class,long.class,long.class,boolean.class);
            match=matcher.getMethod("match",input,loader.loadClass("kotlin.jvm.functions.Function1"));
            Class<?> result=loader.loadClass("com.nuvio.tv.livetv.sports.SportsMatcher$Result");matches=result.getMethod("getMatches");summary=result.getMethod("getSummary");
            Class<?> cat=loader.loadClass("com.nuvio.tv.livetv.sports.SportsCatalog");catalog=cat.getField("INSTANCE").get(null);league=cat.getMethod("league",String.class);
            Class<?> state=loader.loadClass("com.nuvio.tv.livetv.sports.GameState"),source=loader.loadClass("com.nuvio.tv.livetv.sports.EventSource");live=enumValue(state,"LIVE");pre=enumValue(state,"PRE");espn=enumValue(source,"ESPN");
        }
        Object matcher(Object db,boolean nativeOn)throws Exception{
            return nativeOn||managedConstructor==null ? matcherConstructor.newInstance(db,null,false,checkpoint)
                : managedConstructor.newInstance(db,null,false,checkpoint,false);
        }
        long batches()throws Exception{return nativeBatches==null?0:((Number)call(nativeBatches,null)).longValue();}
        void writeValue(DataOutputStream out,Object value)throws Exception{
            if(value==null){out.writeByte(0);return;}
            if(value instanceof String||value instanceof Number||value instanceof Boolean){out.writeByte(1);text(out,value.getClass().getName());text(out,value.toString());return;}
            if(value instanceof Enum<?>){out.writeByte(2);text(out,value.getClass().getName());text(out,((Enum<?>)value).name());return;}
            if(value instanceof Map<?,?>){Map<String,Object> ordered=new TreeMap<>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())ordered.put(e.getKey().getClass().getName()+":"+e.getKey(),e.getValue());out.writeByte(3);out.writeInt(ordered.size());for(Map.Entry<String,Object> e:ordered.entrySet()){text(out,e.getKey());writeValue(out,e.getValue());}return;}
            if(value instanceof List<?>){out.writeByte(4);out.writeInt(((List<?>)value).size());for(Object item:(List<?>)value)writeValue(out,item);return;}
            Class<?> type=value.getClass();
            if(type.getName().equals("kotlin.Pair")){out.writeByte(5);writeValue(out,call(type.getMethod("getFirst"),value));writeValue(out,call(type.getMethod("getSecond"),value));return;}
            if(!type.getName().startsWith("com.nuvio.tv.livetv."))throw new IllegalStateException("Unrecognized golden model "+type);
            List<Method> access=getters.get(type);
            if(access==null){access=new ArrayList<>();List<Field> fields=new ArrayList<>(Arrays.asList(type.getDeclaredFields()));fields.sort(Comparator.comparing(Field::getName));for(Field f:fields){if(Modifier.isStatic(f.getModifiers())||f.isSynthetic())continue;String name=f.getName();access.add(type.getMethod("get"+Character.toUpperCase(name.charAt(0))+name.substring(1)));}getters.put(type,access);}
            out.writeByte(6);text(out,type.getName());out.writeInt(access.size());for(Method method:access){text(out,method.getName());writeValue(out,call(method,value));}
        }
    }
    private static Constructor<?> constructor(Class<?> type,int arity){for(Constructor<?> c:type.getDeclaredConstructors())if(c.getParameterCount()==arity){c.setAccessible(true);return c;}throw new IllegalStateException("Missing actual constructor "+type.getName()+"/"+arity);}
    private static Object enumValue(Class<?> type,String name){for(Object e:type.getEnumConstants())if(((Enum<?>)e).name().equals(name))return e;throw new IllegalStateException("Missingenum "+type+"/"+name);}
    private static Object call(Method method,Object target,Object...args)throws Exception{try{return method.invoke(target,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw failure;}}
    private static final class Golden{
        final File file;final String sha;final long events,matches;
        Golden(File file,String sha,long events,long matches){this.file=file;this.sha=sha;this.events=events;this.matches=matches;}
        JSONObject json()throws Exception{return new JSONObject().put("orderedTupleSha256",sha).put("encodedBytes",file.length()).put("guideEvents",events).put("channelMatches",matches);}
    }
    private static Golden golden(Binding b,Read read)throws Exception{
        File file=File.createTempFile("golden-",".bin",b.goldenDirectory);MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(new FileOutputStream(file),digest),64*1024))){b.writeValue(out,read.days);b.writeValue(out,read.matches);}
        long events=0,matches=0;for(Object list:((Map<?,?>)read.days).values())events+=((List<?>)list).size();for(Object list:((Map<?,?>)read.matches).values())matches+=((List<?>)list).size();
        StringBuilder hex=new StringBuilder();for(byte item:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return new Golden(file,hex.toString(),events,matches);
    }
    private static void equal(Golden expected,Golden actual,String phase)throws IOException{
        try{if(!same(expected,actual))throw new IllegalStateException("Exact ordered Sports parity failed: "+phase+" ("+expected.sha+" vs "+actual.sha+")");}
        finally{if(!actual.file.equals(expected.file)&&!actual.file.delete()&&actual.file.exists())actual.file.deleteOnExit();}
    }
    private static boolean same(Golden a,Golden b)throws IOException{
        if(a.file.length()!=b.file.length()||!a.sha.equals(b.sha))return false;
        try(DataInputStream left=new DataInputStream(new BufferedInputStream(new FileInputStream(a.file),64*1024));DataInputStream right=new DataInputStream(new BufferedInputStream(new FileInputStream(b.file),64*1024))){
            // Hash equality is only a quick filter; compare the complete encoded values too.
            byte[] x=new byte[64*1024],y=new byte[64*1024];long remaining=a.file.length();
            while(remaining>0){int length=(int)Math.min(x.length,remaining);left.readFully(x,0,length);right.readFully(y,0,length);for(int i=0;i<length;i++)if(x[i]!=y[i])return false;remaining-=length;}
            return left.read()==-1&&right.read()==-1;
        }
    }
    private static void deleteGoldenFiles(File directory){File[] files=directory.listFiles();if(files!=null)for(File file:files)if(!file.delete()&&file.exists())file.deleteOnExit();}
    private static void text(DataOutputStream out,String value)throws IOException{
        // Preserve exact JVM UTF-16 code units, including any isolated surrogate.
        out.writeInt(value.length());for(int i=0;i<value.length();i++)out.writeChar(value.charAt(i));
    }
}
