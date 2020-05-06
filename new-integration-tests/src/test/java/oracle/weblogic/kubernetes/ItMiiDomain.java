// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretReference;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.OperatorParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.annotations.tags.MustNotRunInParallel;
import oracle.weblogic.kubernetes.annotations.tags.Slow;
import oracle.weblogic.kubernetes.extensions.LoggedTest;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_CHART_DIR;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_DUMMY_VALUE;
import static oracle.weblogic.kubernetes.TestConstants.REPO_EMAIL;
import static oracle.weblogic.kubernetes.TestConstants.REPO_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_PASSWORD;
import static oracle.weblogic.kubernetes.TestConstants.REPO_REGISTRY;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_USERNAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.DOWNLOAD_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WDT_VERSION;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WIT_BUILD_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
import static oracle.weblogic.kubernetes.actions.TestActions.createDockerConfigJson;
import static oracle.weblogic.kubernetes.actions.TestActions.createDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.createMiiImage;
import static oracle.weblogic.kubernetes.actions.TestActions.createSecret;
import static oracle.weblogic.kubernetes.actions.TestActions.createServiceAccount;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultWitParams;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteImage;
import static oracle.weblogic.kubernetes.actions.TestActions.dockerLogin;
import static oracle.weblogic.kubernetes.actions.TestActions.dockerPush;
import static oracle.weblogic.kubernetes.actions.TestActions.execCommand;
import static oracle.weblogic.kubernetes.actions.TestActions.getOperatorImageName;
import static oracle.weblogic.kubernetes.actions.TestActions.installOperator;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.upgradeOperator;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.appAccessibleInPod;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.appAccessibleInPodKubectl;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.appNotAccessibleInPod;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.doesImageExist;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainResourceImagePatched;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.isHelmReleaseDeployed;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.operatorIsReady;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podImagePatched;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podReady;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.serviceExists;
import static oracle.weblogic.kubernetes.utils.FileUtils.checkDirectory;
import static oracle.weblogic.kubernetes.utils.FileUtils.cleanupDirectory;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// Test to create model in image domain and verify the domain started successfully
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Test to create model in image domain and start the domain")
@IntegrationTest
class ItMiiDomain implements LoggedTest {

  // mii constants
  private static final String WDT_MODEL_FILE = "model1-wls.yaml";
  private static final String MII_IMAGE_NAME = "mii-image";
  private static final String APP_NAME = "sample-app";

  // domain constants
  private static final String DOMAIN_VERSION = "v7";
  private static final String API_VERSION = "weblogic.oracle/" + DOMAIN_VERSION;
  
  // application constants
  private static final String APP_RESPONSE_V1 = "Hello World, you have reached server managed-server";
  private static final String APP_RESPONSE_V2 = "Hello World AGAIN, you have reached server managed-server";
  private static final String APP_RESPONSE_V3 = "How are you doing! You have reached server managed-server";

  private static final String READ_STATE_COMMAND = "/weblogic-operator/scripts/readState.sh";

  private static HelmParams opHelmParams = null;
  private static V1ServiceAccount serviceAccount = null;
  private String serviceAccountName = null;
  private static String opNamespace = null;
  private static String operatorImage = null;
  private static String domainNamespace = null;
  private static String domainNamespace1 = null;
  private static String domainNamespace2 = null;
  private static ConditionFactory withStandardRetryPolicy = null;
  private static ConditionFactory withQuickRetryPolicy = null;
  private static String dockerConfigJson = "";

  private String domainUid = "domain1";
  private String domainUid1 = "domain2";
  private String miiImagePatchAppV2 = null;
  private String miiImageAddSecondApp = null;
  private String miiImage = null;

