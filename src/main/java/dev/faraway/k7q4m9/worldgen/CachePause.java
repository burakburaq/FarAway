package dev.faraway.k7q4m9.worldgen;

import java.util.concurrent.CompletableFuture;

/** Allows a resumable tile step to give up its worker at a shared-cache read. */
public final class CachePause {
    private static final ThreadLocal<Boolean> ALLOWED=new ThreadLocal<>();

    private CachePause() {}

    public static Scope allow(boolean enabled) {
        if(!enabled) return Scope.NO_CHANGE;
        boolean previous=allowed();
        ALLOWED.set(Boolean.TRUE);
        return previous?Scope.NO_CHANGE:Scope.REMOVE;
    }

    public static boolean allowed() { return ALLOWED.get()==Boolean.TRUE; }

    public static Scope producing() {
        if(!allowed()) return Scope.NO_CHANGE;
        ALLOWED.remove();
        return Scope.RESTORE;
    }

    public static void waitFor(CompletableFuture<?> result) {
        if(!result.isDone() && allowed()) throw new Pending(result);
    }

    public enum Scope implements AutoCloseable {
        NO_CHANGE, REMOVE, RESTORE;
        @Override public void close() {
            if(this==REMOVE) ALLOWED.remove();
            else if(this==RESTORE) ALLOWED.set(Boolean.TRUE);
        }
    }

    public static final class Pending extends RuntimeException {
        private final CompletableFuture<?> result;
        private Pending(CompletableFuture<?> result) {
            super(null,null,false,false);
            this.result=result;
        }
        public CompletableFuture<?> result() { return result; }
    }
}
