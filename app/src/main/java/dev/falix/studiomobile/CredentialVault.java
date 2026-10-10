package dev.falix.studiomobile;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore AES/GCM; ciphertext only, excluded from backup. */
final class CredentialVault {
    private static final String ALIAS="studio_mobile_api_key";
    private final android.content.SharedPreferences prefs;
    CredentialVault(Context c){prefs=c.getSharedPreferences("credentials",Context.MODE_PRIVATE);}
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)&&create){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    void save(String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));byte[] encrypted=cipher.doFinal(value.getBytes("UTF-8"));
        if(!prefs.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())throw new java.io.IOException("Unable to persist encrypted credentials.");
    }
    String load() throws Exception {
        String value=prefs.getString("cipher",null);if(value==null)return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)),"UTF-8");
    }
    void clear() throws Exception {
        prefs.edit().clear().commit();KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(ALIAS))ks.deleteEntry(ALIAS);
    }
}