  /**
   * Install Operator.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(3) List<String> namespaces) {
    // create standard, reusable retry/backoff policy
    withStandardRetryPolicy = with().pollDelay(2, SECONDS)
        .and().with().pollInterval(10, SECONDS)
        .atMost(6, MINUTES).await();

    // create a reusable quick retry policy
    withQuickRetryPolicy = with().pollDelay(0, SECONDS)
        .and().with().pollInterval(4, SECONDS)
        .atMost(10, SECONDS).await();

    // clean up the download directory so that we always get the latest
    // versions of the tools in every run of the test class.
    try {
      cleanupDirectory(DOWNLOAD_DIR);
    } catch (IOException | IllegalArgumentException e) {    
      logger.severe("Failed to cleanup the download directory " + DOWNLOAD_DIR, e);    
    }

    // get a new unique opNamespace
    logger.info("Creating unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Creating unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);
    assertNotNull(namespaces.get(2), "Namespace list is null");
    domainNamespace1 = namespaces.get(2);

    // Create a service account for the unique opNamespace
    logger.info("Creating service account");
    String serviceAccountName = opNamespace + "-sa";
    assertDoesNotThrow(() -> createServiceAccount(new V1ServiceAccount()
        .metadata(
            new V1ObjectMeta()
                .namespace(opNamespace)
                .name(serviceAccountName))));
    logger.info("Created service account: {0}", serviceAccountName);

    // get Operator image name
    operatorImage = getOperatorImageName();
    assertFalse(operatorImage.isEmpty(), "Operator image name can not be empty");
    logger.info("Operator image name {0}", operatorImage);

    // Create docker registry secret in the operator namespace to pull the image from repository
    logger.info("Creating docker registry secret in namespace {0}", opNamespace);
    JsonObject dockerConfigJsonObject = createDockerConfigJson(
        REPO_USERNAME, REPO_PASSWORD, REPO_EMAIL, REPO_REGISTRY);
    dockerConfigJson = dockerConfigJsonObject.toString();

    // Create the V1Secret configuration
    V1Secret repoSecret = new V1Secret()
        .metadata(new V1ObjectMeta()
            .name(REPO_SECRET_NAME)
            .namespace(opNamespace))
        .type("kubernetes.io/dockerconfigjson")
        .putDataItem(".dockerconfigjson", dockerConfigJson.getBytes());

    boolean secretCreated = assertDoesNotThrow(() -> createSecret(repoSecret),
        String.format("createSecret failed for %s", REPO_SECRET_NAME));
    assertTrue(secretCreated, String.format("createSecret failed while creating secret %s in namespace",
                  REPO_SECRET_NAME, opNamespace));

    // map with secret
    Map<String, Object> secretNameMap = new HashMap<String, Object>();
    secretNameMap.put("name", REPO_SECRET_NAME);
    // helm install parameters
    opHelmParams = new HelmParams()
        .releaseName(OPERATOR_RELEASE_NAME)
        .namespace(opNamespace)
        .chartDir(OPERATOR_CHART_DIR);

    // Operator chart values to override
    OperatorParams opParams =
        new OperatorParams()
            .helmParams(opHelmParams)
            .image(operatorImage)
            .imagePullSecrets(secretNameMap)
            .domainNamespaces(Arrays.asList(domainNamespace))
            .serviceAccount(serviceAccountName);

    // install Operator
    logger.info("Installing Operator in namespace {0}", opNamespace);
    assertTrue(installOperator(opParams),
        String.format("Operator install failed in namespace %s", opNamespace));
    logger.info("Operator installed in namespace {0}", opNamespace);

    // list helm releases matching Operator release name in operator namespace
    logger.info("Checking Operator release {0} status in namespace {1}",
        OPERATOR_RELEASE_NAME, opNamespace);
    assertTrue(isHelmReleaseDeployed(OPERATOR_RELEASE_NAME, opNamespace),
        String.format("Operator release %s is not in deployed status in namespace %s",
            OPERATOR_RELEASE_NAME, opNamespace));
    logger.info("Operator release {0} status is deployed in namespace {1}",
        OPERATOR_RELEASE_NAME, opNamespace);

    // check operator is running
    logger.info("Check Operator pod is running in namespace {0}", opNamespace);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for operator to be running in namespace {0} "
                    + "(elapsed time {1}ms, remaining time {2}ms)",
                opNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(operatorIsReady(opNamespace));

  }

  @Test
  @Order(1)
  @DisplayName("Create model in image domain")
  @Slow
  @MustNotRunInParallel
  public void testCreateMiiDomain() {
    // admin/managed server name here should match with model yaml in WDT_MODEL_FILE
    final String adminServerPodName = domainUid + "-admin-server";
    final String managedServerPrefix = domainUid + "-managed-server";
    final int replicaCount = 2;

    // create image with model files
    miiImage = createInitialDomainImage();

    // push the image to a registry to make the test work in multi node cluster
    pushImageIfNeeded(miiImage);

    // Create the repo secret to pull the image
    assertDoesNotThrow(() -> createRepoSecret(domainNamespace),
            String.format("createSecret failed for %s", REPO_SECRET_NAME));

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    assertDoesNotThrow(() -> createDomainSecret(adminSecretName,"weblogic",
            "welcome1", domainNamespace),
            String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createDomainSecret(encryptionSecretName, "weblogicenc",
            "weblogicenc", domainNamespace),
             String.format("createSecret failed for %s", encryptionSecretName));

    // create the domain CR
    createDomainResource(domainUid, domainNamespace, adminSecretName, REPO_SECRET_NAME,
              encryptionSecretName, replicaCount);

    // wait for the domain to exist
    logger.info("Check for domain custom resource in namespace {0}", domainNamespace);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for domain {0} to be created in namespace {1} "
                    + "(elapsed time {2}ms, remaining time {3}ms)",
                domainUid,
                domainNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(domainExists(domainUid, DOMAIN_VERSION, domainNamespace));


    // check admin server pod exists
    logger.info("Check for admin server pod {0} existence in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodCreated(adminServerPodName, domainUid, domainNamespace);

    // check managed server pods exist
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check for managed server pod {0} existence in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodCreated(managedServerPrefix + i, domainUid, domainNamespace);
    }

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    logger.info("Check admin server status by calling read state command");
    checkServerReadyStatusByExec(adminServerPodName, domainNamespace);

    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReady(managedServerPrefix + i, domainUid, domainNamespace);
    }

    logger.info("Check admin service {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkServiceCreated(adminServerPodName, domainNamespace);

    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed server service {0} is created in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkServiceCreated(managedServerPrefix + i, domainNamespace);
    }
    
    // check and wait for the application to be accessible in all server pods
    for (int i = 1; i <= replicaCount; i++) {
      checkAppRunning(
          domainNamespace,
          managedServerPrefix + i,
          "8001",
          "sample-war/index.jsp",
          APP_RESPONSE_V1 + i);
    }
 
    logger.info("Domain {0} is fully started - servers are running and application is available",
        domainUid);
  }

  @Test
  @Order(2)
  @DisplayName("Create a second domain with the image from the the first test")
  @Slow
  @MustNotRunInParallel
  public void testCreateMiiSecondDomainDiffNSSameImage() {
    // admin/managed server name here should match with model yaml in WDT_MODEL_FILE
    final String adminServerPodName = domainUid1 + "-admin-server";
    final String managedServerPrefix = domainUid1 + "-managed-server";
    final int replicaCount = 2;

    OperatorParams opParams =
            new OperatorParams()
                    .helmParams(opHelmParams)
                    .image(operatorImage)
                    .domainNamespaces(Arrays.asList(domainNamespace,domainNamespace1))
                    .serviceAccount(serviceAccountName);

    // upgrade Operator
    logger.info("Upgrading Operator in namespace {0}", opNamespace);
    assertTrue(upgradeOperator(opParams),
            String.format("Operator upgrade failed in namespace %s", opNamespace));
    logger.info("Operator upgraded in namespace {0}", opNamespace);

    // Create the repo secret to pull the image
    assertDoesNotThrow(() -> createRepoSecret(domainNamespace1),
              String.format("createSecret failed for %s", REPO_SECRET_NAME));

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = domainUid1 + "-weblogic-credentials";
    assertDoesNotThrow(() -> createDomainSecret(adminSecretName,"weblogic",
            "welcome2", domainNamespace1),
            String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecretdomain2";
    assertDoesNotThrow(() -> createDomainSecret(encryptionSecretName, "weblogicencdomain2",
            "weblogicencdomain2", domainNamespace1),
             String.format("createSecret failed for %s", encryptionSecretName));

    // create the domain CR
    createDomainResource(domainUid1, domainNamespace1, adminSecretName, REPO_SECRET_NAME,
              encryptionSecretName, replicaCount);

    // wait for the domain to exist
    logger.info("Check for domain custom resource in namespace {0}", domainNamespace1);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for domain {0} to be created in namespace {1} "
                    + "(elapsed time {2}ms, remaining time {3}ms)",
                domainUid1,
                domainNamespace1,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(domainExists(domainUid1, DOMAIN_VERSION, domainNamespace1));


    // check admin server pod exists
    logger.info("Check for admin server pod {0} existence in namespace {1}",
            adminServerPodName, domainNamespace1);
    checkPodCreated(adminServerPodName, domainUid1, domainNamespace1);

    // check managed server pods exist
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check for managed server pod {0} existence in namespace {1}",
              managedServerPrefix + i, domainNamespace1);
      checkPodCreated(managedServerPrefix + i, domainUid1, domainNamespace1);
    }

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
            adminServerPodName, domainNamespace1);
    checkPodReady(adminServerPodName, domainUid1, domainNamespace1);

    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
              managedServerPrefix + i, domainNamespace1);
      checkPodReady(managedServerPrefix + i, domainUid1, domainNamespace1);
    }

    logger.info("Check admin service {0} is created in namespace {1}",
            adminServerPodName, domainNamespace1);
    checkServiceCreated(adminServerPodName, domainNamespace1);

    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed server service {0} is created in namespace {1}",
              managedServerPrefix + i, domainNamespace1);
      checkServiceCreated(managedServerPrefix + i, domainNamespace1);
    }
  }

  @Test
  @Order(3)
  @DisplayName("Create a domain with same domainUid as first domain but in a new namespace")
  @Slow
  @MustNotRunInParallel
  public void testCreateMiiDomainSameDomainUidDiffNS() {
    // admin/managed server name here should match with model yaml in WDT_MODEL_FILE
    final String adminServerPodName = domainUid + "-admin-server";
    final String managedServerPrefix = domainUid + "-managed-server";
    final int replicaCount = 2;

    // Create the repo secret to pull the image
    assertDoesNotThrow(() -> createRepoSecret(domainNamespace1),
            String.format("createSecret failed for %s", REPO_SECRET_NAME));

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = domainUid + "-weblogic-credentials";
    assertDoesNotThrow(() -> createDomainSecret(adminSecretName,"weblogic",
            "welcome3", domainNamespace1),
            String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecretdomain3";
    assertDoesNotThrow(() -> createDomainSecret(encryptionSecretName, "weblogicencdomain3",
            "weblogicencdomain3", domainNamespace1),
             String.format("createSecret failed for %s", encryptionSecretName));

    // create the domain CR
    createDomainResource(domainUid, domainNamespace1, adminSecretName, REPO_SECRET_NAME,
              encryptionSecretName, replicaCount);

    // wait for the domain to exist
    logger.info("Check for domain custom resource in namespace {0}", domainNamespace1);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for domain {0} to be created in namespace {1} "
                   + "(elapsed time {2}ms, remaining time {3}ms)",
                domainUid,
                domainNamespace1,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(domainExists(domainUid, DOMAIN_VERSION, domainNamespace1));


    // check admin server pod exists
    logger.info("Check for admin server pod {0} existence in namespace {1}",
            adminServerPodName, domainNamespace1);
    checkPodCreated(adminServerPodName, domainUid, domainNamespace1);

    // check managed server pods exist
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check for managed server pod {0} existence in namespace {1}",
              managedServerPrefix + i, domainNamespace1);
      checkPodCreated(managedServerPrefix + i, domainUid, domainNamespace1);
    }

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
            adminServerPodName, domainNamespace1);
    checkPodReady(adminServerPodName, domainUid, domainNamespace1);

    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
              managedServerPrefix + i, domainNamespace1);
      checkPodReady(managedServerPrefix + i, domainUid, domainNamespace1);
    }

    logger.info("Check admin service {0} is created in namespace {1}",
            adminServerPodName, domainNamespace1);
    checkServiceCreated(adminServerPodName, domainNamespace1);

    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed server service {0} is created in namespace {1}",
              managedServerPrefix + i, domainNamespace1);
      checkServiceCreated(managedServerPrefix + i, domainNamespace1);
    }
  }

  @Test
  @Order(4)
  @DisplayName("Update the sample-app application to version 2")
  @Slow
  @MustNotRunInParallel
  public void testPatchAppV2() {
    
    // application in the new image contains what is in the original application directory sample-app, 
    // plus the replacements or/and additions in the second application directory sample-app-2.
    final String appDir1 = "sample-app";
    final String appDir2 = "sample-app-2";
    final String adminServerPodName = domainUid + "-admin-server";
    final String managedServerPrefix = domainUid + "-managed-server";
    final int replicaCount = 2;
    
    // The verification of application's availability during patching is turned off
    // because it fails intermittently right now. It can be enabled using the following system property.
    // We'll remove the property and enable it all the time once the product problem (tracked
    // by owls-81575) is fixed.
    final String enableAppAvailbilityCheck = 
        System.getProperty("weblogic.operator.enableAppAvailabilityCheck", "false");
    Thread accountingThread = null;
    List<Integer> appAvailability = new ArrayList<Integer>();
    
    if (enableAppAvailbilityCheck.equalsIgnoreCase("true")) {
      // start a new thread to collect the availability data of the application while the
      // main thread performs patching operation, and checking of the results.
      accountingThread =
          new Thread(
              () -> {
                collectAppAvaiability(
                    domainNamespace,
                    appAvailability,
                    managedServerPrefix,
                    replicaCount,
                    "8001",
                    "sample-war/index.jsp");
              });
      accountingThread.start();
    }
   
    try {
      logger.info("Check that V1 application is still running");
      for (int i = 1; i <= replicaCount; i++) {
        quickCheckAppRunning(
            domainNamespace,
            managedServerPrefix + i,
            "8001",
            "sample-war/index.jsp",
            APP_RESPONSE_V1 + i);
      }
 
      logger.info("Check that the version 2 application is NOT running");
      for (int i = 1; i <= replicaCount; i++) {
        quickCheckAppNotRunning(
            domainNamespace,
            managedServerPrefix + i,
            "8001",
            "sample-war/index.jsp",
            APP_RESPONSE_V2 + i);   
      }
 
      logger.info("Create a new image with application V2");
      miiImagePatchAppV2 = updateImageWithAppV2Patch(
          String.format("%s-%s", MII_IMAGE_NAME, "test-patch-app-v2"),
          Arrays.asList(appDir1, appDir2));

      // push the image to a registry to make the test work in multi node cluster
      pushImageIfNeeded(miiImagePatchAppV2);

      // patch the domain resource with the new image and verify that the domain resource is patched, 
      // and all server pods are patched as well.
      logger.info("Patch domain resource with the new image, and verify the results");
      patchAndVerify(
          domainUid,
          domainNamespace,
          adminServerPodName,
          managedServerPrefix,
          replicaCount,
          miiImagePatchAppV2);

      logger.info("Check and wait for the V2 application to become available");
      for (int i = 1; i <= replicaCount; i++) {
        checkAppRunning(
            domainNamespace,
            managedServerPrefix + i,
            "8001",
            "sample-war/index.jsp",
            APP_RESPONSE_V2 + i);
      } 
    } finally {
    
      if (accountingThread != null) {
        try {
          accountingThread.join();
        } catch (InterruptedException ie) {
          // do nothing
        }
 
        // check the application availability data that we have collected, and see if
        // the application has been available all the time since the beginning of this test method
        logger.info("Verify that V2 application was available when domain {0} was being patched with image {1}",
            domainUid, miiImagePatchAppV2); 
        assertTrue(appAlwaysAvailable(appAvailability),
            String.format("Application V2 was not always available when domain %s was being patched with image %s",
                domainUid, miiImagePatchAppV2));
      }
    }
    
    logger.info("The version 2 application has been deployed correctly on all server Pods");
  }

  @Test
  @Order(5)
  @DisplayName("Update the domain with another application")
  @Slow
  @MustNotRunInParallel
  public void testAddSecondApp() {
    
    // the existing application is the combination of what are in appDir1 and appDir2 as in test case number 4,
    // the second application is in appDir3.
    final String appDir1 = "sample-app";
    final String appDir2 = "sample-app-2";
    final String appDir3 = "sample-app-3";
    final String adminServerPodName = domainUid + "-admin-server";
    final String managedServerPrefix = domainUid + "-managed-server";
    final int replicaCount = 2;

    logger.info("Check V2 application is still running after the previous test");
    for (int i = 1; i <= replicaCount; i++) {
      quickCheckAppRunning(
          domainNamespace,
          managedServerPrefix + i,
          "8001",
          "sample-war/index.jsp",
          APP_RESPONSE_V2 + i);
    }

    logger.info("Check that the new application is NOT already running");
    for (int i = 1; i <= replicaCount; i++) {
      quickCheckAppNotRunning(
          domainNamespace,
          managedServerPrefix + i,
          "8001",
          "sample-war-3/index.jsp",
          APP_RESPONSE_V3 + i);
    }
   
    logger.info("Create a new image that contains the additional application");
    miiImageAddSecondApp = updateImageWithSampleApp3(
        String.format("%s-%s", MII_IMAGE_NAME, "test-add-second-app"),
        Arrays.asList(appDir1, appDir2),
        Collections.singletonList(appDir3),
        "model2-wls.yaml");
    
    // push the image to a registry to make the test work in multi node cluster
    pushImageIfNeeded(miiImageAddSecondApp);
   
    // patch the domain resource with the new image and verify that the domain resource is patched, 
    // and all server pods are patched as well.
    logger.info("Patch the domain with the new image, and verify the result"); 
    patchAndVerify(
        domainUid,
        domainNamespace,
        adminServerPodName,
        managedServerPrefix,
        replicaCount,
        miiImageAddSecondApp);
    
    logger.info("Check and wait for the new application to become ready");
    for (int i = 1; i <= replicaCount; i++) {
      checkAppRunning(
          domainNamespace,
          managedServerPrefix + i,
          "8001",
          "sample-war-3/index.jsp",
          APP_RESPONSE_V3 + i);
    }
 
    logger.info("Check and wait for the original application V2 to become ready");
    for (int i = 1; i <= replicaCount; i++) {
      checkAppRunning(
          domainNamespace,
          managedServerPrefix + i,
          "8001",
          "sample-war/index.jsp",
          APP_RESPONSE_V2 + i);
    }

    logger.info("Both of the applications are running correctly after patching");
  }

  // This method is needed in this test class, since the cleanup util
  // won't cleanup the images.

  @AfterAll
  public void tearDownAll() {
    // Delete domain custom resource
    logger.info("Delete domain custom resource in namespace {0}", domainNamespace);
    assertDoesNotThrow(() -> deleteDomainCustomResource(domainUid, domainNamespace),
        "deleteDomainCustomResource failed with ApiException");
    logger.info("Deleted Domain Custom Resource " + domainUid + " from " + domainNamespace);

    logger.info("Delete domain custom resource in namespace {0}", domainNamespace1);
    assertDoesNotThrow(() -> deleteDomainCustomResource(domainUid1, domainNamespace1),
            "deleteDomainCustomResource failed with ApiException");
    logger.info("Deleted Domain Custom Resource " + domainUid1 + " from " + domainNamespace1);

    logger.info("Delete domain custom resource in namespace {0}", domainNamespace1);
    assertDoesNotThrow(() -> deleteDomainCustomResource(domainUid, domainNamespace1),
            "deleteDomainCustomResource failed with ApiException");
    logger.info("Deleted Domain Custom Resource " + domainUid + " from " + domainNamespace1);

    // delete the domain images created in the test class
    if (miiImage != null) {
      deleteImage(miiImage);
    }
    if (miiImagePatchAppV2 != null) {
      deleteImage(miiImagePatchAppV2);
    }
    if (miiImageAddSecondApp != null) {
      deleteImage(miiImageAddSecondApp);
    }
  }

  private void pushImageIfNeeded(String image) {
    // push the image to a registry to make the test work in multi node cluster
    if (!REPO_USERNAME.equals(REPO_DUMMY_VALUE)) {
      logger.info("docker login to registry {0}", REPO_REGISTRY);
      assertTrue(dockerLogin(REPO_REGISTRY, REPO_USERNAME, REPO_PASSWORD), "docker login failed");

      logger.info("docker push image {0} to registry {1}", image, REPO_REGISTRY);
      assertTrue(dockerPush(image), String.format("docker push failed for image %s", image));
    }
  }

  private String createUniqueImageTag() {
    // create unique image name with date
    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    Date date = new Date();
    return dateFormat.format(date) + "-" + System.currentTimeMillis();
  }

  private String createImageName(String baseImageName) {
    // Add repository name in image name for Jenkins runs
    return REPO_USERNAME.equals(REPO_DUMMY_VALUE) ? baseImageName : REPO_NAME + baseImageName;
  }

  private String createInitialDomainImage() {
    // build the model file list
    final List<String> modelList = 
        Collections.singletonList(String.format("%s/%s", MODEL_DIR, WDT_MODEL_FILE));

    // build an application archive using what is in resources/apps/APP_NAME
    assertTrue(buildAppArchive(defaultAppParams()
        .srcDirList(Collections.singletonList(APP_NAME))), 
        String.format("Failed to create application archive for %s", APP_NAME));

    // build the archive list
    List<String> archiveList = 
        Collections.singletonList(String.format("%s/%s.zip", ARCHIVE_DIR, APP_NAME));

    return createImageAndVerify(
      createImageName(MII_IMAGE_NAME),
      createUniqueImageTag(),
      modelList,
      archiveList);
  }
  
  private String updateImageWithAppV2Patch(
      String baseImageName,
      List<String> appDirList
  ) {
    // build the model file list
    List<String> modelList = 
        Collections.singletonList(String.format("%s/%s", MODEL_DIR, WDT_MODEL_FILE));
   
    // build an application archive
    assertTrue(
        buildAppArchive(
            defaultAppParams()
                .srcDirList(appDirList)),
        String.format("Failed to create application archive for %s",
            APP_NAME));

    // build the archive list
    List<String> archiveList = 
        Collections.singletonList(
            String.format("%s/%s.zip", ARCHIVE_DIR, APP_NAME));
    
    return createImageAndVerify(
      createImageName(baseImageName),
      createUniqueImageTag(),
      modelList,
      archiveList);
  }

  private String updateImageWithSampleApp3(
      String baseImageName,
      List<String> appDirList1,
      List<String> appDirList2,
      String modelFile
  ) {
    // build the model file list
    List<String> modelList = Collections.singletonList(MODEL_DIR + "/" + modelFile);
 
    String appName1 = appDirList1.get(0);
    String appName2 = appDirList2.get(0);
    
    // build an application archive that contains the existing application artifacts
    assertTrue(
        buildAppArchive(
            defaultAppParams()
                .srcDirList(appDirList1)
                .appName(appName1)),
        String.format("Failed to create application archive for %s",
            appName1));
    
    logger.info("Successfully created application zip file: " + appName1);
     
    // build an application archive that contains the new application artifacts
    assertTrue(
        buildAppArchive(
            defaultAppParams()
                .srcDirList(appDirList2)
                .appName(appName2)),
        String.format("Failed to create application archive for %s",
            appName2));
    
    logger.info("Successfully cteated application zip file: " + appName2); 
    
    // build the archive list with two zip files
    List<String> archiveList = Arrays.asList(
        String.format("%s/%s.zip", ARCHIVE_DIR, appName1),
        String.format("%s/%s.zip", ARCHIVE_DIR, appName2));
    
    return createImageAndVerify(
      createImageName(baseImageName),
      createUniqueImageTag(),
      modelList,
      archiveList);
  }

  /**
   * Patch the domain resource with a new image.
   * Here is an example of the JSON patch string that is constructed in this method.
   * [
   *   {"op": "replace", "path": "/spec/image", "value": "mii-image:v2" }
   * ]
   * 
   * @param domainResourceName name of the domain resource
   * @param namespace Kubernetes namespace that the domain is hosted
   * @param image name of the new image
   */
  private void patchDomainResourceImage(
      String domainResourceName,
      String namespace,
      String image
  ) {
    String patch = 
        String.format("[\n  {\"op\": \"replace\", \"path\": \"/spec/image\", \"value\": \"%s\"}\n]\n",
            image);
    logger.info("About to patch the domain resource {0} in namespace {1} with:{2}\n",
        domainResourceName, namespace, patch);

    assertTrue(patchDomainCustomResource(
            domainResourceName,
            namespace,
            new V1Patch(patch),
            V1Patch.PATCH_FORMAT_JSON_PATCH),
        String.format("Failed to patch the domain resource {0} in namespace {1} with image {2}",
            domainResourceName, namespace, image));
  }

