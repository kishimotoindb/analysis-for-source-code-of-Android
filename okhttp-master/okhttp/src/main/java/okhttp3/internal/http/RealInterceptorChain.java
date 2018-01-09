/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;

import static okhttp3.internal.Util.checkDuration;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 */
public final class RealInterceptorChain implements Interceptor.Chain {
  /*
   * 下面这些变量在整个Chain的处理过程中都可能要被用到，那是这些变量里，streamAllocation，httpCodec，
   * connection都是在单个Interceptor中生成的。保存在chain中是为了在整个chain的处理过程中方便使用。
   *
   * 一旦chain开始proceed，这个Chain自身是一个完整的个体，拥有处理当前网络请求所需要的所有资源，
   * 可以实现整个网络请求的生命周期。Chain对外有一个入口，接收初始参数；一个出口，返回response。
   *
   * Chain的proceed是一个递归的调用过程，至于为什么，还不清楚，为什么不采用for循环的形式调用interceptors。
   * 递归的调用过程中，一旦某一层的interceptor获取到了response，就直接一层一层将response往上层传。
   */
  private final List<Interceptor> interceptors;

  //下面这三个变量是在Chain的proceed过程中创建或复用的。
  private final StreamAllocation streamAllocation;
  private final HttpCodec httpCodec;
  private final RealConnection connection;

  private final int index;
  private final Request request;
  private final Call call;
  private final EventListener eventListener;
  private final int connectTimeout;
  private final int readTimeout;
  private final int writeTimeout;
  // 递归调用chain的时候，一个chain只可以被调用一次，这个calls记录当前Chain的proceed方法被调用的次数
  private int calls;

  public RealInterceptorChain(List<Interceptor> interceptors, StreamAllocation streamAllocation,
      HttpCodec httpCodec, RealConnection connection, int index, Request request, Call call,
      EventListener eventListener, int connectTimeout, int readTimeout, int writeTimeout) {
    this.interceptors = interceptors;
    this.connection = connection;
    this.streamAllocation = streamAllocation;
    this.httpCodec = httpCodec;
    this.index = index;
    this.request = request;
    this.call = call;
    this.eventListener = eventListener;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.writeTimeout = writeTimeout;
  }

  @Override public Connection connection() {
    return connection;
  }

  @Override public int connectTimeoutMillis() {
    return connectTimeout;
  }

  @Override public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
    int millis = checkDuration("timeout", timeout, unit);
    return new RealInterceptorChain(interceptors, streamAllocation, httpCodec, connection, index,
        request, call, eventListener, millis, readTimeout, writeTimeout);
  }

  @Override public int readTimeoutMillis() {
    return readTimeout;
  }

  @Override public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
    int millis = checkDuration("timeout", timeout, unit);
    return new RealInterceptorChain(interceptors, streamAllocation, httpCodec, connection, index,
        request, call, eventListener, connectTimeout, millis, writeTimeout);
  }

  @Override public int writeTimeoutMillis() {
    return writeTimeout;
  }

  @Override public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) {
    int millis = checkDuration("timeout", timeout, unit);
    return new RealInterceptorChain(interceptors, streamAllocation, httpCodec, connection, index,
        request, call, eventListener, connectTimeout, readTimeout, millis);
  }

  public StreamAllocation streamAllocation() {
    return streamAllocation;
  }

  public HttpCodec httpStream() {
    return httpCodec;
  }

  @Override public Call call() {
    return call;
  }

  public EventListener eventListener() {
    return eventListener;
  }

  @Override public Request request() {
    return request;
  }

  /*
   * 核心方法，很简单，就是将request传入InterceptorChain，经过InterceptorChain处理后返回response
   * 1.RealCall刚开始执行的时候，是new了一个全新的chain，这时候streamAllocation、HttpCodec、connection
   * 都是null。
   *
   * 经过每一层的interceptor之后，这些需要被使用的对象会被逐个创建。
   *
   * 猜测，如果通过缓存机制就可以拿到response，那么可能就不会去创建或复用connection
   */
  @Override public Response proceed(Request request) throws IOException {
    return proceed(request, streamAllocation, httpCodec, connection);
  }

  public Response proceed(Request request, StreamAllocation streamAllocation, HttpCodec httpCodec,
      RealConnection connection) throws IOException {
    // 如果所有interceptor都获取到response，说明这个请求是有问题的。
    // 这个index在创建InterceptorChain对象的时候，初始化为0，然后每经过一个interceptor，index+1
    if (index >= interceptors.size()) throw new AssertionError();

    calls++;

    // If we already have a stream, confirm that the incoming request will use it.
    // httpCodec本质上就是stream，只不过是对stream的封装。
    // 这里httpCodec!=null，实际上就是chain里存在一个stream。既然存在stream，那么connection是一定
    // 存在的，所以这里connection.supportsUrl()在调用前没有对connection判空。
    // 这里的意思是说，chain中存在一个stream，那么当前的request就应该使用这个stream，而不应该再创建
    // 一个新的stream。但是如果这个存在的stream不支持当前request的url，说明某一层级的interceptor
    // 对request的url进行了修改，这种操作是不允许的。
    if (this.httpCodec != null && !this.connection.supportsUrl(request.url())) {
      throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
          + " must retain the same host and port");
    }

    // If we already have a stream, confirm that this is the only call to chain.proceed().
    if (this.httpCodec != null && calls > 1) {
      throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
          + " must call proceed() exactly once");
    }

    // Call the next interceptor in the chain.
    RealInterceptorChain next = new RealInterceptorChain(interceptors, streamAllocation, httpCodec,
        connection, index + 1, request, call, eventListener, connectTimeout, readTimeout,
        writeTimeout);
    Interceptor interceptor = interceptors.get(index);
    Response response = interceptor.intercept(next);

    // Confirm that the next interceptor made its required call to chain.proceed().
    if (httpCodec != null && index + 1 < interceptors.size() && next.calls != 1) {
      throw new IllegalStateException("network interceptor " + interceptor
          + " must call proceed() exactly once");
    }

    // Confirm that the intercepted response isn't null.
    if (response == null) {
      throw new NullPointerException("interceptor " + interceptor + " returned null");
    }

    if (response.body() == null) {
      throw new IllegalStateException(
          "interceptor " + interceptor + " returned a response with no body");
    }

    return response;
  }
}
