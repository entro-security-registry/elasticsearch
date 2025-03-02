/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.node;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Build;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.cluster.repositories.reservedstate.ReservedRepositoryAction;
import org.elasticsearch.action.admin.indices.template.reservedstate.ReservedComposableIndexTemplateAction;
import org.elasticsearch.action.ingest.ReservedPipelineAction;
import org.elasticsearch.action.search.SearchExecutionStatsCollector;
import org.elasticsearch.action.search.SearchPhaseController;
import org.elasticsearch.action.search.SearchTransportService;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.action.update.UpdateHelper;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.CoordinationDiagnosticsService;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.coordination.MasterHistoryService;
import org.elasticsearch.cluster.coordination.Reconfigurator;
import org.elasticsearch.cluster.coordination.StableMasterHealthIndicatorService;
import org.elasticsearch.cluster.desirednodes.DesiredNodesSettingsValidator;
import org.elasticsearch.cluster.metadata.IndexMetadataVerifier;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.MetadataCreateDataStreamService;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.MetadataDataStreamsService;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.MetadataUpdateSettingsService;
import org.elasticsearch.cluster.metadata.SystemIndexMetadataUpgradeService;
import org.elasticsearch.cluster.metadata.TemplateUpgradeService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.routing.BatchedRerouteService;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.DiskThresholdMonitor;
import org.elasticsearch.cluster.routing.allocation.WriteLoadForecaster;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.TransportVersionsFixupListener;
import org.elasticsearch.cluster.version.CompatibilityVersions;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Key;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.logging.HeaderWarning;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.ConsistentSettingsService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.features.FeatureSpecification;
import org.elasticsearch.gateway.GatewayAllocator;
import org.elasticsearch.gateway.GatewayMetaState;
import org.elasticsearch.gateway.GatewayModule;
import org.elasticsearch.gateway.MetaStateService;
import org.elasticsearch.gateway.PersistedClusterStateService;
import org.elasticsearch.health.HealthPeriodicLogger;
import org.elasticsearch.health.HealthService;
import org.elasticsearch.health.metadata.HealthMetadataService;
import org.elasticsearch.health.node.DiskHealthIndicatorService;
import org.elasticsearch.health.node.HealthInfoCache;
import org.elasticsearch.health.node.LocalHealthMonitor;
import org.elasticsearch.health.node.ShardsCapacityHealthIndicatorService;
import org.elasticsearch.health.node.selection.HealthNodeTaskExecutor;
import org.elasticsearch.health.stats.HealthApiStats;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.IndexSettingProvider;
import org.elasticsearch.index.IndexSettingProviders;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexingPressure;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.indices.ExecutorSelector;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.ShardLimitValidator;
import org.elasticsearch.indices.SystemIndexMappingUpdateService;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.indices.breaker.BreakerSettings;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.indices.recovery.PeerRecoverySourceService;
import org.elasticsearch.indices.recovery.PeerRecoveryTargetService;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.indices.recovery.SnapshotFilesProvider;
import org.elasticsearch.indices.recovery.plan.PeerOnlyRecoveryPlannerService;
import org.elasticsearch.indices.recovery.plan.RecoveryPlannerService;
import org.elasticsearch.indices.recovery.plan.ShardSnapshotsService;
import org.elasticsearch.inference.InferenceServiceRegistry;
import org.elasticsearch.ingest.IngestService;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.monitor.fs.FsHealthService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.node.internal.TerminationHandler;
import org.elasticsearch.node.internal.TerminationHandlerProvider;
import org.elasticsearch.persistent.PersistentTasksClusterService;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.persistent.PersistentTasksExecutorRegistry;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.CircuitBreakerPlugin;
import org.elasticsearch.plugins.ClusterCoordinationPlugin;
import org.elasticsearch.plugins.ClusterPlugin;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.plugins.EnginePlugin;
import org.elasticsearch.plugins.HealthPlugin;
import org.elasticsearch.plugins.IndexStorePlugin;
import org.elasticsearch.plugins.InferenceServicePlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.MetadataUpgrader;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.PersistentTaskPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.plugins.RecoveryPlannerPlugin;
import org.elasticsearch.plugins.ReloadablePlugin;
import org.elasticsearch.plugins.RepositoryPlugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.plugins.ShutdownAwarePlugin;
import org.elasticsearch.plugins.SystemIndexPlugin;
import org.elasticsearch.plugins.TelemetryPlugin;
import org.elasticsearch.plugins.internal.DocumentParsingObserver;
import org.elasticsearch.plugins.internal.DocumentParsingObserverPlugin;
import org.elasticsearch.plugins.internal.ReloadAwarePlugin;
import org.elasticsearch.plugins.internal.RestExtension;
import org.elasticsearch.plugins.internal.SettingsExtension;
import org.elasticsearch.readiness.ReadinessService;
import org.elasticsearch.repositories.RepositoriesModule;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.reservedstate.ReservedClusterStateHandler;
import org.elasticsearch.reservedstate.ReservedClusterStateHandlerProvider;
import org.elasticsearch.reservedstate.action.ReservedClusterSettingsAction;
import org.elasticsearch.reservedstate.service.FileSettingsService;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.SearchUtils;
import org.elasticsearch.search.aggregations.support.AggregationUsageService;
import org.elasticsearch.shutdown.PluginShutdownService;
import org.elasticsearch.snapshots.InternalSnapshotsInfoService;
import org.elasticsearch.snapshots.RepositoryIntegrityHealthIndicatorService;
import org.elasticsearch.snapshots.RestoreService;
import org.elasticsearch.snapshots.SnapshotShardsService;
import org.elasticsearch.snapshots.SnapshotsInfoService;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.telemetry.TelemetryProvider;
import org.elasticsearch.telemetry.tracing.Tracer;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.upgrades.SystemIndexMigrationExecutor;
import org.elasticsearch.usage.UsageService;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.core.Types.forciblyCast;

/**
 * Class uses to perform all the operations needed to construct a {@link Node} instance.
 * <p>
 * Constructing a {@link Node} is a complex operation, involving many interdependent services.
 * Separating out this logic into a dedicated class is a lot clearer and more flexible than
 * doing all this logic inside a constructor in {@link Node}.
 */
class NodeConstruction {

