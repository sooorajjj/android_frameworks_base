/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_STRUCTURED_ALLOCATION_H
#define ANDROID_STRUCTURED_ALLOCATION_H

#include "rsType.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {



class Allocation : public ObjectBase
{
    // The graphics equilivent of malloc.  The allocation contains a structure of elements.


public:
    // By policy this allocation will hold a pointer to the type
    // but will not destroy it on destruction.
    Allocation(Context *rsc, const Type *);
    Allocation(Context *rsc, const Type *, int index);
    virtual ~Allocation();

    void setCpuWritable(bool);
    void setGpuWritable(bool);
    void setCpuReadable(bool);
    void setGpuReadable(bool);

    bool fixAllocation();

    void * getPtr() const {return mPtr;}
    void ** getPtrAddr() {return &mPtr;}
    const Type * getType() const {return mType.get();}

    void uploadToTexture(Context *rsc, uint32_t lodOffset = 0);
    uint32_t getTextureID() const {return mTextureID;}

    void uploadToBufferObject();
    uint32_t getBufferObjectID() const {return mBufferID;}


    void data(const void *data, uint32_t sizeBytes);
    void subData(uint32_t xoff, uint32_t count, const void *data, uint32_t sizeBytes);
    void subData(uint32_t xoff, uint32_t yoff,
                 uint32_t w, uint32_t h, const void *data, uint32_t sizeBytes);
    void subData(uint32_t xoff, uint32_t yoff, uint32_t zoff,
                 uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes);

    void read(void *data);

    void enableGLVertexBuffers() const;
    void setupGLIndexBuffers() const;

    virtual void dumpLOGV(const char *prefix) const;


protected:
    ObjectBaseRef<const Type> mType;
    void * mPtr;

    // Usage restrictions
    bool mCpuWrite;
    bool mCpuRead;
    bool mGpuWrite;
    bool mGpuRead;
    bool mUseEarlierAllocation;

    // more usage hint data from the application
    // which can be used by a driver to pick the best memory type.
    // Likely ignored for now
    float mReadWriteRatio;
    float mUpdateSize;


    // Is this a legal structure to be used as a texture source.
    // Initially this will require 1D or 2D and color data
    bool mIsTexture;
    uint32_t mTextureID;

    // Is this a legal structure to be used as a vertex source.
    // Initially this will require 1D and x(yzw).  Additional per element data
    // is allowed.
    bool mIsVertexBuffer;
    uint32_t mBufferID;
};

}
}
#endif

