/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.volley;

public interface ResponseDelivery {
    /**
     * 默认的使用是这个方法，用于响应网络或者缓存获得的响应
     */
    public void postResponse(Request<?> request, Response<?> response);

    /**
     * 后面提供了这个方法，在postResponse(Request<?> request, Response<?> response)的基础上
     * 添加了一个runnable用于在响应回调之后进行一个默认处理
     */
    public void postResponse(Request<?> request, Response<?> response, Runnable runnable);

    /**
     * 用于发送异常回调
     */
    public void postError(Request<?> request, VolleyError error);
}