  private String createImageAndVerify(
      String imageName,
      String imageTag,
      List<String> modelList,
      List<String> archiveList
  ) {
    final String image = String.format("%s:%s", imageName, imageTag);

    // Set additional environment variables for WIT
    checkDirectory(WIT_BUILD_DIR);
    Map<String, String> env = new HashMap<>();
    env.put("WLSIMG_BLDDIR", WIT_BUILD_DIR);

    // build an image using WebLogic Image Tool
    logger.info("Create image {0}:{1} using model directory {2}",
        imageName, imageTag, MODEL_DIR);
    boolean result = createMiiImage(
        defaultWitParams()
            .modelImageName(imageName)
            .modelImageTag(imageTag)
            .modelFiles(modelList)
            .modelArchiveFiles(archiveList)
            .wdtVersion(WDT_VERSION)
            .env(env)
            .redirect(true));

    assertTrue(result, String.format("Failed to create the image %s using WebLogic Image Tool", image));

    /* Check image exists using docker images | grep image tag.
     * Tag name is unique as it contains date and timestamp.
     * This is a workaround for the issue on Jenkins machine
     * as docker images imagename:imagetag is not working and
     * the test fails even though the image exists.
     */
    assertTrue(doesImageExist(imageTag),
        String.format("Image %s doesn't exist", image));

    return image;
  }

