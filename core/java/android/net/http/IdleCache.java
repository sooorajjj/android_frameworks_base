/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2011, The Linux Foundation. All rights reserved.
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

/**
 * Hangs onto idle live connections for a little while
 */

package android.net.http;

import org.apache.http.HttpHost;

import android.net.http.RequestQueue;
import android.os.SystemClock;
import android.os.SystemProperties;
import java.io.IOException;

/**
 * {@hide}
 */
class IdleCache {

    class Entry {
        HttpHost mHost;
        Connection mConnection;
        long mTimeout;
    };

    private final static int IDLE_CACHE_MAX =
        SystemProperties.getInt("net.http.idle_cache.size", 8);

    /* Allow five consecutive empty queue checks before shutdown */
    private final static int EMPTY_CHECK_MAX = 5;

    /* six second timeout for connections */
    private final static int TIMEOUT = 6 * 1000;
    private final static int CHECK_INTERVAL = 2 * 1000;
    private Entry[] mEntries = new Entry[IDLE_CACHE_MAX];

    private boolean mShutdownOnPageFinish;
    public boolean pageFinished;
    private int mCount = 0;

    private IdleReaper mThread = null;

    /* stats */
    private int mCached = 0;
    private int mReused = 0;

    IdleCache() {
        setShutdownFeature(false);
        pageFinished = false;
        for (int i = 0; i < IDLE_CACHE_MAX; i++) {
            mEntries[i] = new Entry();
        }
    }

    /**
     * Caches connection, if there is room.
     * @return true if connection cached
     */
    synchronized boolean cacheConnection(
            HttpHost host, Connection connection) {

        boolean ret = false;

        if (HttpLog.LOGV) {
            HttpLog.v("IdleCache size " + mCount + " host "  + host);
        }

        if (mCount < IDLE_CACHE_MAX) {
            long time = SystemClock.uptimeMillis();
            for (int i = 0; i < IDLE_CACHE_MAX; i++) {
                Entry entry = mEntries[i];
                if (entry.mHost == null) {
                    entry.mHost = host;
                    entry.mConnection = connection;
                    entry.mTimeout = time + TIMEOUT;
                    mCount++;
                    if (HttpLog.LOGV) mCached++;
                    ret = true;
                    if (mThread == null) {
                        mThread = new IdleReaper();
                        mThread.start();
                    }
                    break;
                }
            }
        }
        return ret;
    }

    synchronized Connection getConnection(HttpHost host) {
        Connection ret = null;

        if (mCount > 0) {
            for (int i = 0; i < IDLE_CACHE_MAX; i++) {
                Entry entry = mEntries[i];
                HttpHost eHost = entry.mHost;
                if (eHost != null && eHost.equals(host)) {
                    ret = entry.mConnection;
                    entry.mHost = null;
                    entry.mConnection = null;
                    mCount--;
                    if (HttpLog.LOGV) mReused++;
                    break;
                }
            }
        }
        return ret;
    }

    public void setShutdownFeature(boolean isOn) {
        if (isOn) {
            isOn = SystemProperties.getBoolean("net.http.idle_cache.shutdown", true);
        }
        mShutdownOnPageFinish = isOn;
    }

    synchronized void clear() {
        for (int i = 0; (mCount > 0) && (i < IDLE_CACHE_MAX); i++) {
            Entry entry = mEntries[i];
            if (entry.mHost != null) {
                entry.mHost = null;
                entry.mConnection.closeConnection();
                entry.mConnection = null;
                mCount--;
            }
        }
    }

    synchronized void clearTcpConnections() {
        for (int i = 0; i < IDLE_CACHE_MAX; i++) {
            Entry entry = mEntries[i];
            if (entry.mHost != null) {
                if (entry.mConnection.getTcpPreConnect()) {
                    entry.mConnection.setTcpPreConnect(false);
                }
            }
        }
    }

    private synchronized void clearIdle() {
        if (mCount > 0) {
            long time = SystemClock.uptimeMillis();
            for (int i = 0; (mCount > 0) && (i < IDLE_CACHE_MAX); i++) {
                Entry entry = mEntries[i];
                if (entry.mHost != null && time > entry.mTimeout) {
                    if (!entry.mConnection.getTcpPreConnect()) {
                        entry.mHost = null;
                        entry.mConnection.closeConnection();
                        entry.mConnection = null;
                        mCount--;
                    }
                }
            }
        }
    }

    public synchronized void wakeup() {
        notify();
    }

    private class IdleReaper extends Thread {

        public void run() {
            int check = 0;

            setName("IdleReaper");
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            synchronized (IdleCache.this) {
                while (check < EMPTY_CHECK_MAX) {
                    try {
                        IdleCache.this.wait(CHECK_INTERVAL);
                    } catch (InterruptedException ex) {
                    }

                    if(pageFinished) {
                        clearTcpConnections();
                    }

                    if(mShutdownOnPageFinish && pageFinished && (ConnectionThread.sRunning.get() == 0)) {
                        clear();
                        break;
                    }
                    if (mCount == 0) {
                        check++;
                    } else {
                        check = 0;
                        clearIdle();
                    }
                }
                mThread = null;
            }
            if (HttpLog.LOGV) {
                HttpLog.v("IdleCache IdleReaper shutdown: cached " + mCached +
                          " reused " + mReused);
                mCached = 0;
                mReused = 0;
            }
            pageFinished = false;
        }
    }
}

