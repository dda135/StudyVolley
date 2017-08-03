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

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 *
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 */
public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;

    /** The queue of requests coming in for triage. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** The queue of requests going out to the network. */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** The cache to read from. */
    private final Cache mCache;

    /** For posting responses. */
    private final ResponseDelivery mDelivery;

    /** Used for telling us to die. */
    private volatile boolean mQuit = false;

    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param cacheQueue Queue of incoming requests for triage
     * @param networkQueue Queue to post requests that require network to
     * @param cache Cache interface to use for resolution
     * @param delivery Delivery interface to use for posting responses
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
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

    @Override
    public void run() {
        if (DEBUG) VolleyLog.v("start new dispatcher");
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Make a blocking call to initialize the cache.
        mCache.initialize();

        Request<?> request;
        while (true) {
            request = null;
            try {
                // 从缓存请求队列中获取请求，这个同样会阻塞
                request = mCacheQueue.take();
            } catch (InterruptedException e) {
                if (mQuit) {
                    return;
                }
                continue;
            }
            try {
                request.addMarker("cache-queue-take");

                if (request.isCanceled()) {//当前请求已经被取消，不需要继续从缓存中获取数据等一系列操作
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // 根据缓存的键名从缓存中获取数据
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {//未能命中缓存
                    request.addMarker("cache-miss");
                    // 将当前任务添加当网络请求队列中，那么后续就会进入NetworkDispatcher中执行网络请求
                    mNetworkQueue.put(request);
                    continue;
                }

                //在http缓存机制中缓存有两种过期
                //首先一个响应一定有一个截止时间
                //但是有个响应可能允许额外的延长过期时间
                //这个具体可以看cache-control
                //在Volley中分别被封装成了entry中的
                //softTtl和ttl

                if (entry.isExpired()) {//当前时间已经大于ttl，那么缓存一定是过期的，这个时间可能包括额外的延长时间
                    //虽然成功击中缓存，但是当前缓存已经过期
                    //此时还是去发起网络请求
                    request.addMarker("cache-hit-expired");
                    //也许后续服务器会返回304标记数据没有变化，此时可以直接使用之前的entry
                    request.setCacheEntry(entry);
                    mNetworkQueue.put(request);
                    continue;
                }
                //当前击中缓存，并且缓存数据没有完全过期
                request.addMarker("cache-hit");
                //通过缓存的结果构建response，主要是用于后续的统一回调处理
                Response<?> response = request.parseNetworkResponse(
                        new NetworkResponse(entry.data, entry.responseHeaders));
                request.addMarker("cache-hit-parsed");

                //当前没有完全过期
                if (!entry.refreshNeeded()) {//并且当前还没有到缓存过期的时间
                    //直接使用缓存结果进行回调处理即可
                    mDelivery.postResponse(request, response);
                } else {//当前已经超过缓存过期的时间，不过却在额外延长的时间内
                    request.addMarker("cache-hit-refresh-needed");
                    request.setCacheEntry(entry);

                    response.intermediate = true;

                    final Request<?> finalRequest = request;
                    //此时还是认为缓存有效并且进行回调
                    //但是回调之后还会发出网络请求，用于刷新缓存数据
                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                //回调处理缓存之后，再次发起网络请求
                                mNetworkQueue.put(finalRequest);
                            } catch (InterruptedException e) {
                                // Not much we can do about this.
                            }
                        }
                    });
                }
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
            }
        }
    }
}
