package com.pocketbuilder;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView statusView = findViewById(R.id.status_text);
        statusView.setText(getString(R.string.scaffold_status));
    }
}
