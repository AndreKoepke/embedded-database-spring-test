/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.zonky.test.db.flyway;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import io.zonky.test.db.context.DataSourceContext;
import io.zonky.test.db.flyway.preparer.BaselineFlywayDatabasePreparer;
import io.zonky.test.db.flyway.preparer.CleanFlywayDatabasePreparer;
import io.zonky.test.db.flyway.preparer.FlywayDatabasePreparer;
import io.zonky.test.db.flyway.preparer.MigrateFlywayDatabasePreparer;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FlywayExtension implements BeanPostProcessor {

    private static final int flywayVersion = FlywayClassUtils.getFlywayVersion();

    protected final Multimap<DataSourceContext, Flyway> flywayBeans = HashMultimap.create();
    protected final BlockingQueue<FlywayOperation> pendingOperations = new LinkedBlockingQueue<>();

    protected boolean optimizedTestExecutionListenerActive = false;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AopInfrastructureBean) {
            return bean;
        }

        if (bean instanceof Flyway) {
            Flyway flyway = (Flyway) bean;
            FlywayWrapper wrapper = FlywayWrapper.of(flyway);
            flywayBeans.put(wrapper.getDataSourceContext(), flyway);

            if (bean instanceof Advised && !((Advised) bean).isFrozen()) {
                ((Advised) bean).addAdvisor(0, createAdvisor(wrapper));
                return bean;
            } else {
                ProxyFactory proxyFactory = new ProxyFactory(bean);
                proxyFactory.addAdvisor(createAdvisor(wrapper));
                proxyFactory.setProxyTargetClass(true);
                return proxyFactory.getProxy();
            }
        }

        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }

    void processPendingOperations() {
        List<FlywayOperation> pendingOperations = new LinkedList<>();
        this.pendingOperations.drainTo(pendingOperations);

        Map<DataSourceContext, List<FlywayOperation>> databaseOperations = pendingOperations.stream()
                .collect(Collectors.groupingBy(operation -> operation.getFlywayWrapper().getDataSourceContext()));

        for (Map.Entry<DataSourceContext, List<FlywayOperation>> entry : databaseOperations.entrySet()) {
            DataSourceContext dataSourceContext = entry.getKey();
            List<FlywayOperation> flywayOperations = entry.getValue();

            Function<FlywayOperation, Set<String>> schemasExtractor = op ->
                    ImmutableSet.copyOf(op.getFlywayWrapper().getSchemas());

            if (flywayBeans.get(dataSourceContext).size() == 1 && flywayOperations.stream().map(schemasExtractor).distinct().count() == 1) {
                flywayOperations = squashOperations(flywayOperations);

                if (flywayOperations.size() == 2 && flywayOperations.get(0).isClean() && flywayOperations.get(1).isMigrate()) {
                    FlywayOperation flywayOperation = flywayOperations.get(1);

                    dataSourceContext.reset();

                    if (isAppendable(flywayOperation)) {
                        applyTestMigrations(flywayOperation);
                        continue;
                    }
                }
            }

            flywayOperations.forEach(operation -> dataSourceContext.apply(operation.getPreparer()));
        }
    }

    protected Advisor createAdvisor(FlywayWrapper wrapper) {
        Advice advice = new FlywayContextExtensionInterceptor(wrapper);
        NameMatchMethodPointcutAdvisor advisor = new NameMatchMethodPointcutAdvisor(advice);
        advisor.setMappedNames("clean", "baseline", "migrate");
        return advisor;
    }

    protected class FlywayContextExtensionInterceptor implements MethodInterceptor {

        protected final FlywayWrapper flywayWrapper;

        protected FlywayContextExtensionInterceptor(FlywayWrapper flywayWrapper) {
            this.flywayWrapper = flywayWrapper;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            switch (invocation.getMethod().getName()) {
                case "clean":
                    return apply(CleanFlywayDatabasePreparer::new);
                case "baseline":
                    return apply(BaselineFlywayDatabasePreparer::new);
                case "migrate":
                    return apply(MigrateFlywayDatabasePreparer::new);
                default:
                    return invocation.proceed();
            }
        }

        protected Object apply(Function<FlywayDescriptor, FlywayDatabasePreparer> creator) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

            boolean listenerProcessing = Arrays.stream(stackTrace)
                    .anyMatch(e -> e.getClassName().endsWith("FlywayTestExecutionListener")
                            && (e.getMethodName().equals("dbResetWithAnnotation") || e.getMethodName().equals("dbResetWithAnotation")));
            boolean optimizedListenerProcessing = listenerProcessing && Arrays.stream(stackTrace)
                    .anyMatch(e -> e.getClassName().endsWith("OptimizedFlywayTestExecutionListener"));
            boolean standardListenerProcessing = listenerProcessing && !optimizedListenerProcessing;

            FlywayDescriptor descriptor = FlywayDescriptor.from(flywayWrapper);
            FlywayDatabasePreparer preparer = creator.apply(descriptor);

            if (standardListenerProcessing && optimizedTestExecutionListenerActive) {
                return preparer instanceof MigrateFlywayDatabasePreparer ? 0 : null;
            }

            if (optimizedListenerProcessing) {
                optimizedTestExecutionListenerActive = true;
                pendingOperations.add(new FlywayOperation(flywayWrapper, preparer));
            } else {
                DataSourceContext dataSourceContext = flywayWrapper.getDataSourceContext();
                dataSourceContext.apply(preparer);
            }

            if (preparer instanceof MigrateFlywayDatabasePreparer) {
                try {
                    return ((MigrateFlywayDatabasePreparer) preparer).getResult().get(0, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    return 0;
                }
            }

            return null;
        }
    }

    protected static class FlywayOperation {

        private final FlywayWrapper flywayWrapper;
        private final FlywayDatabasePreparer preparer;

        public FlywayOperation(FlywayWrapper flywayWrapper, FlywayDatabasePreparer preparer) {
            this.flywayWrapper = flywayWrapper;
            this.preparer = preparer;
        }

        public FlywayWrapper getFlywayWrapper() {
            return flywayWrapper;
        }

        public FlywayDatabasePreparer getPreparer() {
            return preparer;
        }

        public boolean isClean() {
            return preparer instanceof CleanFlywayDatabasePreparer;
        }

        public boolean isBaseline() {
            return preparer instanceof BaselineFlywayDatabasePreparer;
        }

        public boolean isMigrate() {
            return preparer instanceof MigrateFlywayDatabasePreparer;
        }
    }

    protected List<FlywayOperation> squashOperations(List<FlywayOperation> operations) {
        if (operations.stream().anyMatch(FlywayOperation::isBaseline)) {
            return operations;
        }

        int reverseIndex = Iterables.indexOf(Lists.reverse(operations), FlywayOperation::isClean);
        if (reverseIndex == -1) {
            return operations;
        }

        return operations.subList(operations.size() - 1 - reverseIndex, operations.size());
    }

    protected void applyTestMigrations(FlywayOperation operation) {
        FlywayWrapper flywayWrapper = operation.getFlywayWrapper();
        DataSourceContext dataSourceContext = flywayWrapper.getDataSourceContext();
        MigrateFlywayDatabasePreparer migratePreparer = (MigrateFlywayDatabasePreparer) operation.getPreparer();

        List<String> preparerLocations = migratePreparer.getFlywayDescriptor().getLocations();
        List<String> testLocations = resolveTestLocations(flywayWrapper, preparerLocations);

        if (!testLocations.isEmpty()) {
            List<String> defaultLocations = flywayWrapper.getLocations();
            boolean ignoreMissingMigrations = flywayWrapper.isIgnoreMissingMigrations();
            try {
                if (flywayVersion >= 41) {
                    flywayWrapper.setLocations(testLocations);
                    flywayWrapper.setIgnoreMissingMigrations(true);
                } else {
                    flywayWrapper.setLocations(ImmutableList.<String>builder()
                            .addAll(defaultLocations).addAll(testLocations).build());
                }
                FlywayDescriptor descriptor = FlywayDescriptor.from(flywayWrapper);
                dataSourceContext.apply(new MigrateFlywayDatabasePreparer(descriptor));
            } finally {
                flywayWrapper.setLocations(defaultLocations);
                flywayWrapper.setIgnoreMissingMigrations(ignoreMissingMigrations);
            }
        }
    }

    protected boolean isAppendable(FlywayOperation operation) {
        FlywayWrapper flywayWrapper = operation.getFlywayWrapper();
        MigrateFlywayDatabasePreparer migratePreparer = (MigrateFlywayDatabasePreparer) operation.getPreparer();
        return isAppendable(flywayWrapper, migratePreparer.getFlywayDescriptor().getLocations());
    }

    /**
     * Checks if test migrations are appendable to core migrations.
     */
    protected boolean isAppendable(FlywayWrapper flyway, List<String> locations) {
        List<String> defaultLocations = flyway.getLocations();
        if (!locations.containsAll(defaultLocations)) {
            return false;
        }

        List<String> testLocations = resolveTestLocations(flyway, locations);
        if (testLocations.isEmpty()) {
            return true;
        }

        MigrationVersion testFirstVersion = findFirstVersion(flyway, testLocations);
        if (testFirstVersion == MigrationVersion.EMPTY) {
            return true;
        }

        MigrationVersion coreLastVersion = findLastVersion(flyway, defaultLocations);
        return coreLastVersion.compareTo(testFirstVersion) < 0;
    }

    protected List<String> resolveTestLocations(FlywayWrapper flyway, List<String> locations) {
        List<String> defaultLocations = flyway.getLocations();
        List<String> testLocations = Lists.newArrayList(locations);
        testLocations.removeAll(defaultLocations);
        return testLocations;
    }

    protected MigrationVersion findFirstVersion(FlywayWrapper flyway, List<String> locations) {
        Collection<ResolvedMigration> migrations = resolveMigrations(flyway, locations);
        return migrations.stream()
                .filter(migration -> migration.getVersion() != null)
                .findFirst()
                .map(ResolvedMigration::getVersion)
                .orElse(MigrationVersion.EMPTY);
    }

    protected MigrationVersion findLastVersion(FlywayWrapper flyway, List<String> locations) {
        Collection<ResolvedMigration> migrations = resolveMigrations(flyway, locations);
        return migrations.stream()
                .filter(migration -> migration.getVersion() != null)
                .reduce((first, second) -> second) // finds last item
                .map(ResolvedMigration::getVersion)
                .orElse(MigrationVersion.EMPTY);
    }

    protected Collection<ResolvedMigration> resolveMigrations(FlywayWrapper flyway, List<String> locations) {
        List<String> oldLocations = flyway.getLocations();
        try {
            flyway.setLocations(locations);
            return flyway.getMigrations();
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) { // TODO: #70 fixes it
            throw new RuntimeException(e);
        } finally {
            flyway.setLocations(oldLocations);
        }
    }
}
