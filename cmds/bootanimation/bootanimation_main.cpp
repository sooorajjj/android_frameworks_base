/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "BootAnimation"

#include <cutils/properties.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <utils/Log.h>
#include <utils/threads.h>

#if defined(HAVE_PTHREADS)
# include <pthread.h>
# include <sys/resource.h>
#endif

#include "BootAnimation.h"

#include <media/IMediaPlayerClient.h>
#include <media/IMediaPlayerService.h>
#include <system/audio.h>
#include <media/AudioTrack.h>

#define USER_BOOTANIMATION_SOUND_FILE "/data/local/bootanimation.ogg"
#define SYSTEM_BOOTANIMATION_SOUND_FILE "/system/media/bootanimation.ogg"

using namespace android;

// ---------------------------------------------------------------------------

class BpMediaPlayerClient: public BpInterface<IMediaPlayerClient>
{
public:
    BpMediaPlayerClient(const sp<IBinder>& impl)
        : BpInterface<IMediaPlayerClient>(impl)
    {
    }

    virtual void notify(int msg, int ext1, int ext2, const Parcel *obj)
    {

    }
};

int main(int argc, char** argv)
{
#if defined(HAVE_PTHREADS)
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);
#endif

    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.nobootanimation", value, "0");
    int noBootAnimation = atoi(value);
    ALOGI_IF(noBootAnimation,  "boot animation disabled");
    if (!noBootAnimation) {
        sp<BpMediaPlayerClient> client;
        sp<IMediaPlayer> player;
        char bootanimation_sound_path[PATH_MAX] = "";

        if (!access(USER_BOOTANIMATION_SOUND_FILE, F_OK)) {
            strcpy(bootanimation_sound_path, USER_BOOTANIMATION_SOUND_FILE);
        } else if (!access(SYSTEM_BOOTANIMATION_SOUND_FILE, F_OK)) {
            strcpy(bootanimation_sound_path, SYSTEM_BOOTANIMATION_SOUND_FILE);
        }

        if (bootanimation_sound_path[0]) {
            sp<IServiceManager> sm = defaultServiceManager();
            sp<IBinder> binder = sm->getService(String16("media.player"));
            sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

            if (service.get()) {
                client = new BpMediaPlayerClient(binder);
                player = service->create(getpid(), client, AudioSystem::newAudioSessionId());
                if (player.get()) {
                    player->setDataSource(bootanimation_sound_path, NULL);
                    player->start();
                }
            }
        }

        sp<ProcessState> proc(ProcessState::self());
        ProcessState::self()->startThreadPool();

        // create the boot animation object
        sp<BootAnimation> boot = new BootAnimation();

        IPCThreadState::self()->joinThreadPool();

    }
    return 0;
}
