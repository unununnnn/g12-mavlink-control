package com.g12.x9;
import android.content.Context;
import com.g12.x9core.Profile;
import com.g12.x9core.ProfileCodec;

/** Private local configuration only. Never saves enabled/armed/ownership state. Backup disabled. */
public final class ProfileStore {
    private final android.content.SharedPreferences preferences;
    public ProfileStore(Context c){preferences=c.getSharedPreferences("x9-profile",Context.MODE_PRIVATE);}
    public Profile load() {
        String text=preferences.getString("profile",null);if(text==null)return null;
        return ProfileCodec.decode(text);
    }
    public void save(Profile p){
        if(!preferences.edit().putString("profile",ProfileCodec.encode(p)).commit())throw new IllegalStateException("保存档案失败，请检查设备存储");
    }
}