    /**
     * Prepare everything needed to create a {@link Node} instance.
     *
     * @param initialEnvironment         the initial environment for this node, which will be added to by plugins
     * @param serviceProvider            provides various service implementations that could be mocked
     * @param forbidPrivateIndexSettings whether or not private index settings are forbidden when creating an index; this is used in the
     *                                   test framework for tests that rely on being able to set private settings
     */
    static NodeConstruction prepareConstruction(
        Environment initialEnvironment,
        NodeServiceProvider serviceProvider,
        boolean forbidPrivateIndexSettings
    ) {
        List<Closeable> closeables = new ArrayList<>();
        try {
            NodeConstruction constructor = new NodeConstruction(closeables);
            Settings settings = constructor.createEnvironment(initialEnvironment, serviceProvider);
            ThreadPool threadPool = constructor.createThreadPool(settings);
            SettingsModule settingsModule = constructor.validateSettings(initialEnvironment.settings(), settings, threadPool);

            constructor.construct(threadPool, settingsModule, serviceProvider, forbidPrivateIndexSettings);

            return constructor;
        } catch (IOException e) {
            IOUtils.closeWhileHandlingException(closeables);
            throw new ElasticsearchException("Failed to bind service", e);
        } catch (Throwable t) {
            IOUtils.closeWhileHandlingException(closeables);
            throw t;
        }
    }

    /**
     * See comments on Node#logger for why this is not static
     */
    private final Logger logger = LogManager.getLogger(Node.class);
    private final DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(Node.class);

    private final List<Closeable> resourcesToClose;
    /*
     * References for storing in a Node
     */
    private Injector injector;
    private Environment environment;
    private NodeEnvironment nodeEnvironment;
    private PluginsService pluginsService;
    private NodeClient client;
    private Collection<LifecycleComponent> pluginLifecycleComponents;
    private Node.LocalNodeFactory localNodeFactory;
    private NodeService nodeService;
    private TerminationHandler terminationHandler;
    private NamedWriteableRegistry namedWriteableRegistry;
    private NamedXContentRegistry xContentRegistry;

    private NodeConstruction(List<Closeable> resourcesToClose) {
        this.resourcesToClose = resourcesToClose;
    }

    Injector injector() {
        return injector;
    }

    Environment environment() {
        return environment;
    }

    NodeEnvironment nodeEnvironment() {
        return nodeEnvironment;
    }

    PluginsService pluginsService() {
        return pluginsService;
    }

    NodeClient client() {
        return client;
    }

    Collection<LifecycleComponent> pluginLifecycleComponents() {
        return pluginLifecycleComponents;
    }

    Node.LocalNodeFactory localNodeFactory() {
        return localNodeFactory;
    }

    NodeService nodeService() {
        return nodeService;
    }

    TerminationHandler terminationHandler() {
        return terminationHandler;
    }

    NamedWriteableRegistry namedWriteableRegistry() {
        return namedWriteableRegistry;
    }

    NamedXContentRegistry namedXContentRegistry() {
        return xContentRegistry;
    }

    private <T> Optional<T> getSinglePlugin(Class<T> pluginClass) {
        return getSinglePlugin(pluginsService.filterPlugins(pluginClass), pluginClass);
    }

    private <T> Optional<T> getSinglePlugin(Stream<T> plugins, Class<T> pluginClass) {
        var it = plugins.iterator();
        if (it.hasNext() == false) {
            return Optional.empty();
        }
        T plugin = it.next();
        if (it.hasNext()) {
            List<T> allPlugins = new ArrayList<>();
            allPlugins.add(plugin);
            it.forEachRemaining(allPlugins::add);
            throw new IllegalStateException("A single " + pluginClass.getName() + " was expected but got :" + allPlugins);
        }
        return Optional.of(plugin);
    }

    private Settings createEnvironment(Environment initialEnvironment, NodeServiceProvider serviceProvider) {
        // Pass the node settings to the DeprecationLogger class so that it can have the deprecation.skip_deprecated_settings setting:
        Settings envSettings = initialEnvironment.settings();
        DeprecationLogger.initialize(envSettings);

        JvmInfo jvmInfo = JvmInfo.jvmInfo();
        logger.info(
            "version[{}], pid[{}], build[{}/{}/{}], OS[{}/{}/{}], JVM[{}/{}/{}/{}]",
            Build.current().qualifiedVersion(),
            jvmInfo.pid(),
            Build.current().type().displayName(),
            Build.current().hash(),
            Build.current().date(),
            Constants.OS_NAME,
            Constants.OS_VERSION,
            Constants.OS_ARCH,
            Constants.JVM_VENDOR,
            Constants.JVM_NAME,
            Constants.JAVA_VERSION,
            Constants.JVM_VERSION
        );
        logger.info("JVM home [{}], using bundled JDK [{}]", System.getProperty("java.home"), jvmInfo.getUsingBundledJdk());
        logger.info("JVM arguments {}", Arrays.toString(jvmInfo.getInputArguments()));
        if (Build.current().isProductionRelease() == false) {
            logger.warn(
                "version [{}] is a pre-release version of Elasticsearch and is not suitable for production",
                Build.current().qualifiedVersion()
            );
        }
        if (Environment.PATH_SHARED_DATA_SETTING.exists(envSettings)) {
            // NOTE: this must be done with an explicit check here because the deprecation property on a path setting will
            // cause ES to fail to start since logging is not yet initialized on first read of the setting
            deprecationLogger.warn(
                DeprecationCategory.SETTINGS,
                "shared-data-path",
                "setting [path.shared_data] is deprecated and will be removed in a future release"
            );
        }

        if (initialEnvironment.dataFiles().length > 1) {
            // NOTE: we use initialEnvironment here, but assertEquivalent below ensures the data paths do not change
            deprecationLogger.warn(
                DeprecationCategory.SETTINGS,
                "multiple-data-paths",
                "Configuring multiple [path.data] paths is deprecated. Use RAID or other system level features for utilizing "
                    + "multiple disks. This feature will be removed in a future release."
            );
        }
        if (Environment.dataPathUsesList(envSettings)) {
            // already checked for multiple values above, so if this is a list it is a single valued list
            deprecationLogger.warn(
                DeprecationCategory.SETTINGS,
                "multiple-data-paths-list",
                "Configuring [path.data] with a list is deprecated. Instead specify as a string value."
            );
        }

        if (logger.isDebugEnabled()) {
            logger.debug(
                "using config [{}], data [{}], logs [{}], plugins [{}]",
                initialEnvironment.configFile(),
                Arrays.toString(initialEnvironment.dataFiles()),
                initialEnvironment.logsFile(),
                initialEnvironment.pluginsFile()
            );
        }

        Node.deleteTemporaryApmConfig(
            jvmInfo,
            (e, apmConfig) -> logger.error("failed to delete temporary APM config file [{}], reason: [{}]", apmConfig, e.getMessage())
        );

        pluginsService = serviceProvider.newPluginService(initialEnvironment, envSettings);
        Settings settings = Node.mergePluginSettings(pluginsService.pluginMap(), envSettings);

        /*
         * Create the environment based on the finalized view of the settings. This is to ensure that components get the same setting
         * values, no matter they ask for them from.
         */
        environment = new Environment(settings, initialEnvironment.configFile());
        Environment.assertEquivalent(initialEnvironment, environment);

        return settings;
    }

