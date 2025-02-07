//
// Copyright 2024 Google LLC
//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.google.solutions.jitaccess.web.actions;

import com.google.auth.oauth2.TokenVerifier;
import com.google.solutions.jitaccess.core.AccessDeniedException;
import com.google.solutions.jitaccess.core.auth.UserId;
import com.google.solutions.jitaccess.core.catalog.Catalog;
import com.google.solutions.jitaccess.core.catalog.JustificationPolicy;
import com.google.solutions.jitaccess.core.catalog.ProjectId;
import com.google.solutions.jitaccess.core.catalog.TokenSigner;
import com.google.solutions.jitaccess.core.catalog.project.MpaProjectRoleCatalog;
import com.google.solutions.jitaccess.core.catalog.project.ProjectRole;
import com.google.solutions.jitaccess.core.catalog.project.ProjectRoleActivator;
import com.google.solutions.jitaccess.core.clients.ResourceManagerClient;
import com.google.solutions.jitaccess.web.LogAdapter;
import com.google.solutions.jitaccess.web.RuntimeEnvironment;
import com.google.solutions.jitaccess.web.TokenObfuscator;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class TestIntrospectActivationRequestAction {
  private static final UserId SAMPLE_USER = new UserId("user-1@example.com");
  private static final UserId SAMPLE_USER_2 = new UserId("user-2@example.com");
  private static final String SAMPLE_TOKEN = "eySAMPLE";

  @Test
  public void whenTokenInvalid_ThenActionThrowsException() throws Exception {
    var tokenSigner = Mockito.mock(TokenSigner.class);
    when(tokenSigner.verify(any(), eq(SAMPLE_TOKEN)))
      .thenThrow(new TokenVerifier.VerificationException("mock"));

    var action = new IntrospectActivationRequestAction(
      new LogAdapter(),
      Mockito.mock(RuntimeEnvironment.class),
      Mockito.mock(ProjectRoleActivator.class),
      Mockito.mock(Instance.class),
      tokenSigner);

    assertThrows(
      AccessDeniedException.class,
      () -> action.execute(Mocks.createIapPrincipalMock(
        SAMPLE_USER),
        TokenObfuscator.encode(SAMPLE_TOKEN)));
  }


  @Test
  public void whenCallerNotInvolvedInRequest_ThenActionThrowsException() throws Exception {
    var request = new ProjectRoleActivator(
      Mockito.mock(Catalog.class),
      Mockito.mock(ResourceManagerClient.class),
      Mockito.mock(JustificationPolicy.class),
      new ProjectRoleActivator.Options(1))
      .createMpaRequest(
        new MpaProjectRoleCatalog.UserContext(SAMPLE_USER),
        Set.of(new ProjectRole(new ProjectId("project-1"), "roles/mock")),
        Set.of(SAMPLE_USER_2),
        "a justification",
        Instant.now(),
        Duration.ofSeconds(60));

    var tokenSigner = Mockito.mock(TokenSigner.class);
    when(tokenSigner
      .verify(
        any(),
        eq(SAMPLE_TOKEN)))
      .thenReturn(request);

    var action = new IntrospectActivationRequestAction(
      new LogAdapter(),
      Mockito.mock(RuntimeEnvironment.class),
      Mockito.mock(ProjectRoleActivator.class),
      Mockito.mock(Instance.class),
      tokenSigner);

    assertThrows(
      AccessDeniedException.class,
      () -> action.execute(
        Mocks.createIapPrincipalMock(new UserId("other-party@example.com")),
        TokenObfuscator.encode(SAMPLE_TOKEN)));
  }

  @Test
  public void whenTokenValid_ThenActionSucceeds() throws Exception {
    var request = new ProjectRoleActivator(
      Mockito.mock(Catalog.class),
      Mockito.mock(ResourceManagerClient.class),
      Mockito.mock(JustificationPolicy.class),
      new ProjectRoleActivator.Options(1))
      .createMpaRequest(
        new MpaProjectRoleCatalog.UserContext(SAMPLE_USER),
        Set.of(new ProjectRole(new ProjectId("project-1"), "roles/mock")),
        Set.of(SAMPLE_USER_2),
        "a justification",
        Instant.now(),
        Duration.ofSeconds(60));

    var tokenSigner = Mockito.mock(TokenSigner.class);
    when(tokenSigner
      .verify(
        any(),
        eq(SAMPLE_TOKEN)))
      .thenReturn(request);

    var action = new IntrospectActivationRequestAction(
      new LogAdapter(),
      Mockito.mock(RuntimeEnvironment.class),
      Mockito.mock(ProjectRoleActivator.class),
      Mockito.mock(Instance.class),
      tokenSigner);

    var response =  action.execute(
      Mocks.createIapPrincipalMock(SAMPLE_USER),
      TokenObfuscator.encode(SAMPLE_TOKEN));

    assertEquals(request.requestingUser().email, response.beneficiary.email);
    assertIterableEquals(Set.of(SAMPLE_USER_2), request.reviewers());
    assertTrue(response.isBeneficiary);
    assertFalse(response.isReviewer);
    assertEquals(request.justification(), response.justification);
    assertEquals(1, response.items.size());
    assertEquals(request.id().toString(), response.items.get(0).activationId);
    assertEquals("project-1", response.items.get(0).projectId);
    assertEquals("ACTIVATION_PENDING", response.items.get(0).status.name());
    assertEquals(request.startTime().getEpochSecond(), response.items.get(0).startTime);
    assertEquals(request.endTime()  .getEpochSecond(), response.items.get(0).endTime);
  }
}
