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

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 *
 * Requests added to the specified queue are processed from the network via a
 * specified {@link Network} interface. Responses are committed to cache, if
 * eligible, using a specified {@link Cache} interface. Valid responses and
 * errors are posted back to the caller via a {@link ResponseDelivery}.
 */
public class NetworkDispatcher extends Thread {
    /** 当前请求任务的队列 */
    private final BlockingQueue<Request<?>> mQueue;
    /** 用于实际发起网络请求的对象 */
    private final Network mNetwork;
    /** 用于写入请求结果的缓存 */
    private final Cache mCache;
    /** 用于进行请求结果的分发 */
    private final ResponseDelivery mDelivery;
    /** 当前调度线程是否退出，一旦退出，当前线程就会中断 */
    private volatile boolean mQuit = false;

    /**
     * Creates a new network dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param queue Queue of incoming requests for triage
     * @param network Network interface to use for performing requests
     * @param cache Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
            Network network, Cache cache,
            ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void addTrafficStatsTag(Request<?> request) {
        // Tag the request (if API >= 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }

    @Override
    public void run() {
        //设置线程优先级，在Android中这个优先级相当于中等，一般为后台线程使用
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Request<?> request;
        while (true) {//一个一直运行的线程
            long startTimeMs = SystemClock.elapsedRealtime();
            // release previous request object to avoid leaking request object when mQueue is drained.
            request = null;
            try {
                // 这里通过使用阻塞队列，可以做到空闲的时候阻塞线程
                // volley默认使用的是PriorityBlockingQueue，在take的时候会把线程进行await
                // 那么下一次有数据进入队列的时候会notify再进行唤醒
                // 这样可以很有效的避免线程的轮询，也可以节省CPU的开销
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                request.addMarker("network-queue-take");

                // 在请求正式开始前首先要看一下当前请求是否取消
                // 如果取消的话没有必要浪费网络链接的流量
                // 注意这里取消后是不会进行Listener的回调的
                if (request.isCanceled()) {
                    request.finish("network-discard-cancelled");
                    continue;
                }

                addTrafficStatsTag(request);

                //通过预设的实际网络请求执行者进行请求
                //这里通过接口设计，具有更好的扩展性
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                request.addMarker("network-http-complete");
                //到这里结束后，实际上一次发往服务器的请求和接收服务器响应的过程已经完成

                //一个请求完成了，这时候可能有两种情况
                //1.当前请求在发送之前标记了cache-control，然后去请求服务端，然后返回响应码304，表示客户端本地的缓存数据就是最新的
                //那么这样没有必要重复从网络上拉数据，这个的具体可以去看http自身的缓存机制
                //2.当前请求已经将响应结果进行发送回调，那么没有必要重复进行回调
                //在volley中，request是唯一的
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // 当前请求已经获得响应，那么这里进行返回报文的正体解析
                Response<?> response = request.parseNetworkResponse(networkResponse);
                // 本次请求的响应解析已经完成
                request.addMarker("network-parse-complete");

                // 1.当前请求可以进行缓存
                // 2.当前缓存的为response中的cacheEntry，这个需要注意
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                    request.addMarker("network-cache-written");
                }

                // 准备进行结果的分发，那么这里标记已经分发，为了避免出现重复分发的情况
                request.markDelivered();
                // 将结果交给分发者，进行结果的分发
                // 简单的一种情况就是将结果回调在主线程或者直接在子线程中处理
                mDelivery.postResponse(request, response);

                //出现异常的时候，比方说网络请求一个找不到的域名之类的
                //需要进行异常的回调，从而通知别人进行处理
            } catch (VolleyError volleyError) {
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                parseAndDeliverNetworkError(request, volleyError);
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
                VolleyError volleyError = new VolleyError(e);
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                mDelivery.postError(request, volleyError);
            }
        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }
}