    private ThreadPool createThreadPool(Settings settings) throws IOException {
        ThreadPool threadPool = new ThreadPool(
            settings,
            pluginsService.flatMap(p -> p.getExecutorBuilders(settings)).toArray(ExecutorBuilder<?>[]::new)
        );
        resourcesToClose.add(() -> ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS));

        // adds the context to the DeprecationLogger so that it does not need to be injected everywhere
        HeaderWarning.setThreadContext(threadPool.getThreadContext());
        resourcesToClose.add(() -> HeaderWarning.removeThreadContext(threadPool.getThreadContext()));

        return threadPool;
    }

    private SettingsModule validateSettings(Settings envSettings, Settings settings, ThreadPool threadPool) throws IOException {
        // register the node.data, node.ingest, node.master, node.remote_cluster_client settings here so we can mark them private
        List<Setting<?>> additionalSettings = new ArrayList<>(pluginsService.flatMap(Plugin::getSettings).toList());
        for (final ExecutorBuilder<?> builder : threadPool.builders()) {
            additionalSettings.addAll(builder.getRegisteredSettings());
        }
        SettingsExtension.load().forEach(e -> additionalSettings.addAll(e.getSettings()));

        // this is as early as we can validate settings at this point. we already pass them to ScriptModule as well as ThreadPool
        // so we might be late here already
        SettingsModule settingsModule = new SettingsModule(
            settings,
            additionalSettings,
            pluginsService.flatMap(Plugin::getSettingsFilter).toList()
        );

        // creating `NodeEnvironment` breaks the ability to rollback to 7.x on an 8.0 upgrade (`upgradeLegacyNodeFolders`) so do this
        // after settings validation.
        nodeEnvironment = new NodeEnvironment(envSettings, environment);
        logger.info(
            "node name [{}], node ID [{}], cluster name [{}], roles {}",
            Node.NODE_NAME_SETTING.get(envSettings),
            nodeEnvironment.nodeId(),
            ClusterName.CLUSTER_NAME_SETTING.get(envSettings).value(),
            DiscoveryNode.getRolesFromSettings(settings)
                .stream()
                .map(DiscoveryNodeRole::roleName)
                .collect(Collectors.toCollection(LinkedHashSet::new))
        );
        resourcesToClose.add(nodeEnvironment);

        return settingsModule;
    }

    private void construct(
        ThreadPool threadPool,
        SettingsModule settingsModule,
        NodeServiceProvider serviceProvider,
        boolean forbidPrivateIndexSettings
    ) throws IOException {

        Settings settings = settingsModule.getSettings();

        final ResourceWatcherService resourceWatcherService = new ResourceWatcherService(settings, threadPool);
        resourcesToClose.add(resourceWatcherService);

        final Set<String> taskHeaders = Stream.concat(
            pluginsService.filterPlugins(ActionPlugin.class).flatMap(p -> p.getTaskHeaders().stream()),
            Task.HEADERS_TO_COPY.stream()
        ).collect(Collectors.toSet());

        final TelemetryProvider telemetryProvider = getSinglePlugin(TelemetryPlugin.class).map(p -> p.getTelemetryProvider(settings))
            .orElse(TelemetryProvider.NOOP);

        final Tracer tracer = telemetryProvider.getTracer();

        final TaskManager taskManager = new TaskManager(settings, threadPool, taskHeaders, tracer);

        client = new NodeClient(settings, threadPool);

        final ScriptModule scriptModule = new ScriptModule(settings, pluginsService.filterPlugins(ScriptPlugin.class).toList());
        final ScriptService scriptService = serviceProvider.newScriptService(
            pluginsService,
            settings,
            scriptModule.engines,
            scriptModule.contexts,
            threadPool::absoluteTimeInMillis
        );
        AnalysisModule analysisModule = new AnalysisModule(
            environment,
            pluginsService.filterPlugins(AnalysisPlugin.class).toList(),
            pluginsService.getStablePluginRegistry()
        );
        localNodeFactory = new Node.LocalNodeFactory(settings, nodeEnvironment.nodeId());

        ScriptModule.registerClusterSettingsListeners(scriptService, settingsModule.getClusterSettings());
        final NetworkService networkService = new NetworkService(
            pluginsService.filterPlugins(DiscoveryPlugin.class)
                .map(d -> d.getCustomNameResolver(environment.settings()))
                .filter(Objects::nonNull)
                .toList()
        );

        List<ClusterPlugin> clusterPlugins = pluginsService.filterPlugins(ClusterPlugin.class).toList();
        final ClusterService clusterService = new ClusterService(settings, settingsModule.getClusterSettings(), threadPool, taskManager);
        clusterService.addStateApplier(scriptService);
        resourcesToClose.add(clusterService);

        final Set<Setting<?>> consistentSettings = settingsModule.getConsistentSettings();
        if (consistentSettings.isEmpty() == false) {
            clusterService.addLocalNodeMasterListener(
                new ConsistentSettingsService(settings, clusterService, consistentSettings).newHashPublisher()
            );
        }

        Supplier<DocumentParsingObserver> documentParsingObserverSupplier = getDocumentParsingObserverSupplier();

        var factoryContext = new InferenceServicePlugin.InferenceServiceFactoryContext(client);
        final InferenceServiceRegistry inferenceServiceRegistry = new InferenceServiceRegistry(
            pluginsService.filterPlugins(InferenceServicePlugin.class).toList(),
            factoryContext
        );

        final IngestService ingestService = new IngestService(
            clusterService,
            threadPool,
            environment,
            scriptService,
            analysisModule.getAnalysisRegistry(),
            pluginsService.filterPlugins(IngestPlugin.class).toList(),
            client,
            IngestService.createGrokThreadWatchdog(environment, threadPool),
            documentParsingObserverSupplier
        );
        final SetOnce<RepositoriesService> repositoriesServiceReference = new SetOnce<>();
        final ClusterInfoService clusterInfoService = serviceProvider.newClusterInfoService(
            pluginsService,
            settings,
            clusterService,
            threadPool,
            client
        );
        final UsageService usageService = new UsageService();

        SearchModule searchModule = new SearchModule(settings, pluginsService.filterPlugins(SearchPlugin.class).toList());
        IndexSearcher.setMaxClauseCount(SearchUtils.calculateMaxClauseValue(threadPool));
        final NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(
            Stream.of(
                NetworkModule.getNamedWriteables().stream(),
                IndicesModule.getNamedWriteables().stream(),
                searchModule.getNamedWriteables().stream(),
                pluginsService.flatMap(Plugin::getNamedWriteables),
                ClusterModule.getNamedWriteables().stream(),
                SystemIndexMigrationExecutor.getNamedWriteables().stream(),
                inferenceServiceRegistry.getNamedWriteables().stream()
            ).flatMap(Function.identity()).toList()
        );
        NamedXContentRegistry xContentRegistry = new NamedXContentRegistry(
            Stream.of(
                NetworkModule.getNamedXContents().stream(),
                IndicesModule.getNamedXContents().stream(),
                searchModule.getNamedXContents().stream(),
                pluginsService.flatMap(Plugin::getNamedXContent),
                ClusterModule.getNamedXWriteables().stream(),
                SystemIndexMigrationExecutor.getNamedXContentParsers().stream(),
                HealthNodeTaskExecutor.getNamedXContentParsers().stream()
            ).flatMap(Function.identity()).toList()
        );
        final List<SystemIndices.Feature> features = pluginsService.filterPlugins(SystemIndexPlugin.class).map(plugin -> {
            SystemIndices.validateFeatureName(plugin.getFeatureName(), plugin.getClass().getCanonicalName());
            return SystemIndices.Feature.fromSystemIndexPlugin(plugin, settings);
        }).toList();
        final SystemIndices systemIndices = new SystemIndices(features);
        final ExecutorSelector executorSelector = systemIndices.getExecutorSelector();

        ModulesBuilder modules = new ModulesBuilder();
        final MonitorService monitorService = new MonitorService(settings, nodeEnvironment, threadPool);
        final FsHealthService fsHealthService = new FsHealthService(
            settings,
            clusterService.getClusterSettings(),
            threadPool,
            nodeEnvironment
        );
        final SetOnce<RerouteService> rerouteServiceReference = new SetOnce<>();
        final InternalSnapshotsInfoService snapshotsInfoService = new InternalSnapshotsInfoService(
            settings,
            clusterService,
            repositoriesServiceReference::get,
            rerouteServiceReference::get
        );
        final WriteLoadForecaster writeLoadForecaster = getWriteLoadForecaster(threadPool, settings, clusterService.getClusterSettings());
        final ClusterModule clusterModule = new ClusterModule(
            settings,
            clusterService,
            clusterPlugins,
            clusterInfoService,
            snapshotsInfoService,
            threadPool,
            systemIndices,
            writeLoadForecaster
        );
        modules.add(clusterModule);
        IndicesModule indicesModule = new IndicesModule(pluginsService.filterPlugins(MapperPlugin.class).toList());
        modules.add(indicesModule);

        List<BreakerSettings> pluginCircuitBreakers = pluginsService.filterPlugins(CircuitBreakerPlugin.class)
            .map(plugin -> plugin.getCircuitBreaker(settings))
            .toList();
        final CircuitBreakerService circuitBreakerService = createCircuitBreakerService(
            settingsModule.getSettings(),
            pluginCircuitBreakers,
            settingsModule.getClusterSettings()
        );
        pluginsService.filterPlugins(CircuitBreakerPlugin.class).forEach(plugin -> {
            CircuitBreaker breaker = circuitBreakerService.getBreaker(plugin.getCircuitBreaker(settings).getName());
            plugin.setCircuitBreaker(breaker);
        });
        resourcesToClose.add(circuitBreakerService);
        modules.add(new GatewayModule());

        CompatibilityVersions compatibilityVersions = new CompatibilityVersions(
            TransportVersion.current(),
            systemIndices.getMappingsVersions()
        );
        PageCacheRecycler pageCacheRecycler = serviceProvider.newPageCacheRecycler(pluginsService, settings);
        BigArrays bigArrays = serviceProvider.newBigArrays(pluginsService, pageCacheRecycler, circuitBreakerService);
        modules.add(settingsModule);
        final MetaStateService metaStateService = new MetaStateService(nodeEnvironment, xContentRegistry);
        final PersistedClusterStateService persistedClusterStateService = newPersistedClusterStateService(
            xContentRegistry,
            clusterService.getClusterSettings(),
            threadPool,
            compatibilityVersions
        );

        // collect engine factory providers from plugins
        final Collection<Function<IndexSettings, Optional<EngineFactory>>> engineFactoryProviders = pluginsService.filterPlugins(
            EnginePlugin.class
        ).<Function<IndexSettings, Optional<EngineFactory>>>map(plugin -> plugin::getEngineFactory).toList();

        final Map<String, IndexStorePlugin.DirectoryFactory> indexStoreFactories = pluginsService.filterPlugins(IndexStorePlugin.class)
            .map(IndexStorePlugin::getDirectoryFactories)
            .flatMap(m -> m.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        final Map<String, IndexStorePlugin.RecoveryStateFactory> recoveryStateFactories = pluginsService.filterPlugins(
            IndexStorePlugin.class
        )
            .map(IndexStorePlugin::getRecoveryStateFactories)
            .flatMap(m -> m.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        final List<IndexStorePlugin.IndexFoldersDeletionListener> indexFoldersDeletionListeners = pluginsService.filterPlugins(
            IndexStorePlugin.class
        ).map(IndexStorePlugin::getIndexFoldersDeletionListeners).flatMap(List::stream).toList();

        final Map<String, IndexStorePlugin.SnapshotCommitSupplier> snapshotCommitSuppliers = pluginsService.filterPlugins(
            IndexStorePlugin.class
        )
            .map(IndexStorePlugin::getSnapshotCommitSuppliers)
            .flatMap(m -> m.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (DiscoveryNode.isMasterNode(settings)) {
            clusterService.addListener(new SystemIndexMappingUpdateService(systemIndices, client));
            clusterService.addListener(new TransportVersionsFixupListener(clusterService, client.admin().cluster(), threadPool));
        }

        final RerouteService rerouteService = new BatchedRerouteService(clusterService, clusterModule.getAllocationService()::reroute);
        rerouteServiceReference.set(rerouteService);
        clusterService.setRerouteService(rerouteService);

        FeatureService featureService = new FeatureService(pluginsService.loadServiceProviders(FeatureSpecification.class));

        final IndicesService indicesService = new IndicesService(
            settings,
            pluginsService,
            nodeEnvironment,
            xContentRegistry,
            analysisModule.getAnalysisRegistry(),
            clusterModule.getIndexNameExpressionResolver(),
            indicesModule.getMapperRegistry(),
            namedWriteableRegistry,
            threadPool,
            settingsModule.getIndexScopedSettings(),
            circuitBreakerService,
            bigArrays,
            scriptService,
            clusterService,
            client,
            featureService,
            metaStateService,
            engineFactoryProviders,
            indexStoreFactories,
            searchModule.getValuesSourceRegistry(),
            recoveryStateFactories,
            indexFoldersDeletionListeners,
            snapshotCommitSuppliers,
            searchModule.getRequestCacheKeyDifferentiator(),
            documentParsingObserverSupplier
        );

        final var parameters = new IndexSettingProvider.Parameters(indicesService::createIndexMapperServiceForValidation);
        IndexSettingProviders indexSettingProviders = new IndexSettingProviders(
            pluginsService.flatMap(p -> p.getAdditionalIndexSettingProviders(parameters)).collect(Collectors.toSet())
        );

        final ShardLimitValidator shardLimitValidator = new ShardLimitValidator(settings, clusterService);
        final MetadataCreateIndexService metadataCreateIndexService = new MetadataCreateIndexService(
            settings,
            clusterService,
            indicesService,
            clusterModule.getAllocationService(),
            shardLimitValidator,
            environment,
            settingsModule.getIndexScopedSettings(),
            threadPool,
            xContentRegistry,
            systemIndices,
            forbidPrivateIndexSettings,
            indexSettingProviders
        );

        final MetadataCreateDataStreamService metadataCreateDataStreamService = new MetadataCreateDataStreamService(
            threadPool,
            clusterService,
            metadataCreateIndexService
        );
        final MetadataDataStreamsService metadataDataStreamsService = new MetadataDataStreamsService(clusterService, indicesService);

        final MetadataUpdateSettingsService metadataUpdateSettingsService = new MetadataUpdateSettingsService(
            clusterService,
            clusterModule.getAllocationService(),
            settingsModule.getIndexScopedSettings(),
            indicesService,
            shardLimitValidator,
            threadPool
        );

        record PluginServiceInstances(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService,
            ScriptService scriptService,
            NamedXContentRegistry xContentRegistry,
            Environment environment,
            NodeEnvironment nodeEnvironment,
            NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<RepositoriesService> repositoriesServiceSupplier,
            TelemetryProvider telemetryProvider,
            AllocationService allocationService,
            IndicesService indicesService,
            FeatureService featureService,
            SystemIndices systemIndices
        ) implements Plugin.PluginServices {}
        PluginServiceInstances pluginServices = new PluginServiceInstances(
            client,
            clusterService,
            threadPool,
            resourceWatcherService,
            scriptService,
            xContentRegistry,
            environment,
            nodeEnvironment,
            namedWriteableRegistry,
            clusterModule.getIndexNameExpressionResolver(),
            repositoriesServiceReference::get,
            telemetryProvider,
            clusterModule.getAllocationService(),
            indicesService,
            featureService,
            systemIndices
        );

        Collection<?> pluginComponents = pluginsService.flatMap(p -> p.createComponents(pluginServices)).toList();

        List<ReservedClusterStateHandler<?>> reservedStateHandlers = new ArrayList<>();

        // add all reserved state handlers from server
        reservedStateHandlers.add(new ReservedClusterSettingsAction(settingsModule.getClusterSettings()));

        var templateService = new MetadataIndexTemplateService(
            clusterService,
            metadataCreateIndexService,
            indicesService,
            settingsModule.getIndexScopedSettings(),
            xContentRegistry,
            systemIndices,
            indexSettingProviders
        );

        reservedStateHandlers.add(new ReservedComposableIndexTemplateAction(templateService, settingsModule.getIndexScopedSettings()));

        // add all reserved state handlers from plugins
        pluginsService.loadServiceProviders(ReservedClusterStateHandlerProvider.class)
            .forEach(h -> reservedStateHandlers.addAll(h.handlers()));

        var terminationHandlers = pluginsService.loadServiceProviders(TerminationHandlerProvider.class)
            .stream()
            .map(TerminationHandlerProvider::handler);
        terminationHandler = getSinglePlugin(terminationHandlers, TerminationHandler.class).orElse(null);

        ActionModule actionModule = new ActionModule(
            settings,
            clusterModule.getIndexNameExpressionResolver(),
            settingsModule.getIndexScopedSettings(),
            settingsModule.getClusterSettings(),
            settingsModule.getSettingsFilter(),
            threadPool,
            pluginsService.filterPlugins(ActionPlugin.class).toList(),
            client,
            circuitBreakerService,
            usageService,
            systemIndices,
            tracer,
            clusterService,
            reservedStateHandlers,
            pluginsService.loadSingletonServiceProvider(RestExtension.class, RestExtension::allowAll)
        );
        modules.add(actionModule);

        final RestController restController = actionModule.getRestController();
        final NetworkModule networkModule = new NetworkModule(
            settings,
            pluginsService.filterPlugins(NetworkPlugin.class).toList(),
            threadPool,
            bigArrays,
            pageCacheRecycler,
            circuitBreakerService,
            namedWriteableRegistry,
            xContentRegistry,
            networkService,
            restController,
            actionModule::copyRequestHeadersToThreadContext,
            clusterService.getClusterSettings(),
            tracer
        );
        Collection<UnaryOperator<Map<String, IndexTemplateMetadata>>> indexTemplateMetadataUpgraders = pluginsService.map(
            Plugin::getIndexTemplateMetadataUpgrader
        ).toList();
        final MetadataUpgrader metadataUpgrader = new MetadataUpgrader(indexTemplateMetadataUpgraders);
        final IndexMetadataVerifier indexMetadataVerifier = new IndexMetadataVerifier(
            settings,
            clusterService,
            xContentRegistry,
            indicesModule.getMapperRegistry(),
            settingsModule.getIndexScopedSettings(),
            scriptService
        );
        if (DiscoveryNode.isMasterNode(settings)) {
            clusterService.addListener(new SystemIndexMetadataUpgradeService(systemIndices, clusterService));
            clusterService.addListener(new TemplateUpgradeService(client, clusterService, threadPool, indexTemplateMetadataUpgraders));
        }
        final Transport transport = networkModule.getTransportSupplier().get();
        final TransportService transportService = serviceProvider.newTransportService(
            pluginsService,
            settings,
            transport,
            threadPool,
            networkModule.getTransportInterceptor(),
            localNodeFactory,
            settingsModule.getClusterSettings(),
            taskManager,
            tracer
        );
        final GatewayMetaState gatewayMetaState = new GatewayMetaState();
        final ResponseCollectorService responseCollectorService = new ResponseCollectorService(clusterService);
        final SearchTransportService searchTransportService = new SearchTransportService(
            transportService,
            client,
            SearchExecutionStatsCollector.makeWrapper(responseCollectorService)
        );
        final HttpServerTransport httpServerTransport = serviceProvider.newHttpTransport(pluginsService, networkModule);
        final IndexingPressure indexingLimits = new IndexingPressure(settings);

        final RecoverySettings recoverySettings = new RecoverySettings(settings, settingsModule.getClusterSettings());
        RepositoriesModule repositoriesModule = new RepositoriesModule(
            environment,
            pluginsService.filterPlugins(RepositoryPlugin.class).toList(),
            transportService,
            clusterService,
            bigArrays,
            xContentRegistry,
            recoverySettings,
            telemetryProvider
        );
        RepositoriesService repositoryService = repositoriesModule.getRepositoryService();
        repositoriesServiceReference.set(repositoryService);
        SnapshotsService snapshotsService = new SnapshotsService(
            settings,
            clusterService,
            clusterModule.getIndexNameExpressionResolver(),
            repositoryService,
            transportService,
            actionModule.getActionFilters(),
            systemIndices
        );
        SnapshotShardsService snapshotShardsService = new SnapshotShardsService(
            settings,
            clusterService,
            repositoryService,
            transportService,
            indicesService
        );

        actionModule.getReservedClusterStateService().installStateHandler(new ReservedRepositoryAction(repositoryService));
        actionModule.getReservedClusterStateService().installStateHandler(new ReservedPipelineAction());

        FileSettingsService fileSettingsService = new FileSettingsService(
            clusterService,
            actionModule.getReservedClusterStateService(),
            environment
        );

        RestoreService restoreService = new RestoreService(
            clusterService,
            repositoryService,
            clusterModule.getAllocationService(),
            metadataCreateIndexService,
            indexMetadataVerifier,
            shardLimitValidator,
            systemIndices,
            indicesService,
            fileSettingsService,
            threadPool
        );
        final DiskThresholdMonitor diskThresholdMonitor = new DiskThresholdMonitor(
            settings,
            clusterService::state,
            clusterService.getClusterSettings(),
            client,
            threadPool::relativeTimeInMillis,
            rerouteService
        );
        clusterInfoService.addListener(diskThresholdMonitor::onNewInfo);

        final DiscoveryModule discoveryModule = new DiscoveryModule(
            settings,
            transportService,
            client,
            namedWriteableRegistry,
            networkService,
            clusterService.getMasterService(),
            clusterService.getClusterApplierService(),
            clusterService.getClusterSettings(),
            pluginsService.filterPlugins(DiscoveryPlugin.class).toList(),
            pluginsService.filterPlugins(ClusterCoordinationPlugin.class).toList(),
            clusterModule.getAllocationService(),
            environment.configFile(),
            gatewayMetaState,
            rerouteService,
            fsHealthService,
            circuitBreakerService,
            compatibilityVersions,
            featureService.getNodeFeatures()
        );
        this.nodeService = new NodeService(
            settings,
            threadPool,
            monitorService,
            discoveryModule.getCoordinator(),
            transportService,
            indicesService,
            pluginsService,
            circuitBreakerService,
            scriptService,
            httpServerTransport,
            ingestService,
            clusterService,
            settingsModule.getSettingsFilter(),
            responseCollectorService,
            searchTransportService,
            indexingLimits,
            searchModule.getValuesSourceRegistry().getUsageService(),
            repositoryService
        );

        final SearchService searchService = serviceProvider.newSearchService(
            pluginsService,
            clusterService,
            indicesService,
            threadPool,
            scriptService,
            bigArrays,
            searchModule.getFetchPhase(),
            responseCollectorService,
            circuitBreakerService,
            executorSelector,
            tracer
        );

        final PersistentTasksService persistentTasksService = new PersistentTasksService(clusterService, threadPool, client);
        final SystemIndexMigrationExecutor systemIndexMigrationExecutor = new SystemIndexMigrationExecutor(
            client,
            clusterService,
            systemIndices,
            metadataUpdateSettingsService,
            metadataCreateIndexService,
            settingsModule.getIndexScopedSettings()
        );
        final HealthNodeTaskExecutor healthNodeTaskExecutor = HealthNodeTaskExecutor.create(
            clusterService,
            persistentTasksService,
            featureService,
            settings,
            clusterService.getClusterSettings()
        );
        final Stream<PersistentTasksExecutor<?>> builtinTaskExecutors = Stream.of(systemIndexMigrationExecutor, healthNodeTaskExecutor);
        final Stream<PersistentTasksExecutor<?>> pluginTaskExecutors = pluginsService.filterPlugins(PersistentTaskPlugin.class)
            .map(
                p -> p.getPersistentTasksExecutor(
                    clusterService,
                    threadPool,
                    client,
                    settingsModule,
                    clusterModule.getIndexNameExpressionResolver()
                )
            )
            .flatMap(List::stream);
        final PersistentTasksExecutorRegistry registry = new PersistentTasksExecutorRegistry(
            Stream.concat(pluginTaskExecutors, builtinTaskExecutors).toList()
        );
        final PersistentTasksClusterService persistentTasksClusterService = new PersistentTasksClusterService(
            settings,
            registry,
            clusterService,
            threadPool
        );
        resourcesToClose.add(persistentTasksClusterService);

        PluginShutdownService pluginShutdownService = new PluginShutdownService(
            pluginsService.filterPlugins(ShutdownAwarePlugin.class).toList()
        );
        clusterService.addListener(pluginShutdownService);

        final RecoveryPlannerService recoveryPlannerService = getRecoveryPlannerService(threadPool, clusterService, repositoryService);
        final DesiredNodesSettingsValidator desiredNodesSettingsValidator = new DesiredNodesSettingsValidator();

        final MasterHistoryService masterHistoryService = new MasterHistoryService(transportService, threadPool, clusterService);
        final CoordinationDiagnosticsService coordinationDiagnosticsService = new CoordinationDiagnosticsService(
            clusterService,
            transportService,
            discoveryModule.getCoordinator(),
            masterHistoryService
        );
        final HealthService healthService = createHealthService(clusterService, coordinationDiagnosticsService, threadPool);
        HealthPeriodicLogger healthPeriodicLogger = createHealthPeriodicLogger(clusterService, settings, client, healthService);
        healthPeriodicLogger.init();
        HealthMetadataService healthMetadataService = HealthMetadataService.create(clusterService, featureService, settings);
        LocalHealthMonitor localHealthMonitor = LocalHealthMonitor.create(
            settings,
            clusterService,
            nodeService,
            threadPool,
            client,
            featureService
        );
        HealthInfoCache nodeHealthOverview = HealthInfoCache.create(clusterService);
        HealthApiStats healthApiStats = new HealthApiStats();

        List<ReloadablePlugin> reloadablePlugins = pluginsService.filterPlugins(ReloadablePlugin.class).toList();
        pluginsService.filterPlugins(ReloadAwarePlugin.class).forEach(p -> p.setReloadCallback(wrapPlugins(reloadablePlugins)));

        modules.add(b -> {
            b.bind(NodeService.class).toInstance(nodeService);
            b.bind(NamedXContentRegistry.class).toInstance(xContentRegistry);
            b.bind(PluginsService.class).toInstance(pluginsService);
            b.bind(Client.class).toInstance(client);
            b.bind(NodeClient.class).toInstance(client);
            b.bind(Environment.class).toInstance(environment);
            b.bind(ThreadPool.class).toInstance(threadPool);
            b.bind(NodeEnvironment.class).toInstance(nodeEnvironment);
            b.bind(ResourceWatcherService.class).toInstance(resourceWatcherService);
            b.bind(CircuitBreakerService.class).toInstance(circuitBreakerService);
            b.bind(BigArrays.class).toInstance(bigArrays);
            b.bind(PageCacheRecycler.class).toInstance(pageCacheRecycler);
            b.bind(ScriptService.class).toInstance(scriptService);
            b.bind(AnalysisRegistry.class).toInstance(analysisModule.getAnalysisRegistry());
            b.bind(IngestService.class).toInstance(ingestService);
            b.bind(IndexingPressure.class).toInstance(indexingLimits);
            b.bind(UsageService.class).toInstance(usageService);
            b.bind(AggregationUsageService.class).toInstance(searchModule.getValuesSourceRegistry().getUsageService());
            b.bind(NamedWriteableRegistry.class).toInstance(namedWriteableRegistry);
            b.bind(MetadataUpgrader.class).toInstance(metadataUpgrader);
            b.bind(MetaStateService.class).toInstance(metaStateService);
            b.bind(PersistedClusterStateService.class).toInstance(persistedClusterStateService);
            b.bind(IndicesService.class).toInstance(indicesService);
            b.bind(MetadataCreateIndexService.class).toInstance(metadataCreateIndexService);
            b.bind(MetadataCreateDataStreamService.class).toInstance(metadataCreateDataStreamService);
            b.bind(MetadataDataStreamsService.class).toInstance(metadataDataStreamsService);
            b.bind(MetadataUpdateSettingsService.class).toInstance(metadataUpdateSettingsService);
            b.bind(SearchService.class).toInstance(searchService);
            b.bind(SearchTransportService.class).toInstance(searchTransportService);
            b.bind(SearchPhaseController.class).toInstance(new SearchPhaseController(searchService::aggReduceContextBuilder));
            b.bind(Transport.class).toInstance(transport);
            b.bind(TransportService.class).toInstance(transportService);
            b.bind(NetworkService.class).toInstance(networkService);
            b.bind(UpdateHelper.class).toInstance(new UpdateHelper(scriptService));
            b.bind(IndexMetadataVerifier.class).toInstance(indexMetadataVerifier);
            b.bind(ClusterInfoService.class).toInstance(clusterInfoService);
            b.bind(SnapshotsInfoService.class).toInstance(snapshotsInfoService);
            b.bind(GatewayMetaState.class).toInstance(gatewayMetaState);
            b.bind(FeatureService.class).toInstance(featureService);
            b.bind(Coordinator.class).toInstance(discoveryModule.getCoordinator());
            b.bind(Reconfigurator.class).toInstance(discoveryModule.getReconfigurator());
            {
                serviceProvider.processRecoverySettings(pluginsService, settingsModule.getClusterSettings(), recoverySettings);
                final SnapshotFilesProvider snapshotFilesProvider = new SnapshotFilesProvider(repositoryService);
                b.bind(PeerRecoverySourceService.class)
                    .toInstance(
                        new PeerRecoverySourceService(
                            transportService,
                            indicesService,
                            clusterService,
                            recoverySettings,
                            recoveryPlannerService
                        )
                    );
                b.bind(PeerRecoveryTargetService.class)
                    .toInstance(
                        new PeerRecoveryTargetService(
                            client,
                            threadPool,
                            transportService,
                            recoverySettings,
                            clusterService,
                            snapshotFilesProvider
                        )
                    );
            }
            b.bind(HttpServerTransport.class).toInstance(httpServerTransport);
            pluginComponents.forEach(p -> {
                if (p instanceof PluginComponentBinding<?, ?> pcb) {
                    @SuppressWarnings("unchecked")
                    Class<Object> clazz = (Class<Object>) pcb.inter();
                    b.bind(clazz).toInstance(pcb.impl());

                } else {
                    @SuppressWarnings("unchecked")
                    Class<Object> clazz = (Class<Object>) p.getClass();
                    b.bind(clazz).toInstance(p);
                }
            });
            b.bind(PersistentTasksService.class).toInstance(persistentTasksService);
            b.bind(PersistentTasksClusterService.class).toInstance(persistentTasksClusterService);
            b.bind(PersistentTasksExecutorRegistry.class).toInstance(registry);
            b.bind(RepositoriesService.class).toInstance(repositoryService);
            b.bind(SnapshotsService.class).toInstance(snapshotsService);
            b.bind(SnapshotShardsService.class).toInstance(snapshotShardsService);
            b.bind(RestoreService.class).toInstance(restoreService);
            b.bind(RerouteService.class).toInstance(rerouteService);
            b.bind(ShardLimitValidator.class).toInstance(shardLimitValidator);
            b.bind(FsHealthService.class).toInstance(fsHealthService);
            b.bind(SystemIndices.class).toInstance(systemIndices);
            b.bind(PluginShutdownService.class).toInstance(pluginShutdownService);
            b.bind(ExecutorSelector.class).toInstance(executorSelector);
            b.bind(IndexSettingProviders.class).toInstance(indexSettingProviders);
            b.bind(DesiredNodesSettingsValidator.class).toInstance(desiredNodesSettingsValidator);
            b.bind(HealthService.class).toInstance(healthService);
            b.bind(MasterHistoryService.class).toInstance(masterHistoryService);
            b.bind(CoordinationDiagnosticsService.class).toInstance(coordinationDiagnosticsService);
            b.bind(HealthNodeTaskExecutor.class).toInstance(healthNodeTaskExecutor);
            b.bind(HealthMetadataService.class).toInstance(healthMetadataService);
            b.bind(LocalHealthMonitor.class).toInstance(localHealthMonitor);
            b.bind(HealthInfoCache.class).toInstance(nodeHealthOverview);
            b.bind(HealthApiStats.class).toInstance(healthApiStats);
            b.bind(Tracer.class).toInstance(tracer);
            b.bind(FileSettingsService.class).toInstance(fileSettingsService);
            b.bind(WriteLoadForecaster.class).toInstance(writeLoadForecaster);
            b.bind(HealthPeriodicLogger.class).toInstance(healthPeriodicLogger);
            b.bind(CompatibilityVersions.class).toInstance(compatibilityVersions);
            b.bind(InferenceServiceRegistry.class).toInstance(inferenceServiceRegistry);
        });

        if (ReadinessService.enabled(environment)) {
            modules.add(
                b -> b.bind(ReadinessService.class)
                    .toInstance(serviceProvider.newReadinessService(pluginsService, clusterService, environment))
            );
        }

        injector = modules.createInjector();

        // We allocate copies of existing shards by looking for a viable copy of the shard in the cluster and assigning the shard there.
        // The search for viable copies is triggered by an allocation attempt (i.e. a reroute) and is performed asynchronously. When it
        // completes we trigger another reroute to try the allocation again. This means there is a circular dependency: the allocation
        // service needs access to the existing shards allocators (e.g. the GatewayAllocator) which need to be able to trigger a
        // reroute, which needs to call into the allocation service. We close the loop here:
        clusterModule.setExistingShardsAllocators(injector.getInstance(GatewayAllocator.class));

        List<LifecycleComponent> pluginLifecycleComponents = pluginComponents.stream().map(p -> {
            if (p instanceof PluginComponentBinding<?, ?> pcb) {
                return pcb.impl();
            }
            return p;
        }).filter(p -> p instanceof LifecycleComponent).map(p -> (LifecycleComponent) p).toList();
        resourcesToClose.addAll(pluginLifecycleComponents);
        resourcesToClose.add(injector.getInstance(PeerRecoverySourceService.class));
        this.pluginLifecycleComponents = Collections.unmodifiableList(pluginLifecycleComponents);

        // Due to Java's type erasure with generics, the injector can't give us exactly what we need, and we have
        // to resort to some evil casting.
        @SuppressWarnings("rawtypes")
        Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions =
            forciblyCast(injector.getInstance(new Key<Map<ActionType, TransportAction>>() {
            }));

        client.initialize(
            actions,
            transportService.getTaskManager(),
            () -> clusterService.localNode().getId(),
            transportService.getLocalNodeConnection(),
            transportService.getRemoteClusterService(),
            namedWriteableRegistry
        );
        this.namedWriteableRegistry = namedWriteableRegistry;
        this.xContentRegistry = xContentRegistry;

        logger.debug("initializing HTTP handlers ...");
        actionModule.initRestHandlers(() -> clusterService.state().nodesIfRecovered(), f -> {
            ClusterState state = clusterService.state();
            return state.clusterRecovered() && featureService.clusterHasFeature(state, f);
        });
        logger.info("initialized");
    }

    private Supplier<DocumentParsingObserver> getDocumentParsingObserverSupplier() {
        return getSinglePlugin(DocumentParsingObserverPlugin.class).map(DocumentParsingObserverPlugin::getDocumentParsingObserverSupplier)
            .orElse(() -> DocumentParsingObserver.EMPTY_INSTANCE);
    }

    /**
     * Creates a new {@link CircuitBreakerService} based on the settings provided.
     *
     * @see Node#BREAKER_TYPE_KEY
     */
    private static CircuitBreakerService createCircuitBreakerService(
        Settings settings,
        List<BreakerSettings> breakerSettings,
        ClusterSettings clusterSettings
    ) {
        String type = Node.BREAKER_TYPE_KEY.get(settings);
        return switch (type) {
            case "hierarchy" -> new HierarchyCircuitBreakerService(settings, breakerSettings, clusterSettings);
            case "none" -> new NoneCircuitBreakerService();
            default -> throw new IllegalArgumentException("Unknown circuit breaker type [" + type + "]");
        };
    }

    /**
     * Wrap a group of reloadable plugins into a single reloadable plugin interface
     * @param reloadablePlugins A list of reloadable plugins
     * @return A single ReloadablePlugin that, upon reload, reloads the plugins it wraps
     */
    private static ReloadablePlugin wrapPlugins(List<ReloadablePlugin> reloadablePlugins) {
        return settings -> {
            for (ReloadablePlugin plugin : reloadablePlugins) {
                try {
                    plugin.reload(settings);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private HealthService createHealthService(
        ClusterService clusterService,
        CoordinationDiagnosticsService coordinationDiagnosticsService,
        ThreadPool threadPool
    ) {
        var serverHealthIndicatorServices = Stream.of(
            new StableMasterHealthIndicatorService(coordinationDiagnosticsService, clusterService),
            new RepositoryIntegrityHealthIndicatorService(clusterService),
            new DiskHealthIndicatorService(clusterService),
            new ShardsCapacityHealthIndicatorService(clusterService)
        );
        var pluginHealthIndicatorServices = pluginsService.filterPlugins(HealthPlugin.class)
            .flatMap(plugin -> plugin.getHealthIndicatorServices().stream());
        return new HealthService(Stream.concat(serverHealthIndicatorServices, pluginHealthIndicatorServices).toList(), threadPool);
    }

    private static HealthPeriodicLogger createHealthPeriodicLogger(
        ClusterService clusterService,
        Settings settings,
        NodeClient client,
        HealthService healthService
    ) {
        return new HealthPeriodicLogger(settings, clusterService, client, healthService);
    }

    private RecoveryPlannerService getRecoveryPlannerService(
        ThreadPool threadPool,
        ClusterService clusterService,
        RepositoriesService repositoryService
    ) {
        var recoveryPlannerServices = pluginsService.filterPlugins(RecoveryPlannerPlugin.class)
            .map(
                plugin -> plugin.createRecoveryPlannerService(
                    new ShardSnapshotsService(client, repositoryService, threadPool, clusterService)
                )
            )
            .flatMap(Optional::stream);
        return getSinglePlugin(recoveryPlannerServices, RecoveryPlannerService.class).orElseGet(PeerOnlyRecoveryPlannerService::new);
    }

    private WriteLoadForecaster getWriteLoadForecaster(ThreadPool threadPool, Settings settings, ClusterSettings clusterSettings) {
        var writeLoadForecasters = pluginsService.filterPlugins(ClusterPlugin.class)
            .flatMap(clusterPlugin -> clusterPlugin.createWriteLoadForecasters(threadPool, settings, clusterSettings).stream());

        return getSinglePlugin(writeLoadForecasters, WriteLoadForecaster.class).orElse(WriteLoadForecaster.DEFAULT);
    }

    private PersistedClusterStateService newPersistedClusterStateService(
        NamedXContentRegistry xContentRegistry,
        ClusterSettings clusterSettings,
        ThreadPool threadPool,
        CompatibilityVersions compatibilityVersions
    ) {
        var persistedClusterStateServiceFactories = pluginsService.filterPlugins(ClusterCoordinationPlugin.class)
            .map(ClusterCoordinationPlugin::getPersistedClusterStateServiceFactory)
            .flatMap(Optional::stream);

        return getSinglePlugin(persistedClusterStateServiceFactories, ClusterCoordinationPlugin.PersistedClusterStateServiceFactory.class)
            .map(
                f -> f.newPersistedClusterStateService(
                    nodeEnvironment,
                    xContentRegistry,
                    clusterSettings,
                    threadPool,
                    compatibilityVersions
                )
            )
            .orElseGet(
                () -> new PersistedClusterStateService(nodeEnvironment, xContentRegistry, clusterSettings, threadPool::relativeTimeInMillis)
            );
    }
}
