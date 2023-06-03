/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.strimzi.api.kafka.model.CertAndKeySecretSource;
import io.strimzi.api.kafka.model.CruiseControlResources;
import io.strimzi.api.kafka.model.CruiseControlSpec;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaAuthorization;
import io.strimzi.api.kafka.model.KafkaAuthorizationKeycloak;
import io.strimzi.api.kafka.model.KafkaAuthorizationOpa;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaExporterResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaSpec;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationCustom;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuth;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.ContainerTemplate;
import io.strimzi.api.kafka.model.template.ExternalTrafficPolicy;
import io.strimzi.api.kafka.model.template.InternalServiceTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.api.kafka.model.template.ResourceTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.jmx.JmxModel;
import io.strimzi.operator.cluster.model.jmx.SupportsJmx;
import io.strimzi.operator.cluster.model.logging.LoggingModel;
import io.strimzi.operator.cluster.model.logging.SupportsLogging;
import io.strimzi.operator.cluster.model.metrics.MetricsModel;
import io.strimzi.operator.cluster.model.metrics.SupportsMetrics;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.cluster.operator.resource.KafkaSpecChecker;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlConfigurationParameters;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.MetricsAndLogging;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.kafka.common.Uuid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.strimzi.operator.cluster.model.CruiseControl.CRUISE_CONTROL_METRIC_REPORTER;
import static io.strimzi.operator.cluster.model.ListenersUtils.isListenerWithCustomAuth;
import static io.strimzi.operator.cluster.model.ListenersUtils.isListenerWithOAuth;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

/**
 * Kafka cluster model
 */
@SuppressWarnings({"checkstyle:ClassDataAbstractionCoupling", "checkstyle:ClassFanOutComplexity"})
public class KafkaCluster extends AbstractStatefulModel implements SupportsMetrics, SupportsLogging, SupportsJmx {
    protected static final String COMPONENT_TYPE = "kafka";

    protected static final String ENV_VAR_KAFKA_INIT_EXTERNAL_ADDRESS = "EXTERNAL_ADDRESS";
    /* test */ static final String ENV_VAR_STRIMZI_CLUSTER_ID = "STRIMZI_CLUSTER_ID";
    /* test */ static final String ENV_VAR_STRIMZI_KRAFT_ENABLED = "STRIMZI_KRAFT_ENABLED";
    private static final String ENV_VAR_KAFKA_METRICS_ENABLED = "KAFKA_METRICS_ENABLED";

    // For port names in services, a 'tcp-' prefix is added to support Istio protocol selection
    // This helps Istio to avoid using a wildcard listener and instead present IP:PORT pairs which effects
    // proper listener, routing and metrics configuration sent to Envoy
    /**
     * Port number used for replication
     */
    public static final int REPLICATION_PORT = 9091;
    protected static final String REPLICATION_PORT_NAME = "tcp-replication";
    protected static final int CONTROLPLANE_PORT = 9090;
    protected static final String CONTROLPLANE_PORT_NAME = "tcp-ctrlplane"; // port name is up to 15 characters

    /**
     * Port used by the Route listeners
     */
    public static final int ROUTE_PORT = 443;

    /**
     * Port used by the Ingress listeners
     */
    public static final int INGRESS_PORT = 443;

    protected static final String KAFKA_NAME = "kafka";
    protected static final String CLUSTER_CA_CERTS_VOLUME = "cluster-ca";
    protected static final String BROKER_CERTS_VOLUME = "broker-certs";
    protected static final String CLIENT_CA_CERTS_VOLUME = "client-ca-cert";
    protected static final String CLUSTER_CA_CERTS_VOLUME_MOUNT = "/opt/kafka/cluster-ca-certs";
    protected static final String BROKER_CERTS_VOLUME_MOUNT = "/opt/kafka/broker-certs";
    protected static final String CLIENT_CA_CERTS_VOLUME_MOUNT = "/opt/kafka/client-ca-certs";
    protected static final String TRUSTED_CERTS_BASE_VOLUME_MOUNT = "/opt/kafka/certificates";
    protected static final String CUSTOM_AUTHN_SECRETS_VOLUME_MOUNT = "/opt/kafka/custom-authn-secrets";
    private static final String DATA_VOLUME_MOUNT_PATH = "/var/lib/kafka";
    private static final String LOG_AND_METRICS_CONFIG_VOLUME_NAME = "kafka-metrics-and-logging";
    private static final String LOG_AND_METRICS_CONFIG_VOLUME_MOUNT = "/opt/kafka/custom-config/";

    private static final String KAFKA_METRIC_REPORTERS_CONFIG_FIELD = "metric.reporters";
    private static final String KAFKA_NUM_PARTITIONS_CONFIG_FIELD = "num.partitions";
    private static final String KAFKA_REPLICATION_FACTOR_CONFIG_FIELD = "default.replication.factor";

    protected static final String CO_ENV_VAR_CUSTOM_KAFKA_POD_LABELS = "STRIMZI_CUSTOM_KAFKA_LABELS";

    /**
     * Records the Kafka version currently running inside Kafka StrimziPodSet
     */
    public static final String ANNO_STRIMZI_IO_KAFKA_VERSION = Annotations.STRIMZI_DOMAIN + "kafka-version";

    /**
     * Records the used log.message.format.version
     */
    public static final String ANNO_STRIMZI_IO_LOG_MESSAGE_FORMAT_VERSION = Annotations.STRIMZI_DOMAIN + "log-message-format-version";

    /**
     * Records the used inter.broker.protocol.version
     */
    public static final String ANNO_STRIMZI_IO_INTER_BROKER_PROTOCOL_VERSION = Annotations.STRIMZI_DOMAIN + "inter-broker-protocol-version";

    /**
     * Records the state of the Kafka upgrade process. Unset outside of upgrades.
     */
    public static final String ANNO_STRIMZI_BROKER_CONFIGURATION_HASH = Annotations.STRIMZI_DOMAIN + "broker-configuration-hash";

    /**
     * Annotation for keeping certificate thumprints
     */
    public static final String ANNO_STRIMZI_CUSTOM_LISTENER_CERT_THUMBPRINTS = Annotations.STRIMZI_DOMAIN + "custom-listener-cert-thumbprints";

    /**
     * Key under which the broker configuration is stored in Config Map
     */
    public static final String BROKER_CONFIGURATION_FILENAME = "server.config";

    /**
     * Key under which the listener configuration is stored in Config Map
     */
    public static final String BROKER_LISTENERS_FILENAME = "listeners.config";

    // Cruise Control defaults
    private static final String CRUISE_CONTROL_DEFAULT_NUM_PARTITIONS = "1";
    private static final String CRUISE_CONTROL_DEFAULT_REPLICATION_FACTOR = "1";

    // Kafka configuration
    private int replicas;
    private Rack rack;
    private String initImage;
    private List<GenericKafkaListener> listeners;
    private KafkaAuthorization authorization;
    private KafkaVersion kafkaVersion;
    private CruiseControlSpec cruiseControlSpec;
    private String ccNumPartitions = null;
    private String ccReplicationFactor = null;
    private String ccMinInSyncReplicas = null;
    private boolean useKRaft = false;
    private String clusterId;
    private JmxModel jmx;
    private MetricsModel metrics;
    private LoggingModel logging;
    /* test */ KafkaConfiguration configuration;

