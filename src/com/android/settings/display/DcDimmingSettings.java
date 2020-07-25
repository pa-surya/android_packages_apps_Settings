/*
 * Copyright (C) 2020-22 Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settings.display;

import static android.hardware.display.DcDimmingManager.MODE_AUTO_OFF;
import static android.hardware.display.DcDimmingManager.MODE_AUTO_TIME;
import static android.hardware.display.DcDimmingManager.MODE_AUTO_BRIGHTNESS;
import static android.hardware.display.DcDimmingManager.MODE_AUTO_FULL;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinear;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGamma;

import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.hardware.display.DcDimmingManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.SearchIndexableResource;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.DropDownPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekbarPreference;

import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.search.Indexable;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.OnMainSwitchChangeListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen for DC Dimming.
 */
@SearchIndexable(forTarget = SearchIndexable.ALL & ~SearchIndexable.ARC)
public class DcDimmingSettings extends DashboardFragment
        implements Preference.OnPreferenceChangeListener, OnMainSwitchChangeListener  {

    private static final String TAG = "DcDimmingSettings";

    private static final String KEY_MAIN_SWITCH = "dc_dimming_activated";
    private static final String KEY_BRIGHTNESS = "dc_dimming_brightness";
    private static final String KEY_RESTORE_BUTTON = "dc_dimming_restore_button";
    private static final String KEY_AUTO_MODE = "dc_dimming_auto_mode";

    private DcDimmingManager mDcDimmingManager;
    private Context mContext;

    private int mMinimumBacklight;
    private int mMaximumBacklight;

    private LayoutPreference mRestoreAuto;
    private DropDownPreference mAutoPref;
    private SeekBarPreference mBrightnessPref;
    private Button mRestoreButton;
    private MainSwitchPreference mPreference;

    private final View.OnClickListener mRestoreListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mDcDimmingManager.restoreAutoMode();
            updateStateInternal();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getContext();
        mDcDimmingManager = (DcDimmingManager) getSystemService(Context.DC_DIM_SERVICE);

        if (mDcDimmingManager == null || !mDcDimmingManager.isAvailable()) {
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mMinimumBacklight = pm.getMinimumScreenBrightnessSetting();
        mMaximumBacklight = pm.getMaximumScreenBrightnessSetting();

        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();

        mPreference = findPreference(KEY_MAIN_SWITCH);
        mPreference.addOnSwitchChangeListener(this);
        mPreference.updateStatus(mDcDimmingManager.isDcDimmingOn());

        mBrightnessPref = findPreference(KEY_BRIGHTNESS);
        final int gamma = convertLinearToGamma(mDcDimmingManager.getBrightnessThreshold(),
                mMinimumBacklight, mMaximumBacklight);
        mBrightnessPref.setValue((int) ((float) gamma * 100.0f / GAMMA_SPACE_MAX););
        mBrightnessPref.setOnPreferenceChangeListener(this);

        mRestoreAuto = findPreference(KEY_RESTORE_BUTTON);
        Button restoreActiveButton = mRestoreAuto.findViewById(R.id.dc_dimming_restore_active);
        restoreActiveButton.setOnClickListener(mRestoreListener);

        mAutoPref = findPreference(KEY_AUTO_MODE);
        mAutoPref.setEntries(new CharSequence[]{
                mContext.getString(R.string.dark_ui_auto_mode_never),
                mContext.getString(R.string.dark_ui_auto_mode_auto),
                mContext.getString(R.string.dc_dimming_mode_brightness),
                mContext.getString(R.string.dc_dimming_mode_full)
        });
        mAutoPref.setEntryValues(new CharSequence[]{
                String.valueOf(MODE_AUTO_OFF),
                String.valueOf(MODE_AUTO_TIME),
                String.valueOf(MODE_AUTO_BRIGHTNESS),
                String.valueOf(MODE_AUTO_FULL)
        });
        mAutoPref.setValue(String.valueOf(mDcDimmingManager.getAutoMode()));
        mAutoPref.setOnPreferenceChangeListener(this);

        updateStateInternal();
    }

    private void updateBrightnessValue(int gamma) {
        int per = (int) ((float) gamma * 100.0f / GAMMA_SPACE_MAX);
        if (per == 0) {
            mBrightnessValue.setText(R.string.dc_dimming_brightness_disabled);
        } else {
            mBrightnessValue.setText(String.valueOf(per) + "%");
        }
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.dc_dimming_settings;
    }

    @Override
    public int getMetricsCategory() {
        return -1;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mAutoPref) {
            mDcDimmingManager.setAutoMode(Integer.parseInt((String) newValue));
        } else if (preference == mBrightnessPref) {
            final int progress = Integer.parseInt((String) newValue);
            final int gamma = (int) ((float) progress / 100.0f * GAMMA_SPACE_MAX);
            final int linear = convertGammaToLinear(gamma, mMinimumBacklight, mMaximumBacklight);
            mDcDimmingManager.setBrightnessThreshold(linear);
        }
        updateStateInternal();
        return true;
    }

    @Override
    public void onSwitchChanged(Switch switchView, boolean isChecked) {
        mDcDimmingManager.setDcDimming(isChecked);
        updateStateInternal();
    }

    private void updateStateInternal() {
        if (mTurnOnButton == null || mTurnOffButton == null) {
            return;
        }

        final int mode = mDcDimmingManager.getAutoMode();
        final boolean isForce = mDcDimmingManager.isForcing();

        mRestoreAuto.setVisible(isForce);
        mBrightnessPref.setEnabled((mode >= MODE_AUTO_BRIGHTNESS) && !isForce);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DC_DIMMING_AUTO_MODE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DC_DIMMING_STATE), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateStateInternal();
        }
    }

    public static final Indexable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
                @Override
                public List<SearchIndexableResource> getXmlResourcesToIndex(Context context,
                        boolean enabled) {
                    final ArrayList<SearchIndexableResource> result = new ArrayList<>();
                    final SearchIndexableResource sir = new SearchIndexableResource(context);
                    sir.xmlResId = R.xml.dc_dimming_settings;
                    result.add(sir);
                    return result;
                }

                @Override
                protected boolean isPageSearchEnabled(Context context) {
                    DcDimmingManager dm = (DcDimmingManager) context
                            .getSystemService(Context.DC_DIM_SERVICE);
                    return dm != null && dm.isAvailable();
                }

                @Override
                public List<AbstractPreferenceController> createPreferenceControllers(
                        Context context) {
                    return new ArrayList(1);
                }
            };
}
