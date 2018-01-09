/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

/*
 * call.excute()和enqueue()的同步和异步，其实描述的是Call是否在dispatcher中排队。排队就是异步，
 * 不排队就是同步。本身不论是excute()和enqueue()，都是通过getResponseWithInterceptorChain()
 * 这个同步方法来执行网络请求。
 *
 * 网络请求的实现过程，是一个interceptor chain的处理过程。connection复用应该是在interceptor中
 * 实现的。Call的排队是由dispatcher实现，和connection的复用是两码事儿。每一个Call会创建一个供自己
 * 使用的RealInterceptorChain对象。
 *
 * okhttp的主要部件：（猜测）
 * Client
 * Call
 * Dispatcher
 * InterceptorChain ->streamAllocation，connection啥的好像都是在各个chain中完成的。
 *
 * okHttp的执行流程：
 * Client初始化各种参数 -> Client生成RealCall -> RealCall.getResponseWithInterceptorChain()
 * -> InterceptorChain接管这个网络请求，并返回response。
 * 1.connection, streamAllocation, 这个那个的都是在每一个Interceptor中生成并使用的。
 *
 * interceptors的顺序：
 * 1.interceptors(自己配置的)
 * 2.retryAndFollowUpInterceptor
 * 2.BridgeInterceptor
 * 3.CacheInterceptor
 * 4.ConnectInterceptor
 * 5.networkInterceptor(自己配置的)
 * 6.CallServerInterceptor
 *
 * StreamAllocation的作用：
 * 1.寻找可用的connection：1)当前streamAllocation正在使用的connection，2)connectionPool中找一个
 *                        能用的，3）创建一个新的connection
 * 2.给connection分配stream
 * 3.将connection提供给call使用。
 *
 * connection里包含了socket
 *
 * InterceptorChain疑问：
 * 对于单个InterceptorChain对象来说，streamAllocation是创建全新的还是复用的？
 * connectionPool是全局的，一个okhttpclient对应一个connectionPool
 *
 * 对于单个OkHttpClient对象来说，哪些对象是全局的：
 * 1.connectionPool
 * 2.dispathcer
 *
 * 对于每一次网络请求，哪些对象需要重新创建
 * 1.request
 * 2.call
 * 3.InterceptorChain
 *
 */

final class RealCall implements Call {
  final OkHttpClient client;
  // 与当前Call的生命周期相同，用于全程记录Call的状态并进行控制。
  final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

  /**
   * There is a cycle between the {@link Call} and {@link EventListener} that makes this awkward.
   * This will be set after we create the call instance then create the event listener instance.
   */
  private EventListener eventListener;

  /** The application's original request unadulterated by redirects or auth headers. */
  final Request originalRequest;
  final boolean forWebSocket;

  // Guarded by this.
  private boolean executed;

  private RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.forWebSocket = forWebSocket;
    this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
  }

  static RealCall newRealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    // Safely publish the Call instance to the EventListener.
    RealCall call = new RealCall(client, originalRequest, forWebSocket);
    call.eventListener = client.eventListenerFactory().create(call);
    return call;
  }

  @Override public Request request() {
    return originalRequest;
  }

  @Override public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    eventListener.callStart(this);
    try {
      client.dispatcher().executed(this);
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      eventListener.callFailed(this, e);
      throw e;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  private void captureCallStackTrace() {
    Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
    retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
  }

  @Override public void enqueue(Callback responseCallback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    eventListener.callStart(this);
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public RealCall clone() {
    return RealCall.newRealCall(client, originalRequest, forWebSocket);
  }

  StreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    RealCall get() {
      return RealCall.this;
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        Response response = getResponseWithInterceptorChain();
        if (retryAndFollowUpInterceptor.isCanceled()) {
          signalledCallback = true;
          // 被取消的Call都会返回一个IOException，message为Canceled
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          // OkHttp的onResponse回调，针对的是传输层的请求成功来说的
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          eventListener.callFailed(RealCall.this, e);
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
        + (forWebSocket ? "web socket" : "call")
        + " to " + redactedUrl();
  }

  String redactedUrl() {
    return originalRequest.url().redact();
  }

  Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    interceptors.add(new CacheInterceptor(client.internalCache()));
    interceptors.add(new ConnectInterceptor(client));
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
        originalRequest, this, eventListener, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis());

    return chain.proceed(originalRequest);
  }
}
