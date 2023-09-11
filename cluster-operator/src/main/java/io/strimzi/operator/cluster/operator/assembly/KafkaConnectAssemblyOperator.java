/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorRequirement;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectSpec;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.authentication.KafkaClientAuthentication;
import io.strimzi.api.kafka.model.status.KafkaConnectStatus;
import io.strimzi.api.kafka.model.status.KafkaConnectorStatus;
import io.strimzi.operator.cluster.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.KafkaConnectBuild;
import io.strimzi.operator.cluster.model.KafkaConnectCluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.StorageUtils;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationException;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.NamespaceAndName;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.strimzi.operator.common.operator.resource.PvcOperator;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.strimzi.operator.common.operator.resource.StorageClassOperator;
import io.strimzi.operator.common.operator.resource.StrimziPodSetOperator;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>Assembly operator for a "Kafka Connect" assembly, which manages:</p>
 * <ul>
 *     <li>A Kafka Connect Deployment and related Services</li>
 * </ul>
 */
public class KafkaConnectAssemblyOperator extends AbstractConnectOperator<KubernetesClient, KafkaConnect, KafkaConnectList, Resource<KafkaConnect>, KafkaConnectSpec, KafkaConnectStatus> {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaConnectAssemblyOperator.class.getName());
    private final PvcOperator pvcOperations;
    private final StorageClassOperator storageClassOperator;
    private final DeploymentOperator deploymentOperations;
    private final StrimziPodSetOperator podSetOperations;
    private final PodOperator podOperations;
    private final ConnectBuildOperator connectBuildOperator;
    private final KafkaVersion.Lookup versions;
    private final boolean stableIdentities;

    protected static final Logger LOG = LogManager.getLogger(KafkaConnectAssemblyOperator.class.getName());

    /**
     * Constructor
     *
     * @param vertx The Vertx instance
     * @param pfa Platform features availability properties
     * @param supplier Supplies the operators for different resources
     * @param config ClusterOperator configuration. Used to get the user-configured image pull policy and the secrets.
     */
    public KafkaConnectAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config) {
        this(vertx, pfa, supplier, config, connect -> new KafkaConnectApiImpl(vertx));
    }

    /**
     * Constructor which allows providing custom implementation of the Kafka Connect Client. This is used in tests.
     *
     * @param vertx                     The Vertx instance
     * @param pfa                       Platform features availability properties
     * @param supplier                  Supplies the operators for different resources
     * @param config                    ClusterOperator configuration. Used to get the user-configured image pull policy
     *                                  and the secrets.
     * @param connectClientProvider     Provider of the Kafka Connect client
     */
    protected KafkaConnectAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config,
                                        Function<Vertx, KafkaConnectApi> connectClientProvider) {
        this(vertx, pfa, supplier, config, connectClientProvider, KafkaConnectCluster.REST_API_PORT);
    }

    /**
     * Constructor which allows providing custom implementation of the Kafka Connect Client and port on which the Kafka
     * Connect REST API is listening. This is used in tests.
     *
     * @param vertx                     The Vertx instance
     * @param pfa                       Platform features availability properties
     * @param supplier                  Supplies the operators for different resources
     * @param config                    ClusterOperator configuration. Used to get the user-configured image pull policy
     *                                  and the secrets.
     * @param connectClientProvider     Provider of the Kafka Connect client
     * @param port                      Port of the Kafka Connect REST API
     */
    protected KafkaConnectAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config,
                                        Function<Vertx, KafkaConnectApi> connectClientProvider, int port) {
        super(vertx, pfa, KafkaConnect.RESOURCE_KIND, supplier.connectOperator, supplier, config, connectClientProvider, port);
        this.deploymentOperations = supplier.deploymentOperations;
        this.podSetOperations = supplier.strimziPodSetOperator;
        this.podOperations = supplier.podOperations;
        this.connectBuildOperator = new ConnectBuildOperator(pfa, supplier, config);

        this.versions = config.versions();
        this.stableIdentities = config.featureGates().stableConnectIdentitiesEnabled();
        this.pvcOperations = supplier.pvcOperations;
        this.storageClassOperator = supplier.storageClassOperations;
    }

    @Override
    protected Future<KafkaConnectStatus> createOrUpdate(Reconciliation reconciliation, KafkaConnect kafkaConnect) {
        KafkaConnectCluster connect;
        KafkaConnectBuild build;
        KafkaConnectStatus kafkaConnectStatus = new KafkaConnectStatus();
        try {
            connect = KafkaConnectCluster.fromCrd(reconciliation, kafkaConnect, versions);
            build = KafkaConnectBuild.fromCrd(reconciliation, kafkaConnect, versions);
        } catch (Exception e) {
            LOGGER.warnCr(reconciliation, e);
            StatusUtils.setStatusConditionAndObservedGeneration(kafkaConnect, kafkaConnectStatus, Future.failedFuture(e));
            return Future.failedFuture(new ReconciliationException(kafkaConnectStatus, e));
        }

        Promise<KafkaConnectStatus> createOrUpdatePromise = Promise.promise();
        String namespace = reconciliation.namespace();

        Map<String, String> podAnnotations = new HashMap<>();
        Map<String, String> controllerAnnotations = new HashMap<>();

        boolean hasZeroReplicas = connect.getReplicas() == 0;
        final AtomicReference<String> image = new AtomicReference<>();
        final AtomicReference<String> desiredLogging = new AtomicReference<>();
        final AtomicReference<Deployment> deployment = new AtomicReference<>();
        final AtomicReference<StrimziPodSet> podSet = new AtomicReference<>();
        String initCrbName = KafkaConnectResources.initContainerClusterRoleBindingName(kafkaConnect.getMetadata().getName(), namespace);
        ClusterRoleBinding initCrb = connect.generateClusterRoleBinding();

        LOGGER.debugCr(reconciliation, "Creating or updating Kafka Connect cluster");

        controllerResources(reconciliation, connect, deployment, podSet)
                .compose(i -> connectServiceAccount(reconciliation, namespace, KafkaConnectResources.serviceAccountName(connect.getCluster()), connect))
                .compose(i -> {
                    List<PersistentVolumeClaim> pvcs = connect.generatePersistentVolumeClaims(connect.getStorage());
                    return new PvcReconciler(reconciliation, pvcOperations, storageClassOperator)
                            .resizeAndReconcilePvcs(podIndex -> KafkaConnectResources.deploymentName(reconciliation.name()), pvcs);
                })
                .compose(i -> connectInitClusterRoleBinding(reconciliation, initCrbName, initCrb))
                .compose(i -> connectNetworkPolicy(reconciliation, namespace, connect, isUseResources(kafkaConnect)))
                .compose(i -> connectBuildOperator.reconcile(reconciliation, namespace, activeController(deployment.get(), podSet.get()), build))
                .compose(buildInfo -> {
                    if (buildInfo != null) {
                        podAnnotations.put(Annotations.STRIMZI_IO_CONNECT_BUILD_REVISION, buildInfo.buildRevision());
                        controllerAnnotations.put(Annotations.STRIMZI_IO_CONNECT_BUILD_REVISION, buildInfo.buildRevision());
                        controllerAnnotations.put(Annotations.STRIMZI_IO_CONNECT_BUILD_IMAGE, buildInfo.image());
                        image.set(buildInfo.image());
                    }
                    return Future.succeededFuture();
                })
                .compose(i -> serviceOperations.reconcile(reconciliation, namespace, connect.getServiceName(), connect.generateService()))
                .compose(i -> serviceOperations.reconcile(reconciliation, namespace, connect.getComponentName(), stableIdentities ? connect.generateHeadlessService() : null))
                .compose(i -> generateMetricsAndLoggingConfigMap(reconciliation, namespace, connect))
                .compose(logAndMetricsConfigMap -> {
                    String logging = logAndMetricsConfigMap.getData().get(connect.logging().configMapKey());
                    podAnnotations.put(Annotations.ANNO_STRIMZI_LOGGING_APPENDERS_HASH, Util.hashStub(Util.getLoggingDynamicallyUnmodifiableEntries(logging)));
                    desiredLogging.set(logging);
                    return configMapOperations.reconcile(reconciliation, namespace, logAndMetricsConfigMap.getMetadata().getName(), logAndMetricsConfigMap);
                })
                .compose(i -> ReconcilerUtils.reconcileJmxSecret(reconciliation, secretOperations, connect))
                .compose(i -> podDisruptionBudgetOperator.reconcile(reconciliation, namespace, connect.getComponentName(), connect.generatePodDisruptionBudget(stableIdentities)))
                .compose(i -> generateAuthHash(namespace, kafkaConnect.getSpec()))
                .compose(hash -> {
                    podAnnotations.put(Annotations.ANNO_STRIMZI_AUTH_HASH, Integer.toString(hash));
                    return Future.succeededFuture();
                })
                .compose(i -> {
                    KafkaConnectMigration migration = new KafkaConnectMigration(
                            reconciliation,
                            connect,
                            controllerAnnotations,
                            podAnnotations,
                            operationTimeoutMs,
                            pfa.isOpenshift(),
                            imagePullPolicy,
                            imagePullSecrets,
                            image.get(),
                            deploymentOperations,
                            podSetOperations,
                            podOperations
                    );

                    if (stableIdentities)   {
                        return migration.migrateFromDeploymentToStrimziPodSets(deployment.get(), podSet.get());
                    } else {
                        return migration.migrateFromStrimziPodSetsToDeployment(deployment.get(), podSet.get());
                    }
                })
                .compose(i -> {
                    if (stableIdentities)   {
                        return reconcilePodSet(reconciliation, connect, podAnnotations, controllerAnnotations, image.get());
                    } else {
                        return reconcileDeployment(reconciliation, connect, podAnnotations, controllerAnnotations, image.get(), hasZeroReplicas);
                    }
                })
                .compose(i -> reconcileConnectors(reconciliation, kafkaConnect, kafkaConnectStatus, hasZeroReplicas, desiredLogging.get(), connect.defaultLogConfig()))
                .onComplete(reconciliationResult -> {
                    StatusUtils.setStatusConditionAndObservedGeneration(kafkaConnect, kafkaConnectStatus, reconciliationResult);

                    if (!hasZeroReplicas) {
                        kafkaConnectStatus.setUrl(KafkaConnectResources.url(connect.getCluster(), namespace, KafkaConnectCluster.REST_API_PORT));
                    }

                    kafkaConnectStatus.setReplicas(connect.getReplicas());
                    kafkaConnectStatus.setLabelSelector(connect.getSelectorLabels().toSelectorString());

                    if (reconciliationResult.succeeded())   {
                        createOrUpdatePromise.complete(kafkaConnectStatus);
                    } else {
                        createOrUpdatePromise.fail(new ReconciliationException(kafkaConnectStatus, reconciliationResult.cause()));
                    }
                });

        return createOrUpdatePromise.future();
    }

    private Future<Void> controllerResources(Reconciliation reconciliation,
                                             KafkaConnectCluster connect,
                                             AtomicReference<Deployment> deploymentReference,
                                             AtomicReference<StrimziPodSet> podSetReference)   {
        return CompositeFuture
                .join(deploymentOperations.getAsync(reconciliation.namespace(), connect.getComponentName()), podSetOperations.getAsync(reconciliation.namespace(), connect.getComponentName()))
                .compose(res -> {
                    deploymentReference.set(res.resultAt(0));
                    podSetReference.set(res.resultAt(1));

                    return Future.succeededFuture();
                });
    }

    /**
     * Finds and returns the correct controller resource:
     *     - If stable identities are enabled and PodSet exists, returns PodSet
     *     - If stable identities are enabled and PodSet is null, we might be in the middle of migration, so it returns the Deployment (which in worst case is null which is fine)
     *     - If stable identities are disabled and Deployment exists, returns Deployment
     *     - If stable identities are disabled and Deployment is null, we might be in the middle of migration, so it returns the PodSet (which in worst case is null which is fine)
     *
     * @param deployment    Deployment resource
     * @param podSet        PodSet resource
     *
     * @return  The active controller resource
     */
    private HasMetadata activeController(Deployment deployment, StrimziPodSet podSet)  {
        if (stableIdentities) {
            return podSet != null ? podSet : deployment;
        } else  {
            return deployment != null ? deployment : podSet;
        }
    }

    private Future<Void> reconcileDeployment(Reconciliation reconciliation,
                                             KafkaConnectCluster connect,
                                             Map<String, String> podAnnotations,
                                             Map<String, String> deploymentAnnotations,
                                             String customContainerImage,
                                             boolean hasZeroReplicas)  {
        return deploymentOperations.scaleDown(reconciliation, reconciliation.namespace(), connect.getComponentName(), connect.getReplicas(), operationTimeoutMs)
                .compose(i -> deploymentOperations.reconcile(reconciliation, reconciliation.namespace(), connect.getComponentName(), connect.generateDeployment(connect.getReplicas(), deploymentAnnotations, podAnnotations, pfa.isOpenshift(), imagePullPolicy, imagePullSecrets, customContainerImage)))
                .compose(i -> deploymentOperations.scaleUp(reconciliation, reconciliation.namespace(), connect.getComponentName(), connect.getReplicas(), operationTimeoutMs))
                .compose(i -> deploymentOperations.waitForObserved(reconciliation, reconciliation.namespace(), connect.getComponentName(), 1_000, operationTimeoutMs))
                .compose(i -> hasZeroReplicas ? Future.succeededFuture() : deploymentOperations.readiness(reconciliation, reconciliation.namespace(), connect.getComponentName(), 1_000, operationTimeoutMs));
    }

    private Future<Void> reconcilePodSet(Reconciliation reconciliation,
                                         KafkaConnectCluster connect,
                                         Map<String, String> podAnnotations,
                                         Map<String, String> podSetAnnotations,
                                         String customContainerImage)  {
        return podSetOperations.reconcile(reconciliation, reconciliation.namespace(), connect.getComponentName(), connect.generatePodSet(connect.getReplicas(), podSetAnnotations, podAnnotations, pfa.isOpenshift(), imagePullPolicy, imagePullSecrets, customContainerImage))
                .compose(reconciliationResult -> {
                    KafkaConnectRoller roller = new KafkaConnectRoller(reconciliation, connect, operationTimeoutMs, podOperations);
                    return roller.maybeRoll(reconciliationResult.resource());
                })
                .compose(i -> podSetOperations.readiness(reconciliation, reconciliation.namespace(), connect.getComponentName(), 1_000, operationTimeoutMs));
    }



    @Override
    protected KafkaConnectStatus createStatus() {
        return new KafkaConnectStatus();
    }

    @Override
    public void reconcileThese(String trigger, Set<NamespaceAndName> desiredNames, String namespace, Handler<AsyncResult<Void>> handler) {
        super.reconcileThese(trigger, desiredNames, namespace, ignore -> {
            List<String> connects = desiredNames.stream().map(NamespaceAndName::getName).collect(Collectors.toList());
            LabelSelectorRequirement requirement = new LabelSelectorRequirement(Labels.STRIMZI_CLUSTER_LABEL, "In", connects);
            Optional<LabelSelector> connectorsSelector = Optional.of(new LabelSelector(List.of(requirement), null));
            connectorOperator.listAsync(namespace, connectorsSelector)
                    .onComplete(ar -> {
                        if (ar.succeeded()) {
                            metrics().resetConnectorsCounters(namespace);
                            ar.result().forEach(connector -> {
                                metrics().connectorsResourceCounter(connector.getMetadata().getNamespace()).incrementAndGet();
                                if (isPaused(connector.getStatus())) {
                                    metrics().pausedConnectorsResourceCounter(connector.getMetadata().getNamespace()).incrementAndGet();
                                }
                            });
                            handler.handle(Future.succeededFuture());
                        } else {
                            handler.handle(ar.map((Void) null));
                        }
                    });
        });
    }

    /**
     * Deletes the ClusterRoleBinding which as a cluster-scoped resource cannot be deleted by the ownerReference
     * <p>
     * @param reconciliation    The Reconciliation identification
     * @return                  Future indicating the result of the deletion
     */
    @Override
    protected Future<Boolean> delete(Reconciliation reconciliation) {
        return super.delete(reconciliation)
                .compose(i -> ReconcilerUtils.withIgnoreRbacError(reconciliation, clusterRoleBindingOperations.reconcile(reconciliation, KafkaConnectResources.initContainerClusterRoleBindingName(reconciliation.name(), reconciliation.namespace()), null), null))
                .map(Boolean.FALSE); // Return FALSE since other resources are still deleted by garbage collection
    }

    /**
     * Generates a hash from the trusted TLS certificates that can be used to spot if it has changed.
     *
     * @param namespace          Namespace of the Connect cluster
     * @param kafkaConnectSpec   KafkaConnectSpec object
     * @return                   Future for tracking the asynchronous result of generating the TLS auth hash
     */
    Future<Integer> generateAuthHash(String namespace, KafkaConnectSpec kafkaConnectSpec) {
        KafkaClientAuthentication auth = kafkaConnectSpec.getAuthentication();
        List<CertSecretSource> trustedCertificates = kafkaConnectSpec.getTls() == null ? Collections.emptyList() : kafkaConnectSpec.getTls().getTrustedCertificates();
        return Util.authTlsHash(secretOperations, namespace, auth, trustedCertificates);
    }

    private boolean isPaused(KafkaConnectorStatus status) {
        return status != null && status.getConditions().stream().anyMatch(condition -> "ReconciliationPaused".equals(condition.getType()));
    }

    private int getPodIndexFromPvcName(String pvcName)  {
        return Integer.parseInt(pvcName.substring(pvcName.lastIndexOf("-") + 1));
    }

    private int getPodIndexFromPodName(String podName)  {
        return Integer.parseInt(podName.substring(podName.lastIndexOf("-") + 1));
    }

    private Future<Void> maybeResizeReconcilePvcs(String namespace, Reconciliation reconciliation, List<PersistentVolumeClaim> pvcs, AbstractModel cluster) {
        List<Future> futures = new ArrayList<>(pvcs.size());

        for (PersistentVolumeClaim desiredPvc : pvcs)  {
            Promise<Void> resultPromise = Promise.promise();

            pvcOperations.getAsync(namespace, desiredPvc.getMetadata().getName()).onComplete(res -> {
                if (res.succeeded())    {
                    PersistentVolumeClaim currentPvc = res.result();

                    if (currentPvc == null || currentPvc.getStatus() == null || !"Bound".equals(currentPvc.getStatus().getPhase())) {
                        // This branch handles the following conditions:
                        // * The PVC doesn't exist yet, we should create it
                        // * The PVC is not Bound and we should reconcile it
                        reconcilePvc(reconciliation, namespace, desiredPvc).onComplete(resultPromise);
                    } else if (currentPvc.getStatus().getConditions().stream().filter(cond -> "Resizing".equals(cond.getType()) && "true".equals(cond.getStatus().toLowerCase(Locale.ENGLISH))).findFirst().orElse(null) != null)  {
                        // The PVC is Bound but it is already resizing => Nothing to do, we should let it resize
                        LOG.debug("{}: The PVC {} is resizing, nothing to do", reconciliation, desiredPvc.getMetadata().getName());
                        resultPromise.complete();
                    } else if (currentPvc.getStatus().getConditions().stream().filter(cond -> "FileSystemResizePending".equals(cond.getType()) && "true".equals(cond.getStatus().toLowerCase(Locale.ENGLISH))).findFirst().orElse(null) != null)  {
                        // The PVC is Bound and resized but waiting for FS resizing => We need to restart the pod which is using it
                        String podName = cluster.getPodName(getPodIndexFromPvcName(desiredPvc.getMetadata().getName()));
                        //TODO fsResizingRestartRequest.add(podName);
                        LOG.info("{}: The PVC {} is waiting for file system resizing and the pod {} needs to be restarted.", reconciliation, desiredPvc.getMetadata().getName(), podName);
                        resultPromise.complete();
                    } else {
                        // The PVC is Bound and resizing is not in progress => We should check if the SC supports resizing and check if size changed
                        Long currentSize = StorageUtils.convertToMillibytes(currentPvc.getSpec().getResources().getRequests().get("storage"));
                        Long desiredSize = StorageUtils.convertToMillibytes(desiredPvc.getSpec().getResources().getRequests().get("storage"));

                        if (!currentSize.equals(desiredSize))   {
                            // The sizes are different => we should resize (shrinking will be handled in StorageDiff, so we do not need to check that)
                            resizePvc(namespace, reconciliation, currentPvc, desiredPvc).onComplete(resultPromise);
                        } else  {
                            // size didn't changed, just reconcile
                            reconcilePvc(reconciliation, namespace, desiredPvc).onComplete(resultPromise);
                        }
                    }
                } else {
                    resultPromise.fail(res.cause());
                }
            });

            futures.add(resultPromise.future());
        }

        return CompositeFuture.all(futures)
            .compose(res -> {
                if (res.failed()) {
                    return Future.failedFuture(res.cause());
                }
                return Future.succeededFuture();
            });
    }

    private Future<Void> reconcilePvc(Reconciliation reconciliation, String namespace, PersistentVolumeClaim desired)  {
        Promise<Void> resultPromise = Promise.promise();

        pvcOperations.reconcile(reconciliation, namespace, desired.getMetadata().getName(), desired).onComplete(pvcRes -> {
            if (pvcRes.succeeded()) {
                resultPromise.complete();
            } else {
                resultPromise.fail(pvcRes.cause());
            }
        });

        return resultPromise.future();
    }

    private Future<Void> resizePvc(String namespace, Reconciliation reconciliation, PersistentVolumeClaim current, PersistentVolumeClaim desired)  {
        Promise<Void> resultPromise = Promise.promise();

        String storageClassName = current.getSpec().getStorageClassName();

        if (storageClassName != null && !storageClassName.isEmpty()) {
            storageClassOperator.getAsync(storageClassName).onComplete(scRes -> {
                if (scRes.succeeded()) {
                    StorageClass sc = scRes.result();

                    if (sc == null) {
                        LOG.warn("{}: Storage Class {} not found. PVC {} cannot be resized. Reconciliation will proceed without reconciling this PVC.", reconciliation, storageClassName, desired.getMetadata().getName());
                        resultPromise.complete();
                    } else if (sc.getAllowVolumeExpansion() == null || !sc.getAllowVolumeExpansion())    {
                        // Resizing not suported in SC => do nothing
                        LOG.warn("{}: Storage Class {} does not support resizing of volumes. PVC {} cannot be resized. Reconciliation will proceed without reconciling this PVC.", reconciliation, storageClassName, desired.getMetadata().getName());
                        resultPromise.complete();
                    } else  {
                        // Resizing supported by SC => We can reconcile the PVC to have it resized
                        LOG.info("{}: Resizing PVC {} from {} to {}.", reconciliation, desired.getMetadata().getName(), current.getStatus().getCapacity().get("storage").getAmount(), desired.getSpec().getResources().getRequests().get("storage").getAmount());
                        pvcOperations.reconcile(reconciliation, namespace, desired.getMetadata().getName(), desired).onComplete(pvcRes -> {
                            if (pvcRes.succeeded()) {
                                resultPromise.complete();
                            } else {
                                resultPromise.fail(pvcRes.cause());
                            }
                        });
                    }
                } else {
                    LOG.error("{}: Storage Class {} not found. PVC {} cannot be resized.", reconciliation, storageClassName, desired.getMetadata().getName(), scRes.cause());
                    resultPromise.fail(scRes.cause());
                }
            });
        } else {
            LOG.warn("{}: PVC {} does not use any Storage Class and cannot be resized. Reconciliation will proceed without reconciling this PVC.", reconciliation, desired.getMetadata().getName());
            resultPromise.complete();
        }

        return resultPromise.future();
    }
}
