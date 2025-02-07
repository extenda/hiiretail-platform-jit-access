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

package com.google.solutions.jitaccess.core.catalog.project;

import com.google.api.services.cloudasset.v1.model.Binding;
import com.google.api.services.directory.model.Member;
import com.google.common.base.Preconditions;
import com.google.solutions.jitaccess.core.*;
import com.google.solutions.jitaccess.core.auth.GroupId;
import com.google.solutions.jitaccess.core.auth.UserId;
import com.google.solutions.jitaccess.core.catalog.*;
import com.google.solutions.jitaccess.core.clients.AssetInventoryClient;
import com.google.solutions.jitaccess.core.clients.DirectoryGroupsClient;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Repository that uses the Asset Inventory API (without its
 * Policy Analyzer subset) to find entitlements.
 *
 * Entitlements as used by this class are role bindings that
 * are annotated with a special IAM condition (making the binding
 * "eligible").
 */
public class AssetInventoryRepository extends ProjectRoleRepository {
  public static final String GROUP_PREFIX = "group:";
  public static final String USER_PREFIX = "user:";

  private final @NotNull Options options;
  private final @NotNull Executor executor;
  private final @NotNull DirectoryGroupsClient groupsClient;
  private final @NotNull AssetInventoryClient assetInventoryClient;

  public AssetInventoryRepository(
    @NotNull Executor executor,
    @NotNull DirectoryGroupsClient groupsClient,
    @NotNull AssetInventoryClient assetInventoryClient,
    @NotNull Options options
  ) {
    Preconditions.checkNotNull(executor, "executor");
    Preconditions.checkNotNull(groupsClient, "groupsClient");
    Preconditions.checkNotNull(assetInventoryClient, "assetInventoryClient");
    Preconditions.checkNotNull(options, "options");

    this.executor = executor;
    this.groupsClient = groupsClient;
    this.assetInventoryClient = assetInventoryClient;
    this.options = options;
  }

  @NotNull List<Binding> findProjectBindings(
    @NotNull UserId user,
    ProjectId projectId
  ) throws AccessException, IOException {
    //
    // Lookup in parallel:
    // - the effective set of IAM policies applying to this project. This
    //   includes the IAM policy of the project itself, plus any policies
    //   applied to its ancestry (folders, organization).
    // - groups that the user is a member of.
    //
    var listMembershipsFuture = ThrowingCompletableFuture.submit(
      () -> this.groupsClient.listDirectGroupMemberships(user),
      this.executor);

    var effectivePoliciesFuture = ThrowingCompletableFuture.submit(
      () -> this.assetInventoryClient.getEffectiveIamPolicies(
        this.options.scope(),
        projectId),
      this.executor);

    var principalSetForUser = new PrincipalSet(
      user,
      ThrowingCompletableFuture
        .awaitAndRethrow(listMembershipsFuture)
        .stream()
        .map(g -> new GroupId(g.getEmail()))
        .collect(Collectors.toList()));

    return ThrowingCompletableFuture.awaitAndRethrow(effectivePoliciesFuture)
      .stream()

      // All bindings, across all resources in the ancestry.
      .flatMap(policy -> policy.getPolicy().getBindings().stream())

      // Only bindings that apply to the user.
      .filter(binding -> principalSetForUser.isMember(binding))
      .collect(Collectors.toList());
  }

  //---------------------------------------------------------------------------
  // ProjectRoleRepository.
  //---------------------------------------------------------------------------

  @Override
  public @NotNull SortedSet<ProjectId> findProjectsWithEntitlements(
    @NotNull UserId user
  ) {
    //
    // Not supported.
    //
    throw new IllegalStateException(
      "Feature is not supported. Use search to determine available projects");
  }

