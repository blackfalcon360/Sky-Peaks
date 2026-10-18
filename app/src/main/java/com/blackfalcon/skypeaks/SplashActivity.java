package com.blackfalcon.skypeaks;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);

        TextView line1 = new TextView(this);
        line1.setText("Brought to you By:");
        line1.setTextColor(Color.WHITE);
        line1.setTextSize(22);
        line1.setGravity(Gravity.CENTER);

        TextView line2 = new TextView(this);
        line2.setText("Black Falcon \uD83E\uDD85");
        line2.setTextColor(Color.WHITE);
        line2.setTextSize(40);
        line2.setGravity(Gravity.CENTER);
        line2.setPadding(0, 24, 0, 0);

        root.addView(line1);
        root.addView(line2);
        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, 2200);
    }
}
