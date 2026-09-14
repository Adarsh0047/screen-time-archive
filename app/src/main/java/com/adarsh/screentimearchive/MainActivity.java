package com.adarsh.screentimearchive;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<LocalDate, HistoryDb.DayEntry> days = new TreeMap<>();
    private UsageRepository.ScanResult result;
    private LinearLayout root, body;
    private String page = "Overview", range = "Month";
    private LocalDate anchor = LocalDate.now();
    private boolean busy;
    private int bg, surface, tonal, ink, muted, accent;
    private SharedPreferences prefs;
    private String status = "Loading saved archive…";
    private long start;
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String date(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("d MMM yyyy")); }
    private String duration(long v) { return UsageRepository.formatDuration(v); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("screen_time_settings", MODE_PRIVATE);
        start = prefs.getLong("purchase_date", UsageRepository.atStartOfDay(LocalDate.of(2024,1,1)));
        if (state != null) { page = state.getString("page", page); range = state.getString("range", range); anchor = LocalDate.parse(state.getString("anchor", anchor.toString())); }
        render();
        ArchiveScheduler.schedule(this);
    }
    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b); b.putString("page",page); b.putString("range",range); b.putString("anchor",anchor.toString());
    }
    @Override public void onResume() { super.onResume(); refresh(); }
    private void refresh() {
        if (busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                UsageRepository.ScanResult updated = UsagePermission.isGranted(this) ? new UsageRepository(this).scan(start) : null;
                List<HistoryDb.DayEntry> saved;
                try (HistoryDb db = new HistoryDb(this)) { saved = db.getDaysSince(0); }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    result = updated; days.clear();
                    for (HistoryDb.DayEntry d : saved) days.put(Instant.ofEpochMilli(d.dayStart).atZone(ZoneId.systemDefault()).toLocalDate(),d);
                    busy = false; status = updated == null ? "Usage access needed for automatic collection" : "Updated " + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                    DaytraceWidget.requestUpdate(this);
                    render();
                });
            } catch (Exception e) { ArchiveHealth.recordFailure(this, e); runOnUiThread(() -> { busy=false; status="Update unavailable. Saved records are kept."; if (!isDestroyed()) render(); }); }
        });
    }
    private void palette() {
        String mode=prefs.getString("theme","Dark");
        boolean dark=mode.equals("Dark") || (mode.equals("System") && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        bg=Color.parseColor(dark?"#07171C":"#EAFBFF"); surface=Color.parseColor(dark?"#102B33":"#F8FEFF");
        tonal=Color.parseColor(dark?"#163F49":"#C8F2FA");
        ink=Color.parseColor(dark?"#E5FAFF":"#083440"); muted=Color.parseColor(dark?"#9EC4CE":"#426A75"); accent=Color.parseColor(dark?"#40D9F4":"#007F99");
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }
    private void render() {
        palette();
        root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(bg);
        // Consume system insets explicitly, including Android 15 edge-to-edge.
        root.setOnApplyWindowInsetsListener((v,i)->{ v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom()); return i.consumeSystemWindowInsets(); });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(20),dp(14),dp(16),dp(8));
        TextView title=text("Daytrace",24,true); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button("Settings",()->{page="Settings";render();}); header.addView(settings); root.addView(header);
        addLeadingIcon(settings,R.drawable.ic_settings,accent);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=new LinearLayout(this); body.setOrientation(1); body.setPadding(dp(20),dp(12),dp(20),dp(28)); scroll.addView(body);
        label(body,pageTitle(page),30,true);
        if (!UsagePermission.isGranted(this)) { LinearLayout c=card(); label(c,"Enable automatic archiving",19,true); label(c,"Grant Usage Access once. No manual scan is required.",14,false); c.addView(button("Grant Usage Access",()->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))); }
        switch(page) { case "History": history();break; case "Insights": insights();break; case "Settings": settings();break; case "Data": data();break; default: overview(); }
        LinearLayout nav=new LinearLayout(this); nav.setBackgroundColor(surface); nav.setPadding(dp(6),dp(3),dp(6),dp(3));
        String[] pages={"Overview","History","Insights","Data"};
        String[] labels={"Today","History","Insights","Archive"};
        int[] icons={R.drawable.ic_today,R.drawable.ic_history,R.drawable.ic_insights,R.drawable.ic_archive};
        for(int i=0;i<pages.length;i++) {
            final String destination=pages[i];
            Button b=button(labels[i],()->{page=destination;render();});
            int color=page.equals(destination)?accent:muted;
            b.setTextSize(11); b.setTextColor(color); b.setMinWidth(0); b.setPadding(0,dp(4),0,dp(3));
            b.setGravity(Gravity.CENTER); b.setBackgroundColor(Color.TRANSPARENT);
            Drawable icon=icon(icons[i],color); b.setCompoundDrawables(null,icon,null,null); b.setCompoundDrawablePadding(dp(2));
            nav.addView(b,new LinearLayout.LayoutParams(0,dp(64),1));
        }
        root.addView(nav);
    }
    private TextView text(String s,int size,boolean bold) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(bold?ink:muted);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }
    private void label(LinearLayout p,String s,int size,boolean bold) { TextView t=text(s,size,bold); t.setPadding(0,dp(4),0,dp(6)); p.addView(t); }
    private Button button(String s,Runnable action) {
        Button b=new Button(this); b.setText(s); b.setTextSize(14); b.setTextColor(accent); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setBackground(shape(tonal)); b.setPadding(dp(16),0,dp(16),0); b.setOnClickListener(v->action.run()); return b;
    }
    private String pageTitle(String value) { return value.equals("Overview")?"Today":value.equals("Data")?"Archive":value; }
    private Drawable icon(int resource,int color) { Drawable d=getDrawable(resource).mutate(); d.setTint(color); d.setBounds(0,0,dp(22),dp(22)); return d; }
    private void addLeadingIcon(Button button,int resource,int color) { button.setCompoundDrawables(icon(resource,color),null,null,null); button.setCompoundDrawablePadding(dp(7)); }
    private GradientDrawable shape(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(24)); return d; }
    private Button chip(String title,boolean selected,Runnable action) {
        Button b=button(title,action); b.setMinHeight(dp(42)); b.setTextColor(selected?bg:ink);
        b.setBackground(shape(selected?accent:tonal));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(42)); lp.setMargins(dp(3),dp(2),dp(3),dp(2)); b.setLayoutParams(lp); return b;
    }
    private LinearLayout card() {
        LinearLayout c=new LinearLayout(this); c.setOrientation(1); c.setPadding(dp(16),dp(14),dp(16),dp(14)); c.setBackground(shape(surface));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(14); body.addView(c,lp); return c;
    }
    private long value(LocalDate d) { return days.containsKey(d)?days.get(d).durationMs:0; }
    private boolean complete(LocalDate a,LocalDate b) { for(LocalDate d=a;d.isBefore(b);d=d.plusDays(1))if(!days.containsKey(d))return false;return true; }
    private long sum(LocalDate a,LocalDate b) { long n=0;for(Map.Entry<LocalDate,HistoryDb.DayEntry> e:days.entrySet())if(!e.getKey().isBefore(a)&&e.getKey().isBefore(b))n+=e.getValue().durationMs;return n; }
    private String trend() {
        LocalDate t=LocalDate.now();
        if(!complete(t.minusDays(14),t))return "Need 14 recorded, completed days for a fair comparison";
        long now=sum(t.minusDays(7),t),old=sum(t.minusDays(14),t.minusDays(7));
        if(old==0)return "Previous week has no recorded usage";
        return Math.round(Math.abs(now-old)*100.0/old)+"% "+(now<old?"lower":now>old?"higher":"change")+" vs previous week";
    }
    private void overview() {
        LocalDate t=LocalDate.now(); LinearLayout c=card(); label(c,"TODAY · PARTIAL DAY",12,false);
        label(c,days.containsKey(t)?duration(value(t)):"Not recorded",38,true); label(c,status,13,false);
        c=card();label(c,"Last 7 completed days",18,true);
        label(c,complete(t.minusDays(7),t)?duration(sum(t.minusDays(7),t)/7)+" / day":"Incomplete week",25,true);
        label(c,trend(),14,false); bars(c,t.minusDays(7),t);
        c=card();label(c,"Archive status",18,true);label(c,days.size()+" daily records · stored privately on this phone",14,false);
        label(c,"Historical summaries are estimates. Missing days are not zero-use days.",14,false);
        c.addView(button("Explore history →",()->{page="History";render();}));
    }
    private void choose(java.util.function.Consumer<LocalDate> cb) {
        DatePickerDialog d=new DatePickerDialog(this,(v,y,m,day)->cb.accept(LocalDate.of(y,m+1,day)),anchor.getYear(),anchor.getMonthValue()-1,anchor.getDayOfMonth());
        d.getDatePicker().setMaxDate(System.currentTimeMillis());d.show();
    }
    private void history() {
        HorizontalScrollView filters=new HorizontalScrollView(this);filters.setHorizontalScrollBarEnabled(false);LinearLayout choices=new LinearLayout(this);
        for(String r:new String[]{"Day","Week","Month","Year","All time"}) choices.addView(chip(r,r.equals(range),()->{range=r;render();}));
        filters.addView(choices);body.addView(filters);
        LinearLayout controls=new LinearLayout(this);
        controls.addView(button("‹",()->move(-1)));
        Button selected=button(date(anchor),()->choose(d->{anchor=d;render();}));controls.addView(selected,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("›",()->move(1)));body.addView(controls);
        if(range.equals("Year")||range.equals("All time")) {
            LinearLayout c=card();label(c,"Estimated history",20,true);
            label(c,"Android summaries can be incomplete and cannot be converted into daily history.",14,false);
            if(result==null)label(c,"Grant Usage Access to load retained summaries.",14,false);
            else for(UsageRepository.PeriodUsage p:result.years)if(range.equals("All time")||p.label.equals(""+anchor.getYear()))label(c,p.label+" · "+duration(p.durationMs)+" · estimate",17,true);
            c=card();label(c,"Recorded months",20,true);
            int year=anchor.getYear();
            for(int m=1;m<=12;m++){LocalDate a=LocalDate.of(year,m,1);long n=sum(a,a.plusMonths(1));label(c,a.getMonth().toString()+" · "+(hasRecords(a,a.plusMonths(1))?duration(n)+" recorded":"No daily records"),14,false);}
            heatmap();return;
        }
        LocalDate a=range.equals("Day")?anchor:range.equals("Week")?anchor.minusDays(anchor.getDayOfWeek().getValue()-1):anchor.withDayOfMonth(1);
        LocalDate b=range.equals("Day")?a.plusDays(1):range.equals("Week")?a.plusWeeks(1):a.plusMonths(1);
        LinearLayout c=card(); label(c,date(a)+" — "+date(b.minusDays(1)),16,true);
        label(c,hasRecords(a,b)?duration(sum(a,b))+" recorded":"No daily records",28,true);label(c,"Only saved daily values are included; today is partial. Tap a day for app usage.",13,false);bars(c,a,b);
        if(range.equals("Day"))c.addView(button("App usage for this day →",()->detail(anchor)));
        heatmap();
    }
    private void move(int n) { LocalDate next=range.equals("Day")?anchor.plusDays(n):range.equals("Week")?anchor.plusWeeks(n):range.equals("Month")?anchor.plusMonths(n):anchor.plusYears(n);if(!next.isAfter(LocalDate.now()))anchor=next;render(); }
    private void bars(LinearLayout c,LocalDate a,LocalDate b) {
        long max=1;for(LocalDate d=a;d.isBefore(b);d=d.plusDays(1))max=Math.max(max,value(d));
        for(LocalDate d=a;d.isBefore(b)&&!d.isAfter(LocalDate.now());d=d.plusDays(1)) {
            final LocalDate selected=d;LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(6),0,dp(6));
            TextView name=text(d.format(DateTimeFormatter.ofPattern("d MMM")),12,false);row.addView(name,new LinearLayout.LayoutParams(dp(52),-2));
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)(value(d)*1000/max));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));row.addView(bar,new LinearLayout.LayoutParams(0,dp(16),1));
            TextView amount=text(days.containsKey(d)?duration(value(d)):"No data",12,false);amount.setPadding(dp(8),0,0,0);row.addView(amount);row.setOnClickListener(v->detail(selected));c.addView(row);
        }
    }
    private void heatmap() {
        LinearLayout c=card();LocalDate first=anchor.withDayOfMonth(1);
        label(c,first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))+" · calendar",20,true);
        label(c,"Tap a day for details. — = no record; darker = more usage.",13,false);
        LinearLayout names=new LinearLayout(this);for(String s:new String[]{"M","T","W","T","F","S","S"}) { TextView t=text(s,12,false);t.setGravity(Gravity.CENTER);names.addView(t,new LinearLayout.LayoutParams(0,dp(28),1)); }c.addView(names);
        int offset=first.getDayOfWeek().getValue()-1,total=first.lengthOfMonth();
        for(int w=0;w<(offset+total+6)/7;w++) {
            LinearLayout row=new LinearLayout(this);
            for(int k=0;k<7;k++) {
                int day=w*7+k-offset+1;TextView cell=text("",13,true);cell.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(cell,lp);
                if(day>0&&day<=total) {
                    LocalDate d=first.withDayOfMonth(day);boolean exists=days.containsKey(d);
                    cell.setText(day+(exists?"":"\n—")); cell.setTextColor(exists?Color.WHITE:muted);
                    if(exists){int level=(int)Math.min(120,value(d)*120/(12L*3600000));cell.setBackground(shape(Color.rgb(0,150-level/2,180-level/2)));}
                    else cell.setBackground(shape(bg));
                    cell.setContentDescription(date(d)+": "+(exists?duration(value(d)):"no data"));cell.setOnClickListener(v->detail(d));
                }
            }c.addView(row);
        }
    }
    private void detail(LocalDate d) {
        HistoryDb.DayEntry entry=days.get(d);
        worker.execute(()->{
            StringBuilder info=new StringBuilder(entry==null?"No daily total recorded.":duration(entry.durationMs)+" total · "+entry.source);
            try(HistoryDb db=new HistoryDb(this)){
                Map<String,Long> apps=db.getApps(UsageRepository.atStartOfDay(d));
                info.append("\n\nAPP USAGE\n");
                if(apps.isEmpty())info.append("No per-app history saved for this day. Yearly totals and total-only CSV files cannot reconstruct it.");
                for(Map.Entry<String,Long> app:apps.entrySet()){
                    String name=app.getKey();
                    try{name=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(name,0)).toString();}catch(Exception ignored){}
                    info.append("\n").append(name).append(" · ").append(duration(app.getValue()));
                }
                info.append("\n\nEvent-derived foreground usage. Simultaneous apps can overlap; their sum may exceed the daily total.");
            }catch(Exception e){info.append("\nApp history could not be loaded.");}
            runOnUiThread(()->{if(isDestroyed())return;ScrollView scroll=new ScrollView(this);TextView content=text(info.toString(),16,false);content.setPadding(dp(20),dp(16),dp(20),dp(16));scroll.addView(content);scroll.setBackgroundColor(surface);new AlertDialog.Builder(this).setTitle(date(d)).setView(scroll).setPositiveButton("Close",null).show();});
        });
    }
    private boolean hasRecords(LocalDate a,LocalDate b) {
        for(LocalDate d:days.keySet())if(!d.isBefore(a)&&d.isBefore(b))return true;
        return false;
    }
    private void insights() {
        LinearLayout c=card();label(c,"Weekly change",19,true);label(c,trend(),18,false);
        LocalDate best=null;for(LocalDate d:days.keySet())if(d.isBefore(LocalDate.now())&&(best==null||value(d)>value(best)))best=d;
        c=card();label(c,"Highest recorded completed day",19,true);label(c,best==null?"Not enough data":date(best)+" · "+duration(value(best)),20,false);
        int streak=0,target=prefs.getInt("target",360);LocalDate d=LocalDate.now().minusDays(1);
        while(days.containsKey(d)&&value(d)<=target*60000L){streak++;d=d.minusDays(1);}
        c=card();label(c,"Consecutive days within target",19,true);label(c,streak+" days · target "+duration(target*60000L),22,true);
        label(c,"Counts completed consecutive days only. A missing day breaks the streak.",14,false);
    }
    private void settings() {
        LinearLayout c=card();label(c,"Appearance",20,true);
        for(String mode:new String[]{"Dark","Light","System"})c.addView(button((prefs.getString("theme","Dark").equals(mode)?"✓ ":"")+mode,()->{prefs.edit().putString("theme",mode).apply();render();}));
        c=card();label(c,"Daily target",20,true);label(c,duration(prefs.getInt("target",360)*60000L),23,true);
        c.addView(button("Change target",()->{EditText input=new EditText(this);input.setInputType(2);input.setText(""+prefs.getInt("target",360));new AlertDialog.Builder(this).setTitle("Target in minutes (1–1440)").setView(input).setPositiveButton("Save",(d,w)->{try{int v=Integer.parseInt(input.getText().toString());if(v<1||v>1440)throw new Exception();prefs.edit().putInt("target",v).apply();render();}catch(Exception e){Toast.makeText(this,"Enter 1–1440 minutes",1).show();}}).setNegativeButton("Cancel",null).show();}));
        c=card();label(c,"History start date",20,true);c.addView(button(UsageRepository.formatDate(start),()->choose(d->{start=UsageRepository.atStartOfDay(d);prefs.edit().putLong("purchase_date",start).apply();refresh();})));
        c=card();label(c,"Automatic archiving",20,true);label(c,"Checks about every 6 hours and catches up when opened. Android chooses the exact run time; force-stop suspends jobs until the app is reopened.",14,false);
        c.addView(button("Open app battery/settings",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));
    }
    private void data() {
        LinearLayout c=card();label(c,"Archive health",20,true);label(c,ArchiveHealth.summary(this),14,false);label(c,days.size()+" daily records",24,true);
        label(c,"Background job: "+(ArchiveScheduler.isScheduled(this)?"scheduled":"not scheduled"),14,false);
        label(c,"Older Android summaries are estimates, not complete daily records. Export before uninstalling.",14,false);
        c.addView(button("Import CSV",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1002)));
        c.addView(button("Export CSV",()->{if(result==null){Toast.makeText(this,"Usage access and a successful update are needed to export",1).show();return;}startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE,"screen-time-history.csv"),1001);}));
        label(c,"Import currently adds missing daily rows only; existing records are preserved.",13,false);
        c.addView(button("Settings →",()->{page="Settings";render();}));
    }
    @Override protected void onActivityResult(int request,int code,Intent data) {
        super.onActivityResult(request,code,data);if(code!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();worker.execute(()->{
            String message;
            try {
                if(request==1002){try(InputStream in=getContentResolver().openInputStream(uri)){message=new UsageRepository(this).importCsv(in)+" new daily rows imported";}}
                else {try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(result.toCsv().getBytes(StandardCharsets.UTF_8));message="Export saved";}}
            }catch(Exception e){message="File operation failed";}
            final String msg=message;runOnUiThread(()->{if(!isDestroyed()){Toast.makeText(this,msg,1).show();refresh();}});
        });
    }
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
