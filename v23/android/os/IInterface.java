/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

/**
 * Base class for Binder interfaces.  When defining a new interface,
 * you must derive it from IInterface.
 */

/*
 * Binder通讯中传递的Interface必须继承自IInterface接口。
 * Service通过接口定义客户端可以执行的操作，通过Binder进行通信。
 * 所以客户端实质上在调用服务时，需要拿到两个东西：1.定义服务内容的接口 2.通信使用的Binder
 */

public interface IInterface {
    /**
     * Retrieve the Binder object associated with this interface.
     * You must use this instead of a plain cast, so that proxy objects
     * can return the correct result.
     */
    public IBinder asBinder();
}
