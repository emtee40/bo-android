package org.blitzortung.android.data;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.os.AsyncTask;
import org.blitzortung.android.app.view.PreferenceKey;
import org.blitzortung.android.data.beans.AbstractStroke;
import org.blitzortung.android.data.provider.DataProvider;
import org.blitzortung.android.data.provider.DataProviderType;
import org.blitzortung.android.data.provider.DataResult;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DataHandler implements OnSharedPreferenceChangeListener {

    private final Lock lock = new ReentrantLock();

    private final PackageInfo pInfo;
    private DataProvider dataProvider;

    private String username;
    private String password;

    private final Parameters parameters;

    private DataListener listener;

    private int preferencesRasterBaselength;
    private int preferencesRegion;

    public static class UpdateTargets {

        boolean updateStrokes;

        boolean updateStations;

        public UpdateTargets() {
            updateStrokes = false;
            updateStations = false;
        }

        public void addStrokes() {
            updateStrokes = true;
        }

        public boolean updateStrokes() {
            return updateStrokes;
        }

        public void addParticipants() {
            updateStations = true;
        }

        public boolean updateParticipants() {
            return updateStations;
        }

        public boolean anyUpdateRequested() {
            return updateStrokes || updateStations;
        }
    }

    public DataHandler(SharedPreferences sharedPreferences, PackageInfo pInfo) {
        parameters = new Parameters();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        this.pInfo = pInfo;

        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.DATA_SOURCE);
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.USERNAME);
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.PASSWORD);
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.RASTER_SIZE);
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.REGION);
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.INTERVAL_DURATION);
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.HISTORIC_TIMESTEP);

        updateProviderSpecifics();
    }

    private class FetchDataTask extends AsyncTask<Integer, Integer, DataResult> {

        protected void onProgressUpdate(Integer... progress) {
        }

        protected void onPostExecute(DataResult result) {
            if (!result.hasFailed() && listener != null) {
                listener.onDataUpdate(result);
            }

            if (!result.processWasLocked()) {
                listener.setErrorIndicator(result.hasFailed());
            }
        }

        @Override
        protected DataResult doInBackground(Integer... params) {
            int intervalDuration = params[0];
            int intervalOffset = params[1];
            int rasterBaselength = params[2];
            int region = params[3];

            DataResult result = new DataResult();

            if (lock.tryLock()) {
                try {
                    dataProvider.setUp();
                    dataProvider.setCredentials(username, password);

                    List<AbstractStroke> strokes;
                    if (rasterBaselength == 0) {
                        strokes = dataProvider.getStrokes(intervalDuration, intervalOffset, region);
                    } else {
                        strokes = dataProvider.getStrokesRaster(intervalDuration, intervalOffset, rasterBaselength, region);
                    }
                    Parameters parameters = new Parameters();
                    parameters.setIntervalDuration(intervalDuration);
                    parameters.setIntervalOffset(intervalOffset);
                    parameters.setRegion(region);
                    parameters.setRasterBaselength(rasterBaselength);

                    if (dataProvider.returnsIncrementalData()) {
                        result.setContainsIncrementalData();
                    }
                    result.setParameters(parameters);
                    result.setReferenceTime(System.currentTimeMillis());
                    result.setStrokes(strokes);
                    result.setRasterParameters(dataProvider.getRasterParameters());
                    result.setHistogram(dataProvider.getHistogram());

                    if (params.length > 4) {
                        boolean updateParticipants = (params[4] == 1);
                        if (updateParticipants) {
                            result.setParticipants(dataProvider.getStations(region));
                        }
                    }

                    dataProvider.shutDown();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
            } else {
                result.setProcessWasLocked();
            }
            return result;
        }
    }

    public void updateData(UpdateTargets updateTargets) {

        listener.onBeforeDataUpdate();

        boolean updateParticipants = false;
        if (updateTargets.updateParticipants()) {
            if (dataProvider.getType() == DataProviderType.HTTP || parameters.getRasterBaselength() == 0) {
                updateParticipants = true;
            }
        }

        new FetchDataTask().execute(parameters.getIntervalDuration(), parameters.getIntervalOffset(), dataProvider.getType() == DataProviderType.HTTP ? 0 : parameters.getRasterBaselength(), parameters.getRegion(), updateParticipants ? 1 : 0);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String keyString) {
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.fromString(keyString));
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, PreferenceKey key) {
        switch (key) {
            case DATA_SOURCE:
                String providerTypeString = sharedPreferences.getString(key.toString(), DataProviderType.RPC.toString());
                DataProviderType providerType = DataProviderType.valueOf(providerTypeString.toUpperCase());
                dataProvider = providerType.getProvider();
                dataProvider.setPackageInfo(pInfo);

                updateProviderSpecifics();

                notifyDataReset();
                break;

            case USERNAME:
                username = sharedPreferences.getString(key.toString(), "");
                break;

            case PASSWORD:
                password = sharedPreferences.getString(key.toString(), "");
                break;

            case RASTER_SIZE:
                preferencesRasterBaselength = Integer.parseInt(sharedPreferences.getString(key.toString(), "10000"));
                parameters.setRasterBaselength(preferencesRasterBaselength);
                break;

            case INTERVAL_DURATION:
                parameters.setIntervalDuration(Integer.parseInt(sharedPreferences.getString(key.toString(), "120")));
                dataProvider.reset();
                notifyDataReset();
                break;

            case HISTORIC_TIMESTEP:
                parameters.setOffsetIncrement(Integer.parseInt(sharedPreferences.getString(key.toString(), "30")));
                break;

            case REGION:
                preferencesRegion = Integer.parseInt(sharedPreferences.getString(key.toString(), "1"));
                parameters.setRegion(preferencesRegion);
                dataProvider.reset();
                notifyDataReset();
                break;
        }

        if (dataProvider != null) {
            dataProvider.setCredentials(username, password);
        }
    }

    private void updateProviderSpecifics() {

        DataProviderType providerType = dataProvider.getType();

        switch (providerType) {
            case RPC:
                enableRasterMode();
                break;

            case HTTP:
                disableRasterMode();
                break;
        }
    }

    private void notifyDataReset() {
        if (listener != null) {
            listener.onDataReset();
        }
    }

    public void toggleExtendedMode() {
        if (parameters.getRasterBaselength() > 0) {
            disableRasterMode();
            parameters.setRegion(0);
        } else {
            enableRasterMode();
            parameters.setRegion(preferencesRegion);
        }
        if (!isRealtime()) {
            DataHandler.UpdateTargets updateTargets = new DataHandler.UpdateTargets();
            updateTargets.updateStrokes();
            updateData(updateTargets);
        }
    }

    public void disableRasterMode() {
        parameters.setRasterBaselength(0);
    }

    public void enableRasterMode() {
        parameters.setRasterBaselength(preferencesRasterBaselength);
    }

    public void setDataListener(DataListener listener) {
        this.listener = listener;
    }

    public int getIntervalDuration() {
        return parameters.getIntervalDuration();
    }

    public boolean ffwdInterval() {
        return parameters.ffwdInterval();
    }

    public boolean rewInterval() {
        return parameters.revInterval();
    }

    public boolean goRealtime() {
        return parameters.goRealtime();
    }

    public boolean isRealtime() {
        return parameters.isRealtime();
    }

    public boolean matches(Parameters resultParameters) {
        return parameters.equals(resultParameters);
    }

    public boolean isCapableOfHistoricalData() {
        return dataProvider.isCapableOfHistoricalData();
    }

}