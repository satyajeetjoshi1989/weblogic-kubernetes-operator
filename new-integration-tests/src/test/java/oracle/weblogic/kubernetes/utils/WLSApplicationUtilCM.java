// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapVolumeSource;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import oracle.weblogic.kubernetes.TestConstants;
import org.awaitility.core.ConditionFactory;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.KIND_REPO;
import static oracle.weblogic.kubernetes.TestConstants.OCR_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLS_BASE_IMAGE_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLS_BASE_IMAGE_TAG;
import static oracle.weblogic.kubernetes.actions.TestActions.createConfigMap;
import static oracle.weblogic.kubernetes.actions.TestActions.createNamespacedJob;
import static oracle.weblogic.kubernetes.actions.TestActions.getJob;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodLog;
import static oracle.weblogic.kubernetes.actions.TestActions.listPods;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.jobCompleted;
import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Utility class to deploy application to WebLogic server.
 */
public class WLSApplicationUtilCM {

  private static String image = WLS_BASE_IMAGE_NAME + ":" + WLS_BASE_IMAGE_TAG;
  private static boolean isUseSecret = true;
  private static final String MOUNT_POINT = "/deployScripts/";
  private static final String DEPLOY_SCRIPT = "application_deploymentcm.py";
  private static final String DOMAIN_PROPERTIES = "domain.properties";

  private static final ConditionFactory withStandardRetryPolicy
      = with().pollDelay(2, SECONDS)
          .and().with().pollInterval(10, SECONDS)
          .atMost(5, MINUTES).await();

  /**
   * Deploy application.
   *
   * @param host name of the admin server host
   * @param port node port of admin server
   * @param userName admin server user name
   * @param password admin server password
   * @param targets list of targets to deploy applications
   * @param archivePath path of the application archive
   * @param namespace name of the namespace in which server pods running
   */
  public static void deployApplication(String host, String port, String userName,
      String password, String targets, Path archivePath, String namespace) {

    setImageName();

    // create a temporary WebLogic domain property file
    File domainPropertiesFile = assertDoesNotThrow(() -> File.createTempFile("domain", "properties"),
        "Creating domain properties file failed");
    Properties p = new Properties();
    p.setProperty("node_archive_path", MOUNT_POINT + archivePath.getFileName());
    p.setProperty("admin_host", host);
    p.setProperty("admin_port", port);
    p.setProperty("admin_username", userName);
    p.setProperty("admin_password", password);
    p.setProperty("targets", targets);
    assertDoesNotThrow(() -> p.store(new FileOutputStream(domainPropertiesFile), "wlst properties file"),
        "Failed to write the domain properties to file");

    // WLST py script for deploying application
    Path deployScript = Paths.get(RESOURCE_DIR, "python-scripts", "application_deploymentcm.py");

    logger.info("Creating a config map to hold deploy scripts");
    String deployScriptConfigMapName = "create-deploy-scripts-cm";

    Map<String, String> data = new HashMap<>();
    Map<String, byte[]> binaryData = new HashMap<>();
    assertDoesNotThrow(() -> {
      data.put(deployScript.getFileName().toString(), Files.readString(deployScript));
      data.put(DOMAIN_PROPERTIES, Files.readString(domainPropertiesFile.toPath()));
      binaryData.put(archivePath.getFileName().toString(),
          Base64.getMimeEncoder().encode(Files.readAllBytes(archivePath)));
    });

    V1ObjectMeta meta = new V1ObjectMeta()
        .name(deployScriptConfigMapName)
        .namespace(namespace);
    V1ConfigMap configMap = new V1ConfigMap()
        .data(data)
        .binaryData(binaryData)
        .metadata(meta);

    boolean cmCreated = assertDoesNotThrow(() -> createConfigMap(configMap),
        String.format("Failed to create configmap %s with files", configMap));
    assertTrue(cmCreated, String.format("Failed while creating ConfigMap %s", configMap));

    // deploy application with deploy scripts and domain properties on persistent volume
    deploy(namespace, deployScriptConfigMapName);
  }

