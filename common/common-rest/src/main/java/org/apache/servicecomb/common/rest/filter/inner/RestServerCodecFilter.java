/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.common.rest.filter.inner;

import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static com.google.common.net.HttpHeaders.TRANSFER_ENCODING;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.apache.servicecomb.core.exception.Exceptions.exceptionToResponse;
import static org.apache.servicecomb.swagger.invocation.InvocationType.PRODUCER;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import javax.servlet.http.Part;

import org.apache.servicecomb.common.rest.HttpTransportContext;
import org.apache.servicecomb.common.rest.RestConst;
import org.apache.servicecomb.common.rest.codec.RestCodec;
import org.apache.servicecomb.common.rest.codec.produce.ProduceProcessor;
import org.apache.servicecomb.common.rest.definition.RestOperationMeta;
import org.apache.servicecomb.core.Invocation;
import org.apache.servicecomb.core.definition.OperationMeta;
import org.apache.servicecomb.core.filter.Filter;
import org.apache.servicecomb.core.filter.FilterMeta;
import org.apache.servicecomb.core.filter.FilterNode;
import org.apache.servicecomb.foundation.common.utils.AsyncUtils;
import org.apache.servicecomb.foundation.vertx.http.HttpServletRequestEx;
import org.apache.servicecomb.foundation.vertx.http.HttpServletResponseEx;
import org.apache.servicecomb.foundation.vertx.stream.BufferOutputStream;
import org.apache.servicecomb.swagger.invocation.Response;

import io.netty.buffer.Unpooled;

@FilterMeta(name = "rest-server-codec", invocationType = PRODUCER)
public class RestServerCodecFilter implements Filter {
  @Override
  public CompletableFuture<Response> onFilter(Invocation invocation, FilterNode nextNode) {
    return CompletableFuture.completedFuture(invocation)
        .thenCompose(this::decodeRequest)
        .thenCompose(nextNode::onFilter)
        .exceptionally(exception -> exceptionToResponse(invocation, exception, INTERNAL_SERVER_ERROR))
        .thenCompose(response -> encodeResponse(invocation, response));
  }

  protected CompletableFuture<Invocation> decodeRequest(Invocation invocation) {
    HttpTransportContext transportContext = invocation.getTransportContext();
    HttpServletRequestEx requestEx = transportContext.getRequestEx();

    OperationMeta operationMeta = invocation.getOperationMeta();
    RestOperationMeta restOperationMeta = operationMeta.getExtData(RestConst.SWAGGER_REST_OPERATION);
    Map<String, Object> swaggerArguments = RestCodec.restToArgs(requestEx, restOperationMeta);
    invocation.setSwaggerArguments(swaggerArguments);

    return CompletableFuture.completedFuture(invocation);
  }

  protected CompletableFuture<Response> encodeResponse(Invocation invocation, Response response) {
    HttpTransportContext transportContext = invocation.getTransportContext();
    ProduceProcessor produceProcessor = transportContext.getProduceProcessor();
    HttpServletResponseEx responseEx = transportContext.getResponseEx();
    boolean download = isDownloadFileResponseType(invocation, response);

    return encodeResponse(response, download, produceProcessor, responseEx);
  }

  @SuppressWarnings("deprecation")
  public static CompletableFuture<Response> encodeResponse(Response response, boolean download,
      ProduceProcessor produceProcessor, HttpServletResponseEx responseEx) {
    responseEx.setStatus(response.getStatusCode(), response.getReasonPhrase());
    copyHeadersToHttpResponse(response.getHeaders().getHeaderMap(), responseEx);

    if (download) {
      return CompletableFuture.completedFuture(response);
    }

    responseEx.setContentType(produceProcessor.getName() + "; charset=utf-8");
    try (BufferOutputStream output = new BufferOutputStream(Unpooled.compositeBuffer())) {
      produceProcessor.encodeResponse(output, response.getResult());

      responseEx.setBodyBuffer(output.getBuffer());

      return CompletableFuture.completedFuture(response);
    } catch (Throwable e) {
      return AsyncUtils.completeExceptionally(e);
    }
  }

  /**
   * Check whether this response is a downloaded file response,
   * according to the schema recorded in {@link org.apache.servicecomb.swagger.invocation.response.ResponsesMeta}
   * and response status code.
   * @return true if this response is a downloaded file, otherwise false.
   */
  public static boolean isDownloadFileResponseType(Invocation invocation, Response response) {
    return Part.class.isAssignableFrom(
        invocation.findResponseType(response.getStatusCode()).getRawClass());
  }

  public static void copyHeadersToHttpResponse(Map<String, List<Object>> headerMap, HttpServletResponseEx responseEx) {
    if (headerMap == null) {
      return;
    }

    for (Entry<String, List<Object>> entry : headerMap.entrySet()) {
      for (Object value : entry.getValue()) {
        if (!entry.getKey().equalsIgnoreCase(CONTENT_LENGTH)
            && !entry.getKey().equalsIgnoreCase(TRANSFER_ENCODING)) {
          responseEx.addHeader(entry.getKey(), String.valueOf(value));
        }
      }
    }
  }
}
