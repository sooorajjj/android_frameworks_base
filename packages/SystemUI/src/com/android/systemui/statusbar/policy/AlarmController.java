/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Slog;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import com.android.systemui.R;

public class AlarmController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.AlarmController";

    private Context mContext;
    private ArrayList<ImageView> mIconViews = new ArrayList<ImageView>();
    private int mVisible = View.GONE;
    private int mIcon = R.drawable.stat_sys_alarm;

    public AlarmController(Context context) {
        mContext = context;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(this, filter);
        refreshViews();
    }

    public void addIconView(ImageView v) {
        mIconViews.add(v);
    }

    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
            mVisible = intent.getBooleanExtra("alarmSet", false) ? View.VISIBLE : View.GONE;
        }
        refreshViews();
    }

    public void refreshViews() {
        final int N = mIconViews.size();
        for (int i=0; i<N; i++) {
            ImageView v = mIconViews.get(i);
            v.setVisibility(mVisible);
            v.setImageResource(mIcon);
        }
    }
}
