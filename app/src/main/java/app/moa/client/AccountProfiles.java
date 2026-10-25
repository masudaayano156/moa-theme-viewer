package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local profile labels; passwords and cookies are owned by WebView itself. */
public final class AccountProfiles {
    public static final String DEFAULT = "Default";
    public static final int MAX_PROFILES = 5;
    private static final String PREFS = "moa_account_profiles";
    private AccountProfiles() { }
    public static final class Entry {
        public final String id, label;
        Entry(String id, String label) { this.id=id; this.label=label; }
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static boolean supported() {
        try { return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    static boolean validId(String id) { return DEFAULT.equals(id) || id != null && id.matches("moa_[a-f0-9]{32}"); }
    public static List<Entry> entries(Context context) {
        List<Entry> result=new ArrayList<>();
        result.add(new Entry(DEFAULT,prefs(context).getString("default_label","기본 계정")));
        try {
            JSONArray rows=new JSONArray(prefs(context).getString("profiles","[]"));
            for(int i=0;i<rows.length() && result.size()<MAX_PROFILES;i++) {
                JSONObject row=rows.optJSONObject(i);
                if(row==null)continue;
                String id=row.optString("id"),label=row.optString("label").trim();
                if(DEFAULT.equals(id)||!validId(id)||label.isEmpty()||label.length()>40)continue;
                boolean exists=false;for(Entry item:result)if(item.id.equals(id))exists=true;
                if(!exists)result.add(new Entry(id,label));
            }
        } catch(Exception malformed) { }
        return result;
    }
    public static String activeId(Context context) {
        String id=prefs(context).getString("active",DEFAULT);
        if(!DEFAULT.equals(id)&&!supported())return DEFAULT;
        for(Entry item:entries(context))if(item.id.equals(id))return id;
        return DEFAULT;
    }
    public static String activeLabel(Context context) {
        String id=activeId(context);for(Entry item:entries(context))if(item.id.equals(id))return item.label;
        return "기본 계정";
    }
    public static Entry add(Context context,String label) {
        if(!supported()||label==null||label.trim().isEmpty()||label.trim().length()>40)return null;
        List<Entry> rows=entries(context);if(rows.size()>=MAX_PROFILES)return null;
        Entry entry=new Entry("moa_"+UUID.randomUUID().toString().replace("-",""),label.trim());
        rows.add(entry);return save(context,rows)?entry:null;
    }
    public static boolean activate(Context context,String id) {
        if(!validId(id)||!DEFAULT.equals(id)&&!supported())return false;
        for(Entry row:entries(context))if(row.id.equals(id))return prefs(context).edit().putString("active",id).commit();
        return false;
    }
    public static boolean rename(Context context,String id,String label) {
        if(label==null||label.trim().isEmpty()||label.trim().length()>40)return false;
        List<Entry> rows=entries(context);
        for(int i=0;i<rows.size();i++)if(rows.get(i).id.equals(id)) {
            rows.set(i,new Entry(id,label.trim()));return save(context,rows);
        }
        return false;
    }
    private static boolean save(Context context,List<Entry> entries) {
        JSONArray rows=new JSONArray();String defaultLabel="기본 계정";
        try {
            for(Entry item:entries)if(DEFAULT.equals(item.id))defaultLabel=item.label;
            else rows.put(new JSONObject().put("id",item.id).put("label",item.label));
        } catch(Exception invalid) { return false; }
        return prefs(context).edit().putString("profiles",rows.toString()).putString("default_label",defaultLabel).commit();
    }
    public static void attach(WebView web,String profile) {
        if(DEFAULT.equals(profile))return;
        if(!validId(profile))throw new IllegalStateException("Separate WebView profiles are unavailable");
        try {
            if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                WebViewCompat.setProfile(web,profile);
                return;
            }
        } catch(RuntimeException | LinkageError unavailable) {
            throw new IllegalStateException("Separate WebView profiles are unavailable",unavailable);
        }
        throw new IllegalStateException("Separate WebView profiles are unavailable");
    }
    public static CookieManager cookies(String profile) {
        if(DEFAULT.equals(profile))return CookieManager.getInstance();
        if(!validId(profile))throw new IllegalStateException("Separate WebView profiles are unavailable");
        try {
            if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                Profile selected=ProfileStore.getInstance().getOrCreateProfile(profile);
                return selected.getCookieManager();
            }
        } catch(RuntimeException | LinkageError unavailable) {
            throw new IllegalStateException("Separate WebView profiles are unavailable",unavailable);
        }
        throw new IllegalStateException("Separate WebView profiles are unavailable");
    }
}
