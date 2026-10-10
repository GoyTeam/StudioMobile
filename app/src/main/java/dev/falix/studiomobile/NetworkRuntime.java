package dev.falix.studiomobile;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;
/** Shared transport for current REST calls and future supported network features. No request logging. */
public final class NetworkRuntime {
    private static final OkHttpClient CLIENT=new OkHttpClient.Builder()
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
        .writeTimeout(25,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true).build();
    private NetworkRuntime(){}
    public static OkHttpClient client(){return CLIENT;}
}