  private void createRepoSecret(String domNamespace) throws ApiException {
    V1Secret repoSecret = new V1Secret()
            .metadata(new V1ObjectMeta()
                    .name(REPO_SECRET_NAME)
                    .namespace(domNamespace))
            .type("kubernetes.io/dockerconfigjson")
            .putDataItem(".dockerconfigjson", dockerConfigJson.getBytes());

    boolean secretCreated = false;
    try {
      secretCreated = createSecret(repoSecret);
    } catch (ApiException e) {
      logger.info("Exception when calling CoreV1Api#createNamespacedSecret");
      logger.info("Status code: " + e.getCode());
      logger.info("Reason: " + e.getResponseBody());
      logger.info("Response headers: " + e.getResponseHeaders());
      //409 means that the secret already exists - it is not an error, so can proceed
      if (e.getCode() != 409) {
        throw e;
      } else {
        secretCreated = true;
      }

    }
    assertTrue(secretCreated, String.format("create secret failed for %s in namespace %s",
            REPO_SECRET_NAME, domNamespace));
  }

  private void createDomainSecret(String secretName, String username, String password, String domNamespace)
          throws ApiException {
    Map<String, String> secretMap = new HashMap();
    secretMap.put("username", username);
    secretMap.put("password", password);
    boolean secretCreated = assertDoesNotThrow(() -> createSecret(new V1Secret()
            .metadata(new V1ObjectMeta()
                    .name(secretName)
                    .namespace(domNamespace))
            .stringData(secretMap)), "Create secret failed with ApiException");
    assertTrue(secretCreated, String.format("create secret failed for %s in namespace %s", secretName, domNamespace));

  }

