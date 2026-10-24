package com.esjzone.reader;

import android.content.Context;

import java.io.InputStream;

/** Lazily loads the traditional-to-simplified table once per process. */
public final class T2sHolder {

    private static volatile T2s instance;

    public static T2s get(Context context) {
        T2s local = instance;
        if (local == null) {
            synchronized (T2sHolder.class) {
                local = instance;
                if (local == null) {
                    local = load(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    private static T2s load(Context context) {
        try {
            InputStream in = context.getAssets().open("t2s.tsv");
            try {
                return T2s.load(in);
            } finally {
                in.close();
            }
        } catch (Exception error) {
            // without the table the app still works, it just does not convert
            return T2s.empty();
        }
    }

    private T2sHolder() {
    }
}
