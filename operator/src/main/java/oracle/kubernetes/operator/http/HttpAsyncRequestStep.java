// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.http;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

public class HttpAsyncRequestStep extends Step {

  private static Supplier<java.net.http.HttpClient> factory = HttpClient::newHttpClient;
  private final HttpRequest request;

  public HttpAsyncRequestStep(HttpRequest request, HttpResponseStep responseStep) {
    super(responseStep);
    this.request = request;
  }

  public static HttpAsyncRequestStep createRequest(HttpRequest request, HttpResponseStep responseStep) {
    return new HttpAsyncRequestStep(request, responseStep);
  }

  @Override
  public NextAction apply(Packet packet) {
    CompletableFuture<HttpResponse<String>> httpResponseCompletableFuture
          = factory.get().sendAsync(request, HttpResponse.BodyHandlers.ofString());
    return doSuspend(null);
  }
}
