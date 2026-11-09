package dev.faraway.k7q4m9.diagnostics;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Snapshot at client startup: later development builds cannot relabel a live run. */
public record BuildIdentity(String version,String sourceSha256) {
    public static final BuildIdentity CURRENT=read();
    private static BuildIdentity read() {
        try(var stream=BuildIdentity.class.getResourceAsStream("/faraway-build.json")) {
            if(stream==null) throw new IllegalStateException("Missing generated FarAway build identity");
            var identity=new Gson().fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),BuildIdentity.class);
            if(identity==null || identity.version()==null || identity.sourceSha256()==null || !identity.sourceSha256().matches("[a-f0-9]{64}"))
                throw new IllegalStateException("Invalid FarAway build identity");
            return identity;
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot read FarAway build identity",e); }
    }
}
