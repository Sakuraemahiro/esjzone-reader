package com.esjzone.reader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/** Landing page for the offline tools. No network is touched anywhere under here. */
public class ToolsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tools);

        findViewById(R.id.tool_split).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(EpubToolActivity.MODE_SPLIT);
            }
        });
        findViewById(R.id.tool_compress).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(EpubToolActivity.MODE_COMPRESS);
            }
        });

        Nav.wire(this);
    }

    private void open(String mode) {
        Intent intent = new Intent(this, EpubToolActivity.class);
        intent.putExtra(EpubToolActivity.EXTRA_MODE, mode);
        startActivity(intent);
    }
}
