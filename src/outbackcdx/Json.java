package outbackcdx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Json {
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
            .create();

}