    // Templates
    private PodDisruptionBudgetTemplate templatePodDisruptionBudget;
    private ResourceTemplate templatePersistentVolumeClaims;
    private ResourceTemplate templateInitClusterRoleBinding;
    private ResourceTemplate templatePodSet;
    private PodTemplate templatePod;
    private InternalServiceTemplate templateHeadlessService;
    private InternalServiceTemplate templateService;
    private ResourceTemplate templateExternalBootstrapService;
    private ResourceTemplate templatePerBrokerService;
    private ResourceTemplate templatePerBrokerRoute;
    private ResourceTemplate templateBootstrapRoute;
    private ResourceTemplate templateBootstrapIngress;
    private ResourceTemplate templatePerBrokerIngress;
    private ContainerTemplate templateInitContainer;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_KAFKA_POD_LABELS);
        if (value != null) {
            DEFAULT_POD_LABELS.putAll(Util.parseMap(value));
        }
    }

    /**
     * Constructor
     *
     * @param reconciliation The reconciliation
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     */
    private KafkaCluster(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, KafkaResources.kafkaStatefulSetName(resource.getMetadata().getName()), COMPONENT_TYPE);

        this.initImage = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_INIT_IMAGE, "quay.io/strimzi/operator:latest");
    }

    /**
     * Creates the Kafka cluster model instance from a Kafka CR
     *
     * @param reconciliation    Reconciliation marker
     * @param kafkaAssembly     Kafka custom resource
     * @param versions          Supported Kafka versions
     *
     * @return  Kafka cluster instance
     */
    public static KafkaCluster fromCrd(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion.Lookup versions) {
        return fromCrd(reconciliation, kafkaAssembly, versions, null, 0, false);
    }

    /**
     * Creates the Kafka cluster model instance from a Kafka CR
     *
     * @param reconciliation    Reconciliation marker
     * @param kafkaAssembly     Kafka custom resource
     * @param versions          Supported Kafka versions
     * @param oldStorage        The current storage configuration (based on the info from Kubernetes cluster, not from the Kafka CR)
     * @param oldReplicas       Number of replicas in the existing cluster (before possible scale-up / scale-down)
     * @param useKRaft          Flag indicating is KRaft is enabled
     *
     * @return  Kafka cluster instance
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity", "checkstyle:MethodLength", "checkstyle:JavaNCSS"})
    public static KafkaCluster fromCrd(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion.Lookup versions, Storage oldStorage, int oldReplicas, boolean useKRaft) {
        KafkaSpec kafkaSpec = kafkaAssembly.getSpec();
        KafkaClusterSpec kafkaClusterSpec = kafkaSpec.getKafka();

        KafkaCluster result = new KafkaCluster(reconciliation, kafkaAssembly);

        // This also validates that the Kafka version is supported
        result.kafkaVersion = versions.supportedVersion(kafkaClusterSpec.getVersion());

        result.replicas = kafkaClusterSpec.getReplicas();

        // Configures KRaft and KRaft cluster ID
        if (useKRaft)   {
            result.useKRaft = true;
            result.clusterId = getOrGenerateKRaftClusterId(kafkaAssembly);
        }

        ModelUtils.validateComputeResources(kafkaClusterSpec.getResources(), ".spec.kafka.resources");
        validateIntConfigProperty("default.replication.factor", kafkaClusterSpec);
        validateIntConfigProperty("offsets.topic.replication.factor", kafkaClusterSpec);
        validateIntConfigProperty("transaction.state.log.replication.factor", kafkaClusterSpec);
        validateIntConfigProperty("transaction.state.log.min.isr", kafkaClusterSpec);

        result.image = versions.kafkaImage(kafkaClusterSpec.getImage(), kafkaClusterSpec.getVersion());
        result.readinessProbeOptions = ProbeUtils.extractReadinessProbeOptionsOrDefault(kafkaClusterSpec, ProbeUtils.DEFAULT_HEALTHCHECK_OPTIONS);
        result.livenessProbeOptions = ProbeUtils.extractLivenessProbeOptionsOrDefault(kafkaClusterSpec, ProbeUtils.DEFAULT_HEALTHCHECK_OPTIONS);
        result.rack = kafkaClusterSpec.getRack();

        String initImage = kafkaClusterSpec.getBrokerRackInitImage();
        if (initImage == null) {
            initImage = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_INIT_IMAGE, "quay.io/strimzi/operator:latest");
        }
        result.initImage = initImage;

        result.gcLoggingEnabled = kafkaClusterSpec.getJvmOptions() == null ? JvmOptions.DEFAULT_GC_LOGGING_ENABLED : kafkaClusterSpec.getJvmOptions().isGcLoggingEnabled();
        result.jvmOptions = kafkaClusterSpec.getJvmOptions();
        result.metrics = new MetricsModel(kafkaClusterSpec);
        result.logging = new LoggingModel(kafkaClusterSpec, result.getClass().getSimpleName(), false, true);
        result.jmx = new JmxModel(
                reconciliation.namespace(),
                KafkaResources.kafkaJmxSecretName(result.cluster),
                result.labels,
                result.ownerReference,
                kafkaClusterSpec
        );

        // Handle Kafka broker configuration
        KafkaConfiguration configuration = new KafkaConfiguration(reconciliation, kafkaClusterSpec.getConfig().entrySet());
        configureCruiseControlMetrics(kafkaAssembly, result, configuration);
        validateConfiguration(reconciliation, kafkaAssembly, result.kafkaVersion, configuration);
        result.configuration = configuration;

        if (oldStorage != null) {
            Storage newStorage = kafkaClusterSpec.getStorage();
            StorageUtils.validatePersistentStorage(newStorage);

            StorageDiff diff = new StorageDiff(reconciliation, oldStorage, newStorage, oldReplicas, kafkaClusterSpec.getReplicas());

            if (!diff.isEmpty()) {
                LOGGER.warnCr(reconciliation, "Only the following changes to Kafka storage are allowed: " +
                        "changing the deleteClaim flag, " +
                        "adding volumes to Jbod storage or removing volumes from Jbod storage, " +
                        "changing overrides to nodes which do not exist yet " +
                        "and increasing size of persistent claim volumes (depending on the volume type and used storage class).");
                LOGGER.warnCr(reconciliation, "The desired Kafka storage configuration in the custom resource {}/{} contains changes which are not allowed. As a " +
                        "result, all storage changes will be ignored. Use DEBUG level logging for more information " +
                        "about the detected changes.", kafkaAssembly.getMetadata().getNamespace(), kafkaAssembly.getMetadata().getName());

                Condition warning = StatusUtils.buildWarningCondition("KafkaStorage",
                        "The desired Kafka storage configuration contains changes which are not allowed. As a " +
                                "result, all storage changes will be ignored. Use DEBUG level logging for more information " +
                                "about the detected changes.");
                result.warningConditions.add(warning);

                result.setStorage(oldStorage);
            } else {
                result.setStorage(newStorage);
            }
        } else {
            result.setStorage(kafkaClusterSpec.getStorage());
        }

        result.resources = kafkaClusterSpec.getResources();

        // Configure listeners
        if (kafkaClusterSpec.getListeners() == null || kafkaClusterSpec.getListeners().isEmpty()) {
            LOGGER.errorCr(reconciliation, "The required field .spec.kafka.listeners is missing");
            throw new InvalidResourceException("The required field .spec.kafka.listeners is missing");
        }
        List<GenericKafkaListener> listeners = kafkaClusterSpec.getListeners();
        ListenersValidator.validate(reconciliation, kafkaClusterSpec.getReplicas(), listeners);
        result.listeners = listeners;

        // Set authorization
        if (kafkaClusterSpec.getAuthorization() instanceof KafkaAuthorizationKeycloak) {
            if (!ListenersUtils.hasListenerWithOAuth(listeners)) {
                throw new InvalidResourceException("You cannot configure Keycloak Authorization without any listener with OAuth based authentication");
            } else {
                KafkaAuthorizationKeycloak authorizationKeycloak = (KafkaAuthorizationKeycloak) kafkaClusterSpec.getAuthorization();
                if (authorizationKeycloak.getClientId() == null || authorizationKeycloak.getTokenEndpointUri() == null) {
                    LOGGER.errorCr(reconciliation, "Keycloak Authorization: Token Endpoint URI and clientId are both required");
                    throw new InvalidResourceException("Keycloak Authorization: Token Endpoint URI and clientId are both required");
                }
            }
        }

        result.authorization = kafkaClusterSpec.getAuthorization();

        if (kafkaClusterSpec.getTemplate() != null) {
            KafkaClusterTemplate template = kafkaClusterSpec.getTemplate();

            result.templatePodDisruptionBudget = template.getPodDisruptionBudget();
            result.templatePersistentVolumeClaims = template.getPersistentVolumeClaim();
            result.templateInitClusterRoleBinding = template.getClusterRoleBinding();
            result.templatePodSet = template.getPodSet();
            result.templatePod = template.getPod();
            result.templateService = template.getBootstrapService();
            result.templateHeadlessService = template.getBrokersService();
            result.templateExternalBootstrapService = template.getExternalBootstrapService();
            result.templatePerBrokerService = template.getPerPodService();
            result.templateBootstrapRoute = template.getExternalBootstrapRoute();
            result.templatePerBrokerRoute = template.getPerPodRoute();
            result.templateBootstrapIngress = template.getExternalBootstrapIngress();
            result.templatePerBrokerIngress = template.getPerPodIngress();
            result.templateServiceAccount = template.getServiceAccount();
            result.templateContainer = template.getKafkaContainer();
            result.templateInitContainer = template.getInitContainer();
        }

        // Should run at the end when everything is set
        KafkaSpecChecker specChecker = new KafkaSpecChecker(kafkaSpec, versions, result);
        result.warningConditions.addAll(specChecker.run());

        return result;
    }

    /**
     * Generates list of references to Kafka nodes for this Kafka cluster. The references contain both the pod name and
     * the ID of the Kafka node.
     *
     * @return  Set of Kafka node references
     */
    private Set<NodeRef> nodes() {
        return nodes(cluster, replicas);
    }

    /**
     * Generates list of references to Kafka nodes. The references contain both the pod name and the ID of the Kafka node
     *
     * @param cluster   Name of the Kafka cluster
     * @param replicas  Number of replicas
     *
     * @return  Set of Kafka node references
     */
    public static Set<NodeRef> nodes(String cluster, int replicas) {
        Set<NodeRef> podNames = new LinkedHashSet<>(replicas);

        for (int nodeId = 0; nodeId < replicas; nodeId++) {
            podNames.add(new NodeRef(KafkaResources.kafkaPodName(cluster, nodeId), nodeId));
        }

        return podNames;
    }

    /**
     * If the cluster already exists and has a cluster ID set in its status, it will be extracted from it. If it doesn't
     * exist in the status yet, a new cluster ID will be generated. The cluster ID is used to bootstrap KRaft clusters.
     * The clusterID is added to the KafkaStatus in KafkaReconciler method clusterId(...).
     *
     * @param kafkaCr   The Kafka CR from which the cluster ID should be extracted
     *
     * @return  The extracted or generated cluster ID
     */
    private static String getOrGenerateKRaftClusterId(Kafka kafkaCr)   {
        if (kafkaCr.getStatus() != null && kafkaCr.getStatus().getClusterId() != null) {
            return kafkaCr.getStatus().getClusterId();
        } else {
            return Uuid.randomUuid().toString();
        }
    }

    /**
     * Depending on the Cruise Control configuration, it enhances the Kafka configuration to enable the Cruise Control
     * metric reporter and the configuration of its topics.
     *
     * @param kafkaAssembly     Kafka custom resource
     * @param kafkaCluster      KafkaCluster instance
     * @param configuration     Kafka broker configuration
     */
    private static void configureCruiseControlMetrics(Kafka kafkaAssembly, KafkaCluster kafkaCluster, KafkaConfiguration configuration) {
        // If required Cruise Control metric reporter configurations are missing set them using Kafka defaults
        if (configuration.getConfigOption(CruiseControlConfigurationParameters.METRICS_TOPIC_NUM_PARTITIONS.getValue()) == null) {
            kafkaCluster.ccNumPartitions = configuration.getConfigOption(KAFKA_NUM_PARTITIONS_CONFIG_FIELD, CRUISE_CONTROL_DEFAULT_NUM_PARTITIONS);
        }
        if (configuration.getConfigOption(CruiseControlConfigurationParameters.METRICS_TOPIC_REPLICATION_FACTOR.getValue()) == null) {
            kafkaCluster.ccReplicationFactor = configuration.getConfigOption(KAFKA_REPLICATION_FACTOR_CONFIG_FIELD, CRUISE_CONTROL_DEFAULT_REPLICATION_FACTOR);
        }
        if (configuration.getConfigOption(CruiseControlConfigurationParameters.METRICS_TOPIC_MIN_ISR.getValue()) == null) {
            kafkaCluster.ccMinInSyncReplicas = "1";
        } else {
            // If the user has set the CC minISR, but it is higher than the set number of replicas for the metrics topics then we need to abort and make
            // sure that the user sets minISR <= replicationFactor
            int userConfiguredMinInsync = Integer.parseInt(configuration.getConfigOption(CruiseControlConfigurationParameters.METRICS_TOPIC_MIN_ISR.getValue()));
            int configuredCcReplicationFactor = Integer.parseInt(kafkaCluster.ccReplicationFactor != null ? kafkaCluster.ccReplicationFactor : configuration.getConfigOption(CruiseControlConfigurationParameters.METRICS_TOPIC_REPLICATION_FACTOR.getValue()));
            if (userConfiguredMinInsync > configuredCcReplicationFactor) {
                throw new IllegalArgumentException(
                        "The Cruise Control metric topic minISR was set to a value (" + userConfiguredMinInsync + ") " +
                                "which is higher than the number of replicas for that topic (" + configuredCcReplicationFactor + "). " +
                                "Please ensure that the CC metrics topic minISR is <= to the topic's replication factor."
                );
            }
        }
        String metricReporters = configuration.getConfigOption(KAFKA_METRIC_REPORTERS_CONFIG_FIELD);
        Set<String> metricReporterList = new HashSet<>();
        if (metricReporters != null) {
            addAll(metricReporterList, configuration.getConfigOption(KAFKA_METRIC_REPORTERS_CONFIG_FIELD).split(","));
        }

        if (kafkaAssembly.getSpec().getCruiseControl() != null && kafkaAssembly.getSpec().getKafka().getReplicas() < 2) {
            throw new InvalidResourceException("Kafka " +
                    kafkaAssembly.getMetadata().getNamespace() + "/" + kafkaAssembly.getMetadata().getName() +
                    " has invalid configuration. Cruise Control cannot be deployed with a single-node Kafka cluster. It requires at least two Kafka nodes.");
        }
        kafkaCluster.cruiseControlSpec = kafkaAssembly.getSpec().getCruiseControl();
        if (kafkaCluster.cruiseControlSpec != null) {
            metricReporterList.add(CRUISE_CONTROL_METRIC_REPORTER);
        } else {
            metricReporterList.remove(CRUISE_CONTROL_METRIC_REPORTER);
        }
        if (!metricReporterList.isEmpty()) {
            configuration.setConfigOption(KAFKA_METRIC_REPORTERS_CONFIG_FIELD, String.join(",", metricReporterList));
        } else {
            configuration.removeConfigOption(KAFKA_METRIC_REPORTERS_CONFIG_FIELD);
        }
    }

    /**
     * Validates the Kafka broker configuration against the configuration options of the desired Kafka version.
     *
     * @param reconciliation    The reconciliation
     * @param kafkaAssembly     Kafka custom resource
     * @param desiredVersion    Desired Kafka version
     * @param configuration     Kafka broker configuration
     */
    private static void validateConfiguration(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion desiredVersion, KafkaConfiguration configuration) {
        List<String> errorsInConfig = configuration.validate(desiredVersion);

        if (!errorsInConfig.isEmpty()) {
            for (String error : errorsInConfig) {
                LOGGER.warnCr(reconciliation, "Kafka {}/{} has invalid spec.kafka.config: {}",
                        kafkaAssembly.getMetadata().getNamespace(),
                        kafkaAssembly.getMetadata().getName(),
                        error);
            }

            throw new InvalidResourceException("Kafka " +
                    kafkaAssembly.getMetadata().getNamespace() + "/" + kafkaAssembly.getMetadata().getName() +
                    " has invalid spec.kafka.config: " +
                    String.join(", ", errorsInConfig));
        }
    }

    protected static void validateIntConfigProperty(String propertyName, KafkaClusterSpec kafkaClusterSpec) {
        String orLess = kafkaClusterSpec.getReplicas() > 1 ? " or less" : "";
        if (kafkaClusterSpec.getConfig() != null && kafkaClusterSpec.getConfig().get(propertyName) != null) {
            try {
                int propertyVal = Integer.parseInt(kafkaClusterSpec.getConfig().get(propertyName).toString());
                if (propertyVal > kafkaClusterSpec.getReplicas()) {
                    throw new InvalidResourceException("Kafka configuration option '" + propertyName + "' should be set to " + kafkaClusterSpec.getReplicas() + orLess + " because 'spec.kafka.replicas' is " + kafkaClusterSpec.getReplicas());
                }
            } catch (NumberFormatException e) {
                throw new InvalidResourceException("Property " + propertyName + " should be an integer");
            }
        }
    }

    /**
     * Generates ports for bootstrap service.
     * The bootstrap service contains only the client interfaces.
     * Not the replication interface which doesn't need bootstrap service.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getServicePorts() {
        List<GenericKafkaListener> internalListeners = ListenersUtils.internalListeners(listeners);

        List<ServicePort> ports = new ArrayList<>(internalListeners.size() + 1);
        ports.add(ServiceUtils.createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));

        for (GenericKafkaListener listener : internalListeners) {
            ports.add(ServiceUtils.createServicePort(ListenersUtils.backwardsCompatiblePortName(listener), listener.getPort(), listener.getPort(), "TCP"));
        }

        return ports;
    }

    /**
     * Generates ports for headless service.
     * The headless service contains both the client interfaces and replication interface.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getHeadlessServicePorts() {
        List<GenericKafkaListener> internalListeners = ListenersUtils.internalListeners(listeners);

        List<ServicePort> ports = new ArrayList<>(internalListeners.size() + 3);
        ports.add(ServiceUtils.createServicePort(CONTROLPLANE_PORT_NAME, CONTROLPLANE_PORT, CONTROLPLANE_PORT, "TCP"));
        ports.add(ServiceUtils.createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));

        for (GenericKafkaListener listener : internalListeners) {
            ports.add(ServiceUtils.createServicePort(ListenersUtils.backwardsCompatiblePortName(listener), listener.getPort(), listener.getPort(), "TCP"));
        }

        ports.addAll(jmx.servicePorts());

        return ports;
    }

    /**
     * Generates a Service according to configured defaults
     *
     * @return The generated Service
     */
    public Service generateService() {
        return ServiceUtils.createDiscoverableClusterIpService(
                KafkaResources.bootstrapServiceName(cluster),
                namespace,
                labels,
                ownerReference,
                templateService,
                getServicePorts(),
                null,
                getInternalDiscoveryAnnotation()
        );
    }

    /**
     * Generates a JSON String with the discovery annotation for the internal bootstrap service
     *
     * @return  JSON with discovery annotation
     */
    /*test*/ Map<String, String> getInternalDiscoveryAnnotation() {
        JsonArray anno = new JsonArray();

        for (GenericKafkaListener listener : listeners) {
            JsonObject discovery = new JsonObject();
            discovery.put("port", listener.getPort());
            discovery.put("tls", listener.isTls());
            discovery.put("protocol", "kafka");

            if (listener.getAuth() != null) {
                discovery.put("auth", listener.getAuth().getType());
            } else {
                discovery.put("auth", "none");
            }

            anno.add(discovery);
        }

        return singletonMap(Labels.STRIMZI_DISCOVERY_LABEL, anno.encodePrettily());
    }

    /**
     * Generates list of external bootstrap services. These services are used for exposing it externally.
     * Separate services are used to make sure that we do expose the right port in the right way.
     *
     * @return The list with generated Services
     */
    public List<Service> generateExternalBootstrapServices() {
        List<GenericKafkaListener> externalListeners = ListenersUtils.listenersWithOwnServices(listeners);
        List<Service> services = new ArrayList<>(externalListeners.size());

        for (GenericKafkaListener listener : externalListeners)   {
            if (ListenersUtils.skipCreateBootstrapService(listener)) {
                continue;
            }

            String serviceName = ListenersUtils.backwardsCompatibleBootstrapServiceName(cluster, listener);

            List<ServicePort> ports = Collections.singletonList(
                    ServiceUtils.createServicePort(ListenersUtils.backwardsCompatiblePortName(listener),
                            listener.getPort(),
                            listener.getPort(),
                            ListenersUtils.bootstrapNodePort(listener),
                            "TCP")
            );

            Service service = ServiceUtils.createService(
                    serviceName,
                    namespace,
                    labels,
                    ownerReference,
                    templateExternalBootstrapService,
                    ports,
                    labels.strimziSelectorLabels(),
                    ListenersUtils.serviceType(listener),
                    ListenersUtils.bootstrapLabels(listener),
                    ListenersUtils.bootstrapAnnotations(listener),
                    ListenersUtils.ipFamilyPolicy(listener),
                    ListenersUtils.ipFamilies(listener)
            );

            if (KafkaListenerType.LOADBALANCER == listener.getType()) {
                String loadBalancerIP = ListenersUtils.bootstrapLoadBalancerIP(listener);
                if (loadBalancerIP != null) {
                    service.getSpec().setLoadBalancerIP(loadBalancerIP);
                }

                List<String> loadBalancerSourceRanges = ListenersUtils.loadBalancerSourceRanges(listener);
                if (loadBalancerSourceRanges != null
                        && !loadBalancerSourceRanges.isEmpty()) {
                    service.getSpec().setLoadBalancerSourceRanges(loadBalancerSourceRanges);
                }

                List<String> finalizers = ListenersUtils.finalizers(listener);
                if (finalizers != null
                        && !finalizers.isEmpty()) {
                    service.getMetadata().setFinalizers(finalizers);
                }

                String loadBalancerClass = ListenersUtils.controllerClass(listener);
                if (loadBalancerClass != null) {
                    service.getSpec().setLoadBalancerClass(loadBalancerClass);
                }
            }

            if (KafkaListenerType.LOADBALANCER == listener.getType() || KafkaListenerType.NODEPORT == listener.getType()) {
                List<String> eip = ListenersUtils.brokerExternalIPs(listener, pod);
                if (eip != null && !eip.isEmpty()) {
                    service.getSpec().setExternalIPs(eip);
                }
                ExternalTrafficPolicy etp = ListenersUtils.externalTrafficPolicy(listener);
                if (etp != null) {
                    service.getSpec().setExternalTrafficPolicy(etp.toValue());
                } else {
                    service.getSpec().setExternalTrafficPolicy(ExternalTrafficPolicy.CLUSTER.toValue());
                }
            }

            services.add(service);
        }

        return services;
    }

    /**
     * Generates list of per-pod service.
     *
     * @return The list with generated Services
     */
    public List<Service> generatePerPodServices() {
        List<GenericKafkaListener> externalListeners = ListenersUtils.listenersWithOwnServices(listeners);
        List<Service> services = new ArrayList<>(externalListeners.size());

        for (GenericKafkaListener listener : externalListeners)   {
            for (int nodeId = 0; nodeId < replicas; nodeId++) {
                String serviceName = ListenersUtils.backwardsCompatiblePerBrokerServiceName(componentName, nodeId, listener);

                List<ServicePort> ports = Collections.singletonList(
                        ServiceUtils.createServicePort(
                                ListenersUtils.backwardsCompatiblePortName(listener),
                                listener.getPort(),
                                listener.getPort(),
                                ListenersUtils.brokerNodePort(listener, nodeId),
                                "TCP")
                );

                Service service = ServiceUtils.createService(
                        serviceName,
                        namespace,
                        labels,
                        ownerReference,
                        templatePerBrokerService,
                        ports,
                        labels.strimziSelectorLabels().withStatefulSetPod(KafkaResources.kafkaPodName(cluster, nodeId)),
                        ListenersUtils.serviceType(listener),
                        ListenersUtils.brokerLabels(listener, nodeId),
                        ListenersUtils.brokerAnnotations(listener, nodeId),
                        ListenersUtils.ipFamilyPolicy(listener),
                        ListenersUtils.ipFamilies(listener)
                );

                if (KafkaListenerType.LOADBALANCER == listener.getType()) {
                    String loadBalancerIP = ListenersUtils.brokerLoadBalancerIP(listener, nodeId);
                    if (loadBalancerIP != null) {
                        service.getSpec().setLoadBalancerIP(loadBalancerIP);
                    }

                    List<String> loadBalancerSourceRanges = ListenersUtils.loadBalancerSourceRanges(listener);
                    if (loadBalancerSourceRanges != null
                            && !loadBalancerSourceRanges.isEmpty()) {
                        service.getSpec().setLoadBalancerSourceRanges(loadBalancerSourceRanges);
                    }

                    List<String> finalizers = ListenersUtils.finalizers(listener);
                    if (finalizers != null
                            && !finalizers.isEmpty()) {
                        service.getMetadata().setFinalizers(finalizers);
                    }

                    String loadBalancerClass = ListenersUtils.controllerClass(listener);
                    if (loadBalancerClass != null) {
                        service.getSpec().setLoadBalancerClass(loadBalancerClass);
                    }
                }

                if (KafkaListenerType.LOADBALANCER == listener.getType() || KafkaListenerType.NODEPORT == listener.getType()) {
                    List<String> eip = ListenersUtils.bootstrapExternalIPs(listener);
                    if (eip != null && !eip.isEmpty()) {
                        service.getSpec().setExternalIPs(eip);
                    }
                    ExternalTrafficPolicy etp = ListenersUtils.externalTrafficPolicy(listener);
                    if (etp != null) {
                        service.getSpec().setExternalTrafficPolicy(etp.toValue());
                    } else {
                        service.getSpec().setExternalTrafficPolicy(ExternalTrafficPolicy.CLUSTER.toValue());
                    }
                }

                services.add(service);
            }
        }

        return services;
    }

    /**
     * Generates a list of bootstrap route which can be used to bootstrap clients outside of OpenShift.
     *
     * @return The list of generated Routes
     */
    public List<Route> generateExternalBootstrapRoutes() {
        List<GenericKafkaListener> routeListeners = ListenersUtils.routeListeners(listeners);
        List<Route> routes = new ArrayList<>(routeListeners.size());

        for (GenericKafkaListener listener : routeListeners)   {
            String routeName = ListenersUtils.backwardsCompatibleBootstrapRouteOrIngressName(cluster, listener);
            String serviceName = ListenersUtils.backwardsCompatibleBootstrapServiceName(cluster, listener);

            Route route = new RouteBuilder()
                    .withNewMetadata()
                        .withName(routeName)
                        .withLabels(Util.mergeLabelsOrAnnotations(labels.withAdditionalLabels(TemplateUtils.labels(templateBootstrapRoute)).toMap(), ListenersUtils.bootstrapLabels(listener)))
                        .withAnnotations(Util.mergeLabelsOrAnnotations(TemplateUtils.annotations(templateBootstrapRoute), ListenersUtils.bootstrapAnnotations(listener)))
                        .withNamespace(namespace)
                        .withOwnerReferences(ownerReference)
                    .endMetadata()
                    .withNewSpec()
                        .withNewTo()
                            .withKind("Service")
                            .withName(serviceName)
                        .endTo()
                        .withNewPort()
                            .withNewTargetPort(listener.getPort())
                        .endPort()
                        .withNewTls()
                            .withTermination("passthrough")
                        .endTls()
                    .endSpec()
                    .build();

            String host = ListenersUtils.bootstrapHost(listener);
            if (host != null)   {
                route.getSpec().setHost(host);
            }

            routes.add(route);
        }

        return routes;
    }

    /**
     * Generates list of per-pod routes. These routes are used for exposing it externally using OpenShift Routes.
     *
     * @return The list with generated Routes
     */
    public List<Route> generateExternalRoutes() {
        List<GenericKafkaListener> routeListeners = ListenersUtils.routeListeners(listeners);
        List<Route> routes = new ArrayList<>(routeListeners.size());

        for (GenericKafkaListener listener : routeListeners)   {
            for (int nodeId = 0; nodeId < replicas; nodeId++) {
                String routeName = ListenersUtils.backwardsCompatiblePerBrokerServiceName(componentName, nodeId, listener);
                Route route = new RouteBuilder()
                        .withNewMetadata()
                            .withName(routeName)
                            .withLabels(labels.withAdditionalLabels(Util.mergeLabelsOrAnnotations(TemplateUtils.labels(templatePerBrokerRoute), ListenersUtils.brokerLabels(listener, nodeId))).toMap())
                            .withAnnotations(Util.mergeLabelsOrAnnotations(TemplateUtils.annotations(templatePerBrokerRoute), ListenersUtils.brokerAnnotations(listener, nodeId)))
                            .withNamespace(namespace)
                            .withOwnerReferences(ownerReference)
                        .endMetadata()
                        .withNewSpec()
                            .withNewTo()
                                .withKind("Service")
                                .withName(routeName)
                            .endTo()
                            .withNewPort()
                                .withNewTargetPort(listener.getPort())
                            .endPort()
                            .withNewTls()
                                .withTermination("passthrough")
                            .endTls()
                        .endSpec()
                        .build();

                String host = ListenersUtils.brokerHost(listener, nodeId);
                if (host != null)   {
                    route.getSpec().setHost(host);
                }

                routes.add(route);
            }
        }

        return routes;
    }

    /**
     * Generates a list of bootstrap ingress which can be used to bootstrap clients outside of Kubernetes.
     *
     * @return The list of generated Ingresses
     */
    public List<Ingress> generateExternalBootstrapIngresses() {
        List<GenericKafkaListener> ingressListeners = ListenersUtils.ingressListeners(listeners);
        List<Ingress> ingresses = new ArrayList<>(ingressListeners.size());

        for (GenericKafkaListener listener : ingressListeners)   {
            String ingressName = ListenersUtils.backwardsCompatibleBootstrapRouteOrIngressName(cluster, listener);
            String serviceName = ListenersUtils.backwardsCompatibleBootstrapServiceName(cluster, listener);

            String host = ListenersUtils.bootstrapHost(listener);
            String ingressClass = ListenersUtils.controllerClass(listener);

            HTTPIngressPath path = new HTTPIngressPathBuilder()
                    .withPath("/")
                    .withPathType("Prefix")
                    .withNewBackend()
                        .withNewService()
                            .withName(serviceName)
                            .withNewPort()
                                .withNumber(listener.getPort())
                            .endPort()
                        .endService()
                    .endBackend()
                    .build();

            IngressRule rule = new IngressRuleBuilder()
                    .withHost(host)
                    .withNewHttp()
                        .withPaths(path)
                    .endHttp()
                    .build();

            IngressTLS tls = new IngressTLSBuilder()
                    .withHosts(host)
                    .build();

            Ingress ingress = new IngressBuilder()
                    .withNewMetadata()
                        .withName(ingressName)
                        .withLabels(labels.withAdditionalLabels(Util.mergeLabelsOrAnnotations(TemplateUtils.labels(templateBootstrapIngress), ListenersUtils.bootstrapLabels(listener))).toMap())
                        .withAnnotations(Util.mergeLabelsOrAnnotations(generateInternalIngressAnnotations(), TemplateUtils.annotations(templateBootstrapIngress), ListenersUtils.bootstrapAnnotations(listener)))
                        .withNamespace(namespace)
                        .withOwnerReferences(ownerReference)
                    .endMetadata()
                    .withNewSpec()
                        .withIngressClassName(ingressClass)
                        .withRules(rule)
                        .withTls(tls)
                    .endSpec()
                    .build();

            ingresses.add(ingress);
        }

        return ingresses;
    }

    /**
     * Generates list of per-pod ingress. This ingress is used for exposing it externally using Nginx Ingress.
     *
     * @return The list of generated Ingresses
     */
    public List<Ingress> generateExternalIngresses() {
        List<GenericKafkaListener> ingressListeners = ListenersUtils.ingressListeners(listeners);
        List<Ingress> ingresses = new ArrayList<>(ingressListeners.size());

        for (GenericKafkaListener listener : ingressListeners)   {
            for (int nodeId = 0; nodeId < replicas; nodeId++) {
                String ingressName = ListenersUtils.backwardsCompatiblePerBrokerServiceName(componentName, nodeId, listener);
                String host = ListenersUtils.brokerHost(listener, nodeId);
                String ingressClass = ListenersUtils.controllerClass(listener);

                HTTPIngressPath path = new HTTPIngressPathBuilder()
                        .withPath("/")
                        .withPathType("Prefix")
                        .withNewBackend()
                            .withNewService()
                                .withName(ingressName)
                                .withNewPort()
                                    .withNumber(listener.getPort())
                                .endPort()
                            .endService()
                        .endBackend()
                        .build();

                IngressRule rule = new IngressRuleBuilder()
                        .withHost(host)
                        .withNewHttp()
                            .withPaths(path)
                        .endHttp()
                        .build();

                IngressTLS tls = new IngressTLSBuilder()
                        .withHosts(host)
                        .build();

                Ingress ingress = new IngressBuilder()
                        .withNewMetadata()
                            .withName(ingressName)
                            .withLabels(labels.withAdditionalLabels(Util.mergeLabelsOrAnnotations(TemplateUtils.labels(templatePerBrokerIngress), ListenersUtils.brokerLabels(listener, nodeId))).toMap())
                            .withAnnotations(Util.mergeLabelsOrAnnotations(generateInternalIngressAnnotations(), TemplateUtils.annotations(templatePerBrokerIngress), ListenersUtils.brokerAnnotations(listener, nodeId)))
                            .withNamespace(namespace)
                            .withOwnerReferences(ownerReference)
                        .endMetadata()
                        .withNewSpec()
                            .withIngressClassName(ingressClass)
                            .withRules(rule)
                            .withTls(tls)
                        .endSpec()
                        .build();

                ingresses.add(ingress);
            }
        }

        return ingresses;
    }

    /**
     * Generates the annotations needed to configure the Ingress as TLS passthrough
     *
     * @return Map with the annotations
     */
    private Map<String, String> generateInternalIngressAnnotations() {
        Map<String, String> internalAnnotations = new HashMap<>(3);

        internalAnnotations.put("ingress.kubernetes.io/ssl-passthrough", "true");
        internalAnnotations.put("nginx.ingress.kubernetes.io/ssl-passthrough", "true");
        internalAnnotations.put("nginx.ingress.kubernetes.io/backend-protocol", "HTTPS");

        return internalAnnotations;
    }

    /**
     * Generates a headless Service according to configured defaults
     *
     * @return The generated Service
     */
    public Service generateHeadlessService() {
        return ServiceUtils.createHeadlessService(
                KafkaResources.brokersServiceName(cluster),
                namespace,
                labels,
                ownerReference,
                templateHeadlessService,
                getHeadlessServicePorts()
        );
    }

    /**
     * Prepares annotations for the controller resource such as StrimziPodSet.
     *
     * @return  Map with all annotations which should be used for thr controller resource
     */
    private Map<String, String> preparePodSetAnnotations()   {
        Map<String, String> controllerAnnotations = new HashMap<>(2);
        controllerAnnotations.put(ANNO_STRIMZI_IO_KAFKA_VERSION, kafkaVersion.version());
        controllerAnnotations.put(Annotations.ANNO_STRIMZI_IO_STORAGE, ModelUtils.encodeStorageToJson(storage));

        return controllerAnnotations;
    }

    /**
     * Generates the StrimziPodSet for the Kafka cluster.
     *
     * @param replicas                  Number of replicas the StrimziPodSet should have. During scale-ups or scale-downs, node
     *                                  sets with different numbers of pods are generated.
     * @param isOpenShift               Flags whether we are on OpenShift or not
     * @param imagePullPolicy           Image pull policy which will be used by the pods
     * @param imagePullSecrets          List of image pull secrets
     * @param podAnnotationsProvider    Function which provides annotations for given pod based on its broker ID. The
     *                                  annotations for each pod are different due to the individual configurations.
     *                                  So they need to be dynamically generated though this function instead of just
     *                                  passed as Map.
     *
     * @return                          Generated StrimziPodSet with Kafka pods
     */
    public StrimziPodSet generatePodSet(int replicas,
                                        boolean isOpenShift,
                                        ImagePullPolicy imagePullPolicy,
                                        List<LocalObjectReference> imagePullSecrets,
                                        Function<Integer, Map<String, String>> podAnnotationsProvider) {
        return WorkloadUtils.createPodSet(
                componentName,
                namespace,
                labels,
                ownerReference,
                templatePodSet,
                replicas,
                preparePodSetAnnotations(),
                labels.strimziSelectorLabels(),
                brokerId -> WorkloadUtils.createStatefulPod(
                        reconciliation,
                        getPodName(brokerId),
                        namespace,
                        labels,
                        componentName,
                        componentName,
                        templatePod,
                        DEFAULT_POD_LABELS,
                        podAnnotationsProvider.apply(brokerId),
                        KafkaResources.brokersServiceName(cluster),
                        getMergedAffinity(),
                        ContainerUtils.listOrNull(createInitContainer(imagePullPolicy)),
                        List.of(createContainer(imagePullPolicy)),
                        getPodSetVolumes(getPodName(brokerId), isOpenShift),
                        imagePullSecrets,
                        securityProvider.kafkaPodSecurityContext(new PodSecurityProviderContextImpl(storage, templatePod))
                )
        );
    }

    /**
     * Generates the private keys for the Kafka brokers (if needed) and the secret with them which contains both the
     * public and private keys.
     *
     * @param clusterCa                             The CA for cluster certificates
     * @param clientsCa                             The CA for clients certificates
     * @param externalBootstrapDnsName              Map with bootstrap DNS names which should be added to the certificate
     * @param externalDnsNames                      Map with broker DNS names  which should be added to the certificate
     * @param isMaintenanceTimeWindowsSatisfied     Indicates whether we are in a maintenance window or not
     *
     * @return  The generated Secret with broker certificates
     */
    public Secret generateCertificatesSecret(ClusterCa clusterCa, ClientsCa clientsCa, Set<String> externalBootstrapDnsName, Map<Integer, Set<String>> externalDnsNames, boolean isMaintenanceTimeWindowsSatisfied) {
        Map<String, CertAndKey> brokerCerts;
        Map<String, String> data = new HashMap<>(replicas * 4);

        try {
            brokerCerts = clusterCa.generateBrokerCerts(namespace, cluster, nodes(), externalBootstrapDnsName, externalDnsNames, isMaintenanceTimeWindowsSatisfied);
        } catch (IOException e) {
            LOGGER.warnCr(reconciliation, "Error while generating certificates", e);
            throw new RuntimeException("Failed to prepare Kafka certificates", e);
        }

        for (int i = 0; i < replicas; i++) {
            CertAndKey cert = brokerCerts.get(KafkaResources.kafkaPodName(cluster, i));
            data.put(KafkaResources.kafkaPodName(cluster, i) + ".key", cert.keyAsBase64String());
            data.put(KafkaResources.kafkaPodName(cluster, i) + ".crt", cert.certAsBase64String());
            data.put(KafkaResources.kafkaPodName(cluster, i) + ".p12", cert.keyStoreAsBase64String());
            data.put(KafkaResources.kafkaPodName(cluster, i) + ".password", cert.storePasswordAsBase64String());
        }

        return ModelUtils.createSecret(
                KafkaResources.kafkaSecretName(cluster),
                namespace,
                labels,
                ownerReference,
                data,
                Map.of(
                        clusterCa.caCertGenerationAnnotation(), String.valueOf(clusterCa.certGeneration()),
                        clientsCa.caCertGenerationAnnotation(), String.valueOf(clientsCa.certGeneration())
                ),
                emptyMap());
    }

    /* test */ List<ContainerPort> getContainerPortList() {
        List<ContainerPort> ports = new ArrayList<>(listeners.size() + 3);
        ports.add(ContainerUtils.createContainerPort(CONTROLPLANE_PORT_NAME, CONTROLPLANE_PORT));
        ports.add(ContainerUtils.createContainerPort(REPLICATION_PORT_NAME, REPLICATION_PORT));

        for (GenericKafkaListener listener : listeners) {
            ports.add(ContainerUtils.createContainerPort(ListenersUtils.backwardsCompatiblePortName(listener), listener.getPort()));
        }

        if (metrics.isEnabled()) {
            ports.add(ContainerUtils.createContainerPort(MetricsModel.METRICS_PORT_NAME, MetricsModel.METRICS_PORT));
        }

        ports.addAll(jmx.containerPorts());

        return ports;
    }

    /**
     * Generate the persistent volume claims for the storage It's called recursively on the related inner volumes if the
     * storage is of {@link Storage#TYPE_JBOD} type.
     *
     * @param storage the Storage instance from which building volumes, persistent volume claims and
     *                related volume mount paths.
     * @return The PersistentVolumeClaims.
     */
    public List<PersistentVolumeClaim> generatePersistentVolumeClaims(Storage storage) {
        return PersistentVolumeClaimUtils
                .createPersistentVolumeClaims(
                        namespace,
                        nodes(),
                        storage,
                        false,
                        labels,
                        ownerReference,
                        templatePersistentVolumeClaims
                );
    }

    /**
     * Generates list of non-data volumes used by Kafka Pods. This includes tmp volumes, mounted secrets and config
     * maps.
     *
     * @param isOpenShift               Indicates whether we are on OpenShift or not
     * @param perBrokerConfiguration    Indicates whether the shared configuration ConfigMap or the per-broker ConfigMap
     *                                  should be mounted.
     * @param podName                   The name of the Pod for which are these volumes generated. The Pod name
     *                                  identifies which ConfigMap should be used when perBrokerConfiguration is set to
     *                                  true. When perBrokerConfiguration is set to false, the Pod name is not used and
     *                                  can be set to null.
     *
     * @return                          List of non-data volumes used by the ZooKeeper pods
     */
    /* test */ List<Volume> getNonDataVolumes(boolean isOpenShift, boolean perBrokerConfiguration, String podName) {
        List<Volume> volumeList = new ArrayList<>();

        if (rack != null || isExposedWithNodePort()) {
            volumeList.add(VolumeUtils.createEmptyDirVolume(INIT_VOLUME_NAME, "1Mi", "Memory"));
        }

        volumeList.add(VolumeUtils.createTempDirVolume(templatePod));
        volumeList.add(VolumeUtils.createSecretVolume(CLUSTER_CA_CERTS_VOLUME, AbstractModel.clusterCaCertSecretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createSecretVolume(BROKER_CERTS_VOLUME, KafkaResources.kafkaSecretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createSecretVolume(CLIENT_CA_CERTS_VOLUME, KafkaResources.clientsCaCertificateSecretName(cluster), isOpenShift));

        if (perBrokerConfiguration) {
            volumeList.add(VolumeUtils.createConfigMapVolume(LOG_AND_METRICS_CONFIG_VOLUME_NAME, podName));
        } else {
            volumeList.add(VolumeUtils.createConfigMapVolume(LOG_AND_METRICS_CONFIG_VOLUME_NAME, KafkaResources.kafkaMetricsAndLogConfigMapName(cluster)));
        }

        volumeList.add(VolumeUtils.createEmptyDirVolume("ready-files", "1Ki", "Memory"));

        for (GenericKafkaListener listener : listeners) {
            if (listener.isTls()
                    && listener.getConfiguration() != null
                    && listener.getConfiguration().getBrokerCertChainAndKey() != null)  {
                CertAndKeySecretSource secretSource = listener.getConfiguration().getBrokerCertChainAndKey();

                Map<String, String> items = new HashMap<>(2);
                items.put(secretSource.getKey(), "tls.key");
                items.put(secretSource.getCertificate(), "tls.crt");

                volumeList.add(
                        VolumeUtils.createSecretVolume(
                                "custom-" + ListenersUtils.identifier(listener) + "-certs",
                                secretSource.getSecretName(),
                                items,
                                isOpenShift
                        )
                );
            }

            if (isListenerWithOAuth(listener))   {
                KafkaListenerAuthenticationOAuth oauth = (KafkaListenerAuthenticationOAuth) listener.getAuth();
                volumeList.addAll(AuthenticationUtils.configureOauthCertificateVolumes("oauth-" + ListenersUtils.identifier(listener), oauth.getTlsTrustedCertificates(), isOpenShift));
            }

            if (isListenerWithCustomAuth(listener)) {
                KafkaListenerAuthenticationCustom custom = (KafkaListenerAuthenticationCustom) listener.getAuth();
                volumeList.addAll(AuthenticationUtils.configureGenericSecretVolumes("custom-listener-" + ListenersUtils.identifier(listener), custom.getSecrets(), isOpenShift));
            }
        }

        if (authorization instanceof KafkaAuthorizationOpa opaAuthz) {
            volumeList.addAll(AuthenticationUtils.configureOauthCertificateVolumes("authz-opa", opaAuthz.getTlsTrustedCertificates(), isOpenShift));
        }

        if (authorization instanceof KafkaAuthorizationKeycloak keycloakAuthz) {
            volumeList.addAll(AuthenticationUtils.configureOauthCertificateVolumes("authz-keycloak", keycloakAuthz.getTlsTrustedCertificates(), isOpenShift));
        }

        return volumeList;
    }

    /**
     * Generates a list of volumes used by PodSets. For StrimziPodSet, it needs to include also all persistent claim
     * volumes which StatefulSet would generate on its own.
     *
     * @param podName       Name of the pod used to name the volumes
     * @param isOpenShift   Flag whether we are on OpenShift or not
     *
     * @return              List of volumes to be included in the StrimziPodSet pod
     */
    private List<Volume> getPodSetVolumes(String podName, boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>();

        volumeList.addAll(VolumeUtils.createPodSetVolumes(podName, storage, false));
        volumeList.addAll(getNonDataVolumes(isOpenShift, true, podName));

        return volumeList;
    }

    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>(VolumeUtils.createVolumeMounts(storage, DATA_VOLUME_MOUNT_PATH, false));
        volumeMountList.add(VolumeUtils.createTempDirVolumeMount());
        volumeMountList.add(VolumeUtils.createVolumeMount(CLUSTER_CA_CERTS_VOLUME, CLUSTER_CA_CERTS_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(BROKER_CERTS_VOLUME, BROKER_CERTS_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(CLIENT_CA_CERTS_VOLUME, CLIENT_CA_CERTS_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount(LOG_AND_METRICS_CONFIG_VOLUME_NAME, LOG_AND_METRICS_CONFIG_VOLUME_MOUNT));
        volumeMountList.add(VolumeUtils.createVolumeMount("ready-files", "/var/opt/kafka"));

        if (rack != null || isExposedWithNodePort()) {
            volumeMountList.add(VolumeUtils.createVolumeMount(INIT_VOLUME_NAME, INIT_VOLUME_MOUNT));
        }

        for (GenericKafkaListener listener : listeners) {
            String identifier = ListenersUtils.identifier(listener);

            if (listener.isTls()
                    && listener.getConfiguration() != null
                    && listener.getConfiguration().getBrokerCertChainAndKey() != null)  {
                volumeMountList.add(VolumeUtils.createVolumeMount("custom-" + identifier + "-certs", "/opt/kafka/certificates/custom-" + identifier + "-certs"));
            }

            if (isListenerWithOAuth(listener))   {
                KafkaListenerAuthenticationOAuth oauth = (KafkaListenerAuthenticationOAuth) listener.getAuth();
                volumeMountList.addAll(AuthenticationUtils.configureOauthCertificateVolumeMounts("oauth-" + identifier, oauth.getTlsTrustedCertificates(), TRUSTED_CERTS_BASE_VOLUME_MOUNT + "/oauth-" + identifier + "-certs"));
            }

            if (isListenerWithCustomAuth(listener)) {
                KafkaListenerAuthenticationCustom custom = (KafkaListenerAuthenticationCustom) listener.getAuth();
                volumeMountList.addAll(AuthenticationUtils.configureGenericSecretVolumeMounts("custom-listener-" + identifier, custom.getSecrets(), CUSTOM_AUTHN_SECRETS_VOLUME_MOUNT + "/custom-listener-" + identifier));
            }
        }

        if (authorization instanceof KafkaAuthorizationOpa opaAuthz) {
            volumeMountList.addAll(AuthenticationUtils.configureOauthCertificateVolumeMounts("authz-opa", opaAuthz.getTlsTrustedCertificates(), TRUSTED_CERTS_BASE_VOLUME_MOUNT + "/authz-opa-certs"));
        }

        if (authorization instanceof KafkaAuthorizationKeycloak keycloakAuthz) {
            volumeMountList.addAll(AuthenticationUtils.configureOauthCertificateVolumeMounts("authz-keycloak", keycloakAuthz.getTlsTrustedCertificates(), TRUSTED_CERTS_BASE_VOLUME_MOUNT + "/authz-keycloak-certs"));
        }

        return volumeMountList;
    }

    /**
     * Returns a combined affinity: Adding the affinity needed for the "kafka-rack" to the user-provided affinity.
     */
    protected Affinity getMergedAffinity() {
        Affinity userAffinity = templatePod != null && templatePod.getAffinity() != null ? templatePod.getAffinity() : new Affinity();
        AffinityBuilder builder = new AffinityBuilder(userAffinity);
        if (rack != null) {
            // If there's a rack config, we need to add a podAntiAffinity to spread the brokers among the racks
            builder = builder
                    .editOrNewPodAntiAffinity()
                        .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                            .withWeight(100)
                            .withNewPodAffinityTerm()
                                .withTopologyKey(rack.getTopologyKey())
                                .withNewLabelSelector()
                                    .addToMatchLabels(Labels.STRIMZI_CLUSTER_LABEL, cluster)
                                    .addToMatchLabels(Labels.STRIMZI_NAME_LABEL, componentName)
                                .endLabelSelector()
                            .endPodAffinityTerm()
                        .endPreferredDuringSchedulingIgnoredDuringExecution()
                    .endPodAntiAffinity();

            builder = ModelUtils.populateAffinityBuilderWithRackLabelSelector(builder, userAffinity, rack.getTopologyKey());
        }

        return builder.build();
    }

    protected List<EnvVar> getInitContainerEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(ContainerUtils.createEnvVarFromFieldRef(ENV_VAR_KAFKA_INIT_NODE_NAME, "spec.nodeName"));

        if (rack != null) {
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_KAFKA_INIT_RACK_TOPOLOGY_KEY, rack.getTopologyKey()));
        }

        if (!ListenersUtils.nodePortListeners(listeners).isEmpty()) {
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_KAFKA_INIT_EXTERNAL_ADDRESS, "TRUE"));
        }

        // Add shared environment variables used for all containers
        varList.addAll(ContainerUtils.requiredEnvVars());

        ContainerUtils.addContainerEnvsToExistingEnvs(reconciliation, varList, templateInitContainer);

        return varList;
    }

    /* test */ Container createInitContainer(ImagePullPolicy imagePullPolicy) {
        if (rack != null || !ListenersUtils.nodePortListeners(listeners).isEmpty()) {
            return ContainerUtils.createContainer(
                    INIT_NAME,
                    initImage,
                    List.of("/opt/strimzi/bin/kafka_init_run.sh"),
                    securityProvider.kafkaInitContainerSecurityContext(new ContainerSecurityProviderContextImpl(templateInitContainer)),
                    resources,
                    getInitContainerEnvVars(),
                    null,
                    List.of(VolumeUtils.createVolumeMount(INIT_VOLUME_NAME, INIT_VOLUME_MOUNT)),
                    null,
                    null,
                    imagePullPolicy
            );
        } else {
            return null;
        }
    }

    /* test */ Container createContainer(ImagePullPolicy imagePullPolicy) {
        return ContainerUtils.createContainer(
                KAFKA_NAME,
                image,
                List.of("/opt/kafka/kafka_run.sh"),
                securityProvider.kafkaContainerSecurityContext(new ContainerSecurityProviderContextImpl(storage, templateContainer)),
                resources,
                getEnvVars(),
                getContainerPortList(),
                getVolumeMounts(),
                ProbeUtils.defaultBuilder(livenessProbeOptions).withNewExec().withCommand("/opt/kafka/kafka_liveness.sh").endExec().build(),
                ProbeUtils.defaultBuilder(readinessProbeOptions).withNewExec().withCommand("/opt/kafka/kafka_readiness.sh").endExec().build(),
                imagePullPolicy
        );
    }

    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_KAFKA_METRICS_ENABLED, String.valueOf(metrics.isEnabled())));
        varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));

        ModelUtils.heapOptions(varList, 50, 5L * 1024L * 1024L * 1024L, jvmOptions, resources);
        ModelUtils.jvmPerformanceOptions(varList, jvmOptions);
        ModelUtils.jvmSystemProperties(varList, jvmOptions);

        for (GenericKafkaListener listener : listeners) {
            if (isListenerWithOAuth(listener))   {
                KafkaListenerAuthenticationOAuth oauth = (KafkaListenerAuthenticationOAuth) listener.getAuth();

                if (oauth.getClientSecret() != null)    {
                    varList.add(ContainerUtils.createEnvVarFromSecret("STRIMZI_" + ListenersUtils.envVarIdentifier(listener) + "_OAUTH_CLIENT_SECRET", oauth.getClientSecret().getSecretName(), oauth.getClientSecret().getKey()));
                }
            }
        }

        if (useKRaft)   {
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_CLUSTER_ID, clusterId));
            varList.add(ContainerUtils.createEnvVar(ENV_VAR_STRIMZI_KRAFT_ENABLED, "true"));
        }

        varList.addAll(jmx.envVars());

        // Add shared environment variables used for all containers
        varList.addAll(ContainerUtils.requiredEnvVars());

        // Add user defined environment variables to the Kafka broker containers
        ContainerUtils.addContainerEnvsToExistingEnvs(reconciliation, varList, templateContainer);

        return varList;
    }

    /**
     * Creates the ClusterRoleBinding which is used to bind the Kafka SA to the ClusterRole
     * which permissions the Kafka init container to access K8S nodes (necessary for rack-awareness).
     *
     * @param assemblyNamespace The namespace.
     * @return The cluster role binding.
     */
    public ClusterRoleBinding generateClusterRoleBinding(String assemblyNamespace) {
        if (rack != null || isExposedWithNodePort()) {
            Subject subject = new SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(componentName)
                    .withNamespace(assemblyNamespace)
                    .build();

            RoleRef roleRef = new RoleRefBuilder()
                    .withName("strimzi-kafka-broker")
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("ClusterRole")
                    .build();

            return RbacUtils
                    .createClusterRoleBinding(KafkaResources.initContainerClusterRoleBindingName(cluster, namespace), roleRef, List.of(subject), labels, templateInitClusterRoleBinding);
        } else {
            return null;
        }
    }

    /**
     * Generates the NetworkPolicies relevant for Kafka brokers
     *
     * @param operatorNamespace                             Namespace where the Strimzi Cluster Operator runs. Null if not configured.
     * @param operatorNamespaceLabels                       Labels of the namespace where the Strimzi Cluster Operator runs. Null if not configured.
     *
     * @return The network policy.
     */
    public NetworkPolicy generateNetworkPolicy(String operatorNamespace, Labels operatorNamespaceLabels) {
        // Internal peers => Strimzi components which need access
        NetworkPolicyPeer clusterOperatorPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_KIND_LABEL, "cluster-operator"), NetworkPolicyUtils.clusterOperatorNamespaceSelector(namespace, operatorNamespace, operatorNamespaceLabels));
        NetworkPolicyPeer kafkaClusterPeer = NetworkPolicyUtils.createPeer(labels.strimziSelectorLabels().toMap());
        NetworkPolicyPeer entityOperatorPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, KafkaResources.entityOperatorDeploymentName(cluster)));
        NetworkPolicyPeer kafkaExporterPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, KafkaExporterResources.deploymentName(cluster)));
        NetworkPolicyPeer cruiseControlPeer = NetworkPolicyUtils.createPeer(Map.of(Labels.STRIMZI_NAME_LABEL, CruiseControlResources.deploymentName(cluster)));

        // List of network policy rules for all ports
        List<NetworkPolicyIngressRule> rules = new ArrayList<>();

        // Control Plane rule covers the control plane listener.
        // Control plane listener is used by Kafka for internal coordination only
        rules.add(NetworkPolicyUtils.createIngressRule(CONTROLPLANE_PORT, List.of(kafkaClusterPeer)));

        // Replication rule covers the replication listener.
        // Replication listener is used by Kafka but also by our own tools => Operators, Cruise Control, and Kafka Exporter
        rules.add(NetworkPolicyUtils.createIngressRule(REPLICATION_PORT, List.of(clusterOperatorPeer, kafkaClusterPeer, entityOperatorPeer, kafkaExporterPeer, cruiseControlPeer)));

        // User-configured listeners are by default open for all. Users can pass peers in the Kafka CR.
        for (GenericKafkaListener listener : listeners) {
            rules.add(NetworkPolicyUtils.createIngressRule(listener.getPort(), listener.getNetworkPolicyPeers()));
        }

        // The Metrics port (if enabled) is opened to all by default
        if (metrics.isEnabled()) {
            rules.add(NetworkPolicyUtils.createIngressRule(MetricsModel.METRICS_PORT, List.of()));
        }

        // The JMX port (if enabled) is opened to all by default
        rules.addAll(jmx.networkPolicyIngresRules());

        // Build the final network policy with all rules covering all the ports
        return NetworkPolicyUtils.createNetworkPolicy(
                KafkaResources.kafkaNetworkPolicyName(cluster),
                namespace,
                labels,
                ownerReference,
                rules
        );
    }

    /**
     * Generates the PodDisruptionBudget.
     *
     * @return The PodDisruptionBudget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget() {
        return PodDisruptionBudgetUtils.createCustomControllerPodDisruptionBudget(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget, replicas);
    }

    /**
     * @return The listeners
     */
    public List<GenericKafkaListener> getListeners() {
        return listeners;
    }

    /**
     * Returns true when the Kafka cluster is exposed to the outside using NodePort type services
     *
     * @return true when the Kafka cluster is exposed to the outside using NodePort.
     */
    public boolean isExposedWithNodePort() {
        return ListenersUtils.hasNodePortListener(listeners);
    }

    /**
     * Returns true when the Kafka cluster is exposed to the outside of Kubernetes using Ingress
     *
     * @return true when the Kafka cluster is exposed using Kubernetes Ingress.
     */
    public boolean isExposedWithIngress() {
        return ListenersUtils.hasIngressListener(listeners);
    }

    /**
     * Returns true when the Kafka cluster is exposed to the outside of Kubernetes using ClusterIP services
     *
     * @return true when the Kafka cluster is exposed using Kubernetes Ingress with TCP mode.
     */
    public boolean isExposedWithClusterIP() {
        return ListenersUtils.hasClusterIPListener(listeners);
    }

    /**
     * Returns the configuration of the Kafka cluster. This method is currently used by the KafkaSpecChecker to get the
     * Kafka configuration and check it for warnings.
     *
     * @return  Kafka cluster configuration
     */
    public KafkaConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Generates the individual Kafka broker configuration. This configuration uses only minimum of placeholders - for
     * values which are known only inside the pod such as secret values (e.g. OAuth client secrets), NodePort addresses
     * or Rack IDs. All other values such as broker IDs, advertised ports or hostnames are already prefilled in the
     * configuration. This method is normally used with StrimziPodSets.
     *
     * @param brokerId            ID of the broker for which is this configuration generated
     * @param advertisedHostnames Map with advertised hostnames for different listeners
     * @param advertisedPorts     Map with advertised ports for different listeners
     *
     * @return The Kafka broker configuration as a String
     */
    public String generatePerBrokerBrokerConfiguration(int brokerId, Map<Integer, Map<String, String>> advertisedHostnames, Map<Integer, Map<String, String>> advertisedPorts)   {
        if (useKRaft) {
            return new KafkaBrokerConfigurationBuilder(reconciliation, String.valueOf(brokerId))
                    .withRackId(rack)
                    .withKRaft(cluster, namespace, replicas)
                    .withLogDirs(VolumeUtils.createVolumeMounts(storage, DATA_VOLUME_MOUNT_PATH, false))
                    .withListeners(cluster,
                            namespace,
                            listeners,
                            () -> getPodName(brokerId),
                            listenerId -> advertisedHostnames.get(brokerId).get(listenerId),
                            listenerId -> advertisedPorts.get(brokerId).get(listenerId),
                            true)
                    .withAuthorization(cluster, authorization, true)
                    .withCruiseControl(cluster, cruiseControlSpec, ccNumPartitions, ccReplicationFactor, ccMinInSyncReplicas)
                    .withUserConfiguration(configuration)
                    .build().trim();
        } else {
            return new KafkaBrokerConfigurationBuilder(reconciliation, String.valueOf(brokerId))
                    .withRackId(rack)
                    .withZookeeper(cluster)
                    .withLogDirs(VolumeUtils.createVolumeMounts(storage, DATA_VOLUME_MOUNT_PATH, false))
                    .withListeners(cluster,
                            namespace,
                            listeners,
                            () -> getPodName(brokerId),
                            listenerId -> advertisedHostnames.get(brokerId).get(listenerId),
                            listenerId -> advertisedPorts.get(brokerId).get(listenerId),
                            false)
                    .withAuthorization(cluster, authorization, false)
                    .withCruiseControl(cluster, cruiseControlSpec, ccNumPartitions, ccReplicationFactor, ccMinInSyncReplicas)
                    .withUserConfiguration(configuration)
                    .build().trim();
        }
    }

    /**
     * Generates a list of configuration ConfigMaps - one for each broker in the cluster. The ConfigMaps contain the
     * configurations which should be used by given broker. This is used with StrimziPodSets.
     *
     * @param metricsAndLogging   Object with logging and metrics configuration collected from external user-provided config maps
     * @param advertisedHostnames Map with advertised hostnames for different brokers and listeners
     * @param advertisedPorts     Map with advertised ports for different brokers and listeners
     *
     * @return ConfigMap with the shared configuration.
     */
    public List<ConfigMap> generatePerBrokerConfigurationConfigMaps(MetricsAndLogging metricsAndLogging, Map<Integer, Map<String, String>> advertisedHostnames, Map<Integer, Map<String, String>> advertisedPorts)   {
        String parsedMetrics = metrics.metricsJson(reconciliation, metricsAndLogging.metricsCm());
        String parsedLogging = logging().loggingConfiguration(reconciliation, metricsAndLogging.loggingCm());
        List<ConfigMap> configMaps = new ArrayList<>(replicas);

        for (int brokerId = 0; brokerId < replicas; brokerId++) {
            Map<String, String> data = new HashMap<>(4);

            if (parsedMetrics != null) {
                data.put(MetricsModel.CONFIG_MAP_KEY, parsedMetrics);
            }

            data.put(logging.configMapKey(), parsedLogging);
            data.put(BROKER_CONFIGURATION_FILENAME, generatePerBrokerBrokerConfiguration(brokerId, advertisedHostnames, advertisedPorts));
            // List of configured listeners => StrimziPodSets still need this because of OAUTH and how the OAUTH secret
            // environment variables are parsed in the container bash scripts
            data.put(BROKER_LISTENERS_FILENAME, listeners.stream().map(ListenersUtils::envVarIdentifier).collect(Collectors.joining(" ")));

            configMaps.add(ConfigMapUtils.createConfigMap(getPodName(brokerId), namespace, labels.withStrimziPodName(getPodName(brokerId)), ownerReference, data));
        }

        return configMaps;
    }

    /**
     * @return  Kafka version
     */
    public KafkaVersion getKafkaVersion() {
        return this.kafkaVersion;
    }

    /**
     * @return  Kafka's log message format configuration
     */
    public String getLogMessageFormatVersion() {
        return configuration.getConfigOption(KafkaConfiguration.LOG_MESSAGE_FORMAT_VERSION);
    }

    /**
     * Sets the log message format version
     *
     * @param logMessageFormatVersion       Log message format version
     */
    public void setLogMessageFormatVersion(String logMessageFormatVersion) {
        configuration.setConfigOption(KafkaConfiguration.LOG_MESSAGE_FORMAT_VERSION, logMessageFormatVersion);
    }

    /**
     * @return  Kafka's inter-broker protocol configuration
     */
    public String getInterBrokerProtocolVersion() {
        return configuration.getConfigOption(KafkaConfiguration.INTERBROKER_PROTOCOL_VERSION);
    }

    /**
     * Sets the inter-broker protocol version
     *
     * @param interBrokerProtocolVersion    Inter-broker protocol version
     */
    public void setInterBrokerProtocolVersion(String interBrokerProtocolVersion) {
        configuration.setConfigOption(KafkaConfiguration.INTERBROKER_PROTOCOL_VERSION, interBrokerProtocolVersion);
    }

    /**
     * @return The number of replicas
     */
    public int getReplicas() {
        return replicas;
    }

    /**
     * @return  JMX Model instance for configuring JMX access
     */
    public JmxModel jmx()   {
        return jmx;
    }

    /**
     * @return  Metrics Model instance for configuring Prometheus metrics
     */
    public MetricsModel metrics()   {
        return metrics;
    }

    /**
     * @return  Logging Model instance for configuring logging
     */
    public LoggingModel logging()   {
        return logging;
    }
}
