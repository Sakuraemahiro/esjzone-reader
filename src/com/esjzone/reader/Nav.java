package com.esjzone.reader;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

/** Wires the shared bottom bar. Every page includes {@code layout/nav_bar.xml}. */
public final class Nav {

    public static void wire(final Activity activity) {
        bind(activity, R.id.nav_fav, MainActivity.class);
        bind(activity, R.id.nav_downloads, DownloadsActivity.class);
        bind(activity, R.id.nav_library, LibraryActivity.class);
        bind(activity, R.id.nav_tools, ToolsActivity.class);

        View current;
        if (activity instanceof DownloadsActivity) {
            current = activity.findViewById(R.id.nav_downloads);
        } else if (activity instanceof LibraryActivity) {
            current = activity.findViewById(R.id.nav_library);
        } else if (activity instanceof ToolsActivity) {
            current = activity.findViewById(R.id.nav_tools);
        } else {
            current = activity.findViewById(R.id.nav_fav);
        }
        if (current != null) {
            current.setSelected(true);
        }
    }

    private static void bind(final Activity activity, int viewId,
                             final Class<? extends Activity> target) {
        View view = activity.findViewById(viewId);
        if (view == null) {
            return;
        }
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activity.getClass() == target) {
                    return;
                }
                Intent intent = new Intent(activity, target);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
            }
        });
    }

    private Nav() {
    }
}