  private void createDomainResource(String domainUid, String domNamespace, String adminSecretName,
                                    String repoSecretName, String encryptionSecretName, int replicaCount) {
    // create the domain CR
    Domain domain = new Domain()
            .apiVersion(API_VERSION)
            .kind("Domain")
            .metadata(new V1ObjectMeta()
                    .name(domainUid)
                    .namespace(domNamespace))
            .spec(new DomainSpec()
                    .domainUid(domainUid)
                    .domainHomeSourceType("FromModel")
                    .image(miiImage)
                    .addImagePullSecretsItem(new V1LocalObjectReference()
                            .name(repoSecretName))
                    .webLogicCredentialsSecret(new V1SecretReference()
                            .name(adminSecretName)
                            .namespace(domNamespace))
                    .includeServerOutInPodLog(true)
                    .serverStartPolicy("IF_NEEDED")
                    .serverPod(new ServerPod()
                            .addEnvItem(new V1EnvVar()
                                    .name("JAVA_OPTIONS")
                                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                            .addEnvItem(new V1EnvVar()
                                    .name("USER_MEM_ARGS")
                                    .value("-Djava.security.egd=file:/dev/./urandom ")))
                    .adminServer(new AdminServer()
                            .serverStartState("RUNNING")
                            .adminService(new AdminService()
                                    .addChannelsItem(new Channel()
                                            .channelName("default")
                                            .nodePort(0))))
                    .addClustersItem(new Cluster()
                            .clusterName("cluster-1")
                            .replicas(replicaCount)
                            .serverStartState("RUNNING"))
                    .configuration(new Configuration()
                            .model(new Model()
                                    .domainType("WLS")
                                    .runtimeEncryptionSecret(encryptionSecretName))
                        .introspectorJobActiveDeadlineSeconds(300L)));

    logger.info("Create domain custom resource for domainUid {0} in namespace {1}",
            domainUid, domNamespace);
    boolean domCreated = assertDoesNotThrow(() -> createDomainCustomResource(domain),
            String.format("Create domain custom resource failed with ApiException for %s in namespace %s",
                    domainUid, domNamespace));
    assertTrue(domCreated, String.format("Create domain custom resource failed with ApiException "
                    + "for %s in namespace %s", domainUid, domNamespace));
  }

  private void checkPodCreated(String podName, String domainUid, String domNamespace) {
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for pod {0} to be created in namespace {1} "
                    + "(elapsed time {2}ms, remaining time {3}ms)",
                podName,
                domNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> podExists(podName, domainUid, domNamespace),
            String.format("podExists failed with ApiException for %s in namespace in %s",
                podName, domNamespace)));

  }

  private void patchAndVerify(
      final String domainUid,
      final String namespace,
      final String adminServerPodName,
      final String managedServerPrefix,
      final int replicaCount,
      final String image
  ) {
    logger.info(
        "Patch the domain resource {0} in namespace {1} to use the new image {2}",
        domainUid, namespace, image);

    patchDomainResourceImage(domainUid, namespace, image);
    
    logger.info(
        "Check that domain resource {0} in namespace {1} has been patched with image {2}",
        domainUid, namespace, image);
    checkDomainPatched(domainUid, namespace, image);

    // check and wait for the admin server pod to be patched with the new image
    logger.info(
        "Check that admin server pod for domain resource {0} in namespace {1} has been patched with image {2}",
        domainUid, namespace, image);

    checkPodImagePatched(
        domainUid,
        namespace,
        adminServerPodName,
        image);

    // check and wait for the managed server pods to be patched with the new image
    logger.info(
        "Check that server pods for domain resource {0} in namespace {1} have been patched with image {2}",
        domainUid, namespace, image);
    for (int i = 1; i <= replicaCount; i++) {
      checkPodImagePatched(
          domainUid,
          namespace,
          managedServerPrefix + i,
          image);
    }
  }


  private void checkPodReady(String podName, String domainUid, String domNamespace) {
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for pod {0} to be ready in namespace {1} "
                    + "(elapsed time {2}ms, remaining time {3}ms)",
                podName,
                domNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> podReady(podName, domainUid, domNamespace),
            String.format(
                "pod %s is not ready in namespace %s", podName, domNamespace)));

  }

  private void checkServiceCreated(String serviceName, String domNamespace) {
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for service {0} to be created in namespace {1} "
                    + "(elapsed time {2}ms, remaining time {3}ms)",
                serviceName,
                domNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> serviceExists(serviceName, null, domNamespace),
            String.format(
                "Service %s is not ready in namespace %s", serviceName, domNamespace)));

  }

  private void checkAppRunning(
      String namespace,
      String podName,
      String internalPort,
      String appPath,
      String expectedStr
  ) {
   
    // check if the application is accessible inside of a server pod using standard retry policy
    checkAppIsRunning(withStandardRetryPolicy, namespace, podName, internalPort, appPath, expectedStr);
  }
  
  private void quickCheckAppRunning(
      String namespace,
      String podName,
      String internalPort,
      String appPath,
      String expectedStr
  ) {
    // check if the application is accessible inside of a server pod using quick retry policy
    checkAppIsRunning(withQuickRetryPolicy, namespace, podName, internalPort, appPath, expectedStr);
  }

  private void checkAppIsRunning(
      ConditionFactory conditionFactory,
      String namespace,
      String podName,
      String internalPort,
      String appPath,
      String expectedStr
  ) {
   
    // check if the application is accessible inside of a server pod
    conditionFactory
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for application {0} is running on pod {1} in namespace {2} "
            + "(elapsed time {3}ms, remaining time {4}ms)",
            appPath,
            podName,
            namespace,
            condition.getElapsedTimeInMS(),
            condition.getRemainingTimeInMS()))
        .until(() -> appAccessibleInPod(
                namespace,
                podName, 
                internalPort, 
                appPath, 
                expectedStr));

  }
  
  private void quickCheckAppNotRunning(
      String namespace,
      String podName,
      String internalPort,
      String appPath,
      String expectedStr
  ) {
   
    // check if the application is not running inside of a server pod
    withQuickRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Checking if application {0} is not running on pod {1} in namespace {2} "
            + "(elapsed time {3}ms, remaining time {4}ms)",
            appPath,
            podName,
            namespace,
            condition.getElapsedTimeInMS(),
            condition.getRemainingTimeInMS()))
        .until(() -> appNotAccessibleInPod(
                namespace, 
                podName,
                internalPort, 
                appPath, 
                expectedStr));
  }
   
  private void checkDomainPatched(
      String domainUid,
      String namespace,
      String image 
  ) {
   
    // check if the domain resource has been patched with the given image
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for domain {0} to be patched in namespace {1} "
            + "(elapsed time {2}ms, remaining time {3}ms)",
            domainUid,
            namespace,
            condition.getElapsedTimeInMS(),
            condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> domainResourceImagePatched(domainUid, namespace, image),
            String.format(
               "Domain %s is not patched in namespace %s with image %s", domainUid, namespace, image)));

  }
  
  private void checkPodImagePatched(
      String domainUid,
      String namespace,
      String podName,
      String image
  ) {
   
    // check if the server pod has been patched with the given image
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for pod {0} to be patched in namespace {1} "
            + "(elapsed time {2}ms, remaining time {3}ms)",
            podName,
            namespace,
            condition.getElapsedTimeInMS(),
            condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> podImagePatched(domainUid, namespace, podName, "weblogic-server", image),
            String.format(
               "Pod %s is not patched with image %s in namespace %s.",
               podName,
               image,
               namespace)));
  }
  
  private static void collectAppAvaiability(
      String namespace,
      List<Integer> appAvailability,
      String managedServerPrefix,
      int replicaCount,
      String internalPort,
      String appPath
  ) {
    boolean v2AppAvailable = false;
 
    // Access the pod periodically to check application's availability across the duration
    // of patching the domain with newer version of the application.
    // Note: we use the "kubectl exec" command in this method only. This is to avoid
    // problems when two threads accessing the same pod at the same time via Kubernetes
    // Java client.
    while (!v2AppAvailable)  {
      v2AppAvailable = true;
      for (int i = 1; i <= replicaCount; i++) {
        v2AppAvailable = v2AppAvailable && appAccessibleInPodKubectl(
                            namespace,
                            managedServerPrefix + i, 
                            internalPort, 
                            appPath, 
                            APP_RESPONSE_V2 + i);
      }

      int count = 0;
      for (int i = 1; i <= replicaCount; i++) {
        if (appAccessibleInPodKubectl(
            namespace,
            managedServerPrefix + i, 
            internalPort, 
            appPath, 
            "Hello World")) {  
          count++;
        }
      }
      appAvailability.add(count);
      
      // the following log messages are temporarily here for debugging purposes.
      // This part of the code is disabled by default right now, and can be enabled by
      // -Dweblogic.operator.enableAppAvailabilityCheck=true.
      // TODO remove these log messages when this verification is fully enabled.
      if (count == 0) {
        logger.info("XXXXXXXXXXX: application not available XXXXXXXX");
      } else {
        logger.info("YYYYYYYYYYY: application available YYYYYYYY count = " + count);   
      }
      try {
        TimeUnit.MILLISECONDS.sleep(200);
      } catch (InterruptedException ie) {
        // do nothing
      }
    }
  }
  
  private static boolean appAlwaysAvailable(List<Integer> appAvailability) {
    for (Integer count: appAvailability) {
      if (count == 0) {
        logger.warning("Application was not available during patching.");
        return false;
      }
    }
    return true;
  }

  private void checkServerReadyStatusByExec(String podName, String namespace) {
    ExecResult execResult = assertDoesNotThrow(
        () -> execCommand(namespace, podName, null, true, READ_STATE_COMMAND));
    if (execResult.exitValue() == 0) {
      logger.info("execResult: " + execResult);
      assertEquals("RUNNING", execResult.stdout(),
          "Expected " + podName + ", in namespace " + namespace + ", to be in RUNNING ready status");
    } else {
      fail("Read state command failed with exit status code: " + execResult.exitValue());
    }
  }
}
