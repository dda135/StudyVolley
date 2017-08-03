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

import android.os.Handler;

import java.util.concurrent.Executor;

/**
 * Delivers responses and errors.
 */
public class ExecutorDelivery implements ResponseDelivery {
    /** Used for posting responses, typically to the main thread. */
    private final Executor mResponsePoster;

    /**
     * Creates a new response delivery interface.
     * @param handler {@link Handler} to post responses on
     */
    public ExecutorDelivery(final Handler handler) {
        // Make an Executor that just wraps the handler.
        mResponsePoster = new Executor() {//实际上就是把任务扔进handler中执行，所以说重点在handler的执行线程
            @Override
            public void execute(Runnable command) {
                handler.post(command);
            }
        };
    }

    /**
     * Creates a new response delivery interface, mockable version
     * for testing.
     * @param executor For running delivery tasks
     */
    public ExecutorDelivery(Executor executor) {
        mResponsePoster = executor;
    }

    @Override
    public void postResponse(Request<?> request, Response<?> response) {
        postResponse(request, response, null);
    }

    @Override
    public void postResponse(Request<?> request, Response<?> response, Runnable runnable) {
        request.markDelivered();
        request.addMarker("post-response");
        mResponsePoster.execute(new ResponseDeliveryRunnable(request, response, runnable));
    }

    @Override
    public void postError(Request<?> request, VolleyError error) {
        request.addMarker("post-error");
        Response<?> response = Response.error(error);
        mResponsePoster.execute(new ResponseDeliveryRunnable(request, response, null));
    }

    /**
     * A Runnable used for delivering network responses to a listener on the
     * main thread.
     */
    @SuppressWarnings("rawtypes")
    private class ResponseDeliveryRunnable implements Runnable {
        private final Request mRequest;
        private final Response mResponse;
        private final Runnable mRunnable;

        public ResponseDeliveryRunnable(Request request, Response response, Runnable runnable) {
            mRequest = request;
            mResponse = response;
            mRunnable = runnable;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void run() {
            // 在发送之前先检查当前任务是否取消
            if (mRequest.isCanceled()) {
                //任务取消则不会进行回调发送
                mRequest.finish("canceled-at-delivery");
                return;
            }

            //实际上就是在这里进行回调处理
            //在默认的场景下，发出的每一个请求都需要定义一个回调
            //那么就是通过实现Request中的对应方法实现
            if (mResponse.isSuccess()) {
                mRequest.deliverResponse(mResponse.result);
            } else {
                mRequest.deliverError(mResponse.error);
            }

            //此时回调已经完成

            if (mResponse.intermediate) {
                //这个标志意味着当前请求没有完成，此时有点尴尬
                //默认的情况中在NetworkDispatcher中的任务队列中已经移除
                //但是此时在RequestQueue中的当前请求队列中没有移除
                //这个目前的应用在CacheDispatcher中，成功从缓存中获取数据，然后先进行回调
                //之后再通过runnable将当前请求放到任务队列中，用于缓存处理后再次发送请求
                mRequest.addMarker("intermediate-response");
            } else {
                //正常的一次任务完成之后进行收尾处理
                mRequest.finish("done");
            }

            // 目前的使用场景是CacheDispatcher，用于在缓存处理之后再次发送请求
            if (mRunnable != null) {
                mRunnable.run();
            }
       }
    }
}
