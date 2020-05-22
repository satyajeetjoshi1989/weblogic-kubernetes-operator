// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

/**
 * Keys in the generated domain config map.
 */
public interface DomainConfigMapKeys {

  /** The topology generated from the WebLogic domain. */
  String TOPOLOGY_YAML = "topology.yaml";

  /** An MD5 has of the Model-in-Image secrets. */
  String SECRETS_MD_5 = "secrets.md5";

  /** A hash computed from the WebLogic domain. */
  String DOMAINZIP_HASH = "domainzip_hash";

  /** The last value of the restartVersion field from the domain resource. */
  String DOMAIN_RESTART_VERSION = "weblogic.domainRestartVersion";

  /** A hash of the Model-in-Image inputs. */
  String DOMAIN_INPUTS_HASH = "weblogic.domainInputsHash";

  /** The latest recorded value of the configuration overrides, including secrets. */
  String CONFIGURATION_OVERRIDES = "configOverrides";
}