  @Override
  public @NotNull EntitlementSet<ProjectRole> findEntitlements(
    @NotNull UserId user,
    @NotNull ProjectId projectId,
    @NotNull EnumSet<ActivationType> typesToInclude
  ) throws AccessException, IOException {

    List<Binding> allBindings = findProjectBindings(user, projectId);

    //
    // Find all JIT-eligible role bindings. The bindings are
    // conditional and have a special condition that serves
    // as marker.
    //
    Set<Entitlement<ProjectRole>> jitEligible;
    if (typesToInclude.contains(ActivationType.JIT)) {
      jitEligible = allBindings.stream()
        .map(binding -> ProjectRole.fromJitEligibleRoleBinding(projectId, binding))
        .filter(projectRole -> projectRole.isPresent())
        .map(Optional::get)
        .map(projectRole -> new Entitlement<>(
          projectRole,
          projectRole.role(),
          ActivationType.JIT))
        .collect(Collectors.toSet());
    }
    else {
      jitEligible = Set.of();
    }

    //
    // Find all MPA-eligible role bindings. The bindings are
    // conditional and have a special condition that serves
    // as marker.
    //
    Set<Entitlement<ProjectRole>> mpaEligible;
    if (typesToInclude.contains(ActivationType.MPA)) {
      mpaEligible = allBindings.stream()
        .map(binding -> ProjectRole.fromMpaEligibleRoleBinding(projectId, binding))
        .filter(role -> role.isPresent())
        .map(Optional::get)
        .map(projectRole -> new Entitlement<>(
          projectRole,
          projectRole.role(),
          ActivationType.MPA))
        .collect(Collectors.toSet());
    }
    else {
      mpaEligible = Set.of();
    }

    //
    // Determine effective set of eligible roles. If a role is both JIT- and
    // MPA-eligible, only retain the JIT-eligible one.
    //
    var allAvailable = new TreeSet<Entitlement<ProjectRole>>();

    allAvailable.addAll(jitEligible);
    allAvailable.addAll(mpaEligible
      .stream()
      .filter(r -> jitEligible.stream().noneMatch(a -> a.id().equals(r.id()))).toList());

    //
    // Find temporary bindings that reflect activations and sort out which
    // ones are still active and which ones have expired.
    //
    var currentActivations = new HashMap<ProjectRole, Activation>();
    var expiredActivations = new HashMap<ProjectRole, Activation>();

    for (var activatedProjectRole : allBindings.stream()
      .map(binding -> ProjectRole.fromActivationRoleBinding(projectId, binding))
      .filter(Optional::isPresent)
      .map(Optional::get)
      .toList()) {

      var now = Instant.now();
      if (activatedProjectRole.activation().isValid(now)) {
        currentActivations.put(activatedProjectRole.projectRole(), activatedProjectRole.activation());
      }
      else {
        expiredActivations.put(activatedProjectRole.projectRole(), activatedProjectRole.activation());
      }
    }

    return new EntitlementSet<>(allAvailable, currentActivations, expiredActivations, Set.of());
  }

  @Override
  public @NotNull Set<UserId> findEntitlementHolders(
    @NotNull ProjectRole projectRole,
    @NotNull ActivationType activationType
  ) throws AccessException, IOException {

    var policies = this.assetInventoryClient.getEffectiveIamPolicies(
      this.options.scope,
      projectRole.projectId());

    var principals = policies
      .stream()

      // All bindings, across all resources in the ancestry.
      .flatMap(policy -> policy.getPolicy().getBindings().stream())

      // Only consider requested role.
      .filter(binding -> binding.getRole().equals(projectRole.role()))

      // Only consider eligible, equivalent bindings.
      .filter(binding -> {
          var heldProjectRole = switch (activationType) {
            case JIT -> ProjectRole.fromJitEligibleRoleBinding(projectRole.projectId(), binding);
            case MPA -> ProjectRole.fromMpaEligibleRoleBinding(projectRole.projectId(), binding);
          };

          return heldProjectRole.isPresent() && heldProjectRole.get().equals(projectRole);
        })

      .flatMap(binding -> binding.getMembers().stream())
      .collect(Collectors.toSet());

    var allUserMembers = principals.stream()
      .filter(p -> p.startsWith(USER_PREFIX))
      .map(p -> p.substring(USER_PREFIX.length()))
      .distinct()
      .map(email -> new UserId(email))
      .collect(Collectors.toSet());

    //
    // Resolve groups.
    //
    List<CompletableFuture<Collection<Member>>> listMembersFutures = principals.stream()
      .filter(p -> p.startsWith(GROUP_PREFIX))
      .map(p -> p.substring(GROUP_PREFIX.length()))
      .distinct()
      .map(groupEmail -> ThrowingCompletableFuture.submit(
        () -> {
          try {
            return this.groupsClient.listDirectGroupMembers(groupEmail);
          }
          catch (AccessDeniedException e) {
            //
            // Access might be denied if this is an external group,
            // but this is okay.
            //
            return List.<Member>of();
          }
        },
        this.executor))
      .toList();

    var allMembers = new HashSet<>(allUserMembers);

    for (var listMembersFuture : listMembersFutures) {
      var members = ThrowingCompletableFuture.awaitAndRethrow(listMembersFuture)
        .stream()
        .map(m -> new UserId(m.getEmail())).toList();
      allMembers.addAll(members);
    }

    return allMembers;
  }

  // -------------------------------------------------------------------------
  // Inner classes.
  // -------------------------------------------------------------------------

  static class PrincipalSet {
    /**
     * Principal identifiers in the format
     *   user:email
     *   group:email
     *
     * Where the email is in canonical format (i.e., lower-case).
     */
    private final @NotNull Set<String> principalIdentifiers;

    public PrincipalSet(
      @NotNull UserId user,
      @NotNull Collection<GroupId> groups
    ) {
      this.principalIdentifiers = groups
        .stream()
        .map(g -> String.format("group:%s", g.email))
        .collect(Collectors.toSet());
      this.principalIdentifiers.add(String.format("user:%s", user.email));
    }

    public boolean isMember(@NotNull Binding binding) {
      return binding.getMembers()
        .stream()
        .map(String::toLowerCase)
        .anyMatch(member -> this.principalIdentifiers.contains(member));
    }
  }

  /**
   * @param scope Scope to use for queries.
   */
  public record Options(
    @NotNull String scope
  ) {

    public Options {
      Preconditions.checkNotNull(scope, "scope");
    }
  }
}