  private static void deploy(String namespace, String deployScriptConfigMapName) {
    logger.info("Preparing to run deploy job using WLST");
    // create a V1Container with specific scripts and properties for creating domain
    V1Container jobCreationContainer = new V1Container()
        .addCommandItem("/bin/sh")
        .addArgsItem("/u01/oracle/oracle_common/common/bin/wlst.sh")
        .addArgsItem(MOUNT_POINT + "/" + DEPLOY_SCRIPT) //wlst deploy py script
        .addArgsItem("-skipWLSModuleScanning")
        .addArgsItem("-loadProperties")
        .addArgsItem(MOUNT_POINT + "/" + DOMAIN_PROPERTIES); //domain property file

    logger.info("Running a Kubernetes job to deploy");
    assertDoesNotThrow(()
        -> createDeployJob(deployScriptConfigMapName, namespace, jobCreationContainer),
        "Deployment failed");

  }

  /**
   * Create a job to create a domain in persistent volume.
   *
   * @param pvName name of the persistent volume to create domain in
   * @param pvcName name of the persistent volume claim
   * @param domainScriptCM configmap holding domain creation script files
   * @param namespace name of the domain namespace in which the job is created
   * @param jobContainer V1Container with job commands to create domain
   * @throws ApiException when Kubernetes cluster query fails
   */
  private static void createDeployJob(String deployScriptConfigMap, String namespace,
      V1Container jobContainer) throws ApiException {
    logger.info("Running Kubernetes job to create domain");

    V1Job jobBody = new V1Job()
        .metadata(
            new V1ObjectMeta()
                .name(namespace + "-deploy-job")
                .namespace(namespace))
        .spec(new V1JobSpec()
            .backoffLimit(0) // try only once
            .template(new V1PodTemplateSpec()
                .spec(new V1PodSpec()
                    .restartPolicy("Never")
                    .containers(Arrays.asList(jobContainer
                        .name("deploy-application-container")
                        .image(image)
                        .imagePullPolicy("IfNotPresent")
                        .volumeMounts(Arrays.asList(
                            new V1VolumeMount()
                                .name("deploy-job-cm-volume") // deploy scripts volume
                                .mountPath(MOUNT_POINT))))) // mounted under /applications inside pod
                    .volumes(Arrays.asList(
                        new V1Volume()
                            .name("deploy-job-cm-volume") // domain creation scripts volume
                            .configMap(new V1ConfigMapVolumeSource()
                                .name(deployScriptConfigMap)))) //config map containing domain scripts
                    .imagePullSecrets(isUseSecret ? Arrays.asList(
                        new V1LocalObjectReference()
                            .name(OCR_SECRET_NAME))
                        : null))));
    String jobName = assertDoesNotThrow(()
        -> createNamespacedJob(jobBody), "Failed to create Job");

    logger.info("Checking if the build deploy job {0} completed in namespace {1}",
        jobName, namespace);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for job {0} to be completed in namespace {1} "
                + "(elapsed time {2} ms, remaining time {3} ms)",
                jobName,
                namespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(jobCompleted(jobName, null, namespace));

    // check job status and fail test if the job failed to create domain
    V1Job job = getJob(jobName, namespace);
    if (job != null) {
      V1JobCondition jobCondition = job.getStatus().getConditions().stream().filter(
          v1JobCondition -> "Failed".equalsIgnoreCase(v1JobCondition.getType()))
          .findAny()
          .orElse(null);
      if (jobCondition != null) {
        logger.severe("Job {0} failed to do deployment", jobName);
        List<V1Pod> pods = listPods(namespace, "job-name=" + jobName).getItems();
        if (!pods.isEmpty()) {
          logger.severe(getPodLog(pods.get(0).getMetadata().getName(), namespace));
          fail("Deployment job failed");
        }
      }
    }

  }

  private static void setImageName() {
    //determine if the tests are running in Kind cluster. if true use images from Kind registry
    if (KIND_REPO != null) {
      String kindRepoImage = KIND_REPO + image.substring(TestConstants.OCR_REGISTRY.length() + 1);
      logger.info("Using image {0}", kindRepoImage);
      image = kindRepoImage;
      isUseSecret = false;
    }
  }

}
