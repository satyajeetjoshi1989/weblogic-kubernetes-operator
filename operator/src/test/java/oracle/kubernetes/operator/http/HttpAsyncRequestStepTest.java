// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.http;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.meterware.pseudoserver.HttpUserAgentTest;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.typeCompatibleWith;
import static org.hamcrest.junit.MatcherAssert.assertThat;

public class HttpAsyncRequestStepTest extends HttpUserAgentTest {

  private HttpResponseStepImpl responseStep = new HttpResponseStepImpl(null);
  private HttpRequest request;
  private Packet packet = new Packet();

  @Before
  public void setUp() throws Exception {
    request = HttpRequest.newBuilder().uri(new URL("http://nowhere").toURI()).build();
  }

  @After
  public void tearDown() {
  }

  @Test
  public void classImplementsStep() {
    assertThat(HttpAsyncRequestStep.class, typeCompatibleWith(Step.class));
  }

  @Test
  public void constructorReturnsInstanceLinkedToResponse() {
    HttpAsyncRequestStep requestStep = HttpAsyncRequestStep.createRequest(request, responseStep);

    assertThat(requestStep.getNext(), sameInstance(responseStep));
  }

  @Test
  public void whenRequestMade_suspendProcessing() {
    defineWebPage("target", "a value");
    HttpRequest request = HttpRequest.newBuilder(URI.create(super.getHostPath() + "/target")).GET().build();
    HttpAsyncRequestStep requestStep = HttpAsyncRequestStep.createRequest(request, null);

    NextAction action = requestStep.apply(packet);

    assertThat(action.isSuspended(), is(true));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> getResponse() {
    return packet.getSpi(HttpResponse.class);
  }
}

