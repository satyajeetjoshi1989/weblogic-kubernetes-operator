// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.http;

import java.net.http.HttpResponse;

abstract class HttpResponseStub implements HttpResponse<String> {

  private int statusCode;

  HttpResponseStub(int statusCode) {
    this.statusCode = statusCode;
  }

  @Override
  public int statusCode() {
    return statusCode;
  }
}
